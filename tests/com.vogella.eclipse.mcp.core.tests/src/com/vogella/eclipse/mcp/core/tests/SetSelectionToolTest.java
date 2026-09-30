package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * How {@code eclipse_set_selection} handles element specs it cannot resolve.
 * <p>
 * This run is headless, so the tool refuses before it reaches a viewer. What can
 * be held here is the shape of that refusal, and in particular that a multi
 * segment spec does not escape as an unhandled exception on the way to it.
 */
class SetSelectionToolTest {

	@Test
	void aRowPathThatNamesNothingIsNotAnUnhandledException() throws Exception {
		// it used to fall through to IWorkspaceRoot.getProject, which throws
		// IllegalArgumentException on a name with more than one segment, so a row
		// path that missed came back as "The request failed:
		// java.lang.IllegalArgumentException: Path for project must have only one
		// segment" and read as the row form not being implemented at all
		for (String spec : List.of("0/0/1/r4", "0/0/0/r7", "0/r2")) {
			McpToolResult result = TestFixture.call("eclipse_set_selection",
					Map.of("elements", List.of(spec)));
			assertTrue(result.isError(), "got " + result.text());
			assertFalse(result.text().contains("IllegalArgumentException"),
					"'%s' must not escape as an exception, got %s".formatted(spec, result.text()));
			assertFalse(result.text().contains("must have only one segment"), "got " + result.text());
		}
	}

	@Test
	void aRelativePathIsRefusedRatherThanThrown() throws Exception {
		// neither a workspace path, which starts with a slash, nor a project name,
		// which cannot contain one
		McpToolResult result = TestFixture.call("eclipse_set_selection",
				Map.of("elements", List.of("some/relative/path")));

		assertTrue(result.isError(), "got " + result.text());
		assertFalse(result.text().contains("IllegalArgumentException"), "got " + result.text());
	}

	@Test
	void theDescriptionSaysWhereARowPathComesFrom() {
		// the flag is includeRows and not includeItems, and getting that wrong makes
		// a tree look empty, which is what sent a caller guessing paths instead
		String description = TestFixture.tool("eclipse_set_selection").getInputSchema();
		assertTrue(description.contains("includeRows"), "got " + description);
	}
}
