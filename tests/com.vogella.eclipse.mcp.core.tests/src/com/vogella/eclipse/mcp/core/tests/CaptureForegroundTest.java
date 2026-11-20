package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;

/**
 * What the capture tools say about being in front of the screen.
 * <p>
 * This run is headless, so neither the capture nor the focus request can be
 * exercised. What is held here is the wording, which is what a model reads
 * before deciding whether to trust an image: a screen read photographs whatever
 * is in front of the target, and no other field in that answer can reveal it,
 * because a window sitting still is settled and the right size. The
 * bug this came from was fourteen captures of somebody's terminal, every one of
 * them reported as a success.
 */
class CaptureForegroundTest {

	@Test
	void theScreenshotDescriptionSaysRootCaptureReadsWhateverIsInFront() {
		IMcpTool screenshot = TestFixture.tool("eclipse_screenshot");
		String description = screenshot.getDescription();

		assertTrue(description.contains("ROOT CAPTURE READS WHATEVER IS IN FRONT"), "got " + description);
		// the field a caller is meant to assert on, named so that reading the
		// description is enough to know what to check
		assertTrue(description.contains("foreground"), "got " + description);
		// the reason the other signals do not help, which is the part that makes
		// this a bug rather than a limitation
		assertTrue(description.contains("settled and the right size"), "got " + description);
	}

	@Test
	void theVisibilityToolAsksForFocusRatherThanPromisingIt() {
		IMcpTool visibility = TestFixture.tool("eclipse_set_ide_visibility");
		String schema = visibility.getInputSchema();

		// it shipped saying "brings it back and gives it focus", which the window
		// system is entitled to refuse and routinely does on Windows
		assertFalse(schema.contains("gives it focus"), "got " + schema);
		assertTrue(schema.contains("ASKS for focus"), "got " + schema);
		assertTrue(schema.contains("may refuse"), "got " + schema);
	}

	@Test
	void theVisibilityToolSaysHowItAsked() {
		IMcpTool visibility = TestFixture.tool("eclipse_set_ide_visibility");
		String schema = visibility.getInputSchema();

		// asking is not one thing: a plain SetForegroundWindow is refused on
		// Windows to a process that is not already in front, so the answer has to
		// say which route was taken before 'foreground' false means anything
		assertTrue(schema.contains("foregroundMethod"), "got " + schema);
		assertTrue(schema.contains("input queue"), "got " + schema);
	}
}
