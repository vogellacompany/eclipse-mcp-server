package com.vogella.eclipse.mcp.core.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Runs external commands as jobs and keeps their output, so that a client polls
 * instead of holding an HTTP request open for the length of a build.
 */
public final class CommandRegistry {

	/** How many finished commands stay queryable. */
	private static final int HISTORY = 20;

	/** Output lines kept per command. A build log is long and the useful part is at the end. */
	private static final int KEPT_LINES = 2000;

	private static final CommandRegistry INSTANCE = new CommandRegistry();

	private final AtomicLong ids = new AtomicLong();

	private final Map<String, Execution> executions = new LinkedHashMap<>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Execution> eldest) {
			return size() > HISTORY && !eldest.getValue().isRunning();
		}
	};

	private String lastId;

	public static CommandRegistry getInstance() {
		return INSTANCE;
	}

	private CommandRegistry() {
	}

	/** One command run, polled through {@code eclipse_get_command_output}. */
	public static final class Execution {

		private final String id;
		private final List<String> command;
		private final String directory;
		private final long startedAt = System.currentTimeMillis();
		private final CountDownLatch finished = new CountDownLatch(1);
		private final Deque<String> lines = new ArrayDeque<>();

		private volatile Process process;
		private volatile String state = "running"; //$NON-NLS-1$
		private volatile int exitCode = -1;
		private volatile long endedAt;
		private volatile int droppedLines;

		Execution(String id, List<String> command, String directory) {
			this.id = id;
			this.command = command;
			this.directory = directory;
		}

		public String id() {
			return id;
		}

		public List<String> command() {
			return command;
		}

		public String directory() {
			return directory;
		}

		public String state() {
			return state;
		}

		public int exitCode() {
			return exitCode;
		}

		public long elapsedMillis() {
			return (endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt;
		}

		public boolean isRunning() {
			return "running".equals(state); //$NON-NLS-1$
		}

		public int droppedLines() {
			return droppedLines;
		}

		/** The last {@code count} lines of output, oldest first. */
		public synchronized List<String> tail(int count) {
			List<String> all = List.copyOf(lines);
			return all.size() <= count ? all : all.subList(all.size() - count, all.size());
		}

		synchronized void append(String line) {
			lines.addLast(line);
			if (lines.size() > KEPT_LINES) {
				lines.removeFirst();
				droppedLines++;
			}
		}

		/** Waits for the command, returning false while it is still running. */
		public boolean await(long millis) {
			try {
				return finished.await(millis, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		void finish(String outcome, int code) {
			exitCode = code;
			endedAt = System.currentTimeMillis();
			state = outcome;
			finished.countDown();
		}

		/** Ends the process and everything it started, which a build tool needs. */
		public void cancel() {
			Process running = process;
			if (running == null) {
				return;
			}
			running.descendants().forEach(ProcessHandle::destroy);
			running.destroy();
		}
	}

	/** The execution for {@code id}, or the most recent one when {@code id} is null. */
	public synchronized Execution get(String id) {
		return executions.get(id == null ? lastId : id);
	}

	public synchronized boolean isEmpty() {
		return executions.isEmpty();
	}

	public synchronized List<String> knownIds() {
		return List.copyOf(executions.keySet());
	}

	/** Starts {@code command} in {@code directory} and hands back its handle at once. */
	public synchronized Execution start(List<String> command, Path directory, Map<String, String> environment) {
		String id = "command-" + ids.incrementAndGet(); //$NON-NLS-1$
		Execution execution = new Execution(id, List.copyOf(command), directory.toString());
		executions.put(id, execution);
		lastId = id;

		Job job = Job.create("Running " + String.join(" ", command), (IProgressMonitor monitor) -> { //$NON-NLS-1$ //$NON-NLS-2$
			ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
			// merged, because whoever reads a build log wants the failure in the same
			// stream as the step that led to it
			builder.redirectErrorStream(true);
			if (environment != null) {
				builder.environment().putAll(environment);
			}
			try {
				Process process = builder.start();
				execution.process = process;
				try (InputStream stream = process.getInputStream();
						BufferedReader reader = new BufferedReader(new InputStreamReader(stream, outputCharset()))) {
					String line;
					while ((line = reader.readLine()) != null) {
						execution.append(line);
					}
				}
				int code = process.waitFor();
				execution.finish(code == 0 ? "done" : "failed", code); //$NON-NLS-1$ //$NON-NLS-2$
			} catch (IOException e) {
				execution.append("Could not run the command: " + e.getMessage()); //$NON-NLS-1$
				execution.finish("failed", -1); //$NON-NLS-1$
				ILog.get().warn("The MCP command %s failed to start".formatted(execution.id()), e); //$NON-NLS-1$
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				execution.finish("cancelled", -1); //$NON-NLS-1$
			}
			return Status.OK_STATUS;
		});
		job.setPriority(Job.LONG);
		job.schedule();
		return execution;
	}

	/**
	 * What a spawned process writes its output in.
	 * <p>
	 * Not UTF-8 unconditionally: since JDK 18 the JVM's own default is UTF-8
	 * everywhere, but a Windows console and the tools that write to it still use
	 * the machine's ANSI or OEM code page, so decoding a Maven log as UTF-8 there
	 * turns every non-ASCII byte into a replacement character. {@code native.encoding}
	 * is the JVM's report of what the operating system actually uses, and it is
	 * UTF-8 on the Linux and macOS installations this changes nothing for.
	 */
	static Charset outputCharset() {
		String name = System.getProperty("native.encoding"); //$NON-NLS-1$
		if (name == null || name.isBlank()) {
			return StandardCharsets.UTF_8;
		}
		try {
			return Charset.forName(name.strip());
		} catch (IllegalArgumentException e) {
			// an encoding this JVM does not know is no reason to lose the output
			return StandardCharsets.UTF_8;
		}
	}

	/** Only for tests. */
	public synchronized void clear() {
		executions.clear();
		lastId = null;
	}
}
