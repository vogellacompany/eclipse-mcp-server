package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.ScreenshotTools;

/**
 * settlePixels compares two captures before either is encoded, so the comparison has to see every pixel.
 */
class PixelSettleTest {

	private static ImageData image(int width, int height) {
		return new ImageData(width, height, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
	}

	@Test
	void identicalCapturesConverge() {
		ImageData first = image(40, 30);
		ImageData second = image(40, 30);
		first.setPixel(39, 29, 0x123456);
		second.setPixel(39, 29, 0x123456);

		assertTrue(ScreenshotTools.Capture.samePixels(first, second));
	}

	@Test
	void oneDifferingPixelDoesNot() {
		ImageData first = image(40, 30);
		ImageData second = image(40, 30);
		second.setPixel(39, 29, 0x010101);

		assertFalse(ScreenshotTools.Capture.samePixels(first, second));
	}

	@Test
	void aDifferentSizeDoesNot() {
		assertFalse(ScreenshotTools.Capture.samePixels(image(40, 30), image(30, 40)));
	}

	@Test
	void aDifferentAlphaDoesNot() {
		ImageData first = image(4, 4);
		ImageData second = image(4, 4);
		second.setAlpha(0, 0, 10);

		assertFalse(ScreenshotTools.Capture.samePixels(first, second));
	}
}
