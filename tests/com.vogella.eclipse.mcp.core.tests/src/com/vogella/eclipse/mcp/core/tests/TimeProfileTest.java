package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.basic.internal.ThrownEvents;
import com.vogella.eclipse.mcp.basic.internal.TimeProfile;
import com.vogella.eclipse.mcp.core.FlameGraph;
import com.vogella.eclipse.mcp.core.IdleFrames;

/**
 * The aggregation over plain stacks, so that the counts can be asserted exactly, which a real
 * recording's sampler does not allow.
 */
class TimeProfileTest {

	private static final String[] WORK = { "java.util.HashMap.get", "org.app.Lookup.find", "org.app.Main.run" };

	private static final String[] OTHER_WORK = { "org.app.Render.paint", "org.app.Main.run" };

	private static final String[] EVENT_LOOP = { "org.eclipse.swt.internal.win32.OS.WaitMessage",
			"org.eclipse.swt.widgets.Display.sleep", "org.app.Main.run" };

	private static TimeProfile profile(boolean includeIdle, int topMethods, int topThreads, int maxTreeNodes) {
		return new TimeProfile(new TimeProfile.Limits(includeIdle, topMethods, topThreads, 1, maxTreeNodes));
	}

	@Test
	void selfAndPresenceTellExecutionFromNativeAndLeaveIdleOut() throws Exception {
		TimeProfile profile = profile(false, 10, 10, 50);
		for (int i = 0; i < 3; i++) {
			add(profile, "main", true, WORK);
		}
		add(profile, "main", false, OTHER_WORK);
		add(profile, "main", false, EVENT_LOOP);
		add(profile, "main", false, EVENT_LOOP);

		Map<String, Object> answer = TestFixture.parse(profile.toJson().toString());

		assertEquals(4, number(answer, "samples"));
		assertEquals(3, number(answer, "execution"));
		assertEquals(1, number(answer, "native"));
		assertEquals(2, number(answer, "idleSamples"));
		assertEquals(2, number(answer, "idleExcluded"));
		assertEquals("org.eclipse.swt.internal.win32.OS.WaitMessage", first(answer, "idleFrames").get("frame"));

		Map<String, Object> hottest = first(answer, "topBySelfTime");
		assertEquals("java.util.HashMap.get", hottest.get("frame"));
		assertEquals(3, number(hottest, "samples"));
		assertEquals(3, number(hottest, "execution"));
		assertEquals(0, number(hottest, "native"));

		assertEquals(List.of("org.app.Main.run"), answer.get("onEveryStack"), "the trunk is no finding, got " + answer);
		assertEquals("java.util.HashMap.get", first(answer, "topByPresence").get("frame"));
	}

	@Test
	void idleIsIncludedOnRequestAndStillCounted() throws Exception {
		TimeProfile profile = profile(true, 10, 10, 50);
		add(profile, "main", true, WORK);
		add(profile, "main", false, EVENT_LOOP);

		Map<String, Object> answer = TestFixture.parse(profile.toJson().toString());

		assertEquals(2, number(answer, "samples"));
		assertEquals(1, number(answer, "idleSamples"));
		assertEquals(0, number(answer, "idleExcluded"));
	}

	@Test
	void everyListReportsItsTotalAndWhatTheLimitCut() throws Exception {
		TimeProfile profile = profile(false, 1, 1, 2);
		add(profile, "main", true, WORK);
		add(profile, "Worker-1", true, OTHER_WORK);

		Map<String, Object> answer = TestFixture.parse(profile.toJson().toString());

		assertEquals(2, number(answer, "topBySelfTimeTotal"));
		assertEquals(Boolean.TRUE, answer.get("topBySelfTimeTruncated"));
		assertEquals(1, ((List<?>) answer.get("topBySelfTime")).size());
		assertEquals(2, number(answer, "byThreadTotal"));
		assertEquals(Boolean.TRUE, answer.get("byThreadTruncated"));
		assertEquals(2, number(answer, "treeNodes"));
		assertTrue(number(answer, "treeNodesTotal") > 2, "got " + answer);
		assertEquals(Boolean.TRUE, answer.get("treeTruncated"));
	}

	@Test
	void theTreeHasABranchPerThreadAndFoldsARunOfEqualCounts() throws Exception {
		TimeProfile profile = profile(false, 10, 10, 50);
		add(profile, "Worker-1: Indexing", true, WORK);
		add(profile, "Worker-2", true, WORK);

		Map<String, Object> answer = TestFixture.parse(profile.toJson().toString());

		Map<String, Object> thread = first(answer, "tree");
		assertEquals(FlameGraph.THREAD_PREFIX + "Worker-N", thread.get("frame"), "got " + answer);
		assertEquals(2, number(thread, "samples"));
		assertEquals(List.of("org.app.Main.run", "org.app.Lookup.find", "java.util.HashMap.get"), thread.get("chain"),
				"got " + thread);
	}

