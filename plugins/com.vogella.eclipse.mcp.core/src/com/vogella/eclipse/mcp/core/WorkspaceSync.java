package com.vogella.eclipse.mcp.core;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Brings the workspace in line with the file system.
 * <p>
 * A client that edits files through its own shell is invisible to the IDE until the
 * workspace is refreshed, so anything derived from the resource tree, problem markers
 * above all, would otherwise be reported from a stale state.
 */
public final class WorkspaceSync {

	private WorkspaceSync() {
	}

	/** Reads changes made outside the IDE into the workspace. */
	public static void refresh(IResource resource, IProgressMonitor monitor) throws CoreException {
		if (resource != null && resource.exists()) {
			resource.refreshLocal(IResource.DEPTH_INFINITE, monitor);
		}
	}

	/**
	 * Builds and waits, restricted to {@code scope} when one is given.
	 * <p>
	 * {@code IWorkspace.build} takes no resource, so an unscoped build rebuilds every open project.
	 *
	 * @return {@code false} when the wait was cancelled, which leaves the markers stale
	 */
	public static boolean build(IProject scope, IProgressMonitor monitor) throws CoreException {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		if (!workspace.isAutoBuilding()) {
			if (scope != null && scope.isAccessible()) {
				scope.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
			} else {
				workspace.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
			}
		}
		return waitForBuild(monitor);
	}

	/** Waits for the running builds to finish. Returns {@code false} when cancelled. */
	public static boolean waitForBuild(IProgressMonitor monitor) {
		for (Object family : new Object[] { ResourcesPlugin.FAMILY_AUTO_BUILD, ResourcesPlugin.FAMILY_MANUAL_BUILD }) {
			try {
				Job.getJobManager().join(family, monitor);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			} catch (OperationCanceledException e) {
				return false;
			}
		}
		return true;
	}

	public static boolean isAutoBuilding() {
		return ResourcesPlugin.getWorkspace().isAutoBuilding();
	}
}
