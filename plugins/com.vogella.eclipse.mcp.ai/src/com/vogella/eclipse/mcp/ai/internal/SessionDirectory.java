package com.vogella.eclipse.mcp.ai.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * Reads the live sessions from Claude Code's sessions folder.
 */
public final class SessionDirectory {

	private final Path directory;
	private final LongPredicate alive;

	public SessionDirectory(Path directory, LongPredicate alive) {
		this.directory = directory;
		this.alive = alive;
	}

	/** The sessions folder of the current user. */
	public static SessionDirectory userDefault() {
		return new SessionDirectory(ClaudeHome.directory().resolve("sessions"), SessionDirectory::isAlive); //$NON-NLS-1$
	}

	public Path directory() {
		return directory;
	}

	/**
	 * Lists the sessions whose process still runs, newest first; a file that cannot be read or parsed is skipped,
	 * since Claude Code rewrites these files in place.
	 */
	public List<ClaudeSession> read() {
		List<ClaudeSession> sessions = new ArrayList<>();
		if (!Files.isDirectory(directory)) {
			return sessions;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) { //$NON-NLS-1$
			for (Path file : files) {
				ClaudeSession session = readFile(file);
				if (session != null && alive.test(session.pid())) {
					sessions.add(session);
				}
			}
		} catch (IOException e) {
			return sessions;
		}
		sessions.sort(Comparator.comparingLong(ClaudeSession::startedAt).reversed());
		return sessions;
	}

	private static ClaudeSession readFile(Path file) {
		try {
			return ClaudeSession.parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			return null;
		}
	}

	static boolean isAlive(long pid) {
		return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}
}
