package com.vogella.eclipse.mcp.core.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Waits until the workspace has stopped building and the jobs have run out.
 */
public final class WaitUntilQuietTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_wait_until_quiet"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Waits until the auto-build, the manual build and the refresh jobs have finished and no other job is running, then answers what it waited for and how long each part took. Changes nothing. THIS IS THE TOOL TO CALL BEFORE TIMING ANYTHING: after a restart the workspace builds for a minute or two on its own, and a measurement taken in that window measures the build. Watching the process from outside cannot tell the quiet before the build starts from the quiet after it, which is the mistake this exists to prevent; from inside the two are different job states. waitedFor is empty when nothing was running, which is itself the answer that the IDE was already idle. WITH timeoutSeconds 1 IT IS A STATUS QUERY rather than a wait, and unlike eclipse_get_build_status it belongs to no client and therefore needs no id, which makes it the way to ask whether the workspace is building while several clients are connected. IT ANSWERS BEFORE THE SERVER'S OWN CALL TIMEOUT RUNS OUT, with state 'stillBusy' and the jobs that are still going, because a call that is abandoned mid-wait tells the caller nothing at all; ask again until the state is 'quiet', which is a loop of a few calls for the build after a restart. That intermediate answer is kept small, 'running' with the names of what is still going instead of the full jobsBefore and jobsAfter blocks. Raising the timeout in Preferences > General > MCP Server raises what one call can wait for. IT ALSO COVERS A TARGET PLATFORM RESOLVE started by eclipse_set_target_platform, which runs as an ordinary job and is named in waitedFor, so one wait can cover a build and a resolve together; eclipse_get_target_platform waits for that one on its own if the resolve is all that matters. It does NOT cover the Java index: JDT runs that in a queue of its own outside the job manager, and eclipse_search_types with a narrow pattern is what blocks until the index is ready."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "timeoutSeconds": {"type":"integer","default":120,"minimum":1,"maximum":900,"description":"How long to wait at most. It is capped by the server's own call timeout, which is what the answer reports as budgetSeconds; past that the answer is 'stillBusy' rather than nothing, and the wait is resumed by calling again."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		int timeoutSeconds = ToolArguments.of(arguments).getInt("timeoutSeconds", 120, 1, 900); //$NON-NLS-1$
		int budget = Math.min(timeoutSeconds, callTimeoutSeconds() - 3);
		JsonObject before = WorkspaceJobs.snapshot();
		long startedAt = System.currentTimeMillis();
		WorkspaceJobs.Quiet quiet = WorkspaceJobs.waitUntilQuiet(startedAt + Math.max(1, budget) * 1000L);
		long elapsed = System.currentTimeMillis() - startedAt;
		boolean timedOut = quiet.timedOut();
		JsonArray waited = quiet.waitedFor();
		// the caller asked for longer than one call may take, so the wait is not over,
		// only this answer is
		boolean cut = timedOut && budget < timeoutSeconds;
		JsonObject json = new JsonObject().put("state", timedOut ? cut ? "stillBusy" : "timeout" : "quiet") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				.put("elapsedMillis", Long.valueOf(elapsed)) //$NON-NLS-1$
				.put("budgetSeconds", Integer.valueOf(budget)) //$NON-NLS-1$
				.put("requestedSeconds", Integer.valueOf(timeoutSeconds)) //$NON-NLS-1$
				.put("waitedFor", waited); //$NON-NLS-1$
		if (cut) {
			// the caller is going to ask again, so what is still running is all it needs
			json.put("running", WorkspaceJobs.running()); //$NON-NLS-1$
		} else {
			json.put("jobsBefore", before).put("jobsAfter", WorkspaceJobs.snapshot()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return McpToolResult.of(json.put("note", note(timedOut, cut, waited.size(), budget)).toString()); //$NON-NLS-1$
	}

	/**
	 * The server's own call timeout, read by name.
	 * <p>
	 * Reading the preference rather than the server's own class keeps this bundle
	 * free of the bundle that depends on it; the answer is only used to stop waiting
	 * before the call is abandoned.
	 */
	private static int callTimeoutSeconds() {
		return org.eclipse.core.runtime.Platform.getPreferencesService()
				.getInt("com.vogella.eclipse.mcp.server", "callTimeoutSeconds", 30, null); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static String note(boolean timedOut, boolean cut, int waitedFor, int timeoutSeconds) {
		if (cut) {
			return "Still busy after %d seconds, which is as long as one call may take before the server abandons it. 'running' names what is still going; call again to go on waiting, or raise the call timeout in Preferences > General > MCP Server." //$NON-NLS-1$
					.formatted(Integer.valueOf(timeoutSeconds));
		}
		if (timedOut) {
			return "Gave up after %d seconds; jobsAfter names what is still running. A job that never ends, an auto-refresh over a slow file system for instance, keeps the workspace from ever going quiet." //$NON-NLS-1$
					.formatted(Integer.valueOf(timeoutSeconds));
		}
		if (waitedFor == 0) {
			return "Nothing was running when this was called, so nothing was waited for. That is the answer a measurement wants, and it is not the same as the quiet right after a restart, where the build has not begun yet."; //$NON-NLS-1$
		}
		return "The workspace is quiet as far as the job manager is concerned. JDT indexing runs outside it and is not covered."; //$NON-NLS-1$
	}
}
