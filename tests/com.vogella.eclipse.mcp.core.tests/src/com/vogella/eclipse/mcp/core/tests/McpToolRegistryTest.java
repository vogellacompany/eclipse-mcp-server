package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolRegistry;

class McpToolRegistryTest {

	@Test
	void discoversTheContributedTools() {
		Collection<IMcpTool> tools = McpToolRegistry.getInstance().getTools();
		List<String> names = tools.stream().map(IMcpTool::getName).toList();
		assertTrue(names.containsAll(List.of("eclipse_list_projects", "eclipse_get_problems", "eclipse_find_references",
				"eclipse_get_type_hierarchy", "eclipse_get_source", "eclipse_get_outline", "eclipse_search_types",
				"eclipse_organize_imports", "eclipse_format")), "Missing tools, found " + names);
	}

	@Test
	void everyToolIsFullyDescribed() {
		for (IMcpTool tool : McpToolRegistry.getInstance().getTools()) {
			assertFalse(tool.getName().isBlank(), "A tool has a blank name");
			assertFalse(tool.getDescription().isBlank(), tool.getName() + " has a blank description");
			assertDoesNotThrow(() -> TestFixture.parse(tool.getInputSchema()),
					tool.getName() + " has an input schema that is not valid JSON");
		}
	}

	@Test
	void toolNamesAreUnique() {
		List<String> names = McpToolRegistry.getInstance().getTools().stream().map(IMcpTool::getName).toList();
		assertEquals(names.size(), names.stream().distinct().count(), "Duplicate tool names in " + names);
	}
}
