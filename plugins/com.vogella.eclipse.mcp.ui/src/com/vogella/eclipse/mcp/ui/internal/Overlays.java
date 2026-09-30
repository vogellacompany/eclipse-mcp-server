package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Widget;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Highlight rectangles drawn onto a capture after the fact.
 * <p>
 * A highlight is named in points relative to the capture target, either as a
 * widget path or as plain bounds, and lands on the image in pixels: scaled by
 * the zoom the capture was painted at and by the downscale that followed. The
 * rectangle and the fill are written into the pixels directly, so they cannot
 * be shifted by a GC that scales for the monitor; only the label goes through
 * a GC, on an image of its own, and is then copied in.
 */
public final class Overlays {

	/** Reads on a light and on a dark theme alike. */
	public static final RGB DEFAULT_COLOR = new RGB(255, 0, 102);

	private static final int OUTLINE = 3;
	private static final int FILL_ALPHA = 80;
	private static final int LABEL_PADDING = 3;

	private Overlays() {
	}

	/** One requested highlight, resolved to points, or the reason it could not be. */
	public record Highlight(String path, String bounds, Rectangle points, RGB color, String label, boolean fill,
			int lineWidth, String labelPosition, String error) {
	}

	/** Grows a rectangle by the padding, which is where the air around a widget comes from. */
	public static Rectangle pad(Rectangle rectangle, int[] padding) {
		return new Rectangle(rectangle.x - padding[3], rectangle.y - padding[0],
				rectangle.width + padding[1] + padding[3], rectangle.height + padding[0] + padding[2]);
	}

	/** Top, right, bottom, left. One number is all four; four are the sides in that order. */
	public static int[] parsePadding(Object value) {
		if (value == null) {
			return new int[4];
		}
		if (value instanceof Number number) {
			int all = number.intValue();
			return new int[] { all, all, all, all };
		}
		String[] parts = String.valueOf(value).strip().split(","); //$NON-NLS-1$
		try {
			if (parts.length == 1) {
				int all = Integer.parseInt(parts[0].strip());
				return new int[] { all, all, all, all };
			}
			if (parts.length == 4) {
				int[] sides = new int[4];
				for (int i = 0; i < 4; i++) {
					sides[i] = Integer.parseInt(parts[i].strip());
				}
				return sides;
			}
		} catch (NumberFormatException e) {
			// reported below
		}
		throw new IllegalArgumentException(
				"'%s' is not a padding; give a number of points, or 'top,right,bottom,left'.".formatted(value)); //$NON-NLS-1$
	}

	/** Resolves the raw {@code highlights} argument against the capture target. */
	public static List<Highlight> resolve(Display display, Control target, Object raw) {
		List<Highlight> resolved = new ArrayList<>();
		if (!(raw instanceof List<?> entries)) {
			return resolved;
		}
		for (Object entry : entries) {
			if (!(entry instanceof Map<?, ?> map)) {
				resolved.add(new Highlight(null, null, null, DEFAULT_COLOR, null, false, OUTLINE, null,
						"A highlight has to be an object with path or bounds.")); //$NON-NLS-1$
				continue;
			}
			String path = string(map.get("path")); //$NON-NLS-1$
			String bounds = string(map.get("bounds")); //$NON-NLS-1$
			String label = string(map.get("label")); //$NON-NLS-1$
			boolean fill = "fill".equals(string(map.get("style"))); //$NON-NLS-1$ //$NON-NLS-2$
			String labelPosition = string(map.get("labelPosition")); //$NON-NLS-1$
			int lineWidth = map.get("lineWidth") instanceof Number width //$NON-NLS-1$
					? Math.clamp(width.intValue(), 1, 40)
					: OUTLINE;
			RGB color;
			int[] padding;
			try {
				color = parseColor(string(map.get("color"))); //$NON-NLS-1$
				padding = parsePadding(map.get("padding")); //$NON-NLS-1$
			} catch (IllegalArgumentException e) {
				resolved.add(new Highlight(path, bounds, null, DEFAULT_COLOR, label, fill, lineWidth, labelPosition,
						e.getMessage()));
				continue;
			}
			Rectangle points;
			String error = null;
			if (bounds != null) {
				try {
					points = parseBounds(bounds);
				} catch (IllegalArgumentException e) {
					points = null;
					error = e.getMessage();
				}
			} else if (path != null) {
				if (target == null) {
					points = null;
					error = "A widget path needs a part or a shell capture; a display capture has no widget tree."; //$NON-NLS-1$
				} else {
					Widget widget = WidgetTools.resolve(target, path);
					points = widget == null ? null : WidgetTools.boundsRelativeTo(display, widget, target);
					if (points == null) {
						error = widget == null ? "The path '%s' does not resolve under the capture target.".formatted(path) //$NON-NLS-1$
								: "The widget at '%s' has no bounds.".formatted(path); //$NON-NLS-1$
					}
				}
			} else {
				points = null;
				error = "A highlight needs path or bounds."; //$NON-NLS-1$
			}
			// padding widens what was resolved, before any scaling, so it is expressed
			// in the same points the caller used
			resolved.add(new Highlight(path, bounds, points == null ? null : pad(points, padding), color, label, fill,
					lineWidth, labelPosition, error));
		}
		return resolved;
	}

