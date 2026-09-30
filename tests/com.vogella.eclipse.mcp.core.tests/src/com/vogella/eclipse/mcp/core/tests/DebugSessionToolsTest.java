package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Argument handling and refusals of the session tools, which need no debug
 * session at all: every case here is answered before any launch is touched.
 */
class DebugSessionToolsTest {

	private static final String UNKNOWN = "debug-999999";

	@Test
	void anUnknownSessionIdIsRefusedWithTheKnownOnes() throws Exception {
		for (String tool : new String[] { "eclipse_debug_status", "eclipse_debug_get_frames" }) {
			McpToolResult result = TestFixture.call(tool, Map.of("sessionId", UNKNOWN));
			assertTrue(result.isError(), tool + " should refuse an unknown session");
			assertTrue(result.text().contains(UNKNOWN), tool + " named the wrong problem: " + result.text());
		}
		McpToolResult evaluate = TestFixture.call("eclipse_debug_evaluate",
				Map.of("sessionId", UNKNOWN, "expression", "1 + 1"));
		assertTrue(evaluate.isError(), "eclipse_debug_evaluate should refuse an unknown session");
		assertTrue(evaluate.text().contains(UNKNOWN), evaluate.text());

		McpToolResult control = TestFixture.call("eclipse_debug_control",
				Map.of("sessionId", UNKNOWN, "action", "resume"));
		assertTrue(control.isError(), "eclipse_debug_control should refuse an unknown session");
		assertTrue(control.text().contains(UNKNOWN), control.text());
	}

	@Test
	void framesWithoutASessionSayHowToGetOne() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_debug_get_frames", Map.of());

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("session"), result.text());
	}

	@Test
	void evaluationNeedsAnExpression() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_debug_evaluate", Map.of("sessionId", UNKNOWN));
		assertTrue(result.isError());
		assertTrue(result.text().contains("'expression'"), result.text());
	}

	@Test
	void controlRefusesAnActionThatDoesNotExist() throws Exception {
		McpToolResult missing = TestFixture.call("eclipse_debug_control", Map.of("sessionId", UNKNOWN));
		assertTrue(missing.isError());
		assertTrue(missing.text().contains("action"), missing.text());

		McpToolResult nonsense = TestFixture.call("eclipse_debug_control",
				Map.of("sessionId", UNKNOWN, "action", "explode"));
		assertTrue(nonsense.isError());
		assertTrue(nonsense.text().contains("explode"), nonsense.text());
		assertTrue(nonsense.text().contains("resume"), nonsense.text());
	}

	@Test
	void statusAnswersEvenWithNothingRunning() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse("eclipse_debug_status", Map.of());

		assertTrue(result.containsKey("total"), String.valueOf(result));
		assertTrue(result.containsKey("truncated"), String.valueOf(result));
		assertTrue(result.containsKey("sessions"), String.valueOf(result));
	}
}
