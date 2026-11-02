package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.ProductFamily;
import com.vogella.eclipse.mcp.core.TracePages;
import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.ui.internal.SamplingRegistry;
import com.vogella.eclipse.mcp.ui.internal.SamplingRegistry.Sample;
import com.vogella.eclipse.mcp.ui.internal.TracePage;

/**
 * How sampled stacks are merged into the answer of eclipse_stop_sampling.
 * <p>
 * Built from stacks written by hand, because the shapes that matter are the
 * ones a real session produces by accident: a stack deeper than maxDepth that
 * lost a different number of outer frames per sample, and a filter that leaves
 * every remaining sample sharing the same trunk.
 */
class SamplingAggregateTest {

	private static final int MAX_DEPTH = 6;

	/** A stack with the given frames, innermost first. */
	private static Sample sample(String... innermostFirst) {
		StackTraceElement[] stack = new StackTraceElement[innermostFirst.length];
		for (int i = 0; i < stack.length; i++) {
			stack[i] = new StackTraceElement("T", innermostFirst[i], null, -1);
		}
		return new Sample(1, "main", Thread.State.RUNNABLE, stack);
	}

	/**
	 * The full path is main > run > loop > dispatch > work > leaf, with a leaf side
	 * that grows and shrinks. Cut at MAX_DEPTH, the deeper samples lose main, then
	 * run as well, exactly the way ThreadInfo truncates.
	 */
	private static List<Sample> truncatedSamples() {
		List<Sample> samples = new ArrayList<>();
		// depth 6, nothing lost, but at exactly maxDepth it cannot be told apart
		// from a cut one and counts as truncated
		samples.add(sample("leaf", "work", "dispatch", "loop", "run", "main"));
		// depth 7, main lost
		samples.add(sample("deeper", "leaf", "work", "dispatch", "loop", "run"));
		// depth 8, main and run lost
		samples.add(sample("deepest", "deeper", "leaf", "work", "dispatch", "loop"));
		return samples;
	}

	@Test
	@SuppressWarnings("unchecked")
	void samplesCutAtMaxDepthMergeUnderOneRoot() throws Exception {
		JsonObject result = new JsonObject();

		SamplingRegistry.tree(truncatedSamples(), 1, MAX_DEPTH, result);
		Map<String, Object> parsed = TestFixture.parse(result.toString());

		List<Map<String, Object>> roots = (List<Map<String, Object>>) parsed.get("tree");
		assertEquals(1, roots.size(), "one path has to be one root, got " + roots);
		assertEquals("T.loop", roots.get(0).get("frame"), "rooted at the outermost frame every cut sample shares");
		assertEquals(Integer.valueOf(3), roots.get(0).get("samples"));
		assertEquals(Integer.valueOf(3), parsed.get("truncatedSamples"));
		assertTrue(String.valueOf(parsed.get("truncatedNote")).contains("T.loop"), "got " + parsed);
	}

