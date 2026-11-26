package com.vogella.eclipse.mcp.basic.internal;

import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.Job;

import com.vogella.eclipse.mcp.core.WorkspaceSync;

/**
 * Counts the builds that finished anywhere in the workspace, whoever started them,
 * so a problem marker can tell whether anything was built since it was taken.
 */
final class BuildCounter {

	/** No count is available, because the listener could not be registered. */
	static final long UNKNOWN = -1;

	private static final AtomicLong FINISHED = new AtomicLong();

	private static boolean listening;

	private BuildCounter() {
	}

	/**
	 * The builds finished since the listener was registered, or {@link #UNKNOWN}.
	 * It registers on first use: the workspace may not exist when the class loads,
	 * and a failed static initializer would disable the class for the session.
	 */
	static synchronized long finished() {
		if (!listening) {
			try {
				ResourcesPlugin.getWorkspace().addResourceChangeListener(event -> FINISHED.incrementAndGet(),
						IResourceChangeEvent.POST_BUILD);
				listening = true;
			} catch (IllegalStateException e) {
				return UNKNOWN;
			}
		}
		return FINISHED.get();
	}

	/**
	 * Waits for the platform's build jobs and the builds eclipse_build started.
	 * Returns {@code false} when cancelled.
	 */
	static boolean waitForBuilds(IProgressMonitor monitor) {
		try {
			Job.getJobManager().join(BuildRegistry.FAMILY, monitor);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (OperationCanceledException e) {
			return false;
		}
		return WorkspaceSync.waitForBuild(monitor);
	}
}
