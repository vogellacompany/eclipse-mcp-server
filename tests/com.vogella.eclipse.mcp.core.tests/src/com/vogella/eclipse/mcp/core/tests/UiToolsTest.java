package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.eclipse.compare.ISharedDocumentAdapter;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.IFileEditorInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.ui.internal.CompareTool;

/**
 * The UI tools, as far as they can be reached without a workbench.
 * <p>
 * This run is headless, so anything that touches the workbench can only be
 * checked for refusing cleanly. What is worth testing here is the argument
 * handling that happens before the UI thread is involved, because that is where
 * a caller learns it asked for something impossible.
 */
class UiToolsTest {

	private static final String COMPARE = "eclipse_open_compare";

	private static final String PROJECT = "mcp-ui-tools-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void compareNeedsExactlyOneRightHandSide() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		IFile file = write(project, "a.txt", "one");

		assertRefused(TestFixture.call(COMPARE, Map.of("left", file.getFullPath().toString())),
				"exactly one");
		assertRefused(TestFixture.call(COMPARE, Map.of("left", file.getFullPath().toString(), //
				"content", "other", "revision", "HEAD")), "exactly one");
	}

	@Test
	void compareReportsAPathThatIsNotThere() throws Exception {
		fixture.createProject(PROJECT);
		assertRefused(TestFixture.call(COMPARE, Map.of("left", "/" + PROJECT + "/missing.txt", "content", "x")),
				"No file at the workspace path");
	}

	@Test
	void compareReportsAMissingRightHandFile() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		IFile file = write(project, "a.txt", "one");
		assertRefused(
				TestFixture.call(COMPARE,
						Map.of("left", file.getFullPath().toString(), "right", "/" + PROJECT + "/missing.txt")),
				"No file at the workspace path");
	}

	@Test
	void theFileSideSharesItsEditorDocument() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		IFile file = write(project, "a.txt", "one");

		// the unified diff overlays the file's own editor and needs a document key
		// for the file; a plain ResourceNode has none and falls back silently
		ISharedDocumentAdapter adapter = new CompareTool.FileNode(file).getAdapter(ISharedDocumentAdapter.class);
		assertNotNull(adapter);
		assertTrue(adapter.getDocumentKey(new CompareTool.FileNode(file)) instanceof IFileEditorInput input
				&& file.equals(input.getFile()));
	}

	@Test
	void theViewToolsRefuseWithoutAWorkbench() throws Exception {
		assertRefused(TestFixture.call("eclipse_show_view", Map.of("view", "org.eclipse.ui.views.ProblemView")),
				"no running workbench");
		assertRefused(TestFixture.call("eclipse_hide_view", Map.of("view", "org.eclipse.ui.views.ProblemView")),
				"no running workbench");
	}

	@Test
	void theViewToolsNameTheirRequiredArgument() throws Exception {
		assertRefused(TestFixture.call("eclipse_show_view", Map.of()), "'view' is required");
		assertRefused(TestFixture.call("eclipse_hide_view", Map.of()), "'view' is required");
	}

	@Test
	void visibilityNeedsItsArgumentAndAWorkbench() throws Exception {
		assertRefused(TestFixture.call("eclipse_set_ide_visibility", Map.of()), "'visible' is required");
		assertRefused(TestFixture.call("eclipse_set_ide_visibility", Map.of("visible", Boolean.FALSE, "mode", "gone")),
				"Unknown mode");
		assertRefused(TestFixture.call("eclipse_set_ide_visibility", Map.of("visible", Boolean.FALSE)),
				"no running workbench");
	}

	@Test
	void applyingCssNeedsExactlyOneOfItsTwoArguments() throws Exception {
		assertRefused(TestFixture.call("eclipse_apply_css", Map.of()), "'css' or 'reset' is required");
		assertRefused(TestFixture.call("eclipse_apply_css", Map.of("css", "Label {color: #ff0000;}", "reset",
				Boolean.TRUE)), "not both");
	}

	@Test
	void applyingCssRefusesWithoutAWorkbench() throws Exception {
		assertRefused(TestFixture.call("eclipse_apply_css", Map.of("css", "Label {color: #ff0000;}")),
				"no running workbench");
		assertRefused(TestFixture.call("eclipse_apply_css", Map.of("reset", Boolean.TRUE)), "no running workbench");
	}

	@Test
	void thePerspectiveToolsRefuseWithoutAWorkbench() throws Exception {
		assertRefused(TestFixture.call("eclipse_list_perspectives", Map.of()), "no running workbench");
		assertRefused(TestFixture.call("eclipse_switch_perspective", Map.of("perspective", "Java")),
				"no running workbench");
		assertRefused(TestFixture.call("eclipse_reset_perspective", Map.of("confirm", Boolean.TRUE)),
				"no running workbench");
	}

	@Test
	void switchingAPerspectiveNamesItsRequiredArgument() throws Exception {
		assertRefused(TestFixture.call("eclipse_switch_perspective", Map.of()), "'perspective' is required");
	}

	@Test
	void resettingAPerspectiveNeedsToBeConfirmed() throws Exception {
		// it discards a layout the user may have spent time on and nothing can undo it
		assertRefused(TestFixture.call("eclipse_reset_perspective", Map.of()), "confirm");
	}

	@Test
	void movingAPartChecksItsArgumentsBeforeTheUiThread() throws Exception {
		assertRefused(TestFixture.call("eclipse_move_part", Map.of()), "'part' is required");
		assertRefused(TestFixture.call("eclipse_move_part", Map.of("part", "org.eclipse.ui.views.ProblemView")),
				"'target' is required");
		assertRefused(
				TestFixture.call("eclipse_move_part", Map.of("part", "org.eclipse.ui.views.ProblemView", //
						"target", "org.eclipse.ui.console.ConsoleView", "position", "sideways")),
				"Unknown position");
	}

	@Test
	void movingAPartRefusesWithoutAWorkbench() throws Exception {
		assertRefused(TestFixture.call("eclipse_move_part", Map.of("part", "org.eclipse.ui.views.ProblemView", //
				"target", "org.eclipse.ui.console.ConsoleView")), "no running workbench");
		// detaching is the one position that needs no target
		assertRefused(TestFixture.call("eclipse_move_part", Map.of("part", "org.eclipse.ui.views.ProblemView", //
				"position", "detached")), "no running workbench");
	}

	@Test
	void launchShortcutIsSafeByDefaultAndValidatesItsArguments() throws Exception {
		IMcpTool tool = TestFixture.tool("eclipse_run_launch_shortcut");
		Map<String, Object> schema = TestFixture.parse(tool.getInputSchema());
		@SuppressWarnings("unchecked")
		Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
		@SuppressWarnings("unchecked")
		Map<String, Object> dryRun = (Map<String, Object>) properties.get("dryRun");
		assertTrue(Boolean.TRUE.equals(dryRun.get("default")), "launch shortcuts must not run by default");
		assertTrue(properties.containsKey("maxResults"), "candidate lists must have a result limit");
		assertRefused(TestFixture.call("eclipse_run_launch_shortcut", Map.of()), "elements");
		fixture.createProject(PROJECT);
		assertRefused(TestFixture.call("eclipse_run_launch_shortcut", Map.of("elements", List.of(PROJECT))),
				"exactly one");
		assertRefused(TestFixture.call("eclipse_run_launch_shortcut",
				Map.of("elements", List.of(PROJECT), "shortcutId", "a", "mode", "profile")), "mode must be");
	}

	private static IFile write(IProject project, String name, String content) throws Exception {
		IFile file = project.getFile(name);
		file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());
		return file;
	}

	private static void assertRefused(McpToolResult result, String expected) {
		assertTrue(result.isError(), "expected an error, got " + result.text());
		assertTrue(result.text().toLowerCase().contains(expected.toLowerCase()),
				"expected a message about '%s', got %s".formatted(expected, result.text()));
	}
}
