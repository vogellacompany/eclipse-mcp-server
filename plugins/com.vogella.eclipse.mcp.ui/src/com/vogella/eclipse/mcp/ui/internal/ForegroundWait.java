package com.vogella.eclipse.mcp.ui.internal;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Waits a bounded time for the IDE to become the active window, since focus can
 * arrive after the shell that takes it is already open.
 */
public final class ForegroundWait {

	public static final int DEFAULT_MILLIS = 2000;

	public static final int MAX_MILLIS = 10_000;

	public static final long POLL_MILLIS = 50;

	/** The longest one probe waits for the UI thread, so a busy UI ends the wait instead of stretching it. */
	public static final long PROBE_MILLIS = 1000;

	public enum State {
		FOREGROUND("foreground"), TIMED_OUT("timedOut"), UI_BUSY("uiBusy"), PROBE_FAILED("probeFailed"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		CANCELLED("cancelled"); //$NON-NLS-1$

		private final String label;

		State(String label) {
			this.label = label;
		}

		/** The spelling in a tool answer. */
		public String label() {
			return label;
		}
	}

	public record Result(State state, long waitedMillis) {
	}

	/**
	 * Answers whether the IDE is the active window, or null when the UI thread did not answer in time;
	 * throws when the question cannot be asked at all.
	 */
	@FunctionalInterface
	public interface Probe {
		Boolean foreground(long timeoutMillis);
	}

	@FunctionalInterface
	public interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}

	private ForegroundWait() {
	}

	/**
	 * How long to wait, capped at what is left of the call, or null for no wait: synthetic
	 * delivery needs no focus, on the UI thread a wait would hold back the focus it waits for,
	 * and 0 is the client turning the wait off.
	 */
	public static Long budgetMillis(boolean synthetic, boolean onUiThread, int requestedMillis, long leftMillis) {
		if (synthetic || onUiThread || requestedMillis <= 0) {
			return null;
		}
		return Long.valueOf(Math.max(0, Math.min(requestedMillis, leftMillis)));
	}

	public static Result await(Probe probe, long maxMillis, BooleanSupplier cancelled) {
		return await(probe, maxMillis, cancelled, System::nanoTime, Thread::sleep);
	}

	/**
	 * Probes, then every {@link #POLL_MILLIS}, until the IDE is active, the time is up, the UI is
	 * busy, a probe fails or the call is cancelled.
	 */
	public static Result await(Probe probe, long maxMillis, BooleanSupplier cancelled, LongSupplier nanoClock,
			Sleeper sleeper) {
		long started = nanoClock.getAsLong();
		long max = TimeUnit.MILLISECONDS.toNanos(Math.max(0, maxMillis));
		while (true) {
			if (cancelled.getAsBoolean()) {
				return result(State.CANCELLED, started, nanoClock);
			}
			long left = max - (nanoClock.getAsLong() - started);
			if (left <= 0) {
				return result(State.TIMED_OUT, started, nanoClock);
			}
			Boolean foreground;
			try {
				foreground = probe.foreground(Math.max(1, Math.min(PROBE_MILLIS, TimeUnit.NANOSECONDS.toMillis(left))));
			} catch (RuntimeException e) {
				return result(State.PROBE_FAILED, started, nanoClock);
			}
			if (foreground == null) {
				// a probe given up because the call was cancelled is not a busy UI
				return result(cancelled.getAsBoolean() ? State.CANCELLED : State.UI_BUSY, started, nanoClock);
			}
			if (foreground.booleanValue()) {
				return result(State.FOREGROUND, started, nanoClock);
			}
			left = max - (nanoClock.getAsLong() - started);
			if (left <= 0) {
				return result(State.TIMED_OUT, started, nanoClock);
			}
			try {
				sleeper.sleep(Math.min(POLL_MILLIS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(left))));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return result(State.CANCELLED, started, nanoClock);
			}
		}
	}

	private static Result result(State state, long started, LongSupplier nanoClock) {
		return new Result(state, TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - started));
	}
}
