package com.vogella.eclipse.mcp.core.internal;

import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Stops the builds that are running, this server's own and the workspace's.
 */
public final class CancelBuildTool implements IMcpTool {

	/** How long to watch for a cancelled job to actually end. */
	private static final int DEFAULT_WAIT = 5;

	@Override
	public String getName() {
		return "eclipse_cancel_build"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Cancels the builds currently running: the ones eclipse_build started and the workspace's own auto-build and manual build jobs. CANCELS WORK IN PROGRESS, and what a half finished build leaves behind is a partly built workspace, so the problem counts afterwards describe nothing until something builds again. CANCELLING IS A REQUEST, NOT A KILL: a builder only stops where it checks its progress monitor, so one that never checks runs to the end of what it is doing; the answer reports for each job whether it had actually ended by the time of answering. Auto-build is the usual reason to reach for this and the one case where cancelling buys the least: while autobuilding is on it starts again for the next change, so the answer says whether it is on, and turning it off through eclipse_set_preference with description.autobuilding is the thing that actually stops it coming back."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "waitSeconds": {"type":"integer","default":5,"minimum":0,"maximum":20,"description":"How long to watch for the cancelled jobs to end before answering. The answer reports what was still running when the time was up rather than waiting longer."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		int waitSeconds = ToolArguments.of(arguments).getInt("waitSeconds", DEFAULT_WAIT, 0, 20); //$NON-NLS-1$
		JsonArray cancelled = new JsonArray();

		for (BuildRegistry.Build build : BuildRegistry.getInstance().running()) {
			if (build.cancel()) {
				cancelled.add(new JsonObject().put("what", "eclipse_build") //$NON-NLS-1$ //$NON-NLS-2$
						.put("buildId", build.id()) //$NON-NLS-1$
						.put("kind", build.kind())); //$NON-NLS-1$
			}
		}
		IJobManager jobs = Job.getJobManager();
		int platformJobs = cancelFamily(jobs, ResourcesPlugin.FAMILY_MANUAL_BUILD, "manual build", cancelled) //$NON-NLS-1$
				+ cancelFamily(jobs, ResourcesPlugin.FAMILY_AUTO_BUILD, "auto-build", cancelled); //$NON-NLS-1$

		boolean autoBuilding = ResourcesPlugin.getWorkspace().isAutoBuilding();
		JsonObject result = new JsonObject().put("cancelled", cancelled) //$NON-NLS-1$
				.put("autoBuildEnabled", Boolean.valueOf(autoBuilding)); //$NON-NLS-1$
		if (cancelled.size() == 0) {
			return McpToolResult.of(result
					.put("note", autoBuilding //$NON-NLS-1$
							? "Nothing was building, so nothing was cancelled. Auto-build is on, so a build starts by itself at the next change." //$NON-NLS-1$
							: "Nothing was building, so nothing was cancelled.") //$NON-NLS-1$
					.toString());
		}
		if (waitSeconds > 0) {
			awaitEnd(jobs, waitSeconds);
		}
		int stillRunning = activeBuilds(jobs);
		result.put("waitedSeconds", Integer.valueOf(waitSeconds)) //$NON-NLS-1$
				.put("stillRunning", Integer.valueOf(stillRunning)) //$NON-NLS-1$
				.put("note", note(stillRunning, platformJobs, autoBuilding)); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	private static String note(int stillRunning, int platformJobs, boolean autoBuilding) {
		StringBuilder note = new StringBuilder();
		if (stillRunning > 0) {
			note.append(
					"%d build job had not ended when the wait was up. Cancelling only takes effect where a builder checks its monitor, so one that does not check runs to the end of its current work; ask again to see whether it stopped. "
							.formatted(Integer.valueOf(stillRunning)));
		} else if (platformJobs > 0) {
			note.append("The workspace build jobs had ended by the time of answering. "); //$NON-NLS-1$
		}
		note.append(
				"A cancelled build leaves the workspace partly built, so error and warning counts mean nothing until something builds again. "); //$NON-NLS-1$
		if (autoBuilding) {
			note.append(
					"Auto-build is ON, so the next change starts a build again. To stop that, set description.autobuilding to false under org.eclipse.core.resources through eclipse_set_preference, which applies it through the workspace description."); //$NON-NLS-1$
		} else {
			note.append("Auto-build is off, so nothing starts a build by itself."); //$NON-NLS-1$
		}
		return note.toString();
	}

	private static int cancelFamily(IJobManager jobs, Object family, String what, JsonArray cancelled) {
		Job[] found = jobs.find(family);
		for (Job job : found) {
			job.cancel();
			cancelled.add(new JsonObject().put("what", what) //$NON-NLS-1$
					.put("job", job.getName())); //$NON-NLS-1$
		}
		return found.length;
	}

	/** The platform's build jobs plus the ones {@code eclipse_build} started, which belong to no platform family. */
	private static int activeBuilds(IJobManager jobs) {
		return jobs.find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length + jobs.find(ResourcesPlugin.FAMILY_AUTO_BUILD).length
				+ BuildRegistry.getInstance().running().size();
	}

	/** Watches rather than joins: joining a build job waits for it to finish, which is the opposite of the point. */
	private static void awaitEnd(IJobManager jobs, int waitSeconds) {
		long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
		while (System.currentTimeMillis() < deadline) {
			if (activeBuilds(jobs) == 0) {
				return;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
