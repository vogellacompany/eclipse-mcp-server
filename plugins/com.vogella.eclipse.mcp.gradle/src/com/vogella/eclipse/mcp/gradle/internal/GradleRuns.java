package com.vogella.eclipse.mcp.gradle.internal;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Runs Gradle work as jobs and keeps their output, so that a client polls
 * instead of holding an HTTP request open for the length of a build.
 * <p>
 * Free of Buildship types: the work itself is a {@link Body} supplied by the caller.
 */
public final class GradleRuns {

	/** How many runs stay queryable; running ones are always kept, so finished ones go first. */
	private static final int HISTORY = 20;

	/** Output lines kept per run. A build log is long and the useful part is at the end. */
	private static final int KEPT_LINES = 2000;

	private static final GradleRuns INSTANCE = new GradleRuns();

	private final AtomicLong ids = new AtomicLong();

	private final Map<String, Run> runs = new LinkedHashMap<>();

	private String lastId;

	public static GradleRuns getInstance() {
		return INSTANCE;
	}

	private GradleRuns() {
	}

	/** The work of a run; returns whether it succeeded. */
	@FunctionalInterface
	public interface Body {
		boolean run(Run run, IProgressMonitor monitor) throws Exception;
	}

	/** One run, polled through {@code eclipse_get_gradle_run}. */
	public static final class Run {

		private final String id;
		private final String kind;
		private final String project;
		private final List<String> tasks;
		private final long startedAt = System.currentTimeMillis();
		private final CountDownLatch finished = new CountDownLatch(1);
		private final Deque<String> lines = new ArrayDeque<>();

		private volatile Runnable cancelAction = () -> {
		};
		private volatile boolean cancelRequested;
		private volatile String state = "running"; //$NON-NLS-1$
		private volatile long endedAt;
		private volatile int droppedLines;

		Run(String id, String kind, String project, List<String> tasks) {
			this.id = id;
			this.kind = kind;
			this.project = project;
			this.tasks = tasks;
		}

		public String id() {
			return id;
		}

		public boolean isRunning() {
			return "running".equals(state); //$NON-NLS-1$
		}

		/** Replaces what {@link #cancel} does, once the run has something better than cancelling its job. */
		public void onCancel(Runnable action) {
			cancelAction = action;
			if (cancelRequested) {
				action.run();
			}
		}

		public void cancel() {
			if (!isRunning()) {
				return;
			}
			cancelRequested = true;
			cancelAction.run();
		}

		public boolean await(long millis) {
			try {
				return finished.await(millis, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		public synchronized void append(String line) {
			lines.addLast(line);
			if (lines.size() > KEPT_LINES) {
				lines.removeFirst();
				droppedLines++;
			}
		}

		public synchronized List<String> tail(int count) {
			List<String> all = List.copyOf(lines);
			return all.size() <= count ? all : all.subList(all.size() - count, all.size());
		}

		/** A stream that turns what Gradle writes into output lines. */
		OutputStream output() {
			return new LineStream(this);
		}

		synchronized void finish(String outcome) {
			if (!isRunning()) {
				return;
			}
			endedAt = System.currentTimeMillis();
			state = outcome;
			finished.countDown();
		}

		public JsonObject describe(int tailLines) {
			JsonArray taskList = new JsonArray();
			tasks.forEach(taskList::add);
			return new JsonObject().put("runId", id) //$NON-NLS-1$
					.put("kind", kind) //$NON-NLS-1$
					.put("project", project) //$NON-NLS-1$
					.put("tasks", taskList) //$NON-NLS-1$
					.put("state", state) //$NON-NLS-1$
					.put("elapsedMillis", Long.valueOf((endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt)) //$NON-NLS-1$
					.put("droppedLines", Integer.valueOf(droppedLines)) //$NON-NLS-1$
					.put("output", String.join("\n", tail(tailLines))); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/** Starts {@code body} as a job and hands back its handle at once. */
	public synchronized Run start(String kind, String project, List<String> tasks, Body body) {
		String id = "gradle-" + ids.incrementAndGet(); //$NON-NLS-1$
		Run run = new Run(id, kind, project, List.copyOf(tasks));
		runs.put(id, run);
		evictFinished();
		lastId = id;

		Job job = Job.create("Gradle " + kind + " " + project, (IProgressMonitor monitor) -> { //$NON-NLS-1$ //$NON-NLS-2$
			try {
				boolean ok = body.run(run, monitor);
				run.finish(run.cancelRequested || monitor.isCanceled() ? "cancelled" : ok ? "done" : "failed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			} catch (Exception | LinkageError e) {
				if (!run.cancelRequested) {
					run.append("Gradle %s failed: %s".formatted(kind, describe(e))); //$NON-NLS-1$
					ILog.get().warn("The MCP Gradle run %s failed".formatted(id), e); //$NON-NLS-1$
				}
				run.finish(run.cancelRequested ? "cancelled" : "failed"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return Status.OK_STATUS;
		});
		run.cancelAction = job::cancel;
		// a job cancelled before it starts, or ended by an Error, never reaches the body's own finish
		job.addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(IJobChangeEvent event) {
				run.finish(run.cancelRequested || event.getResult().matches(IStatus.CANCEL) ? "cancelled" : "failed"); //$NON-NLS-1$ //$NON-NLS-2$
			}
		});
		job.setPriority(Job.LONG);
		job.schedule();
		return run;
	}

	/** The message of {@code e} and of its causes, since Gradle puts the reason a build failed at the bottom. */
	static String describe(Throwable e) {
		StringBuilder text = new StringBuilder();
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t.getMessage() != null && !text.toString().contains(t.getMessage())) {
				text.append(text.isEmpty() ? "" : "\nCaused by: ").append(t.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return text.isEmpty() ? e.getClass().getName() : text.toString();
	}

	/** The run for {@code id}, or the most recent one when {@code id} is null. */
	public synchronized Run get(String id) {
		return runs.get(id == null ? lastId : id);
	}

	synchronized boolean isEmpty() {
		return runs.isEmpty();
	}

	synchronized List<String> knownIds() {
		return List.copyOf(runs.keySet());
	}

	/** Drops the oldest finished runs until at most HISTORY remain; running ones are kept. */
	private void evictFinished() {
		while (runs.size() > HISTORY) {
			String oldestFinished = null;
			for (Map.Entry<String, Run> entry : runs.entrySet()) {
				if (!entry.getValue().isRunning()) {
					oldestFinished = entry.getKey();
					break;
				}
			}
			if (oldestFinished == null) {
				return;
			}
			runs.remove(oldestFinished);
		}
	}

	/** Splits what is written into lines, decoding each on its own as UTF-8. */
	static final class LineStream extends OutputStream {

		private final Run run;
		private final ByteArrayOutputStream line = new ByteArrayOutputStream();

		LineStream(Run run) {
			this.run = run;
		}

		@Override
		public synchronized void write(int b) {
			if (b == '\n') {
				emit();
			} else {
				line.write(b);
			}
		}

		@Override
		public synchronized void write(byte[] bytes, int offset, int length) {
			for (int i = offset; i < offset + length; i++) {
				write(bytes[i]);
			}
		}

		@Override
		public synchronized void close() {
			if (line.size() > 0) {
				emit();
			}
		}

		private void emit() {
			String text = line.toString(StandardCharsets.UTF_8);
			line.reset();
			run.append(text.endsWith("\r") ? text.substring(0, text.length() - 1) : text); //$NON-NLS-1$
		}
	}
}
