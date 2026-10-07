package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.Overlays;

/**
 * Highlights drawn onto a capture, without a display.
 * <p>
 * The pixel arithmetic is what a reader verifies a highlight against, so it is
 * checked here on a synthetic image: the scale from points to pixels, the
 * clipping, the outline width and the translucent fill. The label needs a GC
 * and stays for the real IDE.
 */
class OverlaysTest {

	private static final PaletteData RGB24 = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
	private static final RGB GREY = new RGB(100, 100, 100);

	@Test
	void parsesBoundsAndColours() {
		assertEquals(new Rectangle(10, 20, 300, 40), Overlays.parseBounds("10,20 300x40"));
		assertEquals(new Rectangle(1, 2, 3, 4), Overlays.parseBounds(" 1,2   3X4 "));
		assertThrows(IllegalArgumentException.class, () -> Overlays.parseBounds("10,20,300,40"));
		assertEquals(new RGB(255, 0, 0), Overlays.parseColor("#ff0000"));
		assertEquals(new RGB(1, 2, 3), Overlays.parseColor("1, 2, 3"));
		assertEquals(Overlays.DEFAULT_COLOR, Overlays.parseColor(null));
		assertThrows(IllegalArgumentException.class, () -> Overlays.parseColor("red"));
	}

	@Test
	void aClientAreaCanvasMovesHighlightsUpByTheTitleBar() {
		Overlays.Highlight combo = new Overlays.Highlight("0/2", null, new Rectangle(333, 97, 120, 24),
				Overlays.DEFAULT_COLOR, null, false, 3, null, null);
		Overlays.Highlight shell = new Overlays.Highlight("/", null, new Rectangle(0, 0, 760, 560),
				Overlays.DEFAULT_COLOR, null, false, 3, null, null);

		List<Overlays.Highlight> moved = Overlays.translate(List.of(combo, shell), 0, -28,
				new Rectangle(0, 0, 760, 532));

		assertEquals(new Rectangle(333, 69, 120, 24), moved.get(0).points());
		assertEquals(new Rectangle(0, 0, 760, 532), moved.get(1).points());
	}

	@Test
	void paddingWidensTheRectangleInPointsBeforeItIsScaled() throws Exception {
		assertArrayEquals(new int[] { 4, 4, 4, 4 }, Overlays.parsePadding(Integer.valueOf(4)));
		assertArrayEquals(new int[] { 1, 2, 3, 4 }, Overlays.parsePadding("1,2,3,4"));
		assertArrayEquals(new int[] { 0, 0, 0, 0 }, Overlays.parsePadding(null));
		assertThrows(IllegalArgumentException.class, () -> Overlays.parsePadding("1,2"));

		ImageData image = plain(200, 100);
		List<Overlays.Highlight> highlights = Overlays.resolve(null, null,
				List.of(Map.of("bounds", "20,20 40x20", "padding", Integer.valueOf(5))));

		Map<String, Object> drawn = first(Overlays.draw(null, image, highlights, 1.0).toString());

		// the padded rectangle is what is reported, so a caller sees what was drawn
		assertEquals("15,15 50x30", drawn.get("pointsInTarget"), "got " + drawn);
		assertEquals("15,15 50x30", drawn.get("pixels"), "got " + drawn);
	}

	@Test
	void paddingIsAppliedInPointsAndThenScaled() throws Exception {
		ImageData image = plain(200, 100);
		List<Overlays.Highlight> highlights = Overlays.resolve(null, null,
				List.of(Map.of("bounds", "20,20 40x20", "padding", Integer.valueOf(5))));

		Map<String, Object> drawn = first(Overlays.draw(null, image, highlights, 2.0).toString());

		assertEquals("15,15 50x30", drawn.get("pointsInTarget"), "got " + drawn);
		assertEquals("30,30 100x60", drawn.get("pixels"), "got " + drawn);
	}

