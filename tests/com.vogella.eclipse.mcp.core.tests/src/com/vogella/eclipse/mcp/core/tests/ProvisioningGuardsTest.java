package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;

/**
 * The declared safety of the provisioning tools.
 * <p>
 * These modify the installation, so they are the highest-risk surface here and
 * the one nothing else covers: no test may actually run them, since a passing
 * test would have changed the IDE it ran in. What can be held is that the guards
 * are still declared, which is what a client sees and what a careless edit would
 * quietly drop.
 */
class ProvisioningGuardsTest {

	@Test
	void theProvisioningToolsAreRegistered() {
		for (String name : List.of("eclipse_check_for_updates", "eclipse_update", "eclipse_install",
				"eclipse_uninstall", "eclipse_get_provisioning_status")) {
			TestFixture.tool(name);
		}
	}

	@Test
	void updateIsADryRunByDefaultAndGuardsSelfUpdates() throws Exception {
		Map<String, Object> schema = TestFixture.parse(TestFixture.tool("eclipse_update").getInputSchema());
		Map<String, Object> properties = properties(schema);

		assertTrue(properties.containsKey("acknowledgeSelfUpdate"),
				"updating the server itself must stay behind an explicit acknowledgement");
		assertTrue(defaultsToTrue(properties, "dryRun"), "eclipse_update must default to a dry run");
	}

	@Test
	void installSaysItRefusesUnconfiguredRepositories() {
		IMcpTool install = TestFixture.tool("eclipse_install");
		// the allowlist is the whole safety story of that tool: adding a source is a
		// decision for the person at the IDE, and the description is where a client
		// learns it before trying
		assertTrue(install.getDescription().contains("refused rather than added"),
				"got " + install.getDescription());
	}

	@Test
	void installStaysOnItsRepositoryAndGuardsInstalledUnits() throws Exception {
		Map<String, Object> properties = properties(
				TestFixture.parse(TestFixture.tool("eclipse_install").getInputSchema()));

		assertTrue(defaultsToFalse(properties, "contactAllSites"),
				"resolving against every site lets the planner swap installed dependencies");
		assertTrue(defaultsToFalse(properties, "dryRun"), "an install goes ahead unless a dry run is asked for");
		assertTrue(defaultsToFalse(properties, "allowChangingInstalled"),
				"changing installed units under a new install must stay behind an explicit opt-in");
	}

	@Test
	void everyProvisioningToolSaysItModifiesTheInstallation() {
		for (String name : List.of("eclipse_update", "eclipse_install", "eclipse_uninstall")) {
			assertTrue(TestFixture.tool(name).getDescription().contains("MODIFIES THE INSTALLATION")
					|| TestFixture.tool(name).getDescription().contains("UNINSTALLS SOFTWARE FROM THE RUNNING INSTALLATION"),
					name + " must announce what it does, since that is the only place the model sees it");
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> properties(Map<String, Object> schema) {
		return (Map<String, Object>) schema.get("properties");
	}

	@SuppressWarnings("unchecked")
	private static boolean defaultsToFalse(Map<String, Object> properties, String name) {
		Object property = properties.get(name);
		return property instanceof Map<?, ?> map && Boolean.FALSE.equals(((Map<String, Object>) map).get("default"));
	}

	@SuppressWarnings("unchecked")
	private static boolean defaultsToTrue(Map<String, Object> properties, String name) {
		Object property = properties.get(name);
		return property instanceof Map<?, ?> map && Boolean.TRUE.equals(((Map<String, Object>) map).get("default"));
	}
}
