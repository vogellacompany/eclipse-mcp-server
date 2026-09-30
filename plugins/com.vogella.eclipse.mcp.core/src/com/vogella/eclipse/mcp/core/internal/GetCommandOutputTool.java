package com.vogella.eclipse.mcp.core.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports a command started through {@code eclipse_run_command}.
 */
public final class GetCommandOutputTool implements IMcpTool {

	private static final int DEFAULT_TAIL = 200;

	@Override
	public String getName() {
		return "eclipse_get_command_output"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports a command started through eclipse_run_command: running, done, failed or cancelled, its exit code and the tail of its output. The tail is what a long build needs, because the failing goal is at the end and the rest is noise; raise tailLines when the reason is further up, and droppedLines says how much fell out of the buffer entirely. Pass cancel to stop one that is still running, which ends the process and everything it started. Omitting commandId reports the most recent command, which is wrong when another client started one in between."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "commandId": {"type":"string","description":"Identifier returned by eclipse_run_command. Omit for the most recent."},
				    "tailLines": {"type":"integer","default":200,"minimum":1,"maximum":2000,"description":"How many of the last output lines to return."},
				    "cancel":    {"type":"boolean","default":false,"description":"Stop the command if it is still running, together with any process it started."},
				    "wait":      {"type":"boolean","default":false,"description":"Wait for it to finish first, bounded by timeoutSeconds and by the server's own call timeout."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"How long to wait for the command to finish. Bounded in practice by the server's own call timeout."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String id = args.getString("commandId"); //$NON-NLS-1$
		CommandRegistry registry = CommandRegistry.getInstance();
		if (registry.isEmpty()) {
			return McpToolResult.of(new JsonObject().put("state", "none") //$NON-NLS-1$ //$NON-NLS-2$
					.put("note", "No command has been run through this server yet.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		CommandRegistry.Execution execution = registry.get(id);
		if (execution == null) {
			return McpToolResult.error("There is no command '%s'. Known: %s".formatted(id, //$NON-NLS-1$
					String.join(", ", registry.knownIds()))); //$NON-NLS-1$
		}
		if (args.getBoolean("cancel", false) && execution.isRunning()) { //$NON-NLS-1$
			execution.cancel();
		}
		int requested = args.getInt("timeoutSeconds", 25, 1, 3600); //$NON-NLS-1$
		boolean wait = args.getBoolean("wait", false); //$NON-NLS-1$
		if (wait) {
			execution.await(CallBudget.boundedWaitSeconds(requested) * 1000L);
		}
		int tail = args.getInt("tailLines", DEFAULT_TAIL, 1, 2000); //$NON-NLS-1$
		JsonObject json = RunCommandTool.CommandOutput.describe(execution, tail);
		if (wait && execution.isRunning()) {
			String clamped = CallBudget.clampNote(requested, "this tool again with the same commandId"); //$NON-NLS-1$
			if (clamped != null) {
				json.put("note", clamped); //$NON-NLS-1$
			}
		}
		return McpToolResult.of(json.toString());
	}
}
