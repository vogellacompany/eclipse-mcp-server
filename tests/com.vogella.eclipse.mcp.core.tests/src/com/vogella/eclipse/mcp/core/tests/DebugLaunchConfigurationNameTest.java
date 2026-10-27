package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolException;

/**
 * Two projects that each share a launch configuration of the same name, which the launch
 * manager lists in no fixed order: a launch by name alone must not start either one.
 */
class DebugLaunchConfigurationNameTest {

	private static final String NAME = "MCP duplicate name " + System.nanoTime();

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void dispose() throws Exception {
		fixture.dispose();
	}

	@Test
	void refusesANameTwoProjectsShare() throws Exception {
		share(fixture.createProject("mcp-launch-name-a"));
		share(fixture.createProject("mcp-launch-name-b"));

		McpToolException refused = assertThrows(McpToolException.class,
				() -> TestFixture.call("eclipse_debug_launch", Map.of("configuration", NAME)));
		assertTrue(refused.getMessage().contains("/mcp-launch-name-a/"), refused.getMessage());
		assertTrue(refused.getMessage().contains("/mcp-launch-name-b/"), refused.getMessage());
		assertTrue(refused.getMessage().contains("'project'"), refused.getMessage());
	}

	@Test
	void refusesAProjectThatHoldsNoneOfThem() throws Exception {
		share(fixture.createProject("mcp-launch-name-a"));
		share(fixture.createProject("mcp-launch-name-b"));

		McpToolException refused = assertThrows(McpToolException.class, () -> TestFixture
				.call("eclipse_debug_launch", Map.of("configuration", NAME, "project", "mcp-launch-name-c")));
		assertTrue(refused.getMessage().contains("None of the launch configurations"), refused.getMessage());
	}

	private static void share(IProject project) throws CoreException {
		ILaunchConfigurationType type = DebugPlugin.getDefault().getLaunchManager()
				.getLaunchConfigurationType(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION);
		var configuration = type.newInstance(project, NAME);
		configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, project.getName());
		configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "sample.Missing");
		configuration.doSave();
	}
}