	/**
	 * Draws every resolved highlight into the pixels and reports where each landed.
	 * {@code scale} is pixels per point: the capture zoom times the downscale.
	 */
	public static JsonArray draw(Display display, ImageData image, List<Highlight> highlights, double scale) {
		JsonArray report = new JsonArray();
		for (Highlight highlight : highlights) {
			JsonObject entry = new JsonObject().put("path", highlight.path()) //$NON-NLS-1$
					.put("bounds", highlight.bounds()) //$NON-NLS-1$
					.put("label", highlight.label()); //$NON-NLS-1$
			if (highlight.error() != null || highlight.points() == null) {
				report.add(entry.put("drawn", Boolean.FALSE).put("error", highlight.error())); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			Rectangle points = highlight.points();
			Rectangle pixels = new Rectangle((int) Math.round(points.x * scale), (int) Math.round(points.y * scale),
					(int) Math.round(points.width * scale), (int) Math.round(points.height * scale));
			Rectangle clipped = pixels.intersection(new Rectangle(0, 0, image.width, image.height));
			entry.put("pointsInTarget", describe(points)).put("pixels", describe(pixels)) //$NON-NLS-1$ //$NON-NLS-2$
					.put("clipped", Boolean.valueOf(!clipped.equals(pixels))); //$NON-NLS-1$
			if (clipped.isEmpty()) {
				report.add(entry.put("drawn", Boolean.FALSE) //$NON-NLS-1$
						.put("error", "The rectangle lies entirely outside the captured image.")); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			if (highlight.fill()) {
				fill(image, clipped, highlight.color());
			}
			outline(image, pixels, image.width, image.height, highlight.color(), highlight.lineWidth());
			Rectangle labelBox = null;
			if (highlight.label() != null && !highlight.label().isBlank() && display != null) {
				labelBox = label(display, image, clipped, highlight.color(), highlight.label(), scale,
						highlight.labelPosition());
			}
			report.add(entry.put("drawn", Boolean.TRUE).put("labelPixels", labelBox == null ? null : describe(labelBox))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return report;
	}

	/** A frame of {@code lineWidth} pixels just inside the rectangle, clipped to the image. */
	static void outline(ImageData image, Rectangle r, int width, int height, RGB color, int lineWidth) {
		int pixel = image.palette.getPixel(color);
		for (int i = 0; i < lineWidth; i++) {
			horizontal(image, r.x, r.x + r.width - 1, r.y + i, width, height, pixel);
			horizontal(image, r.x, r.x + r.width - 1, r.y + r.height - 1 - i, width, height, pixel);
			vertical(image, r.y, r.y + r.height - 1, r.x + i, width, height, pixel);
			vertical(image, r.y, r.y + r.height - 1, r.x + r.width - 1 - i, width, height, pixel);
		}
	}

	private static void horizontal(ImageData image, int x0, int x1, int y, int width, int height, int pixel) {
		if (y < 0 || y >= height) {
			return;
		}
		for (int x = Math.max(0, x0); x <= Math.min(width - 1, x1); x++) {
			image.setPixel(x, y, pixel);
		}
	}

	private static void vertical(ImageData image, int y0, int y1, int x, int width, int height, int pixel) {
		if (x < 0 || x >= width) {
			return;
		}
		for (int y = Math.max(0, y0); y <= Math.min(height - 1, y1); y++) {
			image.setPixel(x, y, pixel);
		}
	}

	/** Blends the colour over the rectangle, which has to be inside the image already. */
	static void fill(ImageData image, Rectangle r, RGB color) {
		PaletteData palette = image.palette;
		int[] row = new int[r.width];
		for (int y = r.y; y < r.y + r.height; y++) {
			image.getPixels(r.x, y, r.width, row, 0);
			for (int i = 0; i < row.length; i++) {
				RGB under = palette.getRGB(row[i]);
				row[i] = palette.getPixel(new RGB(blend(under.red, color.red), blend(under.green, color.green),
						blend(under.blue, color.blue)));
			}
			image.setPixels(r.x, y, r.width, row, 0);
		}
	}

	private static int blend(int under, int over) {
		return (under * (255 - FILL_ALPHA) + over * FILL_ALPHA) / 255;
	}

	/**
	 * Renders the label on an image of its own, at the scale the image has, and
	 * copies the pixels in above the rectangle; below it when there is no room
	 * above, and inside its top left corner when there is room nowhere.
	 */
	private static Rectangle label(Display display, ImageData image, Rectangle anchor, RGB color, String text,
			double scale, String position) {
		Point size;
		GC measure = new GC(display);
		try {
			size = measure.stringExtent(text);
		} finally {
			measure.dispose();
		}
		// the label is drawn in points and fetched at the zoom that turns points
		// into this image's pixels, so it reads at the size the widgets have
		int zoom = Math.clamp(Math.round(100 * scale), 100, 400);
		int boxWidth = size.x + 2 * LABEL_PADDING;
		int boxHeight = size.y + 2 * LABEL_PADDING;
		Color background = new Color(color);
		Color foreground = new Color(luminance(color) > 140 ? new RGB(0, 0, 0) : new RGB(255, 255, 255));
		ImageData rendered;
		Image box = new Image(display, (gc, w, h) -> {
			gc.setBackground(background);
			gc.fillRectangle(0, 0, w, h);
			gc.setForeground(foreground);
			gc.drawString(text, LABEL_PADDING, LABEL_PADDING, true);
		}, boxWidth, boxHeight);
		try {
			rendered = box.getImageData(zoom);
		} finally {
			box.dispose();
			background.dispose();
			foreground.dispose();
		}
		int x = anchor.x;
		int y;
		String wanted = position == null ? "above" : position.toLowerCase(java.util.Locale.ROOT); //$NON-NLS-1$
		switch (wanted) {
		case "below" -> y = anchor.y + anchor.height; //$NON-NLS-1$
		case "inside" -> y = anchor.y; //$NON-NLS-1$
		case "left" -> { //$NON-NLS-1$
			y = anchor.y;
			x = anchor.x - rendered.width;
		}
		case "right" -> { //$NON-NLS-1$
			y = anchor.y;
			x = anchor.x + anchor.width;
		}
		default -> {
			// above, and the fallbacks that keep it on the image when there is no room
			if (anchor.y - rendered.height >= 0) {
				y = anchor.y - rendered.height;
			} else if (anchor.y + anchor.height + rendered.height <= image.height) {
				y = anchor.y + anchor.height;
			} else {
				y = anchor.y;
			}
		}
		}
		x = Math.max(0, Math.min(x, image.width - rendered.width));
		y = Math.max(0, Math.min(y, image.height - rendered.height));
		blit(rendered, image, x, y);
		return new Rectangle(x, y, rendered.width, rendered.height);
	}

	private static void blit(ImageData from, ImageData into, int atX, int atY) {
		int[] row = new int[from.width];
		for (int y = 0; y < from.height && atY + y < into.height; y++) {
			from.getPixels(0, y, from.width, row, 0);
			for (int x = 0; x < from.width && atX + x < into.width; x++) {
				into.setPixel(atX + x, atY + y, into.palette.getPixel(from.palette.getRGB(row[x])));
			}
		}
	}

	private static int luminance(RGB rgb) {
		return (rgb.red * 299 + rgb.green * 587 + rgb.blue * 114) / 1000;
	}

	/** {@code #rrggbb} or {@code r,g,b}; the default when absent. */
	public static RGB parseColor(String text) {
		if (text == null || text.isBlank()) {
			return DEFAULT_COLOR;
		}
		String value = text.strip();
		try {
			if (value.startsWith("#") && value.length() == 7) { //$NON-NLS-1$
				return new RGB(Integer.parseInt(value.substring(1, 3), 16), Integer.parseInt(value.substring(3, 5), 16),
						Integer.parseInt(value.substring(5, 7), 16));
			}
			String[] parts = value.split(","); //$NON-NLS-1$
			if (parts.length == 3) {
				return new RGB(Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()),
						Integer.parseInt(parts[2].strip()));
			}
		} catch (IllegalArgumentException e) {
			// reported below
		}
		throw new IllegalArgumentException("'%s' is not a colour; give #rrggbb or r,g,b.".formatted(text)); //$NON-NLS-1$
	}

	/** The {@code x,y wxh} form the other tools report. */
	public static Rectangle parseBounds(String text) {
		String[] halves = text.strip().split("\\s+"); //$NON-NLS-1$
		if (halves.length == 2) {
			String[] origin = halves[0].split(","); //$NON-NLS-1$
			String[] size = halves[1].toLowerCase().split("x"); //$NON-NLS-1$
			if (origin.length == 2 && size.length == 2) {
				try {
					return new Rectangle(Integer.parseInt(origin[0].strip()), Integer.parseInt(origin[1].strip()),
							Integer.parseInt(size[0].strip()), Integer.parseInt(size[1].strip()));
				} catch (NumberFormatException e) {
					// reported below
				}
			}
		}
		throw new IllegalArgumentException("'%s' is not of the form 'x,y wxh'.".formatted(text)); //$NON-NLS-1$
	}

	public static String describe(Rectangle r) {
		return r.x + "," + r.y + " " + r.width + "x" + r.height; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private static String string(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
