package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
	void aCaptureNarrowerThanTheRequestIsNotScaled() {
		assertEquals(1200, ScreenshotTools.Capture.crispWidth(800, 1200));
	}
}
