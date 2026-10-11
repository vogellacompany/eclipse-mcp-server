package com.vogella.eclipse.mcp.server;

import java.time.Duration;

import org.eclipse.core.runtime.Platform;

import com.vogella.eclipse.mcp.core.CallBudget;

/**
 * The preferences that control the embedded MCP server.
 */
public final class McpPreferences {

	/** Preference node of the server bundle. */
	public static final String QUALIFIER = "com.vogella.eclipse.mcp.server"; //$NON-NLS-1$

	public static final String KEY_ENABLED = "enabled"; //$NON-NLS-1$

	public static final String KEY_PORT = "port"; //$NON-NLS-1$

	public static final String KEY_CALL_TIMEOUT_SECONDS = "callTimeoutSeconds"; //$NON-NLS-1$

	/** How long a tool waits for a busy UI thread before withdrawing its request; read by {@code CallBudget.uiWaitSeconds}. */
	public static final String KEY_UI_WAIT_SECONDS = CallBudget.UI_WAIT_KEY;

	/** A process that listens on a socket and answers questions about the user's code must be opt-in. */
	public static final boolean DEFAULT_ENABLED = false;

	public static final int DEFAULT_PORT = 8642;

	/** Enough for a search over a large workspace, short enough that a wedged tool does not hang a client. */
	public static final int DEFAULT_CALL_TIMEOUT_SECONDS = 30;

	public static final int MIN_CALL_TIMEOUT_SECONDS = 5;

	public static final int MAX_CALL_TIMEOUT_SECONDS = 3600;

	public static final int DEFAULT_UI_WAIT_SECONDS = CallBudget.DEFAULT_UI_WAIT_SECONDS;

	public static final int MIN_UI_WAIT_SECONDS = 1;

	private McpPreferences() {
	}

	public static boolean isEnabled() {
		return Platform.getPreferencesService().getBoolean(QUALIFIER, KEY_ENABLED, DEFAULT_ENABLED, null);
	}

	public static int getPort() {
		return Platform.getPreferencesService().getInt(QUALIFIER, KEY_PORT, DEFAULT_PORT, null);
	}

	/** How long a single tool call may run before the server abandons it. */
	public static Duration getCallTimeout() {
		int seconds = Platform.getPreferencesService().getInt(QUALIFIER, KEY_CALL_TIMEOUT_SECONDS,
				DEFAULT_CALL_TIMEOUT_SECONDS, null);
		return Duration.ofSeconds(Math.clamp(seconds, MIN_CALL_TIMEOUT_SECONDS, MAX_CALL_TIMEOUT_SECONDS));
	}
}
