package com.vogella.eclipse.mcp.core;

import java.util.concurrent.Callable;

/**
 * The bridge from a core tool to the UI thread, for work whose listeners assume it.
 * <p>
 * The UI bundle registers an executor; without one the work runs inline, which is right headless.
 */
public final class UiDispatch {

	/** Runs work on the UI thread and waits for it, at most the given seconds. */
	public interface Executor {

		/**
		 * Never blocks the UI thread on the caller: queue, wait on a future, and throw
		 * {@link java.util.concurrent.TimeoutException} when the UI did not get to it.
		 */
		<T> T call(Callable<T> work, int timeoutSeconds) throws Exception;
	}

	private static volatile Executor executor;

	private UiDispatch() {
	}

	public static void set(Executor newExecutor) {
		executor = newExecutor;
	}

	/** Removes {@code registered}, unless something else took its place meanwhile. */
	public static void unset(Executor registered) {
		if (executor == registered) {
			executor = null;
		}
	}

	/** Runs the work on the UI thread when one is registered, inline otherwise. */
	public static <T> T call(Callable<T> work, int timeoutSeconds) throws Exception {
		Executor current = executor;
		return current == null ? work.call() : current.call(work, timeoutSeconds);
	}
}
