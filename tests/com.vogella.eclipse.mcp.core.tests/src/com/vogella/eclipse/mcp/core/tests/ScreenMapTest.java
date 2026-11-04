package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.eclipse.swt.graphics.Rectangle;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.ScreenMap;
import com.vogella.eclipse.mcp.ui.internal.ScreenMap.Screen;

/** Points and pixels on several monitors, as SWT's two coordinate system mappers lay them out. */
class ScreenMapTest {

	/** The monitor-specific mapper: a monitor's origin is in pixels and its size in points. */
	private static final ScreenMap TWO_AT_125 = new ScreenMap(List.of(
			new Screen(new Rectangle(0, 0, 2752, 1152), new Rectangle(0, 0, 3440, 1440)),
			new Screen(new Rectangle(3440, 0, 1536, 960), new Rectangle(3440, 0, 1920, 1200))));

	@Test
	void aSecondMonitorScalesFromItsOwnOrigin() {
		assertEquals(new Rectangle(3475, 232, 368, 425), TWO_AT_125.toPixels(new Rectangle(3468, 186, 294, 339)));
	}

	@Test
	void theFirstMonitorIsAPlainScale() {
		assertEquals(new Rectangle(125, 125, 1125, 625), TWO_AT_125.toPixels(new Rectangle(100, 100, 900, 500)));
	}

	@Test
	void anAreaThatOnlyPartlyFitsStaysOnTheMonitorOfItsCorner() {
		assertEquals(new Rectangle(4000, 0, 1500, 875), TWO_AT_125.toPixels(new Rectangle(3888, 0, 1200, 700)));
	}

	@Test
	void pixelsOfAPopupOnTheSecondMonitorComeBackAsItsPoints() {
		assertEquals(new Rectangle(3468, 185, 295, 341), TWO_AT_125.toPoints(new Rectangle(3475, 232, 368, 425)));
	}

	@Test
	void theWholeScreenIsTheUnionOfTheMonitors() {
		assertEquals(new Rectangle(0, 0, 5360, 1440), TWO_AT_125.allPixels());
		assertEquals(new Rectangle(0, 0, 4976, 1152), TWO_AT_125.allPoints());
	}

	@Test
	void monitorsWithDifferentZoomsEachUseTheirOwn() {
		ScreenMap mixed = new ScreenMap(List.of(new Screen(new Rectangle(0, 0, 1920, 1080), new Rectangle(0, 0, 1920, 1080)),
				new Screen(new Rectangle(1920, 0, 1920, 1080), new Rectangle(1920, 0, 3840, 2160))));
		assertEquals(new Rectangle(100, 50, 10, 10), mixed.toPixels(new Rectangle(100, 50, 10, 10)));
		assertEquals(new Rectangle(2120, 100, 20, 20), mixed.toPixels(new Rectangle(2020, 50, 10, 10)));
		assertEquals(100, mixed.zoomAt(new Rectangle(100, 50, 10, 10)));
		assertEquals(200, mixed.zoomAt(new Rectangle(2020, 50, 10, 10)));
	}

	@Test
	void aZoomOf110PercentMapsWholePointsToWholePixels() {
		ScreenMap at110 = new ScreenMap(List.of(new Screen(new Rectangle(0, 0, 1920, 1080), new Rectangle(0, 0, 2112, 1188))));
		assertEquals(new Rectangle(0, 0, 110, 110), at110.toPixels(new Rectangle(0, 0, 100, 100)));
		assertEquals(new Rectangle(0, 0, 100, 100), at110.toPoints(new Rectangle(0, 0, 110, 110)));
		assertEquals(110, at110.zoomAt(new Rectangle(0, 0, 100, 100)));
	}

	@Test
	void anAreaWhoseCornerIsOnNoMonitorTakesTheMonitorItOverlapsMost() {
		// 3000 lies in the gap between the first monitor's points and the second one's origin
		assertEquals(new Rectangle(2890, 125, 750, 125), TWO_AT_125.toPixels(new Rectangle(3000, 100, 600, 100)));
	}

	@Test
	void anInexactNegativeOffsetRoundsTowardsTheLeft() {
		// (3001 - 3440) * 1.25 is -548.75, which floors to -549 where truncation would give -548
		assertEquals(new Rectangle(2891, 125, 751, 125), TWO_AT_125.toPixels(new Rectangle(3001, 100, 600, 100)));
	}

	@Test
	void anAreaAcrossTwoMonitorsScalesByTheMonitorOfItsCorner() {
		assertEquals(new Rectangle(3375, 125, 1000, 125), TWO_AT_125.toPixels(new Rectangle(2700, 100, 800, 100)));
	}

	@Test
	void aMonitorLeftOfThePrimaryHasNegativeCoordinates() {
		ScreenMap left = new ScreenMap(List.of(new Screen(new Rectangle(-1920, 0, 1920, 1080), new Rectangle(-3840, 0, 3840, 2160)),
				new Screen(new Rectangle(0, 0, 1920, 1080), new Rectangle(0, 0, 1920, 1080))));
		assertEquals(new Rectangle(-200, 100, 20, 20), left.toPixels(new Rectangle(-100, 50, 10, 10)));
		assertEquals(new Rectangle(-100, 50, 10, 10), left.toPoints(new Rectangle(-200, 100, 20, 20)));
		assertEquals(200, left.zoomAt(new Rectangle(-100, 50, 10, 10)));
		assertEquals(Set.of(Integer.valueOf(100), Integer.valueOf(200)), left.zooms());
	}
}
