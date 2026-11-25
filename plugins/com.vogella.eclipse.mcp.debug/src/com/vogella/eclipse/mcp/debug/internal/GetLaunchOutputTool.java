package com.vogella.eclipse.mcp.debug.internal;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reads the console output of the processes of a debug session, which is where a program that
 * died during startup says why.
 */
public final class GetLaunchOutputTool implements IMcpTool {

	/** Cap per stream, so one endless line cannot make the answer unbounded. */
	private static final int MAX_CHARS = 200_000;

	@Override
	public String getName() {
		return "eclipse_get_launch_output"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reads the console output of a launch's processes: the last tailLines lines of stdout and of stderr, kept apart, plus terminated and exitValue per process. Read only. Use it when eclipse_debug_launch or eclipse_debug_status shows a program that ended or never suspended, since the reason, such as a startup exception, is only in the output. Works for sessions this server started and for launches a person started that eclipse_debug_status lists. The IDE buffers output from the start of the process, so lines printed before the first call are included."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "sessionId": {"type":"string","description":"The id eclipse_debug_launch or eclipse_debug_status reported. Optional when exactly one session is live, or exactly one exists."},
				    "pattern":   {"type":"string","description":"Regular expression; only lines containing a match are returned, and total counts those lines."},
				    "tailLines": {"type":"integer","default":200,"minimum":1,"maximum":5000,"description":"Lines kept from the end of each stream; total and truncated say what was left out."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public String getOutputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "sessionId":  {"type":"string"},
				    "terminated": {"type":"boolean"},
				    "processes":  {"type":"array","items":{"type":"object","properties":{"label":{"type":["string","null"]},"pid":{"type":["string","null"]},"terminated":{"type":"boolean"},"exitValue":{"type":"integer"},"note":{"type":"string"},"stdout":{"type":"object","properties":{"total":{"type":"integer"},"returned":{"type":"integer"},"truncated":{"type":"boolean"},"text":{"type":"string"}}},"stderr":{"type":"object","properties":{"total":{"type":"integer"},"returned":{"type":"integer"},"truncated":{"type":"boolean"},"text":{"type":"string"}}}}}},
				    "note":       {"type":"string"}
				  },
				  "required": ["sessionId","terminated","processes"]
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		int tailLines = args.getInt("tailLines", 200, 1, 5000); //$NON-NLS-1$
		Pattern pattern = null;
		if (args.getString("pattern") != null) { //$NON-NLS-1$
			try {
				pattern = Pattern.compile(args.getString("pattern")); //$NON-NLS-1$
			} catch (PatternSyntaxException e) {
				throw new McpToolException("pattern is not a valid regular expression: %s".formatted(e.getDescription())); //$NON-NLS-1$
			}
		}
		DebugSessionRegistry.Session session = select(DebugSessionRegistry.getInstance(),
				args.getString("sessionId")); //$NON-NLS-1$
		ILaunch launch = session.launch();
		JsonArray processes = new JsonArray();
		if (launch != null) {
			for (IProcess process : launch.getProcesses()) {
				processes.add(describe(process, tailLines, pattern));
			}
		}
		JsonObject json = new JsonObject().put("sessionId", session.id()) //$NON-NLS-1$
				.put("terminated", Boolean.valueOf(session.terminated())) //$NON-NLS-1$
				.put("processes", processes); //$NON-NLS-1$
		if (processes.size() == 0) {
			json.put("note", "No process is registered for this launch yet, or none was started."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return McpToolResult.of(json.toString());
	}

	private static DebugSessionRegistry.Session select(DebugSessionRegistry registry, String sessionId)
			throws McpToolException {
		if (sessionId != null) {
			DebugSessionRegistry.Session session = registry.find(sessionId);
			if (session == null) {
				throw new McpToolException("No debug session '%s'. Known ids, oldest first: %s".formatted(sessionId, //$NON-NLS-1$
						String.join(", ", registry.ids()))); //$NON-NLS-1$
			}
			return session;
		}
		DebugSessionRegistry.Session live = registry.singleLive();
		if (live != null) {
			return live;
		}
		var all = registry.all();
		if (all.size() == 1) {
			return all.get(0);
		}
		throw new McpToolException(
				"Pass 'sessionId'. Known ids, oldest first: %s".formatted(String.join(", ", registry.ids()))); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static JsonObject describe(IProcess process, int tailLines, Pattern pattern) {
		JsonObject entry = new JsonObject().put("label", process.getLabel()) //$NON-NLS-1$
				.put("pid", process.getAttribute(IProcess.ATTR_PROCESS_ID)) //$NON-NLS-1$
				.put("terminated", Boolean.valueOf(process.isTerminated())); //$NON-NLS-1$
		if (process.isTerminated()) {
			try {
				entry.put("exitValue", Integer.valueOf(process.getExitValue())); //$NON-NLS-1$
			} catch (DebugException e) {
				// a process that cannot say how it ended still ended
			}
		}
		IStreamsProxy streams = process.getStreamsProxy();
		if (streams == null) {
			return entry.put("note", "This process has no console streams, so its output is not captured."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return entry.put("stdout", tail(streams.getOutputStreamMonitor(), tailLines, pattern)) //$NON-NLS-1$
				.put("stderr", tail(streams.getErrorStreamMonitor(), tailLines, pattern)); //$NON-NLS-1$
	}

	/** Everything the launch's processes printed so far, both streams. */
	static String contents(ILaunch launch) {
		StringBuilder text = new StringBuilder();
		if (launch != null) {
			for (IProcess process : launch.getProcesses()) {
				IStreamsProxy streams = process.getStreamsProxy();
				if (streams != null) {
					for (IStreamMonitor monitor : new IStreamMonitor[] { streams.getOutputStreamMonitor(),
							streams.getErrorStreamMonitor() }) {
						if (monitor != null && monitor.getContents() != null) {
							text.append(monitor.getContents()).append('\n');
						}
					}
				}
			}
		}
		return text.toString();
	}

	private static JsonObject tail(IStreamMonitor monitor, int tailLines, Pattern pattern) {
		String contents = monitor == null || monitor.getContents() == null ? "" : monitor.getContents(); //$NON-NLS-1$
		String[] lines = contents.isEmpty() ? new String[0] : contents.split("\\R", -1); //$NON-NLS-1$
		int count = lines.length;
		if (count > 0 && lines[count - 1].isEmpty()) {
			count--;
		}
		if (pattern != null) {
			lines = Arrays.stream(lines, 0, count).filter(line -> pattern.matcher(line).find()).toArray(String[]::new);
			count = lines.length;
		}
		int from = Math.max(0, count - tailLines);
		String text = String.join("\n", Arrays.copyOfRange(lines, from, count)); //$NON-NLS-1$
		boolean cut = false;
		while (text.length() > MAX_CHARS && from < count - 1) {
			// whole lines only, so returned counts exactly what text holds
			text = text.substring(lines[from].length() + 1);
			from++;
			cut = true;
		}
		if (text.length() > MAX_CHARS) {
			text = text.substring(text.length() - MAX_CHARS);
			cut = true;
		}
		return new JsonObject().put("total", Integer.valueOf(count)) //$NON-NLS-1$
				.put("returned", Integer.valueOf(count - from)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(from > 0 || cut)) //$NON-NLS-1$
				.put("text", text); //$NON-NLS-1$
	}
}
