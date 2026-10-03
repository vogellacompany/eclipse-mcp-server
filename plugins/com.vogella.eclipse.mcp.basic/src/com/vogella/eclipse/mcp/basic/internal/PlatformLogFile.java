package com.vogella.eclipse.mcp.basic.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the entries of the platform log, the file behind the Error Log view.
 * <p>
 * The format is the one Equinox writes: an {@code !ENTRY} line starts a status,
 * {@code !SUBENTRY} lines nest the children of a multi status, {@code !MESSAGE}
 * and {@code !STACK} carry their payload on the following lines until the next
 * directive.
 */
public final class PlatformLogFile {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"); //$NON-NLS-1$

	/**
	 * One logged status. {@code time} is {@code null} when the entry carried no
	 * parsable timestamp, {@code stackTrace} when it carried no {@code !STACK}
	 * block.
	 */
	public record Entry(String plugin, int severity, int code, LocalDateTime time, String message, String exception,
			String stackTrace, List<Entry> children) {
	}

	private PlatformLogFile() {
	}

	/** Returns the entries of {@code file} in the order they were written. */
	public static List<Entry> read(Path file) throws IOException {
		return parse(readLines(file, 0));
	}

	/**
	 * Returns the entries written after byte {@code fromByte}.
	 * <p>
	 * Entries are appended whole, so a position recorded as the file's size is
	 * always a record boundary and parsing can start there. Reading from a position
	 * rather than filtering on a timestamp is what makes a marker exact: it needs no
	 * clock, and a file that has since shrunk is visibly not the file that was
	 * marked.
	 */
	public static List<Entry> read(Path file, long fromByte) throws IOException {
		return parse(readLines(file, fromByte));
	}

	/** Whether the file still holds everything up to {@code position}. */
	public static boolean stillHas(Path file, long position) throws IOException {
		return Files.size(file) >= position;
	}

