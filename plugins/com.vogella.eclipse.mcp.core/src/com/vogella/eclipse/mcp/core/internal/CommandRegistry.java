package com.vogella.eclipse.mcp.core.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
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
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.vogella.eclipse.mcp.core.FileLocations;

/**
 * Runs external commands as jobs and keeps their output, so that a client polls
 * instead of holding an HTTP request open for the length of a build.
 */
public final class CommandRegistry {

	/** The number in chcp's output, whose surrounding words are localised. */
	private static final Pattern CODE_PAGE = Pattern.compile("\\d+"); //$NON-NLS-1$

	private static Charset outputCharset;

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
		private volatile boolean cancelRequested;
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
			// a process that already exited keeps its own outcome rather than being reported as cancelled
			if (running != null && !running.isAlive()) {
				return;
			}
			cancelRequested = true;
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
				if (execution.cancelRequested) {
					// cancel arrived before there was a process to end
					execution.cancel();
				}
				try (InputStream stream = process.getInputStream()) {
					readLines(stream, outputCharset(), execution::append);
				} catch (IOException e) {
					// destroying the process closes its stream under the reader
					if (!execution.cancelRequested) {
						throw e;
					}
				}
				int code = process.waitFor();
				execution.finish(execution.cancelRequested ? "cancelled" : code == 0 ? "done" : "failed", code); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			} catch (IOException | RuntimeException e) {
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
	 * Splits {@code stream} into lines and decodes each one on its own.
	 * <p>
	 * Per line rather than through one {@code InputStreamReader}, because the two
	 * writers a build log mixes do not agree on an encoding, and a line comes from
	 * one of them whole.
	 */
	public static void readLines(InputStream stream, Charset fallback, Consumer<String> sink) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int read;
		while ((read = stream.read(buffer)) != -1) {
			for (int i = 0; i < read; i++) {
				if (buffer[i] == '\n') {
					sink.accept(decodeLine(line.toByteArray(), fallback));
					line.reset();
				} else {
					line.write(buffer[i]);
				}
			}
		}
		if (line.size() > 0) {
			sink.accept(decodeLine(line.toByteArray(), fallback));
		}
	}

	/**
	 * Decodes one line as UTF-8, falling back to {@code fallback} when it is not
	 * valid UTF-8.
	 * <p>
	 * Git and everything else built for UTF-8 writes it whatever the console is set
	 * to, so preferring it is what keeps those readable; the fallback is what the
	 * console programs need. A pure ASCII line decodes the same either way, which is
	 * almost every line of a build log.
	 */
	public static String decodeLine(byte[] bytes, Charset fallback) {
		int end = bytes.length;
		if (end > 0 && bytes[end - 1] == '\r') {
			end--;
		}
		try {
			// newDecoder reports malformed input, unlike the String constructor, which
			// silently replaces it and would make the fallback unreachable
			return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, end)).toString();
		} catch (CharacterCodingException e) {
			return new String(bytes, 0, end, fallback);
		}
	}

	/**
	 * What a spawned process writes its output in when it is not UTF-8.
	 * <p>
	 * Windows has two code pages and this is the one nothing in the JVM reports:
	 * {@code native.encoding} is the ANSI code page, while cmd.exe and the tools it
	 * starts write the OEM one, so an umlaut from a Maven log arrived as three wrong
	 * characters. Only {@code chcp} knows the number, so it is asked once and cached.
	 * Everywhere else {@code native.encoding} is right and is UTF-8 anyway.
	 */
	static synchronized Charset outputCharset() {
		if (outputCharset == null) {
			outputCharset = FileLocations.isWindows() ? consoleCharset() : nativeCharset();
		}
		return outputCharset;
	}

	private static Charset consoleCharset() {
		try {
			Process process = new ProcessBuilder("cmd.exe", "/c", "chcp").redirectErrorStream(true).start(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			String reported;
			try (InputStream stream = process.getInputStream()) {
				// the code page is a number whatever language the rest of the line is in
				reported = new String(stream.readAllBytes(), StandardCharsets.US_ASCII);
			}
			if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
				Charset charset = charsetOfCodePage(reported);
				if (charset != null) {
					return charset;
				}
			}
			process.destroyForcibly();
		} catch (IOException e) {
			ILog.get().warn("Could not read the console code page, falling back to native.encoding", e); //$NON-NLS-1$
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return nativeCharset();
	}

	/** The charset of the code page {@code chcp} reported, or null for one Java does not know. */
	public static Charset charsetOfCodePage(String reported) {
		Matcher matcher = CODE_PAGE.matcher(reported);
		String page = null;
		while (matcher.find()) {
			page = matcher.group();
		}
		if (page == null) {
			return null;
		}
		if ("65001".equals(page)) { //$NON-NLS-1$
			return StandardCharsets.UTF_8;
		}
		for (String name : List.of("IBM" + page, "windows-" + page, "x-IBM" + page)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			try {
				return Charset.forName(name);
			} catch (IllegalArgumentException e) {
				// try the next spelling
			}
		}
		return null;
	}

	static Charset nativeCharset() {
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
