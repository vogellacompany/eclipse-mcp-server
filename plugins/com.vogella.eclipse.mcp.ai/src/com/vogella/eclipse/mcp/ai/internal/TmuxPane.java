package com.vogella.eclipse.mcp.ai.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The tmux pane a process runs in, found through the {@code TMUX} and {@code TMUX_PANE} variables it was started with.
 */
public record TmuxPane(String socket, String paneId) {

	/**
	 * Reads the pane from a process environment as {@code /proc/<pid>/environ} holds it, or returns {@code null} when
	 * the process does not run in tmux.
	 */
	public static TmuxPane fromEnvironment(byte[] environ) {
		String tmux = null;
		String pane = null;
		for (String variable : new String(environ, StandardCharsets.UTF_8).split("\0")) { //$NON-NLS-1$
			if (variable.startsWith("TMUX=")) { //$NON-NLS-1$
				tmux = variable.substring(5);
			} else if (variable.startsWith("TMUX_PANE=")) { //$NON-NLS-1$
				pane = variable.substring(10);
			}
		}
		if (tmux == null || tmux.isEmpty() || pane == null || pane.isEmpty()) {
			return null;
		}
		// TMUX is "<socket>,<server pid>,<session index>"
		int comma = tmux.indexOf(',');
		return new TmuxPane(comma < 0 ? tmux : tmux.substring(0, comma), pane);
	}

	/** The pane of a running process, or {@code null}; only Linux exposes another process's environment this way. */
	public static TmuxPane of(long pid) {
		Path environ = Path.of("/proc", Long.toString(pid), "environ"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!Files.isReadable(environ)) {
			return null;
		}
		try {
			return fromEnvironment(Files.readAllBytes(environ));
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * The command line that attaches to the session holding this pane and selects it, or {@code null} when the tmux
	 * server no longer knows the pane.
	 */
	public List<String> attachCommand() {
		String session = query("#{session_name}"); //$NON-NLS-1$
		if (session == null) {
			return null;
		}
		List<String> command = new ArrayList<>(List.of("tmux", "-S", socket, "attach-session", "-t", session)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		command.addAll(List.of(";", "select-window", "-t", paneId, ";", "select-pane", "-t", paneId)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
		return command;
	}

	/** A short description such as {@code swt-team:2.0}, or the pane id when tmux cannot be asked. */
	public String describe() {
		String target = query("#{session_name}:#{window_index}.#{pane_index}"); //$NON-NLS-1$
		return target != null ? target : paneId;
	}

	private String query(String format) {
		try {
			Process process = new ProcessBuilder("tmux", "-S", socket, "display-message", "-p", "-t", paneId, format) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
					.redirectErrorStream(true).start();
			if (!process.waitFor(3, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return null;
			}
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
			return process.exitValue() == 0 && !output.isEmpty() ? output : null;
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}
}