	/**
	 * The log is written with the platform's default encoding, which is not
	 * recorded anywhere, so undecodable bytes are replaced rather than failing a
	 * read of an otherwise fine file.
	 */
	private static List<String> readLines(Path file, long fromByte) throws IOException {
		var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE);
		byte[] bytes = Files.readAllBytes(file);
		int from = (int) Math.clamp(fromByte, 0, bytes.length);
		String text = decoder.decode(ByteBuffer.wrap(bytes, from, bytes.length - from)).toString();
		return List.of(text.split("\r\n|\n|\r", -1)); //$NON-NLS-1$
	}

	private static List<Entry> parse(List<String> lines) {
		List<Entry> entries = new ArrayList<>();
		// the builder at index n is the entry a !SUBENTRY of depth n+1 attaches to
		List<Builder> open = new ArrayList<>();
		Builder current = null;
		StringBuilder message = null;
		StringBuilder stack = null;

		for (String line : lines) {
			if (line.startsWith("!ENTRY ")) { //$NON-NLS-1$
				current = finishAndStart(entries, open, current, message, stack, 0, header(line, 1));
				message = null;
				stack = null;
			} else if (line.startsWith("!SUBENTRY ")) { //$NON-NLS-1$
				int depth = depthOf(line);
				current = finishAndStart(entries, open, current, message, stack, depth, header(line, 2));
				message = null;
				stack = null;
			} else if (line.startsWith("!MESSAGE")) { //$NON-NLS-1$
				closeAccumulators(current, message, stack);
				message = new StringBuilder(line.length() > "!MESSAGE ".length() //$NON-NLS-1$
						? line.substring("!MESSAGE ".length()) //$NON-NLS-1$
						: ""); //$NON-NLS-1$
				stack = null;
			} else if (line.startsWith("!STACK")) { //$NON-NLS-1$
				closeAccumulators(current, message, stack);
				message = null;
				stack = new StringBuilder();
			} else if (line.startsWith("!SESSION")) { //$NON-NLS-1$
				current = finish(entries, open, current, message, stack);
				message = null;
				stack = null;
			} else if (message != null) {
				message.append('\n').append(line);
			} else if (stack != null) {
				if (stack.length() > 0) {
					stack.append('\n');
				}
				stack.append(line);
			}
		}
		finish(entries, open, current, message, stack);
		return entries;
	}

	/** Closes the entry under construction and starts a new one at {@code depth}. */
	private static Builder finishAndStart(List<Entry> entries, List<Builder> open, Builder current,
			StringBuilder message, StringBuilder stack, int depth, Builder started) {
		closeAccumulators(current, message, stack);
		if (depth == 0) {
			flushRoot(entries, open);
		} else {
			// a shallower subentry ends every deeper one
			while (open.size() > depth) {
				open.removeLast();
			}
			if (!open.isEmpty()) {
				open.getLast().children.add(started);
			}
		}
		if (started == null) {
			return null;
		}
		if (depth == 0) {
			open.clear();
		}
		open.add(started);
		return started;
	}

	private static Builder finish(List<Entry> entries, List<Builder> open, Builder current, StringBuilder message,
			StringBuilder stack) {
		closeAccumulators(current, message, stack);
		flushRoot(entries, open);
		return null;
	}

	private static void flushRoot(List<Entry> entries, List<Builder> open) {
		if (!open.isEmpty()) {
			entries.add(open.getFirst().build());
			open.clear();
		}
	}

	private static void closeAccumulators(Builder current, StringBuilder message, StringBuilder stack) {
		if (current == null) {
			return;
		}
		if (message != null) {
			current.message = trimTrailingBlankLines(message.toString());
		}
		if (stack != null) {
			current.stackTrace = trimTrailingBlankLines(stack.toString());
		}
	}

	private static String trimTrailingBlankLines(String text) {
		int end = text.length();
		while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
			end--;
		}
		return end == text.length() ? text : text.substring(0, end);
	}

	private static int depthOf(String line) {
		String[] parts = line.split(" +"); //$NON-NLS-1$
		try {
			return Math.max(1, Integer.parseInt(parts[1]));
		} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
			return 1;
		}
	}

	/**
	 * Parses {@code !ENTRY <plugin> <severity> <code> <date> <time>}, where
	 * {@code first} is the index of the plugin name among the whitespace separated
	 * tokens. Severity and code are optional in the format, so they are only taken
	 * when they are numbers.
	 */
	private static Builder header(String line, int first) {
		String[] parts = line.split(" +"); //$NON-NLS-1$
		if (parts.length <= first) {
			return null;
		}
		Builder builder = new Builder();
		builder.plugin = parts[first];
		int next = first + 1;
		if (parts.length > next + 1 && isNumber(parts[next]) && isNumber(parts[next + 1])) {
			builder.severity = Integer.parseInt(parts[next]);
			builder.code = Integer.parseInt(parts[next + 1]);
			next += 2;
		}
		if (parts.length > next + 1) {
			builder.time = timestamp(parts[next] + " " + parts[next + 1]); //$NON-NLS-1$
		}
		return builder;
	}

	private static boolean isNumber(String text) {
		if (text.isEmpty()) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			if (!Character.isDigit(text.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static LocalDateTime timestamp(String text) {
		try {
			return LocalDateTime.parse(text, TIMESTAMP);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static final class Builder {
		private String plugin = ""; //$NON-NLS-1$
		private int severity;
		private int code;
		private LocalDateTime time;
		private String message = ""; //$NON-NLS-1$
		private String stackTrace;
		private final List<Builder> children = new ArrayList<>();

		Entry build() {
			List<Entry> built = new ArrayList<>(children.size());
			for (Builder child : children) {
				built.add(child.build());
			}
			return new Entry(plugin, severity, code, time, message, exception(), stackTrace, List.copyOf(built));
		}

		/** The first line of a stack trace is the throwable, the rest are frames. */
		private String exception() {
			if (stackTrace == null || stackTrace.isEmpty()) {
				return null;
			}
			int newline = stackTrace.indexOf('\n');
			String head = (newline < 0 ? stackTrace : stackTrace.substring(0, newline)).trim();
			return head.isEmpty() || head.startsWith("at ") ? null : head; //$NON-NLS-1$
		}
	}
}
