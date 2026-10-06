package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Method;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;

/**
 * Reads the screen through {@code gdk_pixbuf_get_from_window}, because
 * {@code GC.copyArea} on a display GC returns stale pixels at GDK scale 2.
 * Answers null where the GDK classes are not available.
 */
public final class ScreenPixels {

	private static final String GDK = "org.eclipse.swt.internal.gtk.GDK"; //$NON-NLS-1$

	private ScreenPixels() {
	}

	/**
	 * The area of the root window, given in points, as device pixels, or null
	 * when the pixbuf read is not available here or came back at another size
	 * than the zoom promises.
	 */
	static ImageData read(int x, int y, int width, int height, int zoom) {
		if (!DeviceScale.GTK) {
			return null;
		}
		try {
			ClassLoader loader = org.eclipse.swt.widgets.Display.class.getClassLoader();
			Class<?> gdk = Class.forName(GDK, true, loader);
			Class<?> os = Class.forName("org.eclipse.swt.internal.gtk.OS", true, loader); //$NON-NLS-1$
			Class<?> c = Class.forName("org.eclipse.swt.internal.C", true, loader); //$NON-NLS-1$
			long root = ((Long) gdk.getMethod("gdk_get_default_root_window").invoke(null)).longValue(); //$NON-NLS-1$
			if (root == 0) {
				return null;
			}
			long pixbuf = ((Long) gdk.getMethod("gdk_pixbuf_get_from_window", long.class, int.class, int.class, //$NON-NLS-1$
					int.class, int.class).invoke(null, root, x, y, width, height)).longValue();
			if (pixbuf == 0) {
				return null;
			}
			try {
				int w = intOf(gdk, "gdk_pixbuf_get_width", pixbuf); //$NON-NLS-1$
				int h = intOf(gdk, "gdk_pixbuf_get_height", pixbuf); //$NON-NLS-1$
				int stride = intOf(gdk, "gdk_pixbuf_get_rowstride", pixbuf); //$NON-NLS-1$
				int channels = intOf(gdk, "gdk_pixbuf_get_n_channels", pixbuf); //$NON-NLS-1$
				long pixels = ((Long) gdk.getMethod("gdk_pixbuf_get_pixels", long.class).invoke(null, pixbuf)) //$NON-NLS-1$
						.longValue();
				byte[] bytes = new byte[stride * h];
				Method memmove = c.getMethod("memmove", byte[].class, long.class, long.class); //$NON-NLS-1$
				memmove.invoke(null, bytes, pixels, Long.valueOf(bytes.length));
				if (w != Math.round(width * zoom / 100f) || h != Math.round(height * zoom / 100f)) {
					return null;
				}
				return toImageData(bytes, w, h, stride, channels);
			} finally {
				os.getMethod("g_object_unref", long.class).invoke(null, pixbuf); //$NON-NLS-1$
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** Packed RGB or RGBA rows, as a pixbuf holds them, as 24 bit ImageData. */
	public static ImageData toImageData(byte[] pixels, int width, int height, int rowstride, int channels) {
		// unpadded rows, like the data of a screen read: the PNG writer ignores a scanline pad
		ImageData data = new ImageData(width, height, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF), 1,
				new byte[width * height * 3]);
		for (int row = 0; row < height; row++) {
			int from = row * rowstride;
			int to = row * data.bytesPerLine;
			for (int column = 0; column < width; column++) {
				System.arraycopy(pixels, from + column * channels, data.data, to + column * 3, 3);
			}
		}
		return data;
	}

	private static int intOf(Class<?> gdk, String method, long pixbuf) throws ReflectiveOperationException {
		return ((Integer) gdk.getMethod(method, long.class).invoke(null, pixbuf)).intValue();
	}
}
