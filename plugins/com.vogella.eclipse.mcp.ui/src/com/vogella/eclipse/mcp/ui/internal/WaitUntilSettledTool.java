package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;

/**
 * Waits for the UI to look idle, and reports what it could not look at.
 */
public final class WaitUntilSettledTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_wait_until_settled"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Waits until the UI looks to have stopped working, then reports how it decided and WHAT IT COULD NOT SEE. Changes nothing. THIS IS A HEURISTIC, NOT A GUARANTEE, and the answer says so in every case: use it to reduce flakiness before a capture, and assert the thing you actually need rather than trusting it. It observes two mechanisms. Work queued with asyncExec is drained by posting a fence to the Display and waiting for it, which is the only way to drain that queue from outside the UI thread, and how long the fence took is itself the measurement, since a fence that waits is a UI thread that was busy. The job manager reports what is running, waiting or SLEEPING, and a sleeping job counts as busy because a decoration scheduled to run after a build is exactly the case a capture taken too early misses. It settles only after several consecutive rounds find both quiet, with a pause between them so that briefly delayed work has a chance to appear. It also checks the text editors' RECONCILERS, which is the case that hurt most and the only one of the three that is not public API: AbstractReconciler starts as a job and hands its work to a plain daemon thread, so it is invisible to both mechanisms above and semantic highlighting landed after everything observable had gone quiet. It is reached the way JDT's own performance tests reach it, reflecting into SourceViewer.fReconciler, then AbstractReconciler.fWorker, then isDirty and isActive, plus a JavaReconciler's own initial-pass flag. Those names are internal and can change in any release, so a reconciler that cannot be read counts as BUSY rather than idle: the failure is a settle that never succeeds, never one that succeeds too early. WHAT IT STILL CANNOT SEE is any other plain background thread, and work that has not been scheduled yet. This differs from eclipse_wait_until_quiet, which lives in the headless core and therefore knows only the job manager; this one also drains the display queue, which is what the job manager cannot see. Inside a batch of tools run in one UI turn it refuses, because a fence posted from the UI thread would be run by the thread waiting for it."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "quietRounds":    {"type":"integer","default":3,"minimum":1,"maximum":20,"description":"How many consecutive rounds must find the display queue drained and the job manager idle before this answers settled. More is slower and stricter."},
				    "timeoutSeconds": {"type":"integer","default":10,"minimum":1,"maximum":120,"description":"The whole budget. Running out is answered as settled false with what it saw, never as an error, because 'it did not go quiet' is a result. Capped below the server's tool call timeout, and the answer says 'clamped' when it was; call again to keep waiting."},
				    "pauseMillis":    {"type":"integer","default":120,"minimum":10,"maximum":2000,"description":"How long to wait between rounds. This is what gives work scheduled with a small delay a chance to appear, so a very short pause makes settling easier and less meaningful."}
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
				    "settled":                {"type":"boolean"},
				    "reason":                 {"type":["string","null"]},
				    "reconcilers":            {},
				    "rounds":                 {"type":"integer"},
				    "consecutiveQuietRounds": {"type":"integer"},
				    "elapsedMillis":          {"type":"integer"},
				    "slowestFenceMillis":     {"type":"integer"},
				    "slowestProbeMillis":     {"type":"integer"},
				    "jobs":                   {},
				    "clamped":                {"type":["string","null"]},
				    "cannotSee":              {"type":"string"}
				  },
				  "required": ["settled"]
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (!Workbenches.running()) {
			return McpToolResult.error("There is no running workbench."); //$NON-NLS-1$
		}
		ToolArguments args = ToolArguments.of(arguments);
		int rounds = args.getInt("quietRounds", 3, 1, 20); //$NON-NLS-1$
		int timeout = args.getInt("timeoutSeconds", 10, 1, 120); //$NON-NLS-1$
		int pause = args.getInt("pauseMillis", 120, 10, 2000); //$NON-NLS-1$
		// deliberately not on the UI thread: the whole mechanism is a fence posted
		// from outside it
		return McpToolResult.of(UiSettle.settle(rounds, timeout * 1000L, pause, monitor).toString());
	}
}
