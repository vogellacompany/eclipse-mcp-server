package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.ui.internal.ForegroundWait;
import com.vogella.eclipse.mcp.ui.internal.ForegroundWait.Probe;
import com.vogella.eclipse.mcp.ui.internal.ForegroundWait.Result;
import com.vogella.eclipse.mcp.ui.internal.ForegroundWait.State;

class ForegroundWaitTest {

	/** A clock that only moves when the wait sleeps, so the tests are instant and exact. */
	private final AtomicLong nanos = new AtomicLong();

	private final AtomicInteger probes = new AtomicInteger();

	private Result await(Probe probe, long maxMillis, BooleanSupplier cancelled) {
		return ForegroundWait.await(timeout -> {
			probes.incrementAndGet();
			return probe.foreground(timeout);
		}, maxMillis, cancelled, nanos::get, millis -> nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)));
	}

	@Test
	void focusThatIsAlreadyThereIsNotWaitedFor() {
		Result result = await(timeout -> Boolean.TRUE, 2000, () -> false);
		assertEquals(State.FOREGROUND, result.state());
		assertEquals(0, result.waitedMillis());
		assertEquals(1, probes.get());
	}

	@Test
	void lateFocusIsPolledAtTheInterval() {
		Result result = await(timeout -> Boolean.valueOf(probes.get() >= 4), 2000, () -> false);
		assertEquals(State.FOREGROUND, result.state());
		assertEquals(4, probes.get());
		assertEquals(3 * ForegroundWait.POLL_MILLIS, result.waitedMillis());
	}

	@Test
	void focusThatNeverArrivesEndsAtTheBound() {
		Result result = await(timeout -> Boolean.FALSE, 200, () -> false);
		assertEquals(State.TIMED_OUT, result.state());
		assertEquals(200, result.waitedMillis());
		assertEquals(4, probes.get());
	}

	@Test
	void zeroAndNegativeBoundsDoNotProbe() {
		assertEquals(State.TIMED_OUT, await(timeout -> Boolean.TRUE, 0, () -> false).state());
		assertEquals(State.TIMED_OUT, await(timeout -> Boolean.TRUE, -5, () -> false).state());
		assertEquals(0, probes.get());
	}

	@Test
	void aProbeTheUiDoesNotAnswerEndsTheWait() {
		Result result = await(timeout -> null, 2000, () -> false);
		assertEquals(State.UI_BUSY, result.state());
		assertEquals(1, probes.get());
	}

	@Test
	void aProbeThatCannotAskIsNotABusyUi() {
		Result result = await(timeout -> {
			throw new IllegalStateException("no display");
		}, 2000, () -> false);
		assertEquals(State.PROBE_FAILED, result.state());
		assertEquals("probeFailed", result.state().label());
		assertEquals(1, probes.get());
	}

	@Test
	void aProbeNeverWaitsLongerThanTheRestOfTheBound() {
		AtomicLong longest = new AtomicLong();
		await(timeout -> {
			longest.accumulateAndGet(timeout, Math::max);
			return Boolean.FALSE;
		}, 5000, () -> false);
		assertEquals(ForegroundWait.PROBE_MILLIS, longest.get());
		AtomicLong first = new AtomicLong();
		await(timeout -> {
			first.compareAndSet(0, timeout);
			return Boolean.FALSE;
		}, 300, () -> false);
		assertEquals(300, first.get());
	}

	@Test
	void aProbeGivenUpBecauseOfACancelIsNotABusyUi() {
		AtomicBoolean cancel = new AtomicBoolean();
		Result result = await(timeout -> {
			cancel.set(true);
			return null;
		}, 2000, cancel::get);
		assertEquals(State.CANCELLED, result.state());
		assertEquals("cancelled", result.state().label());
		assertEquals("uiBusy", State.UI_BUSY.label());
	}

	@Test
	void aCancelAfterSeveralPollsStopsWaiting() {
		Result result = await(timeout -> Boolean.FALSE, 2000, () -> probes.get() >= 3);
		assertEquals(State.CANCELLED, result.state());
		assertEquals(3, probes.get());
	}

	@Test
	void anInterruptStopsWaitingAndKeepsTheFlag() {
		try {
			Result result = ForegroundWait.await(timeout -> Boolean.FALSE, 2000, () -> false, nanos::get, millis -> {
				throw new InterruptedException();
			});
			assertEquals(State.CANCELLED, result.state());
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void noWaitIsMadeForSyntheticDeliveryOnTheUiThreadOrWhenTurnedOff() {
		assertNull(ForegroundWait.budgetMillis(true, false, 2000, 20_000));
		assertNull(ForegroundWait.budgetMillis(false, true, 2000, 20_000));
		assertNull(ForegroundWait.budgetMillis(false, false, 0, 20_000));
	}

	@Test
	void theWaitIsCappedByWhatIsLeftOfTheCall() {
		assertEquals(Long.valueOf(2000), ForegroundWait.budgetMillis(false, false, 2000, 20_000));
		assertEquals(Long.valueOf(700), ForegroundWait.budgetMillis(false, false, 2000, 700));
		assertEquals(Long.valueOf(0), ForegroundWait.budgetMillis(false, false, 2000, 0));
	}

	@Test
	void pressKeyDeclaresTheWait() throws Exception {
		IMcpTool tool = TestFixture.tool("eclipse_press_key");
		@SuppressWarnings("unchecked")
		Map<String, Object> property = (Map<String, Object>) ((Map<String, Object>) TestFixture
				.parse(tool.getInputSchema()).get("properties")).get("waitForForegroundMillis");
		assertEquals(ForegroundWait.DEFAULT_MILLIS, ((Number) property.get("default")).intValue());
		assertEquals(0, ((Number) property.get("minimum")).intValue());
		assertEquals(ForegroundWait.MAX_MILLIS, ((Number) property.get("maximum")).intValue());
		assertTrue(tool.getDescription().contains("foregroundWaitMillis"), tool.getDescription());
		assertTrue(tool.getDescription().contains("foregroundWaitClamped"), tool.getDescription());
		assertTrue(tool.getDescription().contains("foregroundWaitState"), tool.getDescription());
		List<String> labels = Arrays.stream(State.values()).filter(state -> state != State.CANCELLED)
				.map(State::label).toList();
		String listed = "(" + String.join(", ", labels.subList(0, labels.size() - 1)) + " or " + labels.getLast() + ")";
		assertTrue(tool.getDescription().contains(listed), listed);
	}

	@Test
	void aCancelledCallSendsNoKey() throws Exception {
		NullProgressMonitor monitor = new NullProgressMonitor();
		monitor.setCanceled(true);
		McpToolResult result = TestFixture.tool("eclipse_press_key")
				.call(Map.of("key", "Escape", "delivery", "synthetic"), monitor);
		assertTrue(result.isError(), result.text());
		assertEquals("The call was cancelled, so no key was sent.", result.text());
	}
}
