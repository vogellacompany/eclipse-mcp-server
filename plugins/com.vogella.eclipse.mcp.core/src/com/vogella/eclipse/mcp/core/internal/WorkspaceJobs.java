package com.vogella.eclipse.mcp.core.internal;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.Job;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * What the job manager is doing right now, and waiting for it to stop.
 * <p>
 * The auto-build is nobody's build: it is not started through a tool, so a
 * registry of started builds cannot see it, and after a restart it is the only
 * thing running. Anything timed against a workspace that is still building
 * measures the build as well.
 */
final class WorkspaceJobs {

	/** The families worth naming, in the order a restart runs them. */
	private static final Object[][] FAMILIES = {
			{ "autoBuild", ResourcesPlugin.FAMILY_AUTO_BUILD }, //$NON-NLS-1$
			{ "manualBuild", ResourcesPlugin.FAMILY_MANUAL_BUILD }, //$NON-NLS-1$
			{ "autoRefresh", ResourcesPlugin.FAMILY_AUTO_REFRESH }, //$NON-NLS-1$
			{ "manualRefresh", ResourcesPlugin.FAMILY_MANUAL_REFRESH } }; //$NON-NLS-1$

	private WorkspaceJobs() {
	}

	/** running, waiting or none, per family, plus every other job that is not the framework's own. */
	static JsonObject snapshot() {
		JsonObject json = new JsonObject();
		boolean building = false;
		for (Object[] family : FAMILIES) {
			String state = state(family[1]);
			json.put((String) family[0], state);
			if (!state.equals("none") && ((String) family[0]).endsWith("Build")) { //$NON-NLS-1$ //$NON-NLS-2$
				building = true;
			}
		}
		JsonArray others = new JsonArray();
		for (Job job : busy()) {
			if (!belongsToAFamily(job)) {
				others.add(new JsonObject().put("name", job.getName()) //$NON-NLS-1$
						.put("state", state(job.getState()))); //$NON-NLS-1$
			}
		}
		return json.put("building", Boolean.valueOf(building)) //$NON-NLS-1$
				.put("otherJobs", others) //$NON-NLS-1$
				.put("idle", Boolean.valueOf(Job.getJobManager().isIdle())); //$NON-NLS-1$
	}

	/** The same busy families and jobs as {@link #snapshot()}, by name only. */
	static JsonArray running() {
		JsonArray names = new JsonArray();
		for (Object[] family : FAMILIES) {
			if (!state(family[1]).equals("none")) { //$NON-NLS-1$
				names.add(family[0]);
			}
		}
		for (Job job : busy()) {
			if (!belongsToAFamily(job)) {
				names.add(job.getName());
			}
		}
		return names;
	}

	/** What a wait ran into: the parts it waited for, and whether it ran out of time. */
	record Quiet(JsonArray waitedFor, boolean timedOut) {
	}

	/**
	 * Joins the build and refresh families and then waits for the remaining jobs.
	 *
	 * @return what was waited for, each with its own duration
	 */
	static Quiet waitUntilQuiet(long deadline) {
		JsonArray waited = new JsonArray();
		// repeated because a job ending in the second phase can schedule a build,
		// the auto-build after JDT's initialisation for one
		while (true) {
			if (!joinFamilies(deadline, waited)) {
				return new Quiet(waited, true);
			}
			if (!waitForOtherJobs(deadline, waited)) {
				return new Quiet(waited, true);
			}
			boolean familyBusy = false;
			for (Object[] family : FAMILIES) {
				familyBusy |= !state(family[1]).equals("none"); //$NON-NLS-1$
			}
			if (!familyBusy) {
				return new Quiet(waited, false);
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return new Quiet(waited, true);
			}
		}
	}

	private static boolean joinFamilies(long deadline, JsonArray waited) {
		for (Object[] family : FAMILIES) {
			long startedAt = System.currentTimeMillis();
			boolean wasBusy = !state(family[1]).equals("none"); //$NON-NLS-1$
			try {
				Job.getJobManager().join(family[1], deadlineMonitor(deadline));
			} catch (OperationCanceledException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				waited.add(entry((String) family[0], startedAt, "timeout")); //$NON-NLS-1$
				return false;
			}
			if (wasBusy) {
				waited.add(entry((String) family[0], startedAt, "done")); //$NON-NLS-1$
			}
		}
		return true;
	}

	private static boolean waitForOtherJobs(long deadline, JsonArray waited) {
		// the rest is whatever else the restart brought up, and a name is the only
		// thing that can be said about it from here
		long startedAt = System.currentTimeMillis();
		List<String> last = List.of();
		while (System.currentTimeMillis() < deadline) {
			List<String> running = new ArrayList<>();
			for (Job job : busy()) {
				if (job.getState() == Job.RUNNING && !belongsToAFamily(job)) {
					running.add(job.getName());
				}
			}
			if (running.isEmpty()) {
				if (!last.isEmpty()) {
					waited.add(entry(String.join(", ", last), startedAt, "done")); //$NON-NLS-1$ //$NON-NLS-2$
				}
				return true;
			}
			last = running;
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		waited.add(entry(last.isEmpty() ? "jobs" : String.join(", ", last), startedAt, "timeout")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		return false;
	}

	private static JsonObject entry(String what, long startedAt, String outcome) {
		return new JsonObject().put("what", what) //$NON-NLS-1$
				.put("millis", Long.valueOf(System.currentTimeMillis() - startedAt)) //$NON-NLS-1$
				.put("outcome", outcome); //$NON-NLS-1$
	}

	/** A monitor that reports cancelled once the deadline has passed, which is how join takes a timeout. */
	private static IProgressMonitor deadlineMonitor(long deadline) {
		return new NullProgressMonitor() {
			@Override
			public boolean isCanceled() {
				return System.currentTimeMillis() >= deadline;
			}
		};
	}

	private static List<Job> busy() {
		List<Job> busy = new ArrayList<>();
		for (Job job : Job.getJobManager().find(null)) {
			if (!job.isSystem() && job.getState() != Job.NONE) {
				busy.add(job);
			}
		}
		return busy;
	}

	private static boolean belongsToAFamily(Job job) {
		for (Object[] family : FAMILIES) {
			if (job.belongsTo(family[1])) {
				return true;
			}
		}
		return false;
	}

	private static String state(Object family) {
		// the constants ascend from NONE to RUNNING, so the largest is the busiest
		int state = Job.NONE;
		for (Job job : Job.getJobManager().find(family)) {
			state = Math.max(state, job.getState());
		}
		return state(state);
	}

	private static String state(int state) {
		return switch (state) {
		case Job.RUNNING -> "running"; //$NON-NLS-1$
		case Job.WAITING -> "waiting"; //$NON-NLS-1$
		case Job.SLEEPING -> "sleeping"; //$NON-NLS-1$
		default -> "none"; //$NON-NLS-1$
		};
	}
}
