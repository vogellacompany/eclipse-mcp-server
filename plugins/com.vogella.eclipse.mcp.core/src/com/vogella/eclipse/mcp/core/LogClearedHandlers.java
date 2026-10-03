package com.vogella.eclipse.mcp.core;

import org.eclipse.core.runtime.ILog;

import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The bridge from clearing the log file to whatever is showing its contents.
 * <p>
 * The UI bundle registers itself here and {@code eclipse_clear_log} reports back what it did.
 */
public final class LogClearedHandlers {

	/** Drops what a UI shows for the log, and says what that came to. */
	@FunctionalInterface
	public interface Handler {

		/** Runs off the UI thread, so it must not block on a busy workbench. */
		JsonObject logCleared();
	}

	private static volatile Handler handler;

	private LogClearedHandlers() {
	}

	public static void set(Handler newHandler) {
		handler = newHandler;
	}

	/** Removes {@code registered}, unless something else took its place meanwhile. */
	public static void unset(Handler registered) {
		if (handler == registered) {
			handler = null;
		}
	}

	/**
	 * Tells the registered handler, and returns what it reported, or {@code null}
	 * when there is none. A handler that fails must not turn a completed clear into
	 * a failed tool call, so its failure is logged and reported rather than thrown.
	 */
	public static JsonObject notifyCleared() {
		Handler current = handler;
		if (current == null) {
			return null;
		}
		try {
			return current.logCleared();
		} catch (RuntimeException e) {
			ILog.get().error("Could not update the UI after the log was cleared", e); //$NON-NLS-1$
			return new JsonObject().put("updated", Boolean.FALSE).put("reason", String.valueOf(e)); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}
}
