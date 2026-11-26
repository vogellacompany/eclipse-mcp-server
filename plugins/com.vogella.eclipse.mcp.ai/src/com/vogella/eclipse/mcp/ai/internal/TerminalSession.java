package com.vogella.eclipse.mcp.ai.internal;

import java.util.List;
import java.util.UUID;

/**
 * A terminal this IDE started: a Claude Code session, or a tmux client attached to a session that runs elsewhere.
 * The AI view shows each one in its own {@link TerminalPane}.
 */
public final class TerminalSession {

	public enum Kind {
		CLAUDE, ATTACH
	}

	private final String id = UUID.randomUUID().toString();
	private final Kind kind;
	private final List<String> command;
	private final String workingDirectory;
	private final String claudeSessionId;
	private final long attachedPid;
	private volatile String title;
	private volatile long pid;
	private volatile boolean ended;

	private TerminalSession(Kind kind, List<String> command, String workingDirectory, String title,
			String claudeSessionId, long attachedPid) {
		this.kind = kind;
		this.command = List.copyOf(command);
		this.workingDirectory = workingDirectory;
		this.title = title;
		this.claudeSessionId = claudeSessionId;
		this.attachedPid = attachedPid;
	}

	/** A new Claude Code session in {@code directory}, started with a session id chosen here. */
	public static TerminalSession claude(String executable, String directory, String title) {
		String sessionId = UUID.randomUUID().toString();
		return new TerminalSession(Kind.CLAUDE, List.of(executable, "--session-id", sessionId), directory, title, //$NON-NLS-1$
				sessionId, 0);
	}

	/** A tmux client attached to the pane an external session runs in. */
	public static TerminalSession attach(List<String> command, ClaudeSession external, String title) {
		return new TerminalSession(Kind.ATTACH, command, external.cwd(), title, null, external.pid());
	}

	public String id() {
		return id;
	}

	public Kind kind() {
		return kind;
	}

	public List<String> command() {
		return command;
	}

	public String workingDirectory() {
		return workingDirectory;
	}

	public String title() {
		return title;
	}

	void setTitle(String title) {
		this.title = title;
	}

	public long pid() {
		return pid;
	}

	void setPid(long pid) {
		this.pid = pid;
	}

	public boolean ended() {
		return ended;
	}

	void setEnded() {
		ended = true;
	}

	/**
	 * Whether a sessions file describes this terminal: the process this IDE started, or the session id it was
	 * started with, which Claude Code changes on {@code /clear} while the pid stays.
	 */
	public boolean runs(ClaudeSession session) {
		if (kind != Kind.CLAUDE) {
			return false;
		}
		return pid != 0 && session.pid() == pid || claudeSessionId.equals(session.sessionId());
	}

	/** Whether this is a tmux client showing the given external session. */
	public boolean shows(ClaudeSession session) {
		return kind == Kind.ATTACH && attachedPid == session.pid();
	}
}