	@Test
	@SuppressWarnings("unchecked")
	void aRunOfFramesWithTheSameCountIsFoldedIntoAChain() throws Exception {
		JsonObject result = new JsonObject();

		SamplingRegistry.tree(truncatedSamples(), 1, MAX_DEPTH, result);
		Map<String, Object> parsed = TestFixture.parse(result.toString());

		Map<String, Object> root = ((List<Map<String, Object>>) parsed.get("tree")).get(0);
		// loop > dispatch > work > leaf all carry every sample; only below leaf do
		// the samples part ways
		assertEquals(List.of("T.dispatch", "T.work", "T.leaf"), root.get("chain"), "got " + root);
		List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");
		assertEquals(1, children.size(), "got " + children);
		assertEquals("T.deeper", children.get(0).get("frame"));
		assertEquals(Integer.valueOf(2), children.get(0).get("samples"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void untruncatedSamplesAreLeftAlone() throws Exception {
		JsonObject result = new JsonObject();

		SamplingRegistry.tree(List.of(sample("a", "main"), sample("b", "main")), 1, MAX_DEPTH, result);
		Map<String, Object> parsed = TestFixture.parse(result.toString());

		List<Map<String, Object>> roots = (List<Map<String, Object>>) parsed.get("tree");
		assertEquals("T.main", roots.get(0).get("frame"));
		assertNull(parsed.get("truncatedSamples"), "nothing was cut, got " + parsed);
	}

	@Test
	@SuppressWarnings("unchecked")
	void framesOnEveryStackAreListedOnceRatherThanToppingPresence() throws Exception {
		JsonObject result = new JsonObject();

		SamplingRegistry.presence(truncatedSamples(), 10, result);
		Map<String, Object> parsed = TestFixture.parse(result.toString());

		List<Object> everywhere = (List<Object>) parsed.get("onEveryStack");
		assertTrue(everywhere.containsAll(List.of("T.leaf", "T.work", "T.dispatch", "T.loop")), "got " + everywhere);
		List<Map<String, Object>> top = (List<Map<String, Object>>) parsed.get("topByPresence");
		for (Map<String, Object> row : top) {
			assertFalse(everywhere.contains(row.get("frame")), "the trunk must not top the list, got " + top);
		}
		assertEquals("T.deeper", top.get(0).get("frame"), "got " + top);
	}

	private static StackTraceElement[] stack(String... innermostFirst) {
		StackTraceElement[] stack = new StackTraceElement[innermostFirst.length];
		for (int i = 0; i < stack.length; i++) {
			int dot = innermostFirst[i].lastIndexOf('.');
			stack[i] = new StackTraceElement(innermostFirst[i].substring(0, dot), innermostFirst[i].substring(dot + 1), null, -1);
		}
		return stack;
	}

	/**
	 * The session of the review, all threads on an idle DBeaver on Windows: 150 ticks, of which the
	 * UI thread dispatched something on two and slept on the rest.
	 */
	private static List<Sample> idleIde() {
		StackTraceElement[] poll = stack("sun.nio.ch.WEPoll.wait", "sun.nio.ch.WEPollSelectorImpl.doSelect");
		List<Sample> samples = new ArrayList<>();
		for (int tick = 0; tick < 150; tick++) {
			samples.add(new Sample(1, "HTTP-Dispatcher", Thread.State.RUNNABLE, poll));
			samples.add(new Sample(2, "HTTP-Dispatcher", Thread.State.RUNNABLE, poll));
			samples.add(new Sample(3, "mcp-jetty-66", Thread.State.RUNNABLE, poll));
			samples.add(new Sample(4, "Navigator events", Thread.State.TIMED_WAITING,
					stack("java.lang.Thread.sleepNanos0", "java.lang.Thread.sleepNanos", "java.lang.Thread.sleep", "org.jkiss.Pause.run")));
			samples.add(new Sample(5, "AWT-Windows", Thread.State.RUNNABLE,
					stack("sun.awt.windows.WToolkit.eventLoop", "sun.awt.windows.WToolkit.run")));
			samples.add(tick < 148
					? new Sample(6, "main", Thread.State.RUNNABLE,
							stack("org.eclipse.swt.internal.win32.OS.WaitMessage", "org.eclipse.swt.widgets.Display.sleep",
									"org.jkiss.dbeaver.App.start", "org.eclipse.equinox.launcher.Main.run"))
					: new Sample(6, "main", Thread.State.RUNNABLE,
							stack("org.eclipse.swt.internal.win32.OS.DispatchMessage", "org.eclipse.swt.widgets.Display.readAndDispatch",
									"org.jkiss.dbeaver.App.start", "org.eclipse.equinox.launcher.Main.run")));
		}
		return samples;
	}

	private static SamplingRegistry.Session session(List<Sample> samples, int ticks) {
		return SamplingRegistry.Session.of("s", samples, ticks, 20, 30, 80);
	}

	private static Map<String, Object> answer(SamplingRegistry.Session session, boolean includeThreads) throws Exception {
		return TestFixture.parse(SamplingRegistry.aggregate(session, 10, 1, false, false, null, includeThreads, false).toString());
	}

	private static int number(Map<String, Object> map, String key) {
		return ((Number) map.get(key)).intValue();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> thread(Map<String, Object> answer, String name) {
		return ((List<Map<String, Object>>) answer.get("byThread")).stream().filter(t -> name.equals(t.get("thread")))
				.findFirst().orElseThrow(() -> new AssertionError(name + " is not in " + answer.get("byThread")));
	}

	@Test
	void theUiThreadKeepsItsWorkAndItsSleepIsCountedAsIdleNotAsTheServers() throws Exception {
		Map<String, Object> answer = answer(session(idleIde(), 150), false);

		// the review's 150 server samples were mcp-jetty's selector, not main: its wait did not count as idle on Windows
		assertEquals(0, number(answer, "serverSamplesExcluded"), "got " + answer);
		Map<String, Object> main = thread(answer, "main");
		assertEquals(150, number(main, "samples"));
		assertEquals(2, number(main, "kept"), "what the tree holds for main, got " + main);
		assertEquals(148, number(main, "idleExcluded"));
		assertEquals(0, number(main, "serverExcluded"));
		assertEquals(2, number(answer, "samples"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> tree = (List<Map<String, Object>>) answer.get("tree");
		assertEquals("org.eclipse.equinox.launcher.Main.run", tree.get(0).get("frame"), "main's stacks are in the tree");
		assertEquals(2, number(tree.get(0), "samples"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void byThreadAddsUpToTheCountsBesideIt() throws Exception {
		Map<String, Object> answer = answer(session(idleIde(), 150), true);

		int kept = 0;
		int idle = 0;
		for (Map<String, Object> thread : (List<Map<String, Object>>) answer.get("byThread")) {
			assertEquals(number(thread, "samples"), number(thread, "kept") + number(thread, "idleExcluded") + number(thread, "serverExcluded"));
			kept += number(thread, "kept");
			idle += number(thread, "idleExcluded");
		}
		assertEquals(number(answer, "samples"), kept);
		assertEquals(number(answer, "idleSamplesExcluded"), idle);
	}

	@Test
	@SuppressWarnings("unchecked")
	void anIdleIdeSaysSoInsteadOfNamingAWait() throws Exception {
		Map<String, Object> answer = answer(session(idleIde(), 150), false);

		assertEquals(Boolean.TRUE, answer.get("nothingBusy"), "got " + answer);
		assertTrue(String.valueOf(answer.get("nothingBusyNote")).contains("WEPoll.wait"), "got " + answer);
		assertEquals(150, number(answer, "idleByState"), "the sleeping thread says so by its state");
		assertEquals(748, number(answer, "idleByFrame"), "the loops waiting in native code are RUNNABLE");
		List<Map<String, Object>> self = (List<Map<String, Object>>) answer.get("topBySelfTime");
		for (Map<String, Object> row : self) {
			String frame = String.valueOf(row.get("frame"));
			assertFalse(frame.contains("WEPoll") || frame.contains("sleepNanos0") || frame.contains("eventLoop"), "got " + self);
		}
		assertEquals("sun.nio.ch.WEPoll.wait", ((List<Map<String, Object>>) answer.get("idleFrames")).get(0).get("frame"));
	}

	@Test
	void oneSaturatedThreadAmongManyParkedOnesIsNotQuiet() throws Exception {
		List<Sample> samples = new ArrayList<>();
		for (int tick = 0; tick < 100; tick++) {
			samples.add(new Sample(1, "main", Thread.State.RUNNABLE, stack("org.jkiss.Work.run", "org.jkiss.App.start")));
			for (int parked = 2; parked < 70; parked++) {
				samples.add(new Sample(parked, "pool-" + parked, Thread.State.WAITING, stack("jdk.internal.misc.Unsafe.park", "java.util.concurrent.locks.LockSupport.park")));
			}
		}
		assertEquals(Boolean.FALSE, answer(session(samples, 100), false).get("nothingBusy"));
	}

	@Test
	void aFilterIsNoVerdictOnTheWholeIde() throws Exception {
		Map<String, Object> answer = TestFixture
				.parse(SamplingRegistry.aggregate(session(idleIde(), 150), 10, 1, false, false, "DispatchMessage", false, false).toString());
		assertEquals(Boolean.FALSE, answer.get("nothingBusy"), "got " + answer);
	}

	@Test
	void workBelowASleepCallbackIsBusyInTheAnswerAndOnThePage() throws Exception {
		Sample callback = new Sample(1, "main", Thread.State.RUNNABLE,
				stack("org.jkiss.Painter.paint", "org.eclipse.swt.widgets.Control.windowProc", "org.eclipse.swt.widgets.Display.windowProc",
						"org.eclipse.swt.internal.cocoa.OS.objc_msgSend", "org.eclipse.swt.internal.cocoa.NSRunLoop.runMode",
						"org.eclipse.swt.widgets.Display.sleep", "org.jkiss.App.start"));
		Sample asleep = new Sample(1, "main", Thread.State.RUNNABLE,
				stack("org.eclipse.swt.internal.cocoa.NSRunLoop.runMode", "org.eclipse.swt.widgets.Display.sleep", "org.jkiss.App.start"));
		SamplingRegistry.Session session = session(List.of(callback, asleep), 2);

		assertEquals(1, number(answer(session, false), "samples"));
		String html = page(session, true, null);
		assertTrue(html.contains("{\"n\":\"org.jkiss.Painter.paint\",\"v\":1}"), "the work carries no idle weight, got " + html);
		assertTrue(html.contains("\"n\":\"org.eclipse.swt.internal.cocoa.NSRunLoop.runMode\",\"v\":2,\"i\":1"),
				"the wait is idle only as far as its stacks are, got " + html);
	}

	@Test
	void theHeaderShowsTheIntervalAchievedAndWarnsAboutIt() {
		String html = page(session(idleIde(), 150), false, null);
		assertTrue(html.contains("an achieved 30 ms interval (20 ms requested)"), html);
		assertTrue(html.contains("<p class=\"warn\">Sampling could not keep the requested 20 ms interval and achieved 30 ms."), html);
		assertTrue(html.contains("data-quiet=\"Nothing was busy."), "the headline must not name a wait");

		String held = page(SamplingRegistry.Session.of("s", idleIde(), 150, 20, 20, 80), false, null);
		assertTrue(held.contains("at a 20 ms interval"), held);
		assertFalse(held.contains("<p class=\"warn\">"), held);
	}

	@Test
	void aPageCarriesTheFamilyOfTheApplication() {
		String html = page(session(idleIde(), 150), false, ProductFamily.of(List.of("com.dbeaver"), "DBeaver", "org.jkiss.dbeaver.core.product"));
		assertTrue(html.contains("org.jkiss."), html);
		assertTrue(html.contains("com.dbeaver."), html);
		assertTrue(html.contains(">Only DBeaver<"), html);
	}

	private static String page(SamplingRegistry.Session session, boolean includeIdle, ProductFamily product) {
		AtomicReference<String> published = new AtomicReference<>();
		TracePages.setPublisher((title, html) -> {
			published.set(html);
			return "http://localhost/" + title;
		});
		try {
			TracePage.publishSampling(session, includeIdle, null, false, product, new JsonObject(), false);
		} finally {
			TracePages.setPublisher(null);
		}
		return published.get();
	}

	@Test
	void realWaitsOfThisPlatformAreIdleAndAJettySelectorIsNotCountedAsTheServersWork() throws Exception {
		CountDownLatch entered = new CountDownLatch(2);
		try (Selector selector = Selector.open()) {
			Thread polling = Thread.ofPlatform().daemon().name("mcp-jetty-1").start(() -> {
				entered.countDown();
				try {
					selector.select();
				} catch (IOException e) {
					// closed by the test
				}
			});
			Thread sleeping = Thread.ofPlatform().daemon().name("sleeper").start(() -> {
				entered.countDown();
				try {
					Thread.sleep(60_000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			entered.await();
			Thread.sleep(200);
			long[] ids = { polling.threadId(), sleeping.threadId() };
			SamplingRegistry.Session session = SamplingRegistry.getInstance().start(mx -> ids, 10, 10, 80);
			while (session.running()) {
				Thread.sleep(10);
			}
			sleeping.interrupt();
			selector.wakeup();

			Map<String, Object> answer = answer(session, true);
			assertEquals(0, number(answer, "samples"), "got " + answer);
			assertEquals(0, number(answer, "serverSamplesExcluded"), "got " + answer);
			assertEquals(20, number(answer, "idleSamplesExcluded"), "got " + answer);
		}
	}

	@Test
	void poolThreadsAndRunningJobsShareOneBranch() {
		assertEquals("Worker-N", SamplingRegistry.threadGroup("Worker-19: Indexing"));
		assertEquals("pool-N", SamplingRegistry.threadGroup("pool-7"));
		assertEquals("main", SamplingRegistry.threadGroup("main"));
		assertEquals("unnamed", SamplingRegistry.threadGroup(null));
	}
}
