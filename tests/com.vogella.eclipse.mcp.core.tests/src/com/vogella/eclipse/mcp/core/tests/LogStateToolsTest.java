package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.LogClearedHandlers;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Marking and clearing the Error Log.
 * <p>
 * The clear is verified from the consuming end rather than from the delete
 * returning true: the framework writes the log through its own handle, so a
 * delete underneath it could leave later entries going somewhere nothing can
 * read, and that failure would be silent.
 */
class LogStateToolsTest {

	@Test
	void aMarkerReportsOnlyWhatWasLoggedAfterIt() throws Exception {
		log("before the marker " + System.nanoTime());
		Map<String, Object> mark = TestFixture.callAndParse("eclipse_mark_log", Map.of());
		String marker = String.valueOf(mark.get("marker"));
		assertNotNull(marker);

		String after = "after the marker " + System.nanoTime();
		log(after);

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_log_entries",
				Map.of("marker", marker, "maxResults", Integer.valueOf(500)));

		assertEquals(Boolean.FALSE, result.get("markerStale"));
		String messages = messages(result);
		assertTrue(messages.contains(after), "the entry after the marker should be reported");
		assertFalse(messages.contains("before the marker"), "nothing from before the marker should be, got " + messages);
	}

	@Test
	void rejectsSomethingThatIsNotAMarker() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_get_log_entries", Map.of("marker", "yesterday"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("marker"), result.text());
	}

	@Test
	void aDryRunClearsNothing() throws Exception {
		log("kept by the dry run " + System.nanoTime());
		Map<String, Object> result = TestFixture.callAndParse("eclipse_clear_log", Map.of());

		assertEquals(Boolean.TRUE, result.get("dryRun"));
		assertEquals(Boolean.FALSE, result.get("cleared"));
		assertTrue(((Number) result.get("entriesDiscarded")).intValue() >= 0, "got " + result);
		assertTrue(messages(TestFixture.callAndParse("eclipse_get_log_entries", Map.of("maxResults", 500)))
				.contains("kept by the dry run"), "a dry run must not delete anything");
	}

	@Test
	void clearingLeavesTheLogWritableAndReadable() throws Exception {
		String old = "logged before the clear " + System.nanoTime();
		log(old);

		Map<String, Object> cleared = TestFixture.callAndParse("eclipse_clear_log",
				Map.of("dryRun", Boolean.FALSE));
		assertEquals(Boolean.TRUE, cleared.get("cleared"), "got " + cleared);

		// the point of the test: the framework holds the log open, so a delete
		// underneath it can leave it appending to a file nothing can reach. The tool
		// checks that itself and reports it, and this asserts the check is honest
		@SuppressWarnings("unchecked")
		Map<String, Object> stillLogging = (Map<String, Object>) cleared.get("stillLogging");
		assertEquals(Boolean.TRUE, stillLogging.get("verified"),
				"logging should still work after the clear, got " + stillLogging);

		String fresh = "logged after the clear " + System.nanoTime();
		log(fresh);
		String messages = messages(TestFixture.callAndParse("eclipse_get_log_entries", Map.of("maxResults", 500)));
		assertTrue(messages.contains(fresh), "an entry written after the clear should be readable, got " + messages);
		assertFalse(messages.contains(old), "and the entries from before it should be gone");
	}

	@Test
	void aMarkerTakenBeforeAClearIsReportedAsStale() throws Exception {
		log("filling the log " + System.nanoTime());
		String marker = String.valueOf(TestFixture.callAndParse("eclipse_mark_log", Map.of()).get("marker"));
		TestFixture.callAndParse("eclipse_clear_log", Map.of("dryRun", Boolean.FALSE));

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_log_entries",
				Map.of("marker", marker, "maxResults", Integer.valueOf(500)));

		// a shorter file cannot be the file that was marked, and reporting a window
		// from a position that no longer means anything is the failure a marker exists
		// to prevent
		assertEquals(Boolean.TRUE, result.get("markerStale"), "got " + result);
		assertNotNull(result.get("markerNote"));
	}

	@Test
	void whatShowsTheLogIsToldAboutARealClearOnly() throws Exception {
		// a fresh workspace has no log file until something is logged, and there is nothing to clear then
		log("filling the log " + System.nanoTime());
		AtomicInteger calls = new AtomicInteger();
		LogClearedHandlers.Handler handler = () -> {
			calls.incrementAndGet();
			return new JsonObject().put("updated", Boolean.TRUE).put("viewsCleared", Integer.valueOf(1));
		};
		LogClearedHandlers.set(handler);
		try {
			TestFixture.callAndParse("eclipse_clear_log", Map.of());
			assertEquals(0, calls.get(), "a dry run deletes nothing, so there is nothing to update");

			Map<String, Object> cleared = TestFixture.callAndParse("eclipse_clear_log",
					Map.of("dryRun", Boolean.FALSE));
			assertEquals(1, calls.get(), "a real clear has to update what is showing the log");
			@SuppressWarnings("unchecked")
			Map<String, Object> view = (Map<String, Object>) cleared.get("errorLogView");
			assertEquals(Boolean.TRUE, view.get("updated"), "got " + cleared);
		} finally {
			LogClearedHandlers.unset(handler);
		}
	}

	@Test
	void aFailingViewUpdateStillLeavesTheLogCleared() throws Exception {
		log("filling the log " + System.nanoTime());
		LogClearedHandlers.Handler handler = () -> {
			throw new IllegalStateException("the view is gone");
		};
		LogClearedHandlers.set(handler);
		try {
			Map<String, Object> cleared = TestFixture.callAndParse("eclipse_clear_log",
					Map.of("dryRun", Boolean.FALSE));
			assertEquals(Boolean.TRUE, cleared.get("cleared"), "got " + cleared);
			@SuppressWarnings("unchecked")
			Map<String, Object> view = (Map<String, Object>) cleared.get("errorLogView");
			assertEquals(Boolean.FALSE, view.get("updated"), "and it has to say the view was not updated");
		} finally {
			LogClearedHandlers.unset(handler);
		}
	}

	@SuppressWarnings("unchecked")
	private static String messages(Map<String, Object> result) {
		StringBuilder all = new StringBuilder();
		for (Map<String, Object> entry : (List<Map<String, Object>>) result.get("entries")) {
			all.append(entry.get("message")).append('\n');
		}
		return all.toString();
	}

	private static void log(String message) {
		Platform.getLog(FrameworkUtil.getBundle(LogStateToolsTest.class))
				.log(new Status(IStatus.INFO, "com.vogella.eclipse.mcp.core.tests", message));
	}
}
