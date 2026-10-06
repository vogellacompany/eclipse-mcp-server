package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.swt.graphics.ImageData;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.ScreenPixels;

/** The conversion of a pixbuf's rows into ImageData; the read itself needs a screen. */
class ScreenPixelsTest {

	@Test
	void rowsWithPaddingAndAnAlphaChannelKeepTheirColours() {
		// two pixels per row, four channels, a row stride of 12 bytes
		byte[] pixbuf = { 1, 2, 3, 99, 4, 5, 6, 99, 0, 0, 0, 0, //
				7, 8, 9, 99, 10, 11, 12, 99, 0, 0, 0, 0 };
		ImageData data = ScreenPixels.toImageData(pixbuf, 2, 2, 12, 4);
		assertEquals(0x010203, data.palette.getRGB(data.getPixel(0, 0)).red << 16
				| data.palette.getRGB(data.getPixel(0, 0)).green << 8 | data.palette.getRGB(data.getPixel(0, 0)).blue);
		assertEquals(10, data.palette.getRGB(data.getPixel(1, 1)).red);
		assertEquals(12, data.palette.getRGB(data.getPixel(1, 1)).blue);
		assertEquals(5, data.palette.getRGB(data.getPixel(1, 0)).green);
	}

	@Test
	void anOddWidthKeepsItsRowsUnpadded() {
		// three pixels per row need 9 bytes, which a pixbuf pads to a stride of 12
		byte[] pixbuf = new byte[24];
		for (int row = 0; row < 2; row++) {
			for (int column = 0; column < 3; column++) {
				pixbuf[row * 12 + column * 3] = (byte) (row * 3 + column + 1);
			}
		}
		ImageData data = ScreenPixels.toImageData(pixbuf, 3, 2, 12, 3);
		assertEquals(9, data.bytesPerLine);
		assertEquals(4, data.palette.getRGB(data.getPixel(0, 1)).red);
		assertEquals(6, data.palette.getRGB(data.getPixel(2, 1)).red);
		assertEquals(4, data.data[9]);
	}
}
