package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;

import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * What a screen read cannot tell by itself on Windows: the native popups of this IDE that have no shell, such as a
 * DateTime calendar, and the windows of other programs on top of an area. Elsewhere this finds nothing.
 */
public final class NativeScreen {

	private static final String PACKAGE = "org.eclipse.swt.internal.win32."; //$NON-NLS-1$

	private static final int GA_ROOT = 2;

	private static final int GW_CHILD = 5;

	private static final int GW_HWNDNEXT = 2;

	/** Points per side of the grid tested for other windows. */
	private static final int GRID = 9;

	/** Not popups of a widget: hover help, native dialogs, which have their own tools, IME windows and drop shadows. */
	private static final Set<String> NOT_POPUPS = Set.of("tooltips_class32", "#32770", "IME", "MSCTFIME UI", "SysShadow"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

	private NativeScreen() {
	}

	/** How far from a target a native popup may open and still be taken as its own, in points. */
	public static final int POPUP_REACH = 8;

	/** A window without a shell on this UI thread, its bounds in points. */
	record Popup(String className, Rectangle bounds) {
	}

	/** A capture area grown over the popups that touch it, and every popup found with whether it was taken. */
	record Growth(Rectangle area, JsonArray popups) {
	}

	/** The area grown over the visible native popups that open at it, on the UI thread. */
	static Growth growOverPopups(Display display, Rectangle area) {
		JsonArray found = new JsonArray();
		Rectangle grown = area;
		for (Popup popup : popups(display)) {
			boolean included = touches(area, popup.bounds());
			found.add(new JsonObject().put("className", popup.className()) //$NON-NLS-1$
					.put("bounds", Overlays.describe(popup.bounds())).put("included", Boolean.valueOf(included))); //$NON-NLS-1$ //$NON-NLS-2$
			if (included) {
				grown = grown.union(popup.bounds());
			}
		}
		return new Growth(grown, found);
	}

	/** Whether a popup lies within {@link #POPUP_REACH} of the area: a popup opens beside its anchor, so one further away belongs to another widget. */
	public static boolean touches(Rectangle area, Rectangle popup) {
		int dx = Math.max(0, Math.max(popup.x - (area.x + area.width), area.x - (popup.x + popup.width)));
		int dy = Math.max(0, Math.max(popup.y - (area.y + area.height), area.y - (popup.y + popup.height)));
		return dx <= POPUP_REACH && dy <= POPUP_REACH;
	}

	/** The part of a region on the screen, or null when none of it is. */
	public static Rectangle clip(Rectangle screen, Rectangle region) {
		Rectangle inside = screen.intersection(region);
		return inside.isEmpty() ? null : inside;
	}

	/** The zooms of the monitors, empty where they cannot be read. */
	static Set<Integer> zooms(Display display) {
		if (!FileLocations.isWindows()) {
			return Set.of();
		}
		try {
			return map(display).zooms();
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return Set.of();
		}
	}

	/** The visible native popups of this UI thread, on the UI thread. */
	static List<Popup> popups(Display display) {
		List<Popup> popups = new ArrayList<>();
		if (!FileLocations.isWindows()) {
			return popups;
		}
		try {
			Class<?> os = os();
			ScreenMap map = map(display);
			long thread = ((Integer) os.getMethod("GetCurrentThreadId").invoke(null)).longValue(); //$NON-NLS-1$
			var getWindow = os.getMethod("GetWindow", long.class, int.class); //$NON-NLS-1$
			Method visible = os.getMethod("IsWindowVisible", long.class); //$NON-NLS-1$
			Method threadOf = os.getMethod("GetWindowThreadProcessId", long.class, int[].class); //$NON-NLS-1$
			long desktop = (Long) os.getMethod("GetDesktopWindow").invoke(null); //$NON-NLS-1$
			long window = (Long) getWindow.invoke(null, Long.valueOf(desktop), Integer.valueOf(GW_CHILD));
			while (window != 0) {
				Long handle = Long.valueOf(window);
				if (Boolean.TRUE.equals(visible.invoke(null, handle)) && display.findWidget(window) == null
						&& ((Integer) threadOf.invoke(null, handle, null)).longValue() == thread) {
					String name = className(os, window);
					int[] rect = rectangle(os, window);
					if (!NOT_POPUPS.contains(name) && rect[2] > rect[0] && rect[3] > rect[1]) {
						popups.add(new Popup(name, map.toPoints(new Rectangle(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1]))));
					}
				}
				window = (Long) getWindow.invoke(null, handle, Integer.valueOf(GW_HWNDNEXT));
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return popups;
		}
		return popups;
	}

	/**
	 * The windows of other programs that are on top of the area at a grid of sample points, with how many of the
	 * samples each one holds, or null where the question cannot be asked.
	 */
	static JsonObject otherWindows(Display display, Rectangle area) {
		if (!FileLocations.isWindows()) {
			return null;
		}
		try {
			Class<?> os = os();
			Class<?> pointClass = Class.forName(PACKAGE + "POINT", true, os.getClassLoader()); //$NON-NLS-1$
			int own = (Integer) os.getMethod("GetCurrentProcessId").invoke(null); //$NON-NLS-1$
			var newPoint = pointClass.getConstructor();
			Field x = pointClass.getField("x"); //$NON-NLS-1$
			Field y = pointClass.getField("y"); //$NON-NLS-1$
			Method fromPoint = os.getMethod("WindowFromPoint", pointClass); //$NON-NLS-1$
			Method ancestor = os.getMethod("GetAncestor", long.class, int.class); //$NON-NLS-1$
			Method processOf = os.getMethod("GetWindowThreadProcessId", long.class, int[].class); //$NON-NLS-1$
			Map<Long, Integer> found = new LinkedHashMap<>();
			List<int[]> points = gridPoints(pixelsOf(display, area));
			for (int[] point : points) {
				Object at = newPoint.newInstance();
				x.setInt(at, point[0]);
				y.setInt(at, point[1]);
				long window = (Long) fromPoint.invoke(null, at);
				long root = window == 0 ? 0 : (Long) ancestor.invoke(null, Long.valueOf(window), Integer.valueOf(GA_ROOT));
				if (root != 0) {
					int[] process = new int[1];
					processOf.invoke(null, Long.valueOf(root), process);
					if (process[0] != own) {
						found.merge(Long.valueOf(root), Integer.valueOf(1), Integer::sum);
					}
				}
			}
			JsonArray windows = new JsonArray();
			for (Map.Entry<Long, Integer> entry : found.entrySet()) {
				long window = entry.getKey().longValue();
				windows.add(new JsonObject().put("title", text(os, window)).put("className", className(os, window)) //$NON-NLS-1$ //$NON-NLS-2$
						.put("samples", entry.getValue())); //$NON-NLS-1$
			}
			return new JsonObject().put("samplesTested", Integer.valueOf(points.size())).put("windows", windows); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** An evenly spaced grid inside the area, in the area's own coordinates. */
	public static List<int[]> gridPoints(Rectangle area) {
		List<int[]> points = new ArrayList<>();
		int columns = Math.min(GRID, Math.max(area.width, 1));
		int rows = Math.min(GRID, Math.max(area.height, 1));
		for (int row = 0; row < rows; row++) {
			for (int column = 0; column < columns; column++) {
				points.add(new int[] { area.x + (2 * column + 1) * area.width / (2 * columns),
						area.y + (2 * row + 1) * area.height / (2 * rows) });
			}
		}
		return points;
	}

	/** The pixels of an area in points, cut at the monitors; the display's own bounds stand for every monitor. */
	static Rectangle pixelsOf(Display display, Rectangle area) throws ReflectiveOperationException {
		ScreenMap map = map(display);
		return area.equals(display.getBounds()) ? map.allPixels() : map.toPixels(area).intersection(map.allPixels());
	}

	/** The monitor's zoom an area is on, or {@code fallback} where the monitors cannot be read. */
	static int zoomAt(Display display, Rectangle area, int fallback) {
		if (!FileLocations.isWindows()) {
			return fallback;
		}
		try {
			return map(display).zoomAt(area);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return fallback;
		}
	}

	/** Every monitor in points, which {@code Display.getBounds} is not when monitors have their origin in pixels. */
	static Rectangle allPoints(Display display) {
		if (!FileLocations.isWindows()) {
			return display.getBounds();
		}
		try {
			return map(display).allPoints();
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return display.getBounds();
		}
	}

	private static ScreenMap map(Display display) throws ReflectiveOperationException {
		Class<?> os = os();
		Class<?> infoClass = Class.forName(PACKAGE + "MONITORINFO", true, os.getClassLoader()); //$NON-NLS-1$
		Field handle = Monitor.class.getDeclaredField("handle"); //$NON-NLS-1$
		handle.setAccessible(true);
		List<ScreenMap.Screen> screens = new ArrayList<>();
		for (Monitor monitor : display.getMonitors()) {
			Object info = infoClass.getConstructor().newInstance();
			infoClass.getField("cbSize").setInt(info, infoClass.getField("sizeof").getInt(null)); //$NON-NLS-1$ //$NON-NLS-2$
			if (!Boolean.TRUE.equals(os.getMethod("GetMonitorInfo", long.class, infoClass).invoke(null, Long.valueOf(handle.getLong(monitor)), info))) { //$NON-NLS-1$
				throw new IllegalStateException("GetMonitorInfo returned false"); //$NON-NLS-1$
			}
			int left = infoClass.getField("rcMonitor_left").getInt(info); //$NON-NLS-1$
			int top = infoClass.getField("rcMonitor_top").getInt(info); //$NON-NLS-1$
			screens.add(new ScreenMap.Screen(monitor.getBounds(), new Rectangle(left, top,
					infoClass.getField("rcMonitor_right").getInt(info) - left, infoClass.getField("rcMonitor_bottom").getInt(info) - top))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return new ScreenMap(screens);
	}

	private static Class<?> os() throws ClassNotFoundException {
		return Class.forName(PACKAGE + "OS", true, Display.class.getClassLoader()); //$NON-NLS-1$
	}

	private static int[] rectangle(Class<?> os, long window) throws ReflectiveOperationException {
		Class<?> rectClass = Class.forName(PACKAGE + "RECT", true, os.getClassLoader()); //$NON-NLS-1$
		Object rect = rectClass.getConstructor().newInstance();
		os.getMethod("GetWindowRect", long.class, rectClass).invoke(null, Long.valueOf(window), rect); //$NON-NLS-1$
		return new int[] { rectClass.getField("left").getInt(rect), rectClass.getField("top").getInt(rect), //$NON-NLS-1$ //$NON-NLS-2$
				rectClass.getField("right").getInt(rect), rectClass.getField("bottom").getInt(rect) }; //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static String className(Class<?> os, long window) throws ReflectiveOperationException {
		// the longest class name Windows allows
		char[] name = new char[256];
		int read = (Integer) os.getMethod("GetClassName", long.class, char[].class, int.class) //$NON-NLS-1$
				.invoke(null, Long.valueOf(window), name, Integer.valueOf(name.length));
		return new String(name, 0, Math.max(read, 0));
	}

	private static String text(Class<?> os, long window) throws ReflectiveOperationException {
		char[] title = new char[256];
		int read = (Integer) os.getMethod("GetWindowText", long.class, char[].class, int.class) //$NON-NLS-1$
				.invoke(null, Long.valueOf(window), title, Integer.valueOf(title.length));
		return new String(title, 0, Math.max(read, 0));
	}
}
