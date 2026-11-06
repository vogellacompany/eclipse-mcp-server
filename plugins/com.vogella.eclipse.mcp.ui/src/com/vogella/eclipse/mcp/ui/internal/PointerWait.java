package com.vogella.eclipse.mcp.ui.internal;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.eclipse.swt.graphics.Point;

/**
 * Waits for a posted pointer move to arrive, and after a wait ran out only
 * gives later moves a short grace until one is seen to arrive, so a process
 * whose moves are dropped holds the UI thread once.
 */
public final class PointerWait {

	static final long WAIT_NANOS = 500_000_000L;

	static final long GRACE_NANOS = 50_000_000L;

	static final long POLL_MILLIS = 5;

	/** Sleeps between reads. */
	@FunctionalInterface
	public interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}

	/** Where the pointer was read last, how long was waited, and whether only the grace was given. */
	public record Arrival(Point point, boolean arrived, long waitedMillis, boolean skipped) {
	}

	private final LongSupplier clock;

	private final Sleeper sleeper;

	private final long waitNanos;

	private final long graceNanos;

	private Point missed;

	public PointerWait(LongSupplier clock, Sleeper sleeper, long waitNanos, long graceNanos) {
		this.clock = clock;
		this.sleeper = sleeper;
		this.waitNanos = waitNanos;
		this.graceNanos = graceNanos;
	}

	/** The wait the IDE uses, on the real clock. */
	static PointerWait system() {
		return new PointerWait(System::nanoTime, Thread::sleep, WAIT_NANOS, GRACE_NANOS);
	}

	/**
	 * Reads the pointer until it is at the target or the budget ran out;
	 * previous is where the pointer was before the move was posted.
	 */
	public Arrival await(Point previous, Point target, Supplier<Point> read) {
		// a missed move that landed later means the wait was too short, not that moves are dropped
		boolean skipping = missed != null && !isAt(previous, missed);
		long budget = skipping ? graceNanos : waitNanos;
		long start = clock.getAsLong();
		// the read back is a round trip to the window server, so it sees the warp
		Point arrived = read.get();
		while (!isAt(arrived, target) && clock.getAsLong() - start < budget) {
			try {
				sleeper.sleep(POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			arrived = read.get();
		}
		long waited = (clock.getAsLong() - start) / 1_000_000;
		boolean at = isAt(arrived, target);
		missed = at ? null : target;
		return new Arrival(arrived, at, waited, skipping);
	}

	/** Whether a read back pointer is at the point, allowing a pixel of rounding. */
	public static boolean isAt(Point arrived, Point point) {
		return Math.abs(arrived.x - point.x) <= 1 && Math.abs(arrived.y - point.y) <= 1;
	}
}
