package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * {@code eclipse_pause} waits for what it was asked and says when it could not.
 */
class PauseToolTest {

	private static final String TOOL = "eclipse_pause";

	private static final String SERVER = "com.vogella.eclipse.mcp.server";

	private static final String TIMEOUT_KEY = "callTimeoutSeconds";

	@Test
	void waitsForTheRequestedTime() throws Exception {
		long before = System.currentTimeMillis();
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("millis", 150));
		long elapsed = System.currentTimeMillis() - before;

		assertTrue(elapsed >= 150, "returned after " + elapsed + " ms");
		assertEquals(Boolean.FALSE, result.get("clamped"));
		assertTrue(((Number) result.get("pausedMillis")).longValue() >= 150, result.toString());
	}

	@Test
	void saysWhenTheCallTimeoutCutTheWaitShort() throws Exception {
		// far above any call timeout, so the cap applies and the answer has to say so
		// rather than sit through it; the wait itself is then the capped length
		// a 4 second call timeout leaves a 1 second wait, so the test does not sit through the default
		InstanceScope.INSTANCE.getNode(SERVER).putInt(TIMEOUT_KEY, 4);
		try {
			McpToolResult result = TestFixture.call(TOOL, Map.of("millis", 600_000));
			assertFalse(result.isError(), result.text());
			Map<String, Object> parsed = TestFixture.parse(result.text());
			assertEquals(Boolean.TRUE, parsed.get("clamped"), result.text());
			assertTrue(String.valueOf(parsed.get("note")).contains("eclipse_pause"), result.text());
		} finally {
			InstanceScope.INSTANCE.getNode(SERVER).remove(TIMEOUT_KEY);
		}
	}

	@Test
	void requiresAPositiveTime() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("millis", 0));
		assertTrue(result.isError(), result.text());
	}
}
