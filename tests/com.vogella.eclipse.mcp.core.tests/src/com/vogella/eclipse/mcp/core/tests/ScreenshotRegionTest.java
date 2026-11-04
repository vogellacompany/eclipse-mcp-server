package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.swt.graphics.Rectangle;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.NativeScreen;
import com.vogella.eclipse.mcp.ui.internal.ScreenshotTools;
import com.vogella.eclipse.mcp.ui.internal.Shells;

/** The parts of a region, widget and popup capture that hold no pixels. */
class ScreenshotRegionTest {

	private static final String TOOL = "eclipse_screenshot";

	@Test
	void readsARegionAsShellBoundsArePrinted() {
		assertEquals(new Rectangle(-10, 20, 300, 40), Shells.boundsOf("-10,20 300x40"));
		assertNull(Shells.boundsOf("10,20"));
		assertNull(Shells.boundsOf(null));
	}

	@Test
	void theSamplesAreADistinctGridInsideTheArea() {
		Rectangle area = new Rectangle(100, 200, 90, 45);
		List<int[]> samples = NativeScreen.gridPoints(area);
		assertEquals(81, samples.size());
		samples.forEach(sample -> assertTrue(area.contains(sample[0], sample[1]), sample[0] + "," + sample[1]));
		assertArrayEquals(new int[] { 105, 202 }, samples.get(0));
		assertArrayEquals(new int[] { 185, 242 }, samples.get(80));
		Set<String> distinct = samples.stream().map(sample -> sample[0] + "," + sample[1]).collect(Collectors.toSet());
		assertEquals(81, distinct.size());
	}

	@Test
	void aTinyAreaHasOneSamplePerPixel() {
		assertEquals(2, NativeScreen.gridPoints(new Rectangle(0, 0, 1, 2)).size());
	}

	@Test
	void aPopupUpToTheReachAwayBelongsToTheTarget() {
		Rectangle area = new Rectangle(100, 100, 50, 50);
		int reach = NativeScreen.POPUP_REACH;
		assertTrue(NativeScreen.touches(area, new Rectangle(150 + reach, 100, 20, 20)));
		assertFalse(NativeScreen.touches(area, new Rectangle(151 + reach, 100, 20, 20)));
		assertTrue(NativeScreen.touches(area, new Rectangle(80 - reach, 100, 20, 20)));
		assertFalse(NativeScreen.touches(area, new Rectangle(150 + reach, 151 + reach, 20, 20)));
		assertTrue(NativeScreen.touches(area, new Rectangle(110, 110, 5, 5)));
	}

	@Test
	void aRegionIsCutAtTheScreen() {
		Rectangle screen = new Rectangle(0, 0, 1920, 1080);
		assertEquals(new Rectangle(1900, 1000, 20, 80), NativeScreen.clip(screen, new Rectangle(1900, 1000, 100, 100)));
		assertEquals(new Rectangle(0, 0, 10, 10), NativeScreen.clip(screen, new Rectangle(-5, -5, 15, 15)));
		assertNull(NativeScreen.clip(screen, new Rectangle(2000, 0, 10, 10)));
	}

	@Test
	void onlyALossBelowTheDeviceZoomIsSofter() {
		assertTrue(ScreenshotTools.Capture.belowDeviceZoom(100, 200, false));
		// a display over a 200% and a 100% monitor comes back at about 130% with every pixel read
		assertFalse(ScreenshotTools.Capture.belowDeviceZoom(130, 200, true));
		assertFalse(ScreenshotTools.Capture.belowDeviceZoom(200, 200, false));
	}

	@Test
	void aRegionWithoutASizeIsRefused() throws Exception {
		TestFixture.assertRefused(TestFixture.call(TOOL, Map.of("region", "10,20")), "needs 'region' as 'x,y wxh'");
	}

	@Test
	void popupsCannotBePainted() throws Exception {
		TestFixture.assertRefused(TestFixture.call(TOOL, Map.of("includePopups", Boolean.TRUE, "method", "print")),
				"cannot be combined with method print");
	}

	@Test
	void theDescriptionNamesTheNotesAndTheSampleFields() {
		String description = TestFixture.tool(TOOL).getDescription();
		for (String field : List.of("includePopupsNote", "popupsNote", "uniformNote", "mixedZoomNote", "samplesTested",
				"title, className and how many samples")) {
			assertTrue(description.contains(field), "the description does not name " + field);
		}
	}
}
