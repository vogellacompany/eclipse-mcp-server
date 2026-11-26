package com.vogella.eclipse.mcp.ai.internal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One session in the drawer of the AI view: a sessions file, the terminal this IDE started for it, or both.
 */
public record SessionRow(ClaudeSession session, TerminalSession terminal) {

	/** Joins the live sessions with this IDE's Claude terminals, its own sessions first. */
	public static List<SessionRow> of(List<ClaudeSession> sessions, List<TerminalSession> terminals) {
		List<SessionRow> rows = new ArrayList<>();
		List<TerminalSession> unmatched = new ArrayList<>(terminals);
		unmatched.removeIf(terminal -> terminal.kind() != TerminalSession.Kind.CLAUDE);
		for (ClaudeSession session : sessions) {
			TerminalSession terminal = null;
			for (TerminalSession candidate : unmatched) {
				if (candidate.runs(session)) {
					terminal = candidate;
					break;
				}
			}
			if (terminal != null) {
				unmatched.remove(terminal);
				if (terminal.pid() == 0) {
					terminal.setPid(session.pid());
				}
			}
			rows.add(new SessionRow(session, terminal));
		}
		for (TerminalSession terminal : unmatched) {
			rows.add(new SessionRow(null, terminal));
		}
		rows.sort(Comparator.comparing((SessionRow row) -> !row.owned()).thenComparing(
				Comparator.comparingLong(SessionRow::startedAt).reversed()));
		return rows;
	}

	/** Whether the session runs in a terminal of this IDE. */
	public boolean owned() {
		return terminal != null;
	}

	public String name() {
		if (session != null) {
			return session.displayName();
		}
		return terminal.title();
	}

	public String folder() {
		if (session != null) {
			return session.folderName();
		}
		Path fileName = Path.of(terminal.workingDirectory()).getFileName();
		return fileName == null ? terminal.workingDirectory() : fileName.toString();
	}

	public String cwd() {
		return session != null ? session.cwd() : terminal.workingDirectory();
	}

	/** The badge text: busy, shell, idle or waiting from Claude Code, or starting and ended for a terminal. */
	public String status() {
		if (terminal != null && terminal.ended()) {
			return Messages.statusEnded;
		}
		if (session == null) {
			return Messages.statusStarting;
		}
		return session.status() != null ? session.status() : ""; //$NON-NLS-1$
	}

	long startedAt() {
		return session != null ? session.startedAt() : Long.MAX_VALUE;
	}

	/** Milliseconds since the session last changed status. */
	public long idleMillis(long now) {
		return session == null ? 0 : Math.max(0, now - session.statusUpdatedAt());
	}

	/** A short age such as {@code now}, {@code 5m}, {@code 3h} or {@code 2d}. */
	public static String age(long millis) {
		long minutes = millis / 60_000;
		if (minutes < 1) {
			return "now"; //$NON-NLS-1$
		}
		if (minutes < 60) {
			return minutes + "m"; //$NON-NLS-1$
		}
		long hours = minutes / 60;
		if (hours < 24) {
			return hours + "h"; //$NON-NLS-1$
		}
		return hours / 24 + "d"; //$NON-NLS-1$
	}
}
