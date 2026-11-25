package com.vogella.eclipse.mcp.gradle.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.ClientSessions;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports, and cancels, a run started through the Gradle tools.
 */
public final class GetGradleRunTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_get_gradle_run"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports a run started through eclipse_run_gradle_tasks or eclipse_refresh_gradle_project: running, done, failed or cancelled, and the tail of its output. The tail is what a long build needs, because the failing task is at the end; raise tailLines when the reason is further up, and droppedLines says how much fell out of the buffer entirely. Pass cancel to stop one that is still running, which asks Gradle to cancel the build. Omitting runId reports the most recent run, and is refused while a second client is connected, since that run may be the other client's. Changes nothing unless cancel is set."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "runId":     {"type":"string","description":"Identifier returned by eclipse_run_gradle_tasks or eclipse_refresh_gradle_project. Omit for the most recent, which is refused while a second client is connected."},
				    "tailLines": {"type":"integer","default":200,"minimum":1,"maximum":2000,"description":"How many of the last output lines to return."},
				    "cancel":    {"type":"boolean","default":false,"description":"Cancel the run if it is still running."},
				    "wait":      {"type":"boolean","default":false,"description":"Wait for it to finish first, bounded by timeoutSeconds and by the server's own call timeout."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"How long to wait when wait is true. Bounded in practice by the server's own call timeout."}
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
				    "runId":         {"type":"string"},
				    "kind":          {"type":"string"},
				    "project":       {"type":"string"},
				    "tasks":         {"type":"array","items":{"type":"string"}},
				    "state":         {"type":"string"},
				    "elapsedMillis": {"type":"integer"},
				    "droppedLines":  {"type":"integer"},
				    "output":        {"type":"string"},
				    "note":          {"type":"string"}
				  },
				  "required": ["state"]
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String id = args.getString("runId"); //$NON-NLS-1$
		GradleRuns registry = GradleRuns.getInstance();
		if (registry.isEmpty()) {
			return McpToolResult.of(new JsonObject().put("state", "none") //$NON-NLS-1$ //$NON-NLS-2$
					.put("note", "No Gradle run has been started through this server yet.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (id == null && !ClientSessions.canAssumeASingleClient()) {
			return McpToolResult.error(ClientSessions.ambiguousDefault("run", "runId", registry.knownIds())); //$NON-NLS-1$ //$NON-NLS-2$
		}
		GradleRuns.Run run = registry.get(id);
		if (run == null) {
			return McpToolResult.error("There is no Gradle run '%s'. Known: %s".formatted(id, //$NON-NLS-1$
					String.join(", ", registry.knownIds()))); //$NON-NLS-1$
		}
		if (args.getBoolean("cancel", false)) { //$NON-NLS-1$
			run.cancel();
		}
		int requested = args.getInt("timeoutSeconds", 25, 1, 3600); //$NON-NLS-1$
		int tail = args.getInt("tailLines", 200, 1, 2000); //$NON-NLS-1$
		boolean wait = args.getBoolean("wait", false); //$NON-NLS-1$
		int bounded = wait ? CallBudget.boundedWaitSeconds(requested) : requested;
		if (wait || args.getBoolean("cancel", false)) { //$NON-NLS-1$
			// a cancel is given a moment to take effect, so the answer says cancelled and not running
			run.await(wait ? bounded * 1000L : 2000L);
		}
		return RunAnswers.describe(run, requested, bounded, tail);
	}
}
