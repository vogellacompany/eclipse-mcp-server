package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Rectangle;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.WindowCapture;

/**
 * A part is cut out of its shell's window capture at the window's own pixel scale.
 */
class WindowCaptureCropTest {

	private static final Rectangle WINDOW = new Rectangle(10, 10, 100, 50);

	@Test
	void cropsAtTheScaleOfTheCapture() {
		ImageData window = new ImageData(200, 100, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
		window.setPixel(20, 20, 0xFF0000);

		ImageData part = WindowCapture.crop(window, WINDOW, new Rectangle(20, 20, 50, 25));

		assertEquals(100, part.width);
		assertEquals(50, part.height);
		assertEquals(0xFF0000, part.getPixel(0, 0));
	}

	@Test
	void theWholeWindowIsReturnedAsIs() {
		ImageData window = new ImageData(200, 100, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));

		assertSame(window, WindowCapture.crop(window, WINDOW, WINDOW));
	}

	@Test
	void anAreaOutsideTheWindowHasNoPixels() {
		ImageData window = new ImageData(200, 100, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));

		assertNull(WindowCapture.crop(window, WINDOW, new Rectangle(500, 500, 10, 10)));
	}
}
