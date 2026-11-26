package com.vogella.eclipse.mcp.ai.internal;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.Platform;

/**
 * Finds executables and prepares the command line and environment of a terminal process.
 */
public final class Commands {

	/**
	 * Variables a Claude Code session sets for its children: an IDE started from one would otherwise hand them to
	 * every session it starts, which then counts as a child and keeps no transcript, and tmux refuses to attach from
	 * inside another tmux client.
	 */
	private static final Set<String> DROPPED = Set.of("CLAUDECODE", "CLAUDE_PID", "AI_AGENT", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_EXECPATH", "CLAUDE_CODE_SSE_PORT", "CLAUDE_CODE_SESSION_ID", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_CHILD_NOTES_DIR", "CLAUDE_CODE_SESSION_ATTENDED", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"CLAUDE_CODE_MESSAGING_SOCKET", "CLAUDE_CODE_MESSAGING_TOKEN", "TMUX", "TMUX_PANE"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	private Commands() {
	}

	/**
	 * Finds {@code claude} on the path or where its installer puts it, since an IDE started from the desktop often
	 * lacks {@code ~/.local/bin} on its path.
	 */
	public static String findClaude() {
		String name = Platform.OS_WIN32.equals(Platform.getOS()) ? "claude.exe" : "claude"; //$NON-NLS-1$ //$NON-NLS-2$
		List<Path> candidates = new ArrayList<>();
		String path = System.getenv("PATH"); //$NON-NLS-1$
		if (path != null) {
			for (String entry : path.split(File.pathSeparator)) {
				if (!entry.isEmpty()) {
					candidates.add(Path.of(entry, name));
				}
			}
		}
		String home = System.getProperty("user.home"); //$NON-NLS-1$
		candidates.add(Path.of(home, ".local", "bin", name)); //$NON-NLS-1$ //$NON-NLS-2$
		candidates.add(Path.of(home, ".claude", "local", name)); //$NON-NLS-1$ //$NON-NLS-2$
		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
				return candidate.toString();
			}
		}
		return null;
	}

	/** This IDE's environment as {@code NAME=value} entries, without the variables of a surrounding session. */
	public static String[] environment(Map<String, String> base) {
		List<String> entries = new ArrayList<>();
		for (Map.Entry<String, String> variable : base.entrySet()) {
			if (!DROPPED.contains(variable.getKey()) && !"TERM".equals(variable.getKey())) { //$NON-NLS-1$
				entries.add(variable.getKey() + '=' + variable.getValue());
			}
		}
		entries.add("TERM=xterm-256color"); //$NON-NLS-1$
		return entries.toArray(String[]::new);
	}

	/**
	 * Joins arguments into the line the terminal's process connector splits again, which honours double quotes and
	 * backslash escapes.
	 */
	public static String join(List<String> arguments) {
		StringBuilder line = new StringBuilder();
		for (String argument : arguments) {
			if (!line.isEmpty()) {
				line.append(' ');
			}
			line.append(quote(argument));
		}
		return line.toString();
	}

	static String quote(String argument) {
		if (!argument.isEmpty() && argument.chars().allMatch(c -> c > ' ' && c != '"' && c != '\'' && c != '\\')) {
			return argument;
		}
		return '"' + argument.replace("\\", "\\\\").replace("\"", "\\\"") + '"'; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}
}
