package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.ScreenshotTools;

/** What a uniform capture answers about the reads that were and were not tried. */
class ScreenshotFailureTest {

	@Test
	void aUniformPrintBehindAnotherWindowSaysTheScreenWasNotRead() {
		String failure = ScreenshotTools.Capture.uniformPrintFailure(false, false, true, null, null);
		assertTrue(failure.contains("the screen was not read"), failure);
		assertTrue(failure.contains("this IDE is not the foreground application"), failure);
		assertTrue(failure.contains("bring the IDE to the front"), failure);
		assertFalse(failure.contains("cannot be captured at all"), failure);
	}

	@Test
	void anAskedForPrintIsNamedBesideOcclusion() {
		String failure = ScreenshotTools.Capture.uniformPrintFailure(true, false, true, null, null);
		assertTrue(failure.contains("method print was asked for and this IDE is not the foreground application"),
				failure);
		assertTrue(failure.contains("drop method print and bring the IDE to the front"), failure);
	}

	@Test
	void anAskedForPrintIsNamedInsideOneUiTurn() {
		String failure = ScreenshotTools.Capture.uniformPrintFailure(true, true, false, null, null);
		assertTrue(failure.contains("method print was asked for and inside one UI turn"), failure);
		assertTrue(failure.contains("drop method print and capture outside a batch run in one UI turn"),
				failure);
	}

	@Test
	void onlyAUniformScreenAndPrintSayTheDisplayCannotBeCaptured() {
		assertTrue(ScreenshotTools.Capture.uniformPrintFailure(false, false, false, null, null)
				.contains("cannot be captured at all"));
	}

	@Test
	void aFailedWindowCaptureIsNamedBesideTheSkipReasons() {
		String failure = ScreenshotTools.Capture.uniformPrintFailure(false, false, true,
				"The window capture came back uniform.", null);
		assertTrue(failure.contains("bring the IDE to the front."), failure);
		assertTrue(failure.endsWith(" The window capture was not used: The window capture came back uniform."),
				failure);
	}

	@Test
	void aFailedWindowCaptureIsNamed() {
		String failure = ScreenshotTools.Capture.uniformPrintFailure(false, false, false,
				"The window capture came back uniform.", null);
		assertTrue(failure.contains("The window capture was not used: The window capture came back uniform."),
				failure);
	}

	@Test
	void oneUiTurnAloneIsTheOnlyReasonAndRemedy() {
		String failure = ScreenshotTools.Capture.uniformPrintFailure(false, true, false, null, null);
		assertTrue(failure.contains("not read because inside one UI turn the screen has not been painted yet."), failure);
		assertTrue(failure.contains("To have the screen read, capture outside a batch run in one UI turn."),
				failure);
	}

	@Test
	void aBatchOnABackgroundIdeNamesBothReasons() {
		String failure = ScreenshotTools.Capture.uniformPrintFailure(false, true, true, null, null);
		assertTrue(failure.contains(
				"inside one UI turn the screen has not been painted yet and this IDE is not the foreground application"),
				failure);
		assertTrue(failure.contains("capture outside a batch run in one UI turn and bring the IDE to the front"),
				failure);
	}

	@Test
	void aFailedScreenReadIsNamedWhenNothingCanBeCaptured() {
		String failure = ScreenshotTools.Capture.uniformPrintFailure(false, false, false, null, "no pixbuf");
		assertTrue(failure.contains("cannot be captured at all"), failure);
		assertTrue(failure.endsWith(" The direct screen read failed, so copyArea read it: no pixbuf"), failure);
	}

	@Test
	void aPopupGrownCaptureNamesIncludePopupsRatherThanTheTarget() {
		String failure = ScreenshotTools.Capture.uniformScreenFailure(true, null, null);
		assertTrue(failure.contains("Capture without includePopups"), failure);
		assertFalse(failure.contains("capture a part, a shell or a widget instead"), failure);
	}

	@Test
	void anAreaOfTheScreenNamesTheFailedWindowCapture() {
		String failure = ScreenshotTools.Capture.uniformScreenFailure(false,
				"An area of the screen has no window of its own.", null);
		assertTrue(failure.contains("capture a part, a shell or a widget instead"), failure);
		assertTrue(failure.endsWith(" The window capture was not used: An area of the screen has no window of its own."),
				failure);
	}
}
