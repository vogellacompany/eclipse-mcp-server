package com.vogella.eclipse.mcp.core;

import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.Platform;

/**
 * How long a tool may block before the server aborts the call.
 * <p>
 * Waits are bounded here so a tool answers with the handle it has instead of being killed at the call timeout.
 */
public final class CallBudget {

	private static final String QUALIFIER = "com.vogella.eclipse.mcp.server"; //$NON-NLS-1$

	private static final String KEY = "callTimeoutSeconds"; //$NON-NLS-1$

	private static final int DEFAULT_CALL_TIMEOUT_SECONDS = 30;

	/** The preference key of the UI wait, in the server's preference node. */
	public static final String UI_WAIT_KEY = "uiWaitSeconds"; //$NON-NLS-1$

	/** The default for {@link #uiWaitSeconds}, which fits inside the default call timeout. */
	public static final int DEFAULT_UI_WAIT_SECONDS = 25;

	/** When the running tool call on this thread is aborted, in {@link System#nanoTime} terms, or null outside a call. */
	private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

	/** Left for the answer to be written and sent after the wait gives up. */
	private static final int MARGIN_SECONDS = 3;

	private CallBudget() {
	}

	/** The configured tool call timeout in seconds. */
	public static int callTimeoutSeconds() {
		return Platform.getPreferencesService().getInt(QUALIFIER, KEY, DEFAULT_CALL_TIMEOUT_SECONDS, null);
	}

	/** The longest a tool should wait, which is the call timeout less a margin. */
	public static int maxWaitSeconds() {
		return Math.max(1, callTimeoutSeconds() - MARGIN_SECONDS);
	}

	/**
	 * Records that a tool call with this timeout starts on the current thread, so that
	 * {@link #uiWaitSeconds} leaves room for the answer even after several waits; pair with {@link #leave}.
	 */
	public static void enter(long timeoutSeconds) {
		DEADLINE.set(Long.valueOf(System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)));
	}

	/** Ends what {@link #enter} began on this thread. */
	public static void leave() {
		DEADLINE.remove();
	}

	/**
	 * How long to wait for the UI thread before withdrawing a request: the longer of
	 * {@code requested} and the configured UI wait, capped at what is left of the call.
	 */
	public static int uiWaitSeconds(long requested) {
		int configured = Platform.getPreferencesService().getInt(QUALIFIER, UI_WAIT_KEY, DEFAULT_UI_WAIT_SECONDS, null);
		return (int) Math.min(Math.max(requested, configured), leftSeconds());
	}

	/** What is left of the current call less the margin, or {@link #maxWaitSeconds} outside a call; at least a second. */
	private static int leftSeconds() {
		Long deadline = DEADLINE.get();
		if (deadline == null) {
			return maxWaitSeconds();
		}
		long left = TimeUnit.NANOSECONDS.toSeconds(deadline.longValue() - System.nanoTime()) - MARGIN_SECONDS;
		return (int) Math.max(1, Math.min(left, maxWaitSeconds()));
	}

	/** {@code requested} capped at what is left of the call, so a wait late in a script still answers in time. */
	public static int boundedWaitSeconds(int requested) {
		return Math.min(requested, leftSeconds());
	}

	/**
	 * A sentence for the answer when the wait was cut short, or {@code null} when it
	 * was not, so that an early return is never mistaken for a finished job.
	 */
	public static String clampNote(int requested, String handleName) {
		int bounded = boundedWaitSeconds(requested);
		if (bounded >= requested) {
			return null;
		}
		return "Waited %d of the %d seconds asked for, because the server aborts any call that outlasts its %d second tool call timeout, which is set in Preferences > General > MCP Server. This is not a failure: poll %s, which is still running." //$NON-NLS-1$
				.formatted(Integer.valueOf(bounded), Integer.valueOf(requested),
						Integer.valueOf(callTimeoutSeconds()), handleName);
	}
}