	@Test
	void stacksCutAtTheStackDepthMergeUnderTheFrameTheyShare() throws Exception {
		TimeProfile profile = profile(false, 10, 10, 50);
		// the same path, cut at two different places on the outer side
		profile.add("main", true, new String[] { "org.app.Deep.leaf", "org.app.Deep.middle", "org.app.Deep.outer" }, true);
		profile.add("main", true, new String[] { "org.app.Deep.leaf", "org.app.Deep.middle" }, true);

		Map<String, Object> answer = TestFixture.parse(profile.toJson().toString());

		assertEquals(2, number(answer, "truncatedStacks"));
		assertTrue(String.valueOf(answer.get("truncatedNote")).contains("stackdepth"), "got " + answer);
		Map<String, Object> thread = first(answer, "tree");
		assertEquals(List.of("org.app.Deep.middle", "org.app.Deep.leaf"), thread.get("chain"),
				"one path has to be one branch, got " + thread);
	}

	@Test
	void thrownEventsGroupByClassMessageAndNearestFrameWithoutCountingAnErrorTwice() throws Exception {
		ThrownEvents thrown = new ThrownEvents();
		String[] error = { "java.lang.Error.<init>", "org.eclipse.swt.graphics.Resource.initNonDisposeTracking",
				"org.eclipse.swt.graphics.Resource.<init>" };
		for (int i = 0; i < 5; i++) {
			thrown.add(true, "java.lang.Error", "not disposed", error);
		}
		for (int i = 0; i < 3; i++) {
			thrown.add(false, "java.lang.Error", "not disposed", error);
		}
		thrown.add(false, "org.app.AppException", "bad", new String[] { "java.lang.Exception.<init>",
				"org.app.AppException.<init>", "org.app.Service.call" });

		Map<String, Object> answer = TestFixture.parse(thrown.toJson(1, "300/s").toString());

		assertEquals(6, number(answer, "thrown"), "an Error is in both streams, got " + answer);
		assertEquals(9, number(answer, "events"));
		assertEquals(2, number(answer, "groupsTotal"));
		assertEquals(Boolean.TRUE, answer.get("groupsTruncated"));
		assertEquals("300/s", answer.get("exceptionThrottle"));
		Map<String, Object> group = first(answer, "groups");
		assertEquals("org.eclipse.swt.graphics.Resource.initNonDisposeTracking", group.get("frame"));
		assertEquals(5, number(group, "count"));
		assertEquals(5, number(group, "errorEvents"));
		assertEquals(3, number(group, "exceptionEvents"));

		assertEquals("org.app.Service.call", ThrownEvents.nearestFrame("org.app.AppException",
				new String[] { "org.app.AppException.<init>", "org.app.Service.call" }), "its own constructor names nothing");
		assertNull(ThrownEvents.nearestFrame("java.lang.Error", new String[] { "java.lang.Error.<init>" }));
	}

	@Test
	void theIdleDefinitionCoversTheEventLoopOfEveryPlatform() {
		for (String frame : List.of("org.eclipse.swt.widgets.Display.sleep", "org.eclipse.swt.internal.win32.OS.WaitMessage",
				"org.eclipse.swt.internal.cocoa.NSRunLoop.runMode", "jdk.internal.misc.Unsafe.park",
				"java.util.concurrent.locks.LockSupport.parkNanos", "java.lang.Object.wait0", "java.lang.Thread.sleep0",
				"sun.nio.ch.WEPoll.wait", "sun.nio.ch.EPoll.wait", "sun.nio.ch.Net.poll", "sun.awt.windows.WToolkit.eventLoop",
				"sun.awt.X11.XToolkit.waitForEvents", "java.lang.ref.Reference.waitForReferencePendingList",
				"org.eclipse.swt.widgets.Display.sleep:4711")) {
			assertTrue(IdleFrames.isIdle(frame), frame);
		}
		for (String frame : List.of("org.app.MyObject.wait", "org.app.Display.dispatch", "org.eclipse.swt.internal.gtk.OS.Call",
				"java.util.HashMap.get", "org.app.Runner.thread")) {
			assertFalse(IdleFrames.isIdle(frame), frame);
		}
		// the wait on GTK is a generic call, recognised by the sleep just outside it
		assertEquals("org.eclipse.swt.widgets.Display.sleep",
				IdleFrames.idleFrame(List.of("org.eclipse.swt.internal.gtk.OS.Call", "org.eclipse.swt.widgets.Display.sleep")));
		// work reached through a native callback from inside the wait is work
		assertNull(IdleFrames.idleFrame(List.of("org.app.A.a", "org.app.B.b", "org.app.C.c", "org.app.D.d",
				"org.eclipse.swt.widgets.Display.sleep")));
	}

	private static void add(TimeProfile profile, String thread, boolean execution, String[] frames) {
		profile.observe(thread, execution, frames);
		profile.add(thread, execution, frames, false);
	}

	private static int number(Map<String, Object> map, String key) {
		return ((Number) map.get(key)).intValue();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> first(Map<String, Object> map, String key) {
		return ((List<Map<String, Object>>) map.get(key)).get(0);
	}
}
