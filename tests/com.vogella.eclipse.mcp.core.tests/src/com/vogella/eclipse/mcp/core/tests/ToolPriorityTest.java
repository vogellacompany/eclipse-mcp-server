package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.eclipse.core.internal.registry.ExtensionRegistry;
import org.eclipse.core.runtime.ContributorFactoryOSGi;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolRegistry;
import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Two tools contributed under one name: the higher priority wins, whichever comes first.
 */
class ToolPriorityTest {

	private static final String NAME = "eclipse_priority_test_tool";

	private static final String EXTENSION_ID = "priorityTest";

	@AfterEach
	void removeContribution() {
		IExtensionRegistry registry = Platform.getExtensionRegistry();
		Object token = ((ExtensionRegistry) registry).getTemporaryUserToken();
		String id = FrameworkUtil.getBundle(ToolPriorityTest.class).getSymbolicName() + "." + EXTENSION_ID;
		IExtension extension = registry.getExtension(id);
		if (extension != null) {
			registry.removeExtension(extension, token);
		}
		McpToolRegistry.getInstance().reset();
	}

	@Test
	void theHigherPriorityWinsWhenItComesSecond() {
		contribute("<tool class=\"%s\"/><tool class=\"%s\" priority=\"1\"/>".formatted(Plain.class.getName(),
				Richer.class.getName()));

		assertEquals(Richer.class, tool().getClass());
	}

	@Test
	void theHigherPriorityWinsWhenItComesFirst() {
		contribute("<tool class=\"%s\" priority=\"1\"/><tool class=\"%s\"/>".formatted(Richer.class.getName(),
				Plain.class.getName()));

		assertEquals(Richer.class, tool().getClass());
	}

	@Test
	void aPriorityThatIsNoNumberCountsAsNone() {
		contribute("<tool class=\"%s\"/><tool class=\"%s\" priority=\"high\"/>".formatted(Plain.class.getName(),
				Richer.class.getName()));

		// the same priority is a duplicate, and the first contribution stays
		assertEquals(Plain.class, tool().getClass());
	}

	@Test
	void theOtherToolsAreUnaffected() {
		contribute("<tool class=\"%s\"/><tool class=\"%s\" priority=\"1\"/>".formatted(Plain.class.getName(),
				Richer.class.getName()));

		assertTrue(McpToolRegistry.getInstance().findTool("eclipse_list_projects").isPresent());
		assertEquals(1, McpToolRegistry.getInstance().getTools().stream().filter(t -> NAME.equals(t.getName())).count());
	}

	private static IMcpTool tool() {
		return McpToolRegistry.getInstance().findTool(NAME).orElseThrow();
	}

	private static void contribute(String tools) {
		IExtensionRegistry registry = Platform.getExtensionRegistry();
		Object token = ((ExtensionRegistry) registry).getTemporaryUserToken();
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<plugin>
				   <extension id="%s" point="%s">%s</extension>
				</plugin>
				""".formatted(EXTENSION_ID, McpToolRegistry.EXTENSION_POINT_ID, tools);
		boolean added = registry.addContribution(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
				ContributorFactoryOSGi.createContributor(FrameworkUtil.getBundle(ToolPriorityTest.class)), false, null,
				null, token);
		assertTrue(added, "the test contribution was not added");
		McpToolRegistry.getInstance().reset();
	}

	public static class Plain implements IMcpTool {

		@Override
		public String getName() {
			return NAME;
		}

		@Override
		public String getDescription() {
			return "The plain version."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return "{\"type\":\"object\",\"properties\":{}}"; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			return McpToolResult.of("{}"); //$NON-NLS-1$
		}
	}

	public static class Richer extends Plain {

		@Override
		public String getDescription() {
			return "The richer version."; //$NON-NLS-1$
		}
	}
}
