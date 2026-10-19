package com.vogella.eclipse.mcp.ui.internal;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Captures a shell's native window through the window system rather than by
 * reading the screen or painting the widgets: {@code screencapture -l} on
 * macOS, {@code PrintWindow} on Windows, the toplevel GdkWindow on GTK. It sees
 * what the window system composited, so it is independent of how an SWT
 * implementation prints, and on macOS and Windows of windows in front.
 */
public final class WindowCapture {

	static final boolean COCOA = "cocoa".equals(SWT.getPlatform()); //$NON-NLS-1$

	private static final long SCREENCAPTURE_TIMEOUT_SECONDS = 10;

	/** The pixels and the window they cover in display points, or why there are none. */
	record Read(ImageData data, Rectangle window, String reader, String failure) {

		static Read failed(String reader, String failure) {
			return new Read(null, null, reader, failure);
		}
	}

	private WindowCapture() {
	}

	static Read read(Shell shell) {
		return switch (SWT.getPlatform()) {
		case "cocoa" -> cocoa(shell); //$NON-NLS-1$
		case "win32" -> win32(shell); //$NON-NLS-1$
		case "gtk" -> gtk(shell); //$NON-NLS-1$
		default -> Read.failed(null, "no window capture for platform " + SWT.getPlatform()); //$NON-NLS-1$
		};
	}

	private static Read cocoa(Shell shell) {
		try {
			return screencapture(windowNumber(shell), shell.getBounds());
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return Read.failed(SCREENCAPTURE, "the window number could not be read: " + e); //$NON-NLS-1$
		}
	}

	private static final String SCREENCAPTURE = "screencapture"; //$NON-NLS-1$

	/** The Cocoa window number of a shell, 0 when it has no NSWindow; read on the UI thread. */
	static long windowNumber(Shell shell) throws ReflectiveOperationException {
		Field field = Shell.class.getDeclaredField("window"); //$NON-NLS-1$
		field.setAccessible(true);
		Object window = field.get(shell);
		return window == null ? 0 : ((Long) window.getClass().getMethod("windowNumber").invoke(window)).longValue(); //$NON-NLS-1$
	}

