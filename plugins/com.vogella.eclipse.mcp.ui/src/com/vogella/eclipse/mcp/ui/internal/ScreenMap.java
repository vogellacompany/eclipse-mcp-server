package com.vogella.eclipse.mcp.ui.internal;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.swt.graphics.Rectangle;

/**
 * Converts between SWT's display points and the pixels of the screen, monitor by monitor. Each monitor is known by both
 * of its rectangles, so this needs no assumption about how SWT lays out its coordinates, which differs between its
 * coordinate system mappers.
 */
public final class ScreenMap {

	/** A monitor in points, as {@code Monitor.getBounds} has it, and in pixels, as Windows has it. */
	public record Screen(Rectangle points, Rectangle pixels) {

		int zoom() {
			return points.width <= 0 ? 100 : Math.round(pixels.width * 100f / points.width);
		}
	}

	private final List<Screen> screens;

	public ScreenMap(List<Screen> screens) {
		this.screens = List.copyOf(screens);
	}

	/** An area in points as pixels, scaled by the monitor that holds its top left corner, else the one it overlaps most. */
	public Rectangle toPixels(Rectangle area) {
		Screen screen = screenOf(area, true);
		return convert(area, screen.points(), screen.pixels());
	}

	/** An area in pixels as points, scaled by the monitor that holds its top left corner, else the one it overlaps most. */
	public Rectangle toPoints(Rectangle area) {
		Screen screen = screenOf(area, false);
		return convert(area, screen.pixels(), screen.points());
	}

	/** The zooms of the monitors, which holds more than one on a mixed setup. */
	public Set<Integer> zooms() {
		Set<Integer> zooms = new TreeSet<>();
		for (Screen screen : screens) {
			zooms.add(Integer.valueOf(screen.zoom()));
		}
		return zooms;
	}

	/** In integers, since a scale such as 2112/1920 is not exact as a double and rounds a whole pixel up. */
	private static Rectangle convert(Rectangle area, Rectangle from, Rectangle to) {
		int left = to.x + (int) Math.floorDiv((long) (area.x - from.x) * to.width, from.width);
		int top = to.y + (int) Math.floorDiv((long) (area.y - from.y) * to.height, from.height);
		int right = to.x + (int) Math.ceilDiv((long) (area.x + area.width - from.x) * to.width, from.width);
		int bottom = to.y + (int) Math.ceilDiv((long) (area.y + area.height - from.y) * to.height, from.height);
		return new Rectangle(left, top, right - left, bottom - top);
	}

	/** The device pixels per point of the monitor an area is on, in percent. */
	public int zoomAt(Rectangle area) {
		return screenOf(area, true).zoom();
	}

	/** The smallest rectangle in pixels around every monitor. */
	public Rectangle allPixels() {
		Rectangle all = screens.get(0).pixels();
		for (Screen screen : screens) {
			all = all.union(screen.pixels());
		}
		return all;
	}

	/** The smallest rectangle in points around every monitor. */
	public Rectangle allPoints() {
		Rectangle all = screens.get(0).points();
		for (Screen screen : screens) {
			all = all.union(screen.points());
		}
		return all;
	}

	private Screen screenOf(Rectangle area, boolean inPoints) {
		Screen best = screens.get(0);
		int mostOverlap = -1;
		for (Screen screen : screens) {
			Rectangle bounds = inPoints ? screen.points() : screen.pixels();
			if (bounds.contains(area.x, area.y)) {
				return screen;
			}
			Rectangle overlap = bounds.intersection(area);
			int size = overlap.isEmpty() ? 0 : overlap.width * overlap.height;
			if (size > mostOverlap) {
				mostOverlap = size;
				best = screen;
			}
		}
		return best;
	}
}
