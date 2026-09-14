package com.vogella.eclipse.mcp.ui.internal;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * Capturing at the pixels the screen really holds rather than at the points SWT
 * measures widgets in.
 * <p>
 * On GTK the two differ by the GDK scale factor, and nothing about a capture
 * says which of them it got: an image of the right size, at zoom 100, with
 * every derived field agreeing, is what a 200% display produced while three
 * quarters of its pixels were thrown away. The two ways of reading a display
 * lose them in different places, so each needs its own answer.
 * <p>
 * A screen read fills an SWT {@code Image} whose surface carries whatever
 * device scale it was created with, and the plain width/height constructor
 * creates a 1:1 surface, so the copy is downsampled on the way in. An image
 * built from {@code ImageData} is scaled up to the device zoom by SWT itself,
 * and that surface takes the real pixels.
 * <p>
 * A widget print goes through {@code gtk_widget_draw}, which paints in GTK's
 * logical coordinates, so a 1:1 canvas of device size leaves the picture at 1x
 * in a corner. The print is therefore painted into a surface that carries the
 * device scale itself, the same kind a screen read uses, which makes GTK
 * rasterise at the device resolution, glyphs included.
 * <p>
 * It has to be the surface and not a scale transform on a 1:1 canvas:
 * {@code GC.fillGradientRectangle} on GTK sets the target surface's device
 * scale to the device zoom and never restores it, so under a transform
 * everything painted after the first gradient, which the Light theme's tabs
 * draw, landed at twice the scale inside a correctly sized image. The
 * transform is kept only for a zoom other than the device zoom, which that
 * write would break anyway.
 * <p>
 * Off GTK this does nothing. SWT scales its own drawing on Windows and macOS
 * and {@code Image} sizes are in points there, so a device sized canvas would
 * be doubled twice; the behaviour those platforms had is kept and the reported
 * zoom is derived from the pixels that came back rather than promised.
 */
public final class DeviceScale {

	/** Whether widget prints land in points here, so a device capture needs a scale. */
	static final boolean GTK = "gtk".equals(SWT.getPlatform()); //$NON-NLS-1$

	private DeviceScale() {
	}

	/** Paints an area given in points; the GC is scaled, so it takes point coordinates. */
	interface Painting {

		void paint(GC gc, int widthInPoints, int heightInPoints);
	}

	/** The device pixels per point of the control's monitor, in percent. */
	static int zoomOf(Control control) {
		try {
			org.eclipse.swt.widgets.Monitor monitor = control.getMonitor();
			int zoom = monitor == null ? 100 : monitor.getZoom();
			return zoom <= 0 ? 100 : zoom;
		} catch (RuntimeException e) {
			return 100;
		}
	}

	/**
	 * The zoom a screen read comes back at, which is the one SWT scales an image
	 * by. It has to be that number rather than the monitor's, because it is what
	 * decides the surface {@link #screenTarget} gets.
	 */
	static int screenZoom() {
		Integer deviceZoom = DisplayScaling.deviceZoom();
		return deviceZoom == null || deviceZoom <= 0 ? 100 : deviceZoom.intValue();
	}

	/** Whether a capture at this zoom is painted in device pixels rather than points. */
	private static boolean inDevicePixels(int zoom) {
		return GTK && zoom > 100;
	}

	/** Whether a paint at this zoom goes into a surface that carries the device scale. */
	private static boolean onScaledSurface(int zoom) {
		return inDevicePixels(zoom) && zoom == screenZoom();
	}

	/** An image to read the screen into that takes the pixels the screen holds. */
	static Image screenTarget(Display display, int widthInPoints, int heightInPoints, int zoom) {
		if (!inDevicePixels(zoom)) {
			return new Image(display, widthInPoints, heightInPoints);
		}
		// SWT scales this data from 100% to the device zoom, and the surface it
		// allocates for the result is the one the copy needs
		return new Image(display,
				new ImageData(widthInPoints, heightInPoints, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF)));
	}

	/** An image of that many points, painted at the device resolution of the zoom. */
	static Image paint(Display display, int widthInPoints, int heightInPoints, int zoom, Painting painting) {
		if (!inDevicePixels(zoom)) {
			return new Image(display, (gc, width, height) -> painting.paint(gc, width, height), widthInPoints,
					heightInPoints);
		}
		if (onScaledSurface(zoom)) {
			Image image = screenTarget(display, widthInPoints, heightInPoints, zoom);
			GC gc = new GC(image);
			try {
				painting.paint(gc, widthInPoints, heightInPoints);
			} finally {
				gc.dispose();
			}
			return image;
		}
		Image image = new Image(display, pixels(widthInPoints, zoom), pixels(heightInPoints, zoom));
		GC gc = new GC(image);
		Transform transform = new Transform(display);
		try {
			transform.scale(zoom / 100f, zoom / 100f);
			gc.setTransform(transform);
			painting.paint(gc, widthInPoints, heightInPoints);
		} finally {
			transform.dispose();
			gc.dispose();
		}
		return image;
	}

	/**
	 * The pixels of an image from {@link #paint}.
	 * <p>
	 * A scaled surface is read at its own zoom, like a screen read. A device sized
	 * 1:1 canvas is a plain image, so SWT counts its zoom as 100 and asking for that
	 * returns the surface untouched; asking for the device zoom would resample it.
	 */
	static ImageData paintedData(Image image, int zoom) {
		if (onScaledSurface(zoom)) {
			return screenData(image, zoom);
		}
		return image.getImageData(inDevicePixels(zoom) ? 100 : zoom);
	}

	/**
	 * The pixels of an image from {@link #screenTarget}, which is the opposite rule:
	 * that one is built from ImageData and therefore carries the device zoom itself,
	 * so it is read at that zoom and reading it at 100 throws the pixels away again.
	 */
	static ImageData screenData(Image image, int zoom) {
		return image.getImageData(inDevicePixels(zoom) ? zoom : 100);
	}

	/**
	 * Draws an image that already holds device pixels at a position in points,
	 * one pixel of it per pixel of the target.
	 */
	static void drawPixels(GC gc, Image image, int xInPoints, int yInPoints, int zoom) {
		// on a scaled surface both images share the device scale, so points are pixels one for one
		if (!inDevicePixels(zoom) || onScaledSurface(zoom)) {
			gc.drawImage(image, xInPoints, yInPoints);
			return;
		}
		Transform previous = new Transform(gc.getDevice());
		try {
			gc.getTransform(previous);
			gc.setTransform(null);
			gc.drawImage(image, pixels(xInPoints, zoom), pixels(yInPoints, zoom));
			gc.setTransform(previous);
		} finally {
			previous.dispose();
		}
	}

	/** Points to device pixels, rounded the way a canvas has to be sized. */
	public static int pixels(int points, int zoom) {
		return Math.round(points * zoom / 100f);
	}
}
