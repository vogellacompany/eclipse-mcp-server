package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.McpToolResult;

class GetLogEntriesToolTest {

	private static final String TOOL = "eclipse_get_log_entries";

	private static final String PLUGIN = "com.vogella.eclipse.mcp.core.tests";

	@Test
	void findsAnEntryItJustLogged() throws Exception {
		String message = "mcp log tool marker " + System.nanoTime();
		log(new Status(IStatus.WARNING, PLUGIN, message));

		Map<String, Object> entry = awaitEntry(message);

		assertEquals(PLUGIN, entry.get("plugin"));
		assertEquals("warning", entry.get("severity"));
		assertEquals(message, entry.get("message"));
		assertNotNull(entry.get("timestamp"));
	}

	@Test
	void keepsTheChildrenAndStackTraceOfAMultiStatus() throws Exception {
		String message = "mcp freeze marker " + System.nanoTime();
		MultiStatus freeze = new MultiStatus(PLUGIN, 0, message, null);
		freeze.add(new Status(IStatus.INFO, PLUGIN, "Sample at 11:58:26.099", new IllegalStateException("sampled")));
		log(freeze);

		Map<String, Object> entry = awaitEntry(message);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> children = (List<Map<String, Object>>) entry.get("children");
		assertNotNull(children, "the child statuses were dropped");
		assertEquals(1, children.size());
		assertEquals("Sample at 11:58:26.099", children.get(0).get("message"));
		String stack = (String) children.get(0).get("stackTrace");
		assertTrue(stack != null && stack.contains("IllegalStateException: sampled"), String.valueOf(stack));
	}

	@Test
	void omitsStackTracesWhenAskedTo() throws Exception {
		String message = "mcp compact marker " + System.nanoTime();
		log(new Status(IStatus.ERROR, PLUGIN, message, new IllegalStateException("boom")));

		awaitEntry(message);
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("messageFilter", message, "includeStackTraces", Boolean.FALSE));
		Map<String, Object> entry = onlyEntry(result);

		assertNull(entry.get("stackTrace"));
		// the throwable itself stays, it is one line and worth having
		assertTrue(String.valueOf(entry.get("exception")).contains("boom"));
	}

	@Test
	void filtersByPluginAndSeverity() throws Exception {
		String message = "mcp severity marker " + System.nanoTime();
		log(new Status(IStatus.WARNING, PLUGIN, message));
		awaitEntry(message);

		Map<String, Object> matching = TestFixture.callAndParse(TOOL,
				Map.of("messageFilter", message, "plugin", PLUGIN, "severity", "warning"));
		assertEquals(Integer.valueOf(1), matching.get("total"));

		Map<String, Object> wrongSeverity = TestFixture.callAndParse(TOOL,
				Map.of("messageFilter", message, "severity", "error"));
		assertEquals(Integer.valueOf(0), wrongSeverity.get("total"));

		Map<String, Object> wrongPlugin = TestFixture.callAndParse(TOOL,
				Map.of("messageFilter", message, "plugin", "org.eclipse.nothing"));
		assertEquals(Integer.valueOf(0), wrongPlugin.get("total"));
	}

	@Test
	void rejectsAnUnknownSeverity() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("severity", "fatal"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("fatal"), result.text());
	}

	@Test
	void rejectsAnUnreadableSinceTimestamp() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("since", "last tuesday"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("last tuesday"), result.text());
	}

	@Test
	void reportsTheLogFileItRead() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("maxResults", Integer.valueOf(1)));

		assertTrue(String.valueOf(result.get("logFile")).endsWith(".log"), String.valueOf(result.get("logFile")));
	}

	private static void log(IStatus status) {
		Platform.getLog(FrameworkUtil.getBundle(GetLogEntriesToolTest.class)).log(status);
	}

	/** The platform writes the log file on its own schedule, so give it a moment to appear. */
	private static Map<String, Object> awaitEntry(String message) throws Exception {
		AssertionError failure = null;
		for (int attempt = 0; attempt < 50; attempt++) {
			Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("messageFilter", message));
			try {
				return onlyEntry(result);
			} catch (AssertionError e) {
				failure = e;
				Thread.sleep(100);
			}
		}
		throw failure;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> onlyEntry(Map<String, Object> result) {
		List<Map<String, Object>> entries = (List<Map<String, Object>>) result.get("entries");
		assertEquals(1, entries.size(), "expected exactly one entry, got " + entries);
		return entries.get(0);
	}
}
