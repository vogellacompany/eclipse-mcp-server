package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.ScreenshotTools;

/** What a uniform capture answers about the reads that were and were not tried. */
class ScreenshotFailureTest {

	@Test
	void aUniformPrintBehindAnotherWindowSaysTheScreenWasNotRead() {
		String failure = ScreenshotTools.Capture.uniformPrintFailure(false, false, true, null);
		assertTrue(failure.contains("the screen was not read"), failure);
		assertTrue(failure.contains("foreground false"), failure);
		assertTrue(failure.contains("bring the IDE to the front"), failure);
		assertFalse(failure.contains("cannot be captured at all"), failure);
	}

	@Test
	void anAskedForPrintIsNamedBesideOcclusion() {
		String failure = ScreenshotTools.Capture.uniformPrintFailure(true, false, true, null);
		assertTrue(failure.contains("method print was asked for and this IDE is not the foreground application"),
				failure);
		assertTrue(failure.contains("drop method print and bring the IDE to the front"), failure);
	}

	@Test
	void anAskedForPrintIsNamedInsideOneUiTurn() {
		String failure = ScreenshotTools.Capture.uniformPrintFailure(true, true, false, null);
		assertTrue(failure.contains("method print was asked for and inside one UI turn"), failure);
		assertTrue(failure.contains("drop method print and capture outside an atomic eclipse_run_script batch"),
				failure);
	}

	@Test
	void onlyAUniformScreenAndPrintSayTheDisplayCannotBeCaptured() {
		assertTrue(ScreenshotTools.Capture.uniformPrintFailure(false, false, false, null)
				.contains("cannot be captured at all"));
	}

	@Test
	void aFailedWindowCaptureIsNamed() {
		String failure = ScreenshotTools.Capture.uniformPrintFailure(false, false, false,
				"The window capture came back uniform.");
		assertTrue(failure.contains("The window capture was not used: The window capture came back uniform."),
				failure);
	}
}
