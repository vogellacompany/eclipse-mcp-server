package com.vogella.eclipse.mcp.ai.internal;

import java.nio.file.Path;
import java.util.Map;

import com.vogella.eclipse.mcp.core.json.Json;

/**
 * One running Claude Code session as its {@code ~/.claude/sessions/<pid>.json} file describes it.
 */
public record ClaudeSession(long pid, String sessionId, String cwd, long startedAt, String version, String kind,
		String name, String status, long statusUpdatedAt) {

	/**
	 * Parses a sessions file, or returns {@code null} when it is not JSON or names no pid.
	 */
	public static ClaudeSession parse(String json) {
		Object parsed;
		try {
			parsed = Json.parse(json);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (!(parsed instanceof Map<?, ?> map) || !(map.get("pid") instanceof Number pid)) { //$NON-NLS-1$
			return null;
		}
		long startedAt = number(map.get("startedAt")); //$NON-NLS-1$
		long statusUpdatedAt = number(map.get("statusUpdatedAt")); //$NON-NLS-1$
		return new ClaudeSession(pid.longValue(), text(map.get("sessionId")), text(map.get("cwd")), startedAt, //$NON-NLS-1$ //$NON-NLS-2$
				text(map.get("version")), text(map.get("kind")), text(map.get("name")), text(map.get("status")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				statusUpdatedAt > 0 ? statusUpdatedAt : startedAt);
	}

	/** The last segment of the working directory, or the whole of it for a root. */
	public String folderName() {
		if (cwd == null || cwd.isEmpty()) {
			return ""; //$NON-NLS-1$
		}
		Path fileName = Path.of(cwd).getFileName();
		return fileName == null ? cwd : fileName.toString();
	}

	/** The name Claude Code gave the session, falling back to the folder. */
	public String displayName() {
		return name != null && !name.isBlank() ? name : folderName();
	}

	/** Whether Claude is working, either thinking or running a command. */
	public boolean isWorking() {
		return "busy".equals(status) || "shell".equals(status); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Whether Claude has stopped and is waiting for the person. */
	public boolean isWaitingForInput() {
		return "idle".equals(status) || "waiting".equals(status); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static String text(Object value) {
		return value instanceof String s ? s : null;
	}

	private static long number(Object value) {
		return value instanceof Number n ? n.longValue() : 0;
	}
}
