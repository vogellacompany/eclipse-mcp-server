package com.vogella.eclipse.mcp.server;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Brings the running server in line with the preferences, off the calling thread.
 */
public final class McpServerLifecycle {

	/** Two reconciliations must not overlap: one would stop what the other just started. */
	private static final ISchedulingRule RULE = new ISchedulingRule() {

		@Override
		public boolean isConflicting(ISchedulingRule rule) {
			return rule == this;
		}

		@Override
		public boolean contains(ISchedulingRule rule) {
			return rule == this;
		}
	};

	private McpServerLifecycle() {
	}

	/** Schedules the reconciliation and returns the job, so that callers can react when it is done. */
	public static Job reconcile() {
		Job job = new ReconcileJob();
		job.setRule(RULE);
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