	/**
	 * The window frame, title bar included, which is what Shell.getBounds covers on
	 * Cocoa. Runs a subprocess and needs no SWT, so it belongs off the UI thread.
	 */
	static Read screencapture(long number, Rectangle bounds) {
		String reader = SCREENCAPTURE;
		if (number == 0) {
			return Read.failed(reader, "the shell has no NSWindow"); //$NON-NLS-1$
		}
		Path file = null;
		try {
			file = Files.createTempFile("mcp-window-", ".png"); //$NON-NLS-1$ //$NON-NLS-2$
			// -o leaves out the shadow, so the image is the frame; -x keeps it silent
			Process process = new ProcessBuilder("screencapture", "-x", "-o", "-l" + number, file.toString()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					.redirectErrorStream(true).start();
			if (!process.waitFor(SCREENCAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return Read.failed(reader, "screencapture did not finish within %d seconds" //$NON-NLS-1$
						.formatted(Long.valueOf(SCREENCAPTURE_TIMEOUT_SECONDS)));
			}
			String output = new String(process.getInputStream().readAllBytes()).strip();
			if (process.exitValue() != 0 || Files.size(file) == 0) {
				return Read.failed(reader, "screencapture exited with %d%s; the IDE process needs the Screen Recording permission" //$NON-NLS-1$
						.formatted(Integer.valueOf(process.exitValue()), output.isEmpty() ? "" : ": " + output)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			ImageData[] data = new ImageLoader().load(file.toString());
			return new Read(data[0], bounds, reader, null);
		} catch (IOException | RuntimeException e) {
			return Read.failed(reader, "screencapture failed: " + e); //$NON-NLS-1$
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Read.failed(reader, "interrupted"); //$NON-NLS-1$
		} finally {
			if (file != null) {
				try {
					Files.deleteIfExists(file);
				} catch (IOException e) {
					// a leftover temporary file is harmless
				}
			}
		}
	}

	/** The whole window rectangle, frame included, which is what Shell.getBounds covers on Windows. */
	private static Read win32(Shell shell) {
		String reader = "PrintWindow"; //$NON-NLS-1$
		Display display = shell.getDisplay();
		Rectangle bounds = shell.getBounds();
		int zoom = DeviceScale.zoomOf(shell);
		Image image = new Image(display, bounds.width, bounds.height);
		try {
			GC gc = new GC(image);
			boolean printed;
			try {
				Class<?> os = Class.forName("org.eclipse.swt.internal.win32.OS", true, Display.class.getClassLoader()); //$NON-NLS-1$
				long hwnd = shell.getClass().getField("handle").getLong(shell); //$NON-NLS-1$
				long hdc = GC.class.getField("handle").getLong(gc); //$NON-NLS-1$
				int flags = os.getField("PW_RENDERFULLCONTENT").getInt(null); //$NON-NLS-1$
				printed = ((Boolean) os.getMethod("PrintWindow", long.class, long.class, int.class) //$NON-NLS-1$
						.invoke(null, Long.valueOf(hwnd), Long.valueOf(hdc), Integer.valueOf(flags))).booleanValue();
			} finally {
				gc.dispose();
			}
			if (!printed) {
				return Read.failed(reader, "PrintWindow returned false"); //$NON-NLS-1$
			}
			return new Read(image.getImageData(zoom), bounds, reader, null);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return Read.failed(reader, "PrintWindow could not be called: " + e); //$NON-NLS-1$
		} finally {
			image.dispose();
		}
	}

	/** The toplevel GdkWindow, without the window manager's frame. */
	private static Read gtk(Shell shell) {
		String reader = "gdkWindow"; //$NON-NLS-1$
		try {
			ClassLoader loader = Display.class.getClassLoader();
			Class<?> gtk3 = Class.forName("org.eclipse.swt.internal.gtk3.GTK3", true, loader); //$NON-NLS-1$
			Class<?> gdk = Class.forName("org.eclipse.swt.internal.gtk.GDK", true, loader); //$NON-NLS-1$
			long handle = shell.getClass().getField("handle").getLong(shell); //$NON-NLS-1$
			long top = ((Long) gtk3.getMethod("gtk_widget_get_toplevel", long.class).invoke(null, handle)).longValue(); //$NON-NLS-1$
			long window = top == 0 ? 0
					: ((Long) gtk3.getMethod("gtk_widget_get_window", long.class).invoke(null, top)).longValue(); //$NON-NLS-1$
			if (window == 0) {
				return Read.failed(reader, "the shell has no GdkWindow"); //$NON-NLS-1$
			}
			int width = ((Integer) gdk.getMethod("gdk_window_get_width", long.class).invoke(null, window)).intValue(); //$NON-NLS-1$
			int height = ((Integer) gdk.getMethod("gdk_window_get_height", long.class).invoke(null, window)).intValue(); //$NON-NLS-1$
			int[] x = new int[1];
			int[] y = new int[1];
			gdk.getMethod("gdk_window_get_origin", long.class, int[].class, int[].class).invoke(null, window, x, y); //$NON-NLS-1$
			ScreenPixels.Read read = ScreenPixels.read(window, 0, 0, width, height, DeviceScale.screenZoom());
			if (read.data() == null) {
				return Read.failed(reader, read.failure());
			}
			return new Read(read.data(), new Rectangle(x[0], y[0], width, height), reader, null);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return Read.failed(reader, "the GDK call failed: " + e); //$NON-NLS-1$
		}
	}

	/** The part of a window capture that covers {@code area}, given in display points, or null when none does. */
	public static ImageData crop(ImageData data, Rectangle window, Rectangle area) {
		Rectangle inside = window.intersection(area);
		if (inside.isEmpty()) {
			return null;
		}
		double scaleX = data.width / (double) window.width;
		double scaleY = data.height / (double) window.height;
		int x = (int) Math.round((inside.x - window.x) * scaleX);
		int y = (int) Math.round((inside.y - window.y) * scaleY);
		int width = Math.min(data.width - x, (int) Math.round(inside.width * scaleX));
		int height = Math.min(data.height - y, (int) Math.round(inside.height * scaleY));
		if (x == 0 && y == 0 && width == data.width && height == data.height) {
			return data;
		}
		ImageData cropped = new ImageData(width, height, data.depth, data.palette);
		int[] row = new int[width];
		for (int line = 0; line < height; line++) {
			data.getPixels(x, y + line, width, row, 0);
			cropped.setPixels(0, line, width, row, 0);
		}
		return cropped;
	}
}
