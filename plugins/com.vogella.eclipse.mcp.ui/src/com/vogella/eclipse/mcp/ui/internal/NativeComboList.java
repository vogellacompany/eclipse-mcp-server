package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Combo;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The Win32 window behind a Combo's drop-down, which has no SWT control and which Windows paints itself.
 * All measures are native pixels.
 */
final class NativeComboList {

	private static final String PACKAGE = "org.eclipse.swt.internal.win32."; //$NON-NLS-1$

	private final Class<?> os;
	private final Class<?> rectClass;
	private final Method sendRect;
	private final long list;

	private NativeComboList(Class<?> os, long list) throws ReflectiveOperationException {
		this.os = os;
		this.list = list;
		rectClass = Class.forName(PACKAGE + "RECT", true, os.getClassLoader()); //$NON-NLS-1$
		sendRect = os.getMethod("SendMessage", long.class, int.class, long.class, rectClass); //$NON-NLS-1$
	}

	/** Reports the list window and its rows and, with {@code capture} and an open list, leaves its picture in {@code picture}. */
	static JsonObject describe(Combo combo, boolean capture, int maxRows, ComboListTool.Capture picture) throws ReflectiveOperationException {
		ClassLoader loader = Combo.class.getClassLoader();
		Class<?> os = Class.forName(PACKAGE + "OS", true, loader); //$NON-NLS-1$
		Class<?> infoClass = Class.forName(PACKAGE + "COMBOBOXINFO", true, loader); //$NON-NLS-1$
		Object info = infoClass.getConstructor().newInstance();
		infoClass.getField("cbSize").setInt(info, infoClass.getField("sizeof").getInt(null)); //$NON-NLS-1$ //$NON-NLS-2$
		long handle = combo.getClass().getField("handle").getLong(combo); //$NON-NLS-1$
		if (!Boolean.TRUE.equals(os.getMethod("GetComboBoxInfo", long.class, infoClass).invoke(null, Long.valueOf(handle), info))) { //$NON-NLS-1$
			return new JsonObject().put("failure", "GetComboBoxInfo failed for this Combo, so its list window is unknown."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		long list = infoClass.getField("hwndList").getLong(info); //$NON-NLS-1$
		if (list == 0) {
			return new JsonObject().put("failure", "GetComboBoxInfo reports no list window for this Combo."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return new NativeComboList(os, list).report(combo, capture, maxRows, picture);
	}

	private JsonObject report(Combo combo, boolean capture, int maxRows, ComboListTool.Capture picture) throws ReflectiveOperationException {
		int[] window = rectangle("GetWindowRect", list); //$NON-NLS-1$
		int[] client = rectangle("GetClientRect", list); //$NON-NLS-1$
		int count = Math.max(send("LB_GETCOUNT", 0), 0); //$NON-NLS-1$
		int highlighted = send("LB_GETCURSEL", 0); //$NON-NLS-1$
		int top = send("LB_GETTOPINDEX", 0); //$NON-NLS-1$
		int zoom = DeviceScale.zoomOf(combo);
		boolean shown = combo.getListVisible() && Boolean.TRUE.equals(os.getMethod("IsWindowVisible", long.class).invoke(null, Long.valueOf(list))); //$NON-NLS-1$
		JsonObject answer = new JsonObject().put("hwndList", "0x" + Long.toHexString(list)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("bounds", area(window)) //$NON-NLS-1$
				.put("zoom", Integer.valueOf(zoom)) //$NON-NLS-1$
				.put("highlightedRow", highlighted < 0 ? null : Integer.valueOf(highlighted)) //$NON-NLS-1$
				.put("topRow", Integer.valueOf(top)); //$NON-NLS-1$
		ImageData data = null;
		int offsetX = 0;
		int offsetY = 0;
		double scale = 1;
		if (capture && shown) {
			int width = window[2] - window[0];
			// rounded up, so the picture is never a pixel short of the window
			WindowCapture.Read read = WindowCapture.printWindow(combo.getDisplay(), list, (width * 100 + zoom - 1) / zoom,
					((window[3] - window[1]) * 100 + zoom - 1) / zoom, zoom);
			if (read.data() == null) {
				answer.put("captureFailure", read.failure()); //$NON-NLS-1$
			} else {
				int[] origin = clientOrigin();
				data = read.data();
				picture.data = data;
				picture.target = answer;
				scale = data.width / (double) width;
				offsetX = origin[0] - window[0];
				offsetY = origin[1] - window[1];
			}
		} else if (capture) {
			answer.put("captureNote", "The list is not open, so nothing was captured; visible true opens it."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		JsonArray rows = new JsonArray();
		for (int row = 0; row < Math.min(count, maxRows); row++) {
			int[] item = item(row);
			JsonObject described = new JsonObject().put("index", Integer.valueOf(row)) //$NON-NLS-1$
					.put("text", row < combo.getItemCount() ? combo.getItem(row) : null) //$NON-NLS-1$
					.put("highlighted", Boolean.valueOf(row == highlighted)); //$NON-NLS-1$
			boolean onScreen = shown && item != null && item[3] > 0 && item[1] < client[3];
			described.put("onScreen", Boolean.valueOf(onScreen)); //$NON-NLS-1$
			if (item != null) {
				described.put("bounds", area(item)); //$NON-NLS-1$
			}
			if (data != null && onScreen) {
				ImageData pixels = data;
				int x = offsetX;
				int y = offsetY;
				double factor = scale;
				int bottom = Math.min(client[3], item[3]);
				picture.fills.add(() -> fill(pixels, item, x, y, factor, bottom, described));
			}
			rows.add(described);
		}
		return answer.put("rows", rows).put("total", Integer.valueOf(count)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("truncated", Boolean.valueOf(count > maxRows)); //$NON-NLS-1$
	}

	/** The commonest colour of a row is its fill; whatever else is there is text, or nothing when the row has no text on screen. */
	private static void fill(ImageData data, int[] item, int offsetX, int offsetY, double scale, int bottom, JsonObject row) {
		int left = (int) Math.round((item[0] + offsetX) * scale);
		int right = Math.min(data.width, (int) Math.round((item[2] + offsetX) * scale));
		int top = (int) Math.round((Math.max(item[1], 0) + offsetY) * scale);
		int end = Math.min(data.height, (int) Math.round((bottom + offsetY) * scale));
		Map<Integer, Integer> counts = new HashMap<>();
		int pixels = 0;
		for (int y = Math.max(top, 0); y < end; y++) {
			for (int x = Math.max(left, 0); x < right; x++) {
				RGB rgb = data.palette.getRGB(data.getPixel(x, y));
				counts.merge(Integer.valueOf(rgb.red << 16 | rgb.green << 8 | rgb.blue), Integer.valueOf(1), Integer::sum);
				pixels++;
			}
		}
		if (pixels == 0) {
			return;
		}
		Map.Entry<Integer, Integer> commonest = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
		row.put("fill", "#%06X".formatted(commonest.getKey())) //$NON-NLS-1$ //$NON-NLS-2$
				.put("otherPixels", Integer.valueOf(pixels - commonest.getValue().intValue())); //$NON-NLS-1$
	}

	private static JsonObject area(int[] rectangle) {
		return new JsonObject().put("x", Integer.valueOf(rectangle[0])).put("y", Integer.valueOf(rectangle[1])) //$NON-NLS-1$ //$NON-NLS-2$
				.put("width", Integer.valueOf(rectangle[2] - rectangle[0])) //$NON-NLS-1$
				.put("height", Integer.valueOf(rectangle[3] - rectangle[1])); //$NON-NLS-1$
	}

	private int send(String message, long wParam) throws ReflectiveOperationException {
		long answer = (Long) os.getMethod("SendMessage", long.class, int.class, long.class, long.class).invoke(null, //$NON-NLS-1$
				Long.valueOf(list), Integer.valueOf(os.getField(message).getInt(null)), Long.valueOf(wParam), Long.valueOf(0));
		return (int) answer;
	}

	/** Left, top, right and bottom of a row in the list's client area, outside it for a row scrolled away, or null when Windows refuses. */
	private int[] item(int row) throws ReflectiveOperationException {
		Object rect = rectClass.getConstructor().newInstance();
		long answer = (Long) sendRect.invoke(null, Long.valueOf(list), Integer.valueOf(os.getField("LB_GETITEMRECT").getInt(null)), //$NON-NLS-1$
				Long.valueOf(row), rect);
		return answer < 0 ? null : sides(rect);
	}

	private int[] rectangle(String call, long window) throws ReflectiveOperationException {
		Object rect = rectClass.getConstructor().newInstance();
		os.getMethod(call, long.class, rectClass).invoke(null, Long.valueOf(window), rect);
		return sides(rect);
	}

	private int[] sides(Object rect) throws ReflectiveOperationException {
		return new int[] { rectClass.getField("left").getInt(rect), rectClass.getField("top").getInt(rect), //$NON-NLS-1$ //$NON-NLS-2$
				rectClass.getField("right").getInt(rect), rectClass.getField("bottom").getInt(rect) }; //$NON-NLS-1$ //$NON-NLS-2$
	}

	private int[] clientOrigin() throws ReflectiveOperationException {
		Class<?> pointClass = Class.forName(PACKAGE + "POINT", true, os.getClassLoader()); //$NON-NLS-1$
		Object point = pointClass.getConstructor().newInstance();
		os.getMethod("ClientToScreen", long.class, pointClass).invoke(null, Long.valueOf(list), point); //$NON-NLS-1$
		return new int[] { pointClass.getField("x").getInt(point), pointClass.getField("y").getInt(point) }; //$NON-NLS-1$ //$NON-NLS-2$
	}
}
