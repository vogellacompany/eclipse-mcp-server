package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * The theme tools, as far as they can be reached without a workbench.
 * <p>
 * The registry tests already check name, description and schema; what is worth
 * its own test here is the argument handling that happens before the UI thread
 * is involved, and the clean refusal of an IDE whose workbench is not running.
 */
class ThemeToolsTest {

	@Test
	void setThemeNamesItsRequiredArgument() throws Exception {
		assertError("eclipse_set_theme", Map.of(), "'theme' is required");
	}

	@Test
	void bothRefuseCleanlyWithoutAWorkbench() throws Exception {
		assertRefusedWithoutAWorkbench("eclipse_list_themes", Map.of());
		assertRefusedWithoutAWorkbench("eclipse_set_theme", Map.of("theme", "org.eclipse.e4.ui.css.theme.e4_dark"));
		assertRefusedWithoutAWorkbench("eclipse_set_theme", Map.of("theme", "Dark", "persist", Boolean.FALSE));
	}

	@Test
	void registeringAThemeNamesItsRequiredArguments() throws Exception {
		assertError("eclipse_register_theme", Map.of(), "'id'");
		assertError("eclipse_register_theme", Map.of("id", "com.example.theme"), "'label'");
		assertError("eclipse_register_theme", Map.of("id", "com.example.theme", "label", "Example"),
				"'css'");
	}

	@Test
	void registeringAThemeReportsAMissingStylesheet() throws Exception {
		assertError("eclipse_register_theme",
				Map.of("id", "com.example.theme", "label", "Example", "css", "/no/such/theme.css"), "No stylesheet at");
	}

	@Test
	void registeringAThemeRefusesWithoutAWorkbench(@TempDir Path dir) throws Exception {
		Path css = Files.writeString(dir.resolve("theme.css"), "Label {color: #ff0000;}");
		assertRefusedWithoutAWorkbench("eclipse_register_theme",
				Map.of("id", "com.example.theme", "label", "Example", "css", css.toString()));
	}

	private static void assertError(String tool, Map<String, Object> arguments, String expected) throws Exception {
		McpToolResult result = TestFixture.call(tool, arguments);
		assertTrue(result.isError(), "expected an error, got " + result.text());
		assertTrue(result.text().contains(expected),
				"expected a message about '%s', got %s".formatted(expected, result.text()));
	}

	private static void assertRefusedWithoutAWorkbench(String tool, Map<String, Object> arguments) throws Exception {
		McpToolResult result = TestFixture.call(tool, arguments);
		assertTrue(result.isError(), "expected an error, got " + result.text());
		assertTrue(result.text().toLowerCase().contains("no running workbench"),
				"expected a refusal without a workbench, got " + result.text());
	}
}
