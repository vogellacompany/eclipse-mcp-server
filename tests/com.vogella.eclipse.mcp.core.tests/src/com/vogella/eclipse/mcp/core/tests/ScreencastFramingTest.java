package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.Screencast;
import com.vogella.eclipse.mcp.ui.internal.ScreencastTools;

/**
 * The arithmetic behind a cropped screencast, which needs no display.
 */
class ScreencastFramingTest {

	@Test
	void parsesBoundsInTheFormTheOtherToolsReport() {
		assertEquals(new Rectangle(10, 20, 300, 200), ScreencastTools.parseBounds("10,20 300x200"));
		assertEquals(new Rectangle(0, 0, 1, 1), ScreencastTools.parseBounds(" 0, 0  1 x 1 "));
	}

	@Test
	void refusesBoundsWithoutASize() {
		assertNull(ScreencastTools.parseBounds("10,20 0x200"));
		assertNull(ScreencastTools.parseBounds("10,20"));
		assertNull(ScreencastTools.parseBounds("ten,20 300x200"));
		assertNull(ScreencastTools.parseBounds(null));
	}

	@Test
	void aShellRegionIsMovedIntoTheClientArea() {
		assertEquals(new Rectangle(333, 69, 120, 24),
				ScreencastTools.inClientArea(new Rectangle(333, 97, 120, 24), new Point(0, 28)));
	}

	@Test
	void clipsTheCropToTheCanvas() {
		assertEquals(new Rectangle(100, 50, 200, 150), Screencast.clampCrop(new Rectangle(100, 50, 200, 150), 800, 600));
		assertEquals(new Rectangle(700, 500, 100, 100), Screencast.clampCrop(new Rectangle(700, 500, 400, 400), 800, 600));
		assertEquals(new Rectangle(0, 0, 50, 60), Screencast.clampCrop(new Rectangle(-50, -40, 100, 100), 800, 600));
	}

	@Test
	void aCropOutsideTheCanvasIsNoCrop() {
		assertNull(Screencast.clampCrop(new Rectangle(900, 0, 100, 100), 800, 600));
		assertNull(Screencast.clampCrop(null, 800, 600));
	}
}
