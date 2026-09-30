package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * The declared safety of the two tools that end the IDE.
 * <p>
 * Same reasoning as {@link ProvisioningGuardsTest}: no test may run these, since
 * a passing test would have taken down the IDE it ran in, and both are
 * registered in this headless run even though nothing here calls them. What can
 * be held is that the guards a careless edit would drop are still declared.
 */
class ShutdownGuardsTest {

	@Test
	void bothAreRegistered() {
		for (String name : List.of("eclipse_restart", "eclipse_exit")) {
			TestFixture.tool(name);
		}
	}

	@Test
	void neitherForcesByDefault() throws Exception {
		for (String name : List.of("eclipse_restart", "eclipse_exit")) {
			Map<String, Object> properties = properties(TestFixture.parse(TestFixture.tool(name).getInputSchema()));
			assertTrue(properties.containsKey("force"), name + " must keep a way to say 'discard the work'");
			assertFalse(defaultsToTrue(properties, "force"),
					name + " must not discard unsaved work unless it was asked to");
			assertTrue(properties.containsKey("save"), name + " must keep the non-destructive way past the guard");
		}
	}

	@Test
	void exitSaysItCannotBeUndoneFromHere() {
		IMcpTool exit = TestFixture.tool("eclipse_exit");
		// the whole difference from a restart: nothing inside the IDE can bring it
		// back, and the description is the only place a model learns that before
		// calling it
		assertTrue(exit.getDescription().contains("SHUTS THE IDE DOWN"), "got " + exit.getDescription());
		assertTrue(exit.getDescription().contains("starting it again is the caller's job"),
				"got " + exit.getDescription());
	}

	@Test
	void bothSayTheAnswerIsNotTheOutcome() {
		// the server dies with the workbench, so neither can report its own success;
		// a client that reads the result as the outcome is being misled
		for (String name : List.of("eclipse_restart", "eclipse_exit")) {
			assertTrue(TestFixture.tool(name).getDescription().contains("REQUESTED, NOT"),
					name + " must say that it answers before it acts");
		}
	}

	@Test
	void bothRefuseHeadlessRatherThanTouchingPlatformUI() throws Exception {
		// this run registers the ui tools and has no workbench, so the refusal is
		// also what keeps the suite from ending itself by loading one of these
		for (String name : List.of("eclipse_restart", "eclipse_exit")) {
			McpToolResult result = TestFixture.call(name, Map.of());
			assertTrue(result.isError(), name + " must refuse without a workbench, got " + result.text());
			assertTrue(result.text().toLowerCase().contains("no running workbench"),
					"expected a message about the missing workbench, got " + result.text());
		}
	}

	@Test
	void displayInfoIsRegisteredAndRefusesHeadless() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_get_display_info", Map.of());
		assertTrue(result.isError(), "expected a refusal, got " + result.text());
		assertTrue(result.text().toLowerCase().contains("no running workbench"), result.text());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> properties(Map<String, Object> schema) {
		return (Map<String, Object>) schema.get("properties");
	}

	@SuppressWarnings("unchecked")
	private static boolean defaultsToTrue(Map<String, Object> properties, String name) {
		Object property = properties.get(name);
		return property instanceof Map<?, ?> map && Boolean.TRUE.equals(((Map<String, Object>) map).get("default"));
	}
}
