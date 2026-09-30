package com.vogella.eclipse.mcp.core.tests;

import static com.vogella.eclipse.mcp.core.tests.TestFixture.assertRefused;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Argument handling and refusals of the command and window tools.
 * <p>
 * This run is headless, so anything past argument checking can only be checked
 * for refusing cleanly. The refusals of exit and restart workbench are
 * deliberately placed before the workbench check, because they have to hold
 * without one, which is what the exit test asserts.
 */
class CommandToolsTest {

	@Test
	void listingNeedsAWorkbench() throws Exception {
		assertRefused(TestFixture.call("eclipse_list_commands", Map.of()), "no running workbench");
		assertRefused(TestFixture.call("eclipse_list_commands", Map.of("filter", "close")), "no running workbench");
	}

	@Test
	void runningNamesItsRequiredArgument() throws Exception {
		assertRefused(TestFixture.call("eclipse_run_workbench_command", Map.of()), "'command' is required");
	}

	@Test
	void refusingExitDoesNotDependOnAWorkbench() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_run_workbench_command",
				Map.of("command", "org.eclipse.ui.file.exit"));

		assertTrue(result.isError());
		assertFalse(result.text().toLowerCase().contains("no running workbench"),
				"the refusal has to fire before the workbench check, got " + result.text());
		assertTrue(result.text().contains("ends the IDE"), result.text());
	}

	@Test
	void restartingIsRefusedTowardsEclipseRestart() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_run_workbench_command",
				Map.of("command", "org.eclipse.ui.file.restartWorkbench"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("eclipse_restart"), result.text());
		assertFalse(result.text().toLowerCase().contains("no running workbench"), result.text());
	}

	@Test
	void managingAWindowNamesItsAction() throws Exception {
		assertRefused(TestFixture.call("eclipse_manage_window", Map.of()), "'action' is required");
		assertRefused(TestFixture.call("eclipse_manage_window", Map.of("action", "restart")), "Unknown action");
	}

	@Test
	void managingAWindowNeedsAWorkbench() throws Exception {
		assertRefused(TestFixture.call("eclipse_manage_window", Map.of("action", "open")), "no running workbench");
		assertRefused(TestFixture.call("eclipse_manage_window", Map.of("action", "close")), "no running workbench");
	}
}
