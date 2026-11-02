package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Recording this JVM and reading the recording back.
 * <p>
 * This is one of the few tools that can be exercised end to end headlessly,
 * because the thing it profiles is the test JVM itself. That is worth using: an
 * aggregation that never saw a real recording proves nothing.
 */
class FlightRecordingToolsTest {

	private static final String START = "eclipse_start_flight_recording";

	private static final String STOP = "eclipse_stop_flight_recording";

	@Test
	void refusesSettingsItDoesNotKnow() throws Exception {
		McpToolResult result = TestFixture.call(START, Map.of("settings", "everything"));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("everything"), result.text());
	}

	@Test
	void recordsThisJvmAndFindsWhatTheTestAllocated() throws Exception {
		// The sampler is throttled and shares its budget with everything else in this
		// JVM, so one round can genuinely miss this frame: the runs that failed had
		// JDT indexing a JRT taking the slots, one sample of it weighing 939 MB.
		// Three rounds missing it is the aggregation being broken rather than the
		// sampler being busy, which is the difference this loop exists to keep.
		Map<String, Object> stopped = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			stopped = recordWhileAllocating();
			assumeTrue(stopped != null, "this JVM does not expose jdk.jfr");
			if (namesTheAllocatingFrame(stopped)) {
				break;
			}
		}

		assertEquals(Boolean.FALSE, stopped.get("stillRunning"), "got " + stopped);
		assertTrue(((Number) stopped.get("eventsRead")).intValue() > 0, "the recording should hold events");
		assertNotNull(stopped.get("allocationByClass"));
		assertNotNull(stopped.get("gc"));
		// the point of the whole tool: the caller of the allocation has to be named,
		// which is what a class on its own never says
		assertTrue(namesTheAllocatingFrame(stopped),
				"the test's own allocating frame should be among the stacks after three rounds, got " + stopped);
		assertTrue(names(stopped, "allocationByClass", "class").size() > 0, "got " + stopped);
	}

	/** One record, allocate, stop round, or null where this JVM has no jdk.jfr. */
	private static Map<String, Object> recordWhileAllocating() throws Exception {
		Map<String, Object> started = TestFixture.callAndParse(START,
				Map.of("settings", "profile", "durationSeconds", Integer.valueOf(0), "maxSizeMegabytes",
						Integer.valueOf(16)));
		if (started.get("recordingId") == null) {
			return null;
		}
		String id = String.valueOf(started.get("recordingId"));
		assertEquals(Boolean.TRUE, started.get("running"), "got " + started);
		try {
			allocate();
			return TestFixture.callAndParse(STOP, Map.of("recordingId", id, "topClasses", Integer.valueOf(30)));
		} finally {
			// the recording is closed by the stop above; this only matters when an
			// assertion failed before it ran
			TestFixture.call(STOP, Map.of("recordingId", id));
		}
	}

	private static boolean namesTheAllocatingFrame(Map<String, Object> stopped) {
		return names(stopped, "allocationByStack", "stack").stream()
				.anyMatch(stack -> stack.contains("FlightRecordingToolsTest.allocate"));
	}

	@Test
	void aMisspeltSettingIsRefusedRatherThanRecordedWithTheOldValue() throws Exception {
		McpToolResult noSuchSetting = TestFixture.call(START,
				Map.of("settingsOverrides", Map.of("jdk.ExecutionSample#perod", "1 ms")));
		// a JVM without jdk.jfr refuses for its own reason, which says so
		assumeTrue(!noSuchSetting.text().contains("does not expose"), "this JVM does not expose jdk.jfr");

		assertTrue(noSuchSetting.isError(), noSuchSetting.text());
		assertTrue(noSuchSetting.text().contains("perod"), noSuchSetting.text());
		assertTrue(TestFixture.call(START, Map.of("settingsOverrides", Map.of("jdk.NoSuchEvent#period", "1 ms"))).isError());
		assertTrue(TestFixture.call(START, Map.of("settingsOverrides", Map.of("noSeparator", "1 ms"))).isError());
	}

	@Test
	void anEmptyTimeWindowAndAnUnknownWeightAreRefused() throws Exception {
		McpToolResult window = TestFixture.call(STOP,
				Map.of("file", "does-not-matter.jfr", "fromSeconds", Integer.valueOf(5), "toSeconds", Integer.valueOf(5)));
		assertTrue(window.isError() && window.text().contains("toSeconds"), window.text());
		McpToolResult weight = TestFixture.call(STOP, Map.of("file", "does-not-matter.jfr", "flameWeight", "weight"));
		assertTrue(weight.isError() && weight.text().contains("flameWeight"), weight.text());
	}

	/** One real recording read from several angles, since a file needs no second recording. */
	@Test
	@SuppressWarnings("unchecked")
	void readsWhereTheTimeWentAndWhatWasThrownFromARealRecording() throws Exception {
		Map<String, Object> started = TestFixture.callAndParse(START,
				Map.of("settings", "profile", "durationSeconds", Integer.valueOf(0), "maxSizeMegabytes", Integer.valueOf(16),
						"settingsOverrides", Map.of("jdk.ExecutionSample#period", "10 ms", "jdk.JavaExceptionThrow#enabled",
								"true", "jdk.JavaExceptionThrow#throttle", "off")));
		assumeTrue(started.get("recordingId") != null, "this JVM does not expose jdk.jfr");
		assertEquals("10 ms", ((Map<String, Object>) started.get("settingsOverrides")).get("jdk.ExecutionSample#period"));

		Thread busy = new Thread(FlightRecordingToolsTest::spinAndThrow, "jfr-test-busy");
		Thread other = new Thread(FlightRecordingToolsTest::spin, "other-busy");
		other.start();
		busy.start();
		busy.join();
		other.join();
		Path file = Files.createTempFile("mcp-flight-test", ".jfr");
		try {
			Map<String, Object> stopped = TestFixture.callAndParse(STOP,
					Map.of("recordingId", started.get("recordingId"), "outputPath", file.toString()));
			Map<String, Object> time = (Map<String, Object>) stopped.get("time");
			Map<String, Object> sampling = (Map<String, Object>) ((Map<String, Object>) time.get("sampling")).get("execution");
			assertEquals("10 ms", sampling.get("period"), "the override has to reach the recording, got " + sampling);
			assertTrue(((Number) time.get("recordingSeconds")).doubleValue() > 0, "got " + time);

			Map<String, Object> selected = TestFixture.callAndParse(STOP,
					Map.of("file", file.toString(), "threadNames", List.of("JFR-TEST-*", "no-such-thread")));
			Map<String, Object> selectedTime = (Map<String, Object>) selected.get("time");
			assertEquals(List.of("no-such-thread"), selectedTime.get("threadNamesNotFound"));
			assertTrue(((Number) selectedTime.get("eventsOnOtherThreads")).intValue() > 0, "got " + selectedTime);
			assertTrue(names(selectedTime, "topBySelfTime", "frame").stream().anyMatch(frame -> frame.contains(".spin")),
					"the thread's own frame should be where its time went, got " + selectedTime);
			assertTrue(selectedTime.get("tree").toString().contains("thread: jfr-test-busy"), "got " + selectedTime);
			assertTrue(names(selectedTime, "byThread", "thread").size() > 1,
					"byThread keeps the other threads so that a name can be chosen, got " + selectedTime);

			Map<String, Object> thrown = (Map<String, Object>) selected.get("thrown");
			Map<String, Object> group = ((List<Map<String, Object>>) thrown.get("groups")).stream()
					.filter(entry -> MESSAGE.equals(entry.get("message"))).findFirst().orElseThrow();
			assertEquals("java.lang.Error", group.get("class"));
			assertTrue(String.valueOf(group.get("frame")).endsWith("FlightRecordingToolsTest.spinAndThrow"), "got " + group);
			// with the throttle off the JVM reports each Error once as an error and twice as an exception
			assertEquals(THROWS, ((Number) group.get("count")).intValue(), "got " + group);
			assertEquals(THROWS, ((Number) group.get("errorEvents")).intValue(), "got " + group);

			Map<String, Object> beforeAnything = TestFixture.callAndParse(STOP,
					Map.of("file", file.toString(), "toSeconds", Double.valueOf(0.000001)));
			Map<String, Object> beforeTime = (Map<String, Object>) beforeAnything.get("time");
			assertTrue(((Number) beforeTime.get("eventsOutsideWindow")).intValue() > 0, "got " + beforeTime);
			assertEquals(0, ((Number) beforeTime.get("samples")).intValue(), "got " + beforeTime);

			Map<String, Object> timePage = TestFixture.callAndParse(STOP,
					Map.of("file", file.toString(), "show", Boolean.TRUE, "flameWeight", "time"));
			assertEquals("time samples", timePage.get("traceWeight"));
			Map<String, Object> bytesPage = TestFixture.callAndParse(STOP, Map.of("file", file.toString(), "show", Boolean.TRUE));
			assertEquals("estimated allocated bytes", bytesPage.get("traceWeight"));
		} finally {
			Files.deleteIfExists(file);
		}
	}

	private static final String MESSAGE = "thrown by the flight recording test";

	private static final int THROWS = 7;

	/** Kept in one method, so that both the samples and the thrown errors name it. */
	private static long spin() {
		long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(800);
		long sum = 0;
		while (System.nanoTime() < until) {
			sum += System.nanoTime() % 7;
		}
		return sum;
	}

	private static void spinAndThrow() {
		long sum = spin();
		for (int i = 0; i < THROWS + (sum == Long.MIN_VALUE ? 1 : 0); i++) {
			try {
				throw new Error(MESSAGE);
			} catch (Error expected) {
				// only the event counts
			}
		}
	}

	@Test
	void stoppingARecordingWhoseDurationIsUpStillReadsIt() throws Exception {
		Map<String, Object> started = TestFixture.callAndParse(START,
				Map.of("settings", "default", "durationSeconds", Integer.valueOf(1), "maxSizeMegabytes",
						Integer.valueOf(16)));
		assumeTrue(started.get("recordingId") != null, "this JVM does not expose jdk.jfr");
		Thread.sleep(2500);

		McpToolResult stopped = TestFixture.call(STOP, Map.of("recordingId", started.get("recordingId")));

		assertTrue(!stopped.isError(), stopped.text());
	}

	@Test
	void readingWithoutARecordingSaysWhichToolStartsOne() throws Exception {
		// the id cannot exist: the registry hands out jfr-<counter>
		McpToolResult result = TestFixture.call(STOP, Map.of("recordingId", "jfr-does-not-exist"));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("jfr-does-not-exist"), result.text());
	}

	/**
	 * Allocates across a window rather than a fixed count of objects.
	 * <p>
	 * jdk.ObjectAllocationSample is throttled per unit of TIME, not per byte, so a
	 * loop that finishes in twenty milliseconds competes for two or three sample
	 * slots against every other thread in the JVM however many megabytes it churns
	 * through. Staying in the loop for about a second is what makes this frame one
	 * the sampler keeps.
	 */
	private static void allocate() {
		List<byte[]> keep = new ArrayList<>();
		long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1200);
		int i = 0;
		while (System.nanoTime() < until) {
			byte[] block = new byte[4096];
			block[0] = (byte) i;
			if (i++ % 500 == 0) {
				keep.add(block);
			}
		}
		if (keep.isEmpty()) {
			throw new IllegalStateException("nothing was allocated");
		}
	}

	@SuppressWarnings("unchecked")
	private static List<String> names(Map<String, Object> result, String field, String key) {
		List<String> names = new ArrayList<>();
		for (Map<String, Object> entry : (List<Map<String, Object>>) result.get(field)) {
			names.add(String.valueOf(entry.get(key)));
		}
		return names;
	}
}
