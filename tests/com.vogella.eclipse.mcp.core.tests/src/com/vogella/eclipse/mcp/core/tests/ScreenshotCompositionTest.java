package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.ScreenshotTools;

/**
 * The geometry of composing a shell capture from its children.
 * <p>
 * This run is headless, so nothing here can produce a real capture; what is
 * testable is which children qualify for the composition and where they land.
 * That the painting itself works needs a real IDE and stays unproven here.
 */
class ScreenshotCompositionTest {

	@Test
	void anInvisibleChildIsLeftOut() {
		assertNull(ScreenshotTools.Capture.placementOf(0, 700, 1332, 57, false));
	}

	@Test
	void aZeroSizedChildIsLeftOut() {
		assertNull(ScreenshotTools.Capture.placementOf(0, 700, 0, 57, true));
		assertNull(ScreenshotTools.Capture.placementOf(0, 700, 1332, 0, true));
	}

	@Test
	void aWidthSnapsToAWholeDivisorOnlyWhenThatIsCloseToTheRequest() {
		assertEquals(1790, ScreenshotTools.Capture.crispWidth(5370, 2000));
		assertEquals(1000, ScreenshotTools.Capture.crispWidth(3000, 1000));
		assertEquals(900, ScreenshotTools.Capture.crispWidth(1000, 900));
	}

	@Test
	void aUniformPrintBehindAnotherWindowSaysTheScreenWasNotRead() {
		String failure = ScreenshotTools.Capture.uniformPrintFailure(true, false, true);
		assertTrue(failure.contains("the screen was not read"), failure);
		assertTrue(failure.contains("foreground false"), failure);
		assertTrue(failure.contains("bring the IDE to the front"), failure);
		assertFalse(failure.contains("cannot be captured at all"), failure);
	}

	@Test
	void onlyAUniformScreenAndPrintSayTheDisplayCannotBeCaptured() {
		assertTrue(ScreenshotTools.Capture.uniformPrintFailure(false, false, false)
				.contains("cannot be captured at all"));
		assertTrue(ScreenshotTools.Capture.uniformPrintFailure(true, true, false).contains("one UI turn"));
		assertTrue(ScreenshotTools.Capture.uniformPrintFailure(true, false, false).contains("method print"));
	}

	@Test
	void aCaptureNarrowerThanTheRequestIsNotScaled() {
		assertEquals(1200, ScreenshotTools.Capture.crispWidth(800, 1200));
	}
}
