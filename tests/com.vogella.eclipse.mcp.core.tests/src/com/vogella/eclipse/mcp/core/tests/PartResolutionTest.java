package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;

/**
 * That the tools taking a part say an id can name more than one part.
 * <p>
 * This run is headless, so no workbench page exists and the resolution itself
 * cannot be exercised. What is held here is the contract a caller reads: every
 * Java editor is {@code org.eclipse.jdt.ui.CompilationUnitEditor}, so a
 * workspace with two Java files open has several parts under that id and the
 * id alone cannot name one of them. Taking the first match refused a capture of
 * a part that was visible and active, and where nothing tested visibility it
 * acted on the wrong editor and reported success.
 */
class PartResolutionTest {

	private static final List<String> TAKING_A_PART = List.of("eclipse_screenshot", "eclipse_start_screencast",
			"eclipse_set_part_state", "eclipse_get_widget_tree", "eclipse_inspect_widget", "eclipse_expand_row",
			"eclipse_select_tab", "eclipse_get_context_menu", "eclipse_set_selection", "eclipse_get_text_bounds",
			"eclipse_list_annotations", "eclipse_type_text");

	@Test
	void everyToolTakingAPartSaysTheIdCanNameSeveral() {
		for (String name : TAKING_A_PART) {
			IMcpTool tool = TestFixture.tool(name);
			String schema = tool.getInputSchema();

			assertTrue(schema.contains("Several open parts share one id"), name + " got " + schema);
			// the way out of the ambiguity, which only helps if the tool names it
			assertTrue(schema.contains("TITLE from eclipse_list_ui_targets"), name + " got " + schema);
		}
	}

	@Test
	void listUiTargetsReportsTheTitleThatTellsThemApart() {
		IMcpTool targets = TestFixture.tool("eclipse_list_ui_targets");

		// the titles are the only field that distinguishes two parts under one id,
		// so the tool the others send callers to has to say the id is not enough
		assertTrue(targets.getDescription().contains("A PART ID IS NOT UNIQUE"), "got " + targets.getDescription());
	}
}
