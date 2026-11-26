package com.vogella.eclipse.mcp.ai.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import com.vogella.eclipse.mcp.core.json.Json;

/**
 * Reads the person's prompts and Claude's answers from a session transcript, the JSON Lines file Claude Code keeps
 * under {@code ~/.claude/projects}.
 */
public final class Transcript {

	/** One prompt or answer. */
	public record Message(boolean fromUser, String text, String timestamp) {
	}

	/** What was read: the newest messages, and how many older ones were left out. */
	public record Contents(List<Message> messages, int omitted) {
	}

	private Transcript() {
	}

	/** The folder name Claude Code derives from a working directory: every character but letters and digits is a dash. */
	public static String projectFolderName(String cwd) {
		return cwd.replaceAll("[^A-Za-z0-9]", "-"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Finds the transcript of a session, looking in every project folder when the one derived from the working
	 * directory does not have it.
	 */
	public static Path locate(Path projectsDirectory, String cwd, String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return null;
		}
		String fileName = sessionId + ".jsonl"; //$NON-NLS-1$
		if (cwd != null) {
			Path expected = projectsDirectory.resolve(projectFolderName(cwd)).resolve(fileName);
			if (Files.isRegularFile(expected)) {
				return expected;
			}
		}
		if (!Files.isDirectory(projectsDirectory)) {
			return null;
		}
		try (DirectoryStream<Path> folders = Files.newDirectoryStream(projectsDirectory, Files::isDirectory)) {
			for (Path folder : folders) {
				Path candidate = folder.resolve(fileName);
				if (Files.isRegularFile(candidate)) {
					return candidate;
				}
			}
		} catch (IOException e) {
			return null;
		}
		return null;
	}

	/** The transcript of a session in the user's Claude Code folder, or {@code null}. */
	public static Path locate(ClaudeSession session) {
		return locate(ClaudeHome.directory().resolve("projects"), session.cwd(), session.sessionId()); //$NON-NLS-1$
	}

	/** Reads the newest {@code limit} messages; lines that are not JSON, such as one being written, are skipped. */
	public static Contents read(Path file, int limit) throws IOException {
		Deque<Message> newest = new ArrayDeque<>();
		int omitted = 0;
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				Message message = parseLine(line);
				if (message == null) {
					continue;
				}
				newest.addLast(message);
				if (newest.size() > limit) {
					newest.removeFirst();
					omitted++;
				}
			}
		}
		return new Contents(new ArrayList<>(newest), omitted);
	}

	/** Returns the prompt or answer a transcript line holds, or {@code null} for everything else. */
	static Message parseLine(String line) {
		if (line.isBlank() || !(line.contains("\"type\":\"user\"") || line.contains("\"type\":\"assistant\""))) { //$NON-NLS-1$ //$NON-NLS-2$
			return null;
		}
		Object parsed;
		try {
			parsed = Json.parse(line);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (!(parsed instanceof Map<?, ?> entry) || Boolean.TRUE.equals(entry.get("isSidechain")) //$NON-NLS-1$
				|| Boolean.TRUE.equals(entry.get("isMeta"))) { //$NON-NLS-1$
			return null;
		}
		Object type = entry.get("type"); //$NON-NLS-1$
		boolean fromUser = "user".equals(type); //$NON-NLS-1$
		if (!fromUser && !"assistant".equals(type) || !(entry.get("message") instanceof Map<?, ?> message)) { //$NON-NLS-1$ //$NON-NLS-2$
			return null;
		}
		String text = text(message.get("content")).strip(); //$NON-NLS-1$
		// harness-generated user turns (task notifications, command output, reminders) are tagged
		if (text.isEmpty() || fromUser && text.startsWith("<")) { //$NON-NLS-1$
			return null;
		}
		Object timestamp = entry.get("timestamp"); //$NON-NLS-1$
		return new Message(fromUser, text, timestamp instanceof String s ? s : null);
	}

	private static String text(Object content) {
		if (content instanceof String s) {
			return s;
		}
		if (!(content instanceof List<?> blocks)) {
			return ""; //$NON-NLS-1$
		}
		StringBuilder text = new StringBuilder();
		for (Object block : blocks) {
			if (block instanceof Map<?, ?> map && "text".equals(map.get("type")) && map.get("text") instanceof String s) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				if (!text.isEmpty()) {
					text.append("\n\n"); //$NON-NLS-1$
				}
				text.append(s);
			}
		}
		return text.toString();
	}
}
