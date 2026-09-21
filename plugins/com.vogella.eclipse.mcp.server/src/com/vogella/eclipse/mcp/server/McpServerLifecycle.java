package com.vogella.eclipse.mcp.server;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Brings the running server in line with the preferences, off the calling thread.
 */
public final class McpServerLifecycle {

	/**
	 * Two reconciliations must not overlap: one would stop what the other just
	 * started. A lock rather than a scheduling rule, because starting the server
	 * loads the tools, which can activate org.eclipse.core.resources, and opening
	 * the workspace inside a foreign rule fails and takes the IDE down with it.
	 */
	private static final Object LOCK = new Object();

	private McpServerLifecycle() {
	}

	/** Schedules the reconciliation and returns the job, so that callers can react when it is done. */
	public static Job reconcile() {
		Job job = new ReconcileJob();
		job.schedule();
		return job;
	}

	private static final class ReconcileJob extends Job {

		ReconcileJob() {
			super("Applying the MCP server preferences"); //$NON-NLS-1$
			setSystem(true);
		}

		@Override
		protected IStatus run(IProgressMonitor monitor) {
			synchronized (LOCK) {
				return reconcile();
			}
		}

		private static IStatus reconcile() {
			McpServerService service = McpServerService.getInstance();
			boolean enabled = McpPreferences.isEnabled();
			int port = McpPreferences.getPort();
			try {
				if (!enabled) {
					service.stop();
					return Status.OK_STATUS;
				}
				if (service.isRunning() && service.getPort() == port) {
					return Status.OK_STATUS;
				}
				service.stop();
				service.start();
			} catch (McpServerException e) {
				// failing to open a socket must not pop up a dialog during startup
				ILog.get().error(e.getMessage(), e.getCause() == null ? e : e.getCause());
			}
			return Status.OK_STATUS;
		}
	}
}
