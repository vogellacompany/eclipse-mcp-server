package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
