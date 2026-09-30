package com.vogella.eclipse.mcp.pde.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.LoadTargetDefinitionJob;

import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * One run of "resolve this target definition, then make it the active target
 * platform", kept so that a call which outlives the tool call timeout can still
 * be polled.
 */
final class TargetLoad {

	private static TargetLoad current;

	private final String target;
	private final ITargetDefinition definition;
	private final boolean resolveOnly;
	private final JsonObject previous;
	private final long startedAt = System.currentTimeMillis();
	private final CountDownLatch finished = new CountDownLatch(1);

	private volatile String state = "running"; //$NON-NLS-1$
	private volatile long endedAt;
	private volatile long resolveMillis;
	private volatile IStatus resolveStatus;
	private volatile IStatus loadStatus;
	private volatile Job job;

	private TargetLoad(String target, ITargetDefinition definition, boolean resolveOnly, JsonObject previous) {
		this.target = target;
		this.definition = definition;
		this.resolveOnly = resolveOnly;
		this.previous = previous;
	}

	/** Starts the work as a job. Any run still going is cancelled first, as PDE does. */
	static synchronized TargetLoad start(String target, ITargetDefinition definition, boolean resolveOnly,
			JsonObject previous) {
		if (current != null && current.isRunning() && current.job != null) {
			current.job.cancel();
		}
		TargetLoad load = new TargetLoad(target, definition, resolveOnly, previous);
		Job job = new Job("Resolving target platform " + (definition.getName() == null ? target : definition.getName())) { //$NON-NLS-1$
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				return load.run(monitor);
			}
		};
		load.job = job;
		current = load;
		job.schedule();
		return load;
	}

	static synchronized TargetLoad current() {
		return current;
	}

	boolean isRunning() {
		return finished.getCount() > 0;
	}

	boolean await(int seconds) throws InterruptedException {
		return finished.await(seconds, TimeUnit.SECONDS);
	}

	private IStatus run(IProgressMonitor monitor) {
		try {
			long resolveStartedAt = System.currentTimeMillis();
			resolveStatus = definition.resolve(monitor);
			resolveMillis = System.currentTimeMillis() - resolveStartedAt;
			if (monitor.isCanceled()) {
				return end("cancelled", Status.CANCEL_STATUS); //$NON-NLS-1$
			}
			if (resolveStatus != null && resolveStatus.getSeverity() == IStatus.ERROR) {
				return end("failed", Status.OK_STATUS); //$NON-NLS-1$
			}
			if (resolveOnly) {
				return end("resolved", Status.OK_STATUS); //$NON-NLS-1$
			}
			loadStatus = load(monitor);
			if (loadStatus == null || loadStatus.getSeverity() == IStatus.CANCEL) {
				return end("cancelled", Status.CANCEL_STATUS); //$NON-NLS-1$
			}
			return end(loadStatus.getSeverity() == IStatus.ERROR ? "failed" : "done", Status.OK_STATUS); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (OperationCanceledException e) {
			return end("cancelled", Status.CANCEL_STATUS); //$NON-NLS-1$
		} catch (RuntimeException e) {
			loadStatus = Status.error(String.valueOf(e.getMessage()), e);
			return end("failed", Status.OK_STATUS); //$NON-NLS-1$
		}
	}

	/** Runs PDE's own load job and waits for it, so that its result is reportable. */
	private IStatus load(IProgressMonitor monitor) {
		CountDownLatch loaded = new CountDownLatch(1);
		IStatus[] result = new IStatus[1];
		Job[] loadJob = new Job[1];
		// through PDE's static load(), because that is what cancels a competing load
		// and gives the job the scheduling rule that keeps two resolves apart
		LoadTargetDefinitionJob.load(definition, new JobChangeAdapter() {
			@Override
			public void scheduled(IJobChangeEvent event) {
				loadJob[0] = event.getJob();
			}

			@Override
			public void done(IJobChangeEvent event) {
				result[0] = event.getResult();
				loaded.countDown();
			}
		});
		try {
			while (!loaded.await(250, TimeUnit.MILLISECONDS)) {
				if (monitor.isCanceled()) {
					if (loadJob[0] != null) {
						loadJob[0].cancel();
					}
					return Status.CANCEL_STATUS;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Status.CANCEL_STATUS;
		}
		return result[0];
	}

	private IStatus end(String finalState, IStatus jobResult) {
		state = finalState;
		endedAt = System.currentTimeMillis();
		finished.countDown();
		return jobResult;
	}

	/** Whether this run loaded the definition {@code other} names, which is when a description of it can be reused. */
	boolean isFor(ITargetDefinition other) {
		String mine = TargetPlatforms.memento(definition.getHandle());
		return mine != null && other != null && mine.equals(TargetPlatforms.memento(other.getHandle()));
	}

	JsonObject toJson(boolean includeLocations, int maxProblems, int maxResults) {
		return toJson(includeLocations, maxProblems, maxResults, null, null);
	}

	/** {@code activeJson} is the already computed description of {@code active}, reused when it is this run's target. */
	JsonObject toJson(boolean includeLocations, int maxProblems, int maxResults, ITargetDefinition active,
			JsonObject activeJson) {
		JsonObject json = new JsonObject().put("target", target) //$NON-NLS-1$
				.put("state", state) //$NON-NLS-1$
				.put("resolveOnly", resolveOnly) //$NON-NLS-1$
				.put("elapsedMillis", (endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt) //$NON-NLS-1$
				.put("resolveMillis", resolveMillis == 0 ? null : Long.valueOf(resolveMillis)) //$NON-NLS-1$
				.put("resolveStatus", TargetPlatforms.status(resolveStatus)) //$NON-NLS-1$
				.put("loadStatus", TargetPlatforms.status(loadStatus)) //$NON-NLS-1$
				.put("previous", previous); //$NON-NLS-1$
		if (isRunning()) {
			json.put("note", //$NON-NLS-1$
					"Still resolving. Resolving a target that is not cached downloads from its p2 repositories and can take many minutes. Poll it with eclipse_get_target_platform."); //$NON-NLS-1$
			return json;
		}
		json.put("definition", //$NON-NLS-1$
				activeJson != null && isFor(active) ? activeJson
						: TargetPlatforms.describe(definition, includeLocations, maxProblems, maxResults));
		if ("done".equals(state)) { //$NON-NLS-1$
			json.put("note", //$NON-NLS-1$
					"This is now the active target platform, and PDE recomputed the plug-in classpaths against it. THE JRE BINDING IS NOT UNDONE BY ACTIVATING SOMETHING ELSE: a target that names a JRE overwrites the WORKSPACE DEFAULT VM, measured, not assumed, and every project whose JRE container names no execution environment follows that one setting. It outlives the target that set it, so going back to another target leaves the new VM in place, and a project compiling for a newer release than that VM can serve then fails with a message about the release rather than about the target. The jre field says which install this one names. Problem markers across the workspace change with it: eclipse_get_problems builds first and reports the new state, and eclipse_get_bundle_info with unresolvedOnly names the bundles that still do not resolve. BUILD FULLY BEFORE BELIEVING ANY ERROR COUNT: a target switch changes what every plug-in project compiles against without changing a single file, so an incremental build, which starts from a resource delta, can leave thousands of stale errors standing. Use eclipse_build with kind 'full', and read builderFailures in its answer, since an exception inside JavaBuilder never becomes a problem marker."); //$NON-NLS-1$
		}
		return json;
	}
}
