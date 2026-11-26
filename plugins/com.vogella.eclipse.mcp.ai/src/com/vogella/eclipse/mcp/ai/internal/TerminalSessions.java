package com.vogella.eclipse.mcp.ai.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The terminals this IDE started, kept for the life of the workbench.
 */
public final class TerminalSessions {

	private static final Map<String, TerminalSession> SESSIONS = new ConcurrentHashMap<>();
	private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

	private TerminalSessions() {
	}

	public static void add(TerminalSession session) {
		SESSIONS.put(session.id(), session);
		changed();
	}

	public static void remove(TerminalSession session) {
		if (SESSIONS.remove(session.id()) != null) {
			changed();
		}
	}

	public static TerminalSession get(String id) {
		return id == null ? null : SESSIONS.get(id);
	}

	public static List<TerminalSession> all() {
		return new ArrayList<>(SESSIONS.values());
	}

	/** A tmux client this IDE attached to an external session, or {@code null}. */
	public static TerminalSession showing(ClaudeSession session) {
		for (TerminalSession terminal : SESSIONS.values()) {
			if (terminal.shows(session) && !terminal.ended()) {
				return terminal;
			}
		}
		return null;
	}

	/** Runs whenever a terminal is added, removed, renamed or ends; may be called on any thread. */
	public static void addListener(Runnable listener) {
		LISTENERS.add(listener);
	}

	public static void removeListener(Runnable listener) {
		LISTENERS.remove(listener);
	}

	static void changed() {
		LISTENERS.forEach(Runnable::run);
	}
}
