package com.vogella.eclipse.mcp.ai.internal;

import java.nio.file.Path;

/**
 * Locates Claude Code's configuration folder.
 */
final class ClaudeHome {

	private ClaudeHome() {
	}

	static Path directory() {
		String configured = System.getenv("CLAUDE_CONFIG_DIR"); //$NON-NLS-1$
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured);
		}
		return Path.of(System.getProperty("user.home"), ".claude"); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
