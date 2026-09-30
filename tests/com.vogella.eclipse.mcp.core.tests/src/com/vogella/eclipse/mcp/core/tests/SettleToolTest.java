package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * What {@code eclipse_wait_until_settled} promises, which is deliberately less
 * than it might appear to.
 * <p>
 * This run is headless, so the barrier itself cannot be exercised. What is held
 * here is the honesty of the contract: it is a heuristic, it names the thing it
 * cannot observe, and it does not claim to be the other wait tool.
 */
class SettleToolTest {

	@Test
	void itIsRegisteredAndRefusesWithoutAWorkbench() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_wait_until_settled", Map.of());

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().toLowerCase().contains("no running workbench"), result.text());
	}

	@Test
	void theDescriptionCallsItselfAHeuristicAndNamesTheBlindSpot() {
		IMcpTool settle = TestFixture.tool("eclipse_wait_until_settled");
		String description = settle.getDescription();

		// the whole point of the tool: a caller that reads this must not come away
		// believing a settled answer means the UI has finished
		assertTrue(description.contains("HEURISTIC"), "got " + description);
		// the blind spot has moved once already: the reconcilers used to be
		// unobservable and are now checked, so what has to hold is that the
		// description still names whatever is left rather than any particular case
		assertTrue(description.contains("STILL CANNOT SEE"),
				"it has to name what is left unobserved, got " + description);
		assertTrue(description.contains("plain background thread"),
				"and say concretely what that is, got " + description);
		assertTrue(description.contains("assert"), "it has to point at asserting instead, got " + description);
	}

	@Test
	void anUnreadableReconcilerCountsAsBusyRatherThanIdle() {
		// the direction of that failure is the whole safety of reaching internals by
		// name: a renamed field must make settling harder, never easier
		String description = TestFixture.tool("eclipse_wait_until_settled").getDescription();

		assertTrue(description.contains("counts as BUSY rather than idle"), "got " + description);
		assertTrue(description.contains("never one that succeeds too early"), "got " + description);
	}

	@Test
	void theScreenshotSettleSaysTheSameThing() {
		// the flag is the convenient path and would otherwise carry none of the
		// caveats the dedicated tool spells out
		String schema = TestFixture.tool("eclipse_screenshot").getInputSchema();

		assertTrue(schema.contains("\"settle\""), "got " + schema);
		assertTrue(schema.contains("HEURISTIC"), "the settle flag must carry the caveat too, got " + schema);
		assertTrue(schema.contains("suppressCaret"), "got " + schema);
	}
}
