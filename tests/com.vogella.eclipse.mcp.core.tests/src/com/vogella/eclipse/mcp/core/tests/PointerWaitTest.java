package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.eclipse.swt.graphics.Point;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.PointerWait;

/** The Cocoa pointer wait, on a fake clock that advances only while it sleeps. */
class PointerWaitTest {

	private static final long WAIT_MILLIS = 500;

	private static final long GRACE_MILLIS = 50;

	private static final Point START = new Point(0, 0);

	private long now;

	private final PointerWait wait = new PointerWait(() -> now, millis -> now += millis * 1_000_000,
			WAIT_MILLIS * 1_000_000, GRACE_MILLIS * 1_000_000);

	/** A pointer that reaches the target once the given time has passed, or never for a negative delay. */
	private Supplier<Point> landingAfter(Point target, long millis) {
		long due = now + millis * 1_000_000;
		return () -> millis >= 0 && now >= due ? target : START;
	}

	@Test
	void aMoveThatHasArrivedIsNotWaitedFor() {
		Point target = new Point(100, 100);
		PointerWait idle = new PointerWait(() -> now, millis -> {
			throw new AssertionError("slept although the pointer had arrived");
		}, WAIT_MILLIS * 1_000_000, GRACE_MILLIS * 1_000_000);
		PointerWait.Arrival arrival = idle.await(START, target, () -> target);
		assertTrue(arrival.arrived());
		assertEquals(0, arrival.waitedMillis());
	}

	@Test
	void anInterruptedWaitCountsAsMissed() {
		Point target = new Point(100, 100);
		PointerWait interrupted = new PointerWait(() -> now, millis -> {
			throw new InterruptedException();
		}, WAIT_MILLIS * 1_000_000, GRACE_MILLIS * 1_000_000);
		try {
			assertFalse(interrupted.await(START, target, () -> START).arrived());
			assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept");
		} finally {
			Thread.interrupted();
		}
		Point next = new Point(200, 200);
		assertTrue(interrupted.await(START, next, () -> START).skipped());
		Thread.interrupted();
	}

	@Test
	void aMoveThatArrivesLateIsWaitedFor() {
		Point target = new Point(100, 100);
		PointerWait.Arrival arrival = wait.await(START, target, landingAfter(target, 120));
		assertTrue(arrival.arrived());
		assertFalse(arrival.skipped());
		assertTrue(arrival.waitedMillis() >= 120 && arrival.waitedMillis() < WAIT_MILLIS, arrival.toString());
	}

	@Test
	void aMoveThatNeverArrivesWaitsTheWholeBudget() {
		Point target = new Point(100, 100);
		PointerWait.Arrival arrival = wait.await(START, target, landingAfter(target, -1));
		assertFalse(arrival.arrived());
		assertFalse(arrival.skipped());
		assertEquals(START, arrival.point());
		assertTrue(arrival.waitedMillis() >= WAIT_MILLIS, arrival.toString());
	}

	@Test
	void aSecondRefusalGetsOnlyTheGrace() {
		wait.await(START, new Point(100, 100), landingAfter(new Point(100, 100), -1));
		Point next = new Point(200, 200);
		PointerWait.Arrival arrival = wait.await(START, next, landingAfter(next, -1));
		assertFalse(arrival.arrived());
		assertTrue(arrival.skipped());
		assertTrue(arrival.waitedMillis() >= GRACE_MILLIS && arrival.waitedMillis() < WAIT_MILLIS,
				arrival.toString());
	}

	@Test
	void aMoveThatLandsWithinTheGraceAfterARefusalIsNotRefused() {
		wait.await(START, new Point(100, 100), landingAfter(new Point(100, 100), -1));
		Point next = new Point(200, 200);
		PointerWait.Arrival arrival = wait.await(START, next, landingAfter(next, 20));
		assertTrue(arrival.arrived());
		Point third = new Point(300, 300);
		PointerWait.Arrival after = wait.await(next, third, landingAfter(third, 120));
		assertTrue(after.arrived(), "an arrival restores the full wait: " + after);
		assertFalse(after.skipped());
	}

	@Test
	void aMissedMoveThatLandedLaterRestoresTheFullWait() {
		Point missed = new Point(100, 100);
		wait.await(START, missed, landingAfter(missed, -1));
		Point next = new Point(200, 200);
		PointerWait.Arrival arrival = wait.await(missed, next, landingAfter(next, 120));
		assertTrue(arrival.arrived(), arrival.toString());
		assertFalse(arrival.skipped());
	}
}
