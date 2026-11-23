package com.vogella.eclipse.mcp.gradle.internal;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** The shared answer of the tools that start or poll a Gradle run, so they cannot describe one differently. */
final class RunAnswers {

	private RunAnswers() {
	}

	/** Waits up to {@code timeoutSeconds} within the call budget, then describes the run, with a note while it still runs. */
	static McpToolResult waitAndDescribe(GradleRuns.Run run, int timeoutSeconds, int tailLines) {
		run.await(CallBudget.boundedWaitSeconds(timeoutSeconds) * 1000L);
		return describe(run, timeoutSeconds, tailLines);
	}

	static McpToolResult describe(GradleRuns.Run run, int timeoutSeconds, int tailLines) {
		JsonObject json = run.describe(tailLines);
		if (run.isRunning()) {
			String clamped = CallBudget.clampNote(timeoutSeconds, "eclipse_get_gradle_run with this runId"); //$NON-NLS-1$
			json.put("note", clamped == null //$NON-NLS-1$
					? "Still running. Poll eclipse_get_gradle_run with this runId, and pass cancel to stop it." //$NON-NLS-1$
					: clamped);
		}
		return McpToolResult.of(json.toString());
	}
}
