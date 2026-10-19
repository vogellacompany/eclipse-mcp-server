package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Field;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Scrollable;

import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The sizes a control computes for itself, so two SWT implementations or two
 * builds can be compared control by control rather than pixel by pixel.
 */
public final class ControlMetrics {

	private ControlMetrics() {
	}

	public static JsonObject of(Control control) {
		JsonObject json = new JsonObject();
		Point preferred = control.computeSize(SWT.DEFAULT, SWT.DEFAULT);
		json.put("preferredSize", size(preferred)) //$NON-NLS-1$
				.put("borderWidth", Integer.valueOf(control.getBorderWidth())) //$NON-NLS-1$
				.put("nativeHandle", hasNativeHandle(control)); //$NON-NLS-1$
		if (control instanceof Scrollable scrollable) {
			json.put("clientArea", Overlays.describe(scrollable.getClientArea())) //$NON-NLS-1$
					.put("trim", Overlays.describe(scrollable.computeTrim(0, 0, 0, 0))); //$NON-NLS-1$
		}
		Font font = control.getFont();
		if (font != null && !font.isDisposed()) {
			FontData[] data = font.getFontData();
			if (data.length > 0) {
				json.put("fontName", data[0].getName()) //$NON-NLS-1$
						.put("fontHeight", Integer.valueOf(data[0].getHeight())); //$NON-NLS-1$
			}
		}
		Integer itemHeight = switch (control) {
		case org.eclipse.swt.widgets.Table table -> Integer.valueOf(table.getItemHeight());
		case org.eclipse.swt.widgets.Tree tree -> Integer.valueOf(tree.getItemHeight());
		case org.eclipse.swt.widgets.List list -> Integer.valueOf(list.getItemHeight());
		case org.eclipse.swt.widgets.Combo combo -> Integer.valueOf(combo.getItemHeight());
		default -> null;
		};
		json.put("itemHeight", itemHeight); //$NON-NLS-1$
		return json;
	}

	private static String size(Point point) {
		return point.x + "x" + point.y; //$NON-NLS-1$
	}

	/**
	 * Whether the control has a window system peer: {@code handle} on GTK, Win32
	 * and lightweight implementations that leave it 0, {@code view} on Cocoa.
	 * Null when neither field is there to read.
	 */
	public static Boolean hasNativeHandle(Control control) {
		for (Class<?> type = control.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (String name : new String[] { "handle", "view" }) { //$NON-NLS-1$ //$NON-NLS-2$
				try {
					Field field = type.getDeclaredField(name);
					field.trySetAccessible();
					if (field.getType() == long.class) {
						return Boolean.valueOf(field.getLong(control) != 0);
					}
					if (field.getType() == int.class) {
						return Boolean.valueOf(field.getInt(control) != 0);
					}
					if (!field.getType().isPrimitive()) {
						return Boolean.valueOf(field.get(control) != null);
					}
				} catch (NoSuchFieldException e) {
					// try the next name or the superclass
				} catch (ReflectiveOperationException | RuntimeException e) {
					return null;
				}
			}
		}
		return null;
	}
}
