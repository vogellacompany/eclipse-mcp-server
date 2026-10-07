package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Method;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Reads the screen through {@code gdk_pixbuf_get_from_window}, because
 * {@code GC.copyArea} on a display GC returns stale pixels at GDK scale 2.
 */
public final class ScreenPixels {

	private static final String GDK = "org.eclipse.swt.internal.gtk.GDK"; //$NON-NLS-1$

	/** The pixels of a read, or why there are none. */
	record Read(ImageData data, String failure) {

		static Read failed(String failure) {
			return new Read(null, failure);
		}
	}

	private ScreenPixels() {
	}

	/**
	 * The area of the root window, given in points, as device pixels; without
	 * data when the pixbuf read is not available here or came back at another
	 * size than the zoom promises, and then the failure says which.
	 */
	static Read read(int x, int y, int width, int height, int zoom) {
		if (!DeviceScale.GTK) {
			return Read.failed("not GTK"); //$NON-NLS-1$
		}
		try {
			ClassLoader loader = Display.class.getClassLoader();
			Class<?> gdk = Class.forName(GDK, true, loader);
			Class<?> os = Class.forName("org.eclipse.swt.internal.gtk.OS", true, loader); //$NON-NLS-1$
			Class<?> c = Class.forName("org.eclipse.swt.internal.C", true, loader); //$NON-NLS-1$
			long root = ((Long) gdk.getMethod("gdk_get_default_root_window").invoke(null)).longValue(); //$NON-NLS-1$
			if (root == 0) {
				return Read.failed("GDK has no root window"); //$NON-NLS-1$
			}
			long pixbuf = ((Long) gdk.getMethod("gdk_pixbuf_get_from_window", long.class, int.class, int.class, //$NON-NLS-1$
					int.class, int.class).invoke(null, root, x, y, width, height)).longValue();
			if (pixbuf == 0) {
				return Read.failed("gdk_pixbuf_get_from_window returned no pixbuf"); //$NON-NLS-1$
			}
			try {
				int w = intOf(gdk, "gdk_pixbuf_get_width", pixbuf); //$NON-NLS-1$
				int h = intOf(gdk, "gdk_pixbuf_get_height", pixbuf); //$NON-NLS-1$
				int expectedWidth = Math.round(width * zoom / 100f);
				int expectedHeight = Math.round(height * zoom / 100f);
				if (w != expectedWidth || h != expectedHeight) {
					return Read.failed("the pixbuf came back %dx%d where zoom %d promises %dx%d".formatted( //$NON-NLS-1$
							Integer.valueOf(w), Integer.valueOf(h), Integer.valueOf(zoom),
							Integer.valueOf(expectedWidth), Integer.valueOf(expectedHeight)));
				}
				int stride = intOf(gdk, "gdk_pixbuf_get_rowstride", pixbuf); //$NON-NLS-1$
				int channels = intOf(gdk, "gdk_pixbuf_get_n_channels", pixbuf); //$NON-NLS-1$
				String malformed = malformed(w, h, stride, channels);
				if (malformed != null) {
					return Read.failed(malformed);
				}
				long pixels = ((Long) gdk.getMethod("gdk_pixbuf_get_pixels", long.class).invoke(null, pixbuf)) //$NON-NLS-1$
						.longValue();
				// the last row of a pixbuf need not be padded to the full stride
				byte[] bytes = new byte[stride * (h - 1) + w * channels];
				Method memmove = c.getMethod("memmove", byte[].class, long.class, long.class); //$NON-NLS-1$
				memmove.invoke(null, bytes, pixels, Long.valueOf(bytes.length));
				return new Read(toImageData(bytes, w, h, stride, channels), null);
			} finally {
				os.getMethod("g_object_unref", long.class).invoke(null, pixbuf); //$NON-NLS-1$
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return Read.failed("the GDK call failed: " + e); //$NON-NLS-1$
		}
	}

	/** Why rows of this geometry cannot be converted, or null when they can. */
	public static String malformed(int width, int height, int rowstride, int channels) {
		if (width <= 0 || height <= 0) {
			return "the pixbuf is %dx%d".formatted(Integer.valueOf(width), Integer.valueOf(height)); //$NON-NLS-1$
		}
		if (channels != 3 && channels != 4) {
			return "the pixbuf has %d channels".formatted(Integer.valueOf(channels)); //$NON-NLS-1$
		}
		if (rowstride < width * channels) {
			return "the pixbuf row stride %d is shorter than a row of %d bytes".formatted(Integer.valueOf(rowstride), //$NON-NLS-1$
					Integer.valueOf(width * channels));
		}
		return null;
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

	/**
	 * The bounds in points the X server holds for the shell's window, its frame
	 * included, or null when GDK cannot be asked. {@code Shell.getBounds} adds
	 * the layout allocation to the position, and the allocation can be larger
	 * than the window.
	 */
	static Rectangle windowBounds(Shell shell) {
		if (!DeviceScale.GTK || shell.isDisposed()) {
			return null;
		}
		try {
			ClassLoader loader = Display.class.getClassLoader();
			Class<?> gtk3 = Class.forName("org.eclipse.swt.internal.gtk3.GTK3", true, loader); //$NON-NLS-1$
			Class<?> gdk = Class.forName(GDK, true, loader);
			Class<?> rectangle = Class.forName("org.eclipse.swt.internal.gtk.GdkRectangle", true, loader); //$NON-NLS-1$
			long top = ((Long) gtk3.getMethod("gtk_widget_get_toplevel", long.class).invoke(null, shell.handle)) //$NON-NLS-1$
					.longValue();
			long window = top == 0 ? 0
					: ((Long) gtk3.getMethod("gtk_widget_get_window", long.class).invoke(null, top)).longValue(); //$NON-NLS-1$
			if (window == 0) {
				return null;
			}
			Object extents = rectangle.getConstructor().newInstance();
			gdk.getMethod("gdk_window_get_frame_extents", long.class, rectangle).invoke(null, window, extents); //$NON-NLS-1$
			return new Rectangle(rectangle.getField("x").getInt(extents), rectangle.getField("y").getInt(extents), //$NON-NLS-1$ //$NON-NLS-2$
					rectangle.getField("width").getInt(extents), rectangle.getField("height").getInt(extents)); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}
}
