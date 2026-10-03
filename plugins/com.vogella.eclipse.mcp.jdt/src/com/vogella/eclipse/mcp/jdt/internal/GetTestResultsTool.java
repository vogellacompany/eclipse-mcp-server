package com.vogella.eclipse.mcp.jdt.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.ClientSessions;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** Reports a test run started through {@code eclipse_run_tests}. */
public final class GetTestResultsTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_get_test_results"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports a test run started through eclipse_run_tests: how many passed, failed, errored and were ignored, and the failing cases with their stack traces and expected and actual values. Passing tests are omitted by default, because the failures are what the question was about. Pass abandon to give up on a run that is still going: only one run may be active at a time, so a run that never reports would otherwise block every later one."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "runId":         {"type":"string","description":"Identifier returned by eclipse_run_tests. Omit for the most recent run."},
				    "includePassed": {"type":"boolean","default":false,"description":"Also list the tests that passed. Off by default, because a green suite is thousands of lines saying nothing."},
				    "maxResults":    {"type":"integer","default":50,"minimum":1,"maximum":2000},
				    "abandon":       {"type":"boolean","default":false,"description":"Give up on a run that is still going, terminating its launch. Only one run may be active at a time, so a run that never reports would otherwise block every later run."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String runId = args.getString("runId"); //$NON-NLS-1$
		if (runId == null && !ClientSessions.canAssumeASingleClient()) {
			return McpToolResult.error(ClientSessions.ambiguousDefault("test run", //$NON-NLS-1$
					"runId", TestRunRegistry.getInstance().ids())); //$NON-NLS-1$
		}
		TestRunRegistry.Run run = runId == null ? TestRunRegistry.getInstance().findLatest()
				: TestRunRegistry.getInstance().find(runId);
		if (run == null) {
			if (runId != null) {
				return McpToolResult.error("No test run with the id '%s'.".formatted(runId)); //$NON-NLS-1$
			}
			return McpToolResult.of(new JsonObject().put("state", "none") //$NON-NLS-1$ //$NON-NLS-2$
					.put("message", "No test run has been started.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		boolean abandoned = args.getBoolean("abandon", false) && TestRunRegistry.abandon(run); //$NON-NLS-1$
		JsonObject result = TestRunRegistry.toJson(run, args.getInt("maxResults", 50, 1, 2000), //$NON-NLS-1$
				args.getBoolean("includePassed", false)); //$NON-NLS-1$
		if (args.getBoolean("abandon", false)) { //$NON-NLS-1$
			// zero tests is the expected outcome of an abandon, so the warnings would be false alarms
			result.remove("stateInconsistent"); //$NON-NLS-1$
			result.remove("launchedPlatformErrors"); //$NON-NLS-1$
			result.remove("launchedPlatformNote"); //$NON-NLS-1$
			result.put("abandoned", abandoned) //$NON-NLS-1$
					.put("abandonNote", abandoned ? "The run was abandoned and its launch terminated." //$NON-NLS-1$ //$NON-NLS-2$
							: "Nothing to abandon; the run had already finished."); //$NON-NLS-1$
		}
		return McpToolResult.of(result.toString());
	}
}