	@Test
	void lineWidthDecidesHowThickTheFrameIs() throws Exception {
		ImageData image = plain(60, 60);
		List<Overlays.Highlight> highlights = Overlays.resolve(null, null,
				List.of(Map.of("bounds", "10,10 30x30", "color", "#ff0000", "lineWidth", Integer.valueOf(1))));

		Overlays.draw(null, image, highlights, 1.0);

		assertEquals(new RGB(255, 0, 0), at(image, 10, 20), "one pixel of frame");
		assertEquals(GREY, at(image, 11, 20), "and no more than one");
	}

	@Test
	void scalesPointsToPixelsAndOutlinesThreePixelsWide() throws Exception {
		ImageData image = plain(200, 100);
		List<Overlays.Highlight> highlights = Overlays.resolve(null, null,
				List.of(Map.of("bounds", "10,5 40x20", "color", "#ff0000")));

		// scale 1: one point is one pixel
		Map<String, Object> drawn = first(Overlays.draw(null, image, highlights, 1.0).toString());

		assertEquals(Boolean.TRUE, drawn.get("drawn"), "got " + drawn);
		assertEquals("10,5 40x20", drawn.get("pixels"), "got " + drawn);
		assertEquals(Boolean.FALSE, drawn.get("clipped"));
		RGB red = new RGB(255, 0, 0);
		assertEquals(red, at(image, 10, 5), "top left corner is outline");
		assertEquals(red, at(image, 12, 15), "three pixels in is still outline");
		assertEquals(GREY, at(image, 13, 15), "the fourth pixel is the image again");
		assertEquals(red, at(image, 49, 24), "bottom right corner is outline");
		assertEquals(GREY, at(image, 30, 15), "an outline leaves the middle alone");
	}

	@Test
	void fillsTranslucentlyAndClipsToTheImage() throws Exception {
		ImageData image = plain(50, 50);
		List<Overlays.Highlight> highlights = Overlays.resolve(null, null,
				List.of(Map.of("bounds", "40,40 30x30", "style", "fill", "color", "#ffffff")));

		Map<String, Object> drawn = first(Overlays.draw(null, image, highlights, 1.0).toString());

		assertEquals(Boolean.TRUE, drawn.get("clipped"), "got " + drawn);
		assertEquals("40,40 30x30", drawn.get("pixels"), "the requested rectangle is reported unclipped");
		RGB inside = at(image, 45, 45);
		assertTrue(inside.red > GREY.red && inside.red < 255, "a fill blends rather than paints, got " + inside);
		assertEquals(GREY, at(image, 30, 30), "outside the rectangle nothing changed");
	}

	@Test
	void aRectangleOutsideTheImageAndAPathWithoutATargetAreReported() throws Exception {
		ImageData image = plain(20, 20);
		List<Overlays.Highlight> highlights = Overlays.resolve(null, null,
				List.of(Map.of("bounds", "100,100 5x5"), Map.of("path", "0/1"), Map.of("label", "nothing")));

		List<Map<String, Object>> drawn = all(Overlays.draw(null, image, highlights, 1.0).toString());

		assertEquals(3, drawn.size());
		for (Map<String, Object> entry : drawn) {
			assertEquals(Boolean.FALSE, entry.get("drawn"), "got " + entry);
			assertNotNull(entry.get("error"), "got " + entry);
		}
		assertNotEquals(drawn.get(0).get("error"), drawn.get(1).get("error"));
	}

	private static ImageData plain(int width, int height) {
		ImageData image = new ImageData(width, height, 24, RGB24);
		int pixel = RGB24.getPixel(GREY);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				image.setPixel(x, y, pixel);
			}
		}
		return image;
	}

	private static RGB at(ImageData image, int x, int y) {
		return image.palette.getRGB(image.getPixel(x, y));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> all(String json) throws Exception {
		return (List<Map<String, Object>>) TestFixture.parse("{\"a\":" + json + "}").get("a");
	}

	private static Map<String, Object> first(String json) throws Exception {
		return all(json).get(0);
	}
}
