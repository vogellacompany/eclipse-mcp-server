package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * The declared safety of {@code eclipse_uninstall}.
 * <p>
 * Like the other provisioning tools, no test may actually uninstall anything,
 * since a passing test would have changed the IDE it ran in. The refusals below
 * fire before any resolution or apply, so calling them is safe: a unit that is
 * not installed is turned away without the profile ever being touched.
 */
class UninstallToolTest {

	@Test
	void refusesToActWithoutAUnitArgument() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_uninstall", Map.of());

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("'unit'"), "got " + result.text());
	}

	@Test
	void refusesAUnitThatIsNotInstalledByName() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_uninstall",
				Map.of("unit", "com.example.uninstall.never.installed"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("is installed in this IDE"), "got " + result.text());
		assertTrue(result.text().contains("nothing was changed"), "got " + result.text());
		assertTrue(result.text().contains("eclipse_get_installation"), "got " + result.text());
	}

	@Test
	void dryRunDefaultsToTrueAndTheUnitIsRequired() throws Exception {
		Map<String, Object> schema = TestFixture.parse(TestFixture.tool("eclipse_uninstall").getInputSchema());

		@SuppressWarnings("unchecked")
		Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
		@SuppressWarnings("unchecked")
		Map<String, Object> dryRun = (Map<String, Object>) properties.get("dryRun");
		assertTrue(Boolean.TRUE.equals(dryRun.get("default")), "eclipse_uninstall must default to a dry run");
		@SuppressWarnings("unchecked")
		var required = (List<Object>) schema.get("required");
		assertTrue(required.contains("unit"), "got " + schema);
	}

	@Test
	void saysTheRemovalWaitsForARestart() {
		IMcpTool uninstall = TestFixture.tool("eclipse_uninstall");

		assertTrue(uninstall.getDescription().contains("NOT IN EFFECT UNTIL THE IDE RESTARTS"),
				"got " + uninstall.getDescription());
	}
}
