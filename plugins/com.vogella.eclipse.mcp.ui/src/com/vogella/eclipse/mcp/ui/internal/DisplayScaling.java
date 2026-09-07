package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Method;

/**
 * Every reference to SWT's internal scaling, drawing and window system classes.
 * <p>
 * {@code org.eclipse.swt.internal.DPIUtil} carries the only answer to what
 * scaling actually took effect, and {@code org.eclipse.swt.internal.gtk.GTK}
 * the GTK version, but both are internal: DPIUtil has changed shape across
 * releases, and the GTK class exists only on one window system, so a direct
 * call would turn this bundle into a GTK-only one. They are reached by name
 * here, the way {@link CssStyling} reaches the CSS engine, so an IDE without
 * them costs a null in one field rather than a failing tool. The same holds for
 * the GDK and cairo entry points {@link #refreshScreenSource()} needs.
 */
final class DisplayScaling {

	private static final String DPI_UTIL = "org.eclipse.swt.internal.DPIUtil"; //$NON-NLS-1$

	private static final String GTK = "org.eclipse.swt.internal.gtk.GTK"; //$NON-NLS-1$

	private static final String GDK = "org.eclipse.swt.internal.gtk.GDK"; //$NON-NLS-1$

	private static final String CAIRO = "org.eclipse.swt.internal.cairo.Cairo"; //$NON-NLS-1$

	private DisplayScaling() {
	}

	/**
	 * What the whole process scales at. This is the number that changes when
	 * {@code swt.autoScale} or {@code GDK_SCALE} takes effect, and the one a
	 * capture's own zoom cannot show.
	 */
	static Integer deviceZoom() {
		return intOf(DPI_UTIL, "getDeviceZoom"); //$NON-NLS-1$
	}

	/** What the window system reported before any autoScale override was applied. */
	static Integer nativeDeviceZoom() {
		return intOf(DPI_UTIL, "getNativeDeviceZoom"); //$NON-NLS-1$
	}

	/** The autoScale value in force, whether it came from a property or a default. */
	static String effectiveAutoScaleValue() {
		Object value = invoke(DPI_UTIL, "getEffectiveAutoScaleValue"); //$NON-NLS-1$
		return value == null ? null : String.valueOf(value);
	}

	/**
	 * Whether {@code swt.autoScale} was set at all.
	 * <p>
	 * This is what separates "the flag was ignored" from "the flag was never
	 * there", which the effective value alone cannot say: a default and an
	 * explicit setting of the same number read identically.
	 */
	static Boolean customAutoScale() {
		Object value = invoke(DPI_UTIL, "isCustomAutoScale"); //$NON-NLS-1$
		return value instanceof Boolean flag ? flag : null;
	}

	/**
	 * Tells cairo that the screen has changed since it last read it, so that the
	 * next root capture takes the pixels that are on it now.
	 * <p>
	 * Cairo keeps a snapshot of a source surface it has already read and
	 * invalidates it only when cairo itself draws on it. The screen is changed by
	 * everything except cairo, so the snapshot of the root window is never
	 * invalidated: every capture after the first repeats the first one, with no
	 * field of the answer able to say so. It only bites when the destination is an
	 * image surface, which is what a scaled display needs, so it arrived with the
	 * fix for those pixels rather than being there all along.
	 *
	 * @return whether the source was invalidated, false off GTK 3 and wherever
	 *         the internals could not be reached
	 */
	static boolean refreshScreenSource() {
		long context = 0;
		try {
			Class<?> gdk = Class.forName(GDK);
			// GTK4 has no gdk_cairo_create, and SWT reads no root window there
			// either, so there is nothing cached to invalidate
			if (Boolean.TRUE.equals(Class.forName(GTK).getField("GTK4").get(null))) { //$NON-NLS-1$
				return false;
			}
			Class<?> cairo = Class.forName(CAIRO);
			long window = longOf(gdk.getMethod("gdk_get_default_root_window").invoke(null)); //$NON-NLS-1$
			if (window == 0) {
				return false;
			}
			context = longOf(gdk.getMethod("gdk_cairo_create", long.class).invoke(null, Long.valueOf(window))); //$NON-NLS-1$
			if (context == 0) {
				return false;
			}
			long surface = longOf(cairo.getMethod("cairo_get_target", long.class) //$NON-NLS-1$
					.invoke(null, Long.valueOf(context)));
			if (surface == 0) {
				return false;
			}
			cairo.getMethod("cairo_surface_flush", long.class).invoke(null, Long.valueOf(surface)); //$NON-NLS-1$
			cairo.getMethod("cairo_surface_mark_dirty", long.class).invoke(null, Long.valueOf(surface)); //$NON-NLS-1$
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return false;
		} finally {
			destroy(context);
		}
	}

	private static void destroy(long context) {
		if (context == 0) {
			return;
		}
		try {
			Class.forName(CAIRO).getMethod("cairo_destroy", long.class).invoke(null, Long.valueOf(context)); //$NON-NLS-1$
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			// leaking one cairo context is better than failing a capture that worked
		}
	}

	private static long longOf(Object value) {
		return value instanceof Long number ? number.longValue() : 0;
	}

	/** The GTK version as major.minor.micro, or {@code null} off GTK. */
	static String gtkVersion() {
		Integer major = intOf(GTK, "gtk_get_major_version"); //$NON-NLS-1$
		Integer minor = intOf(GTK, "gtk_get_minor_version"); //$NON-NLS-1$
		Integer micro = intOf(GTK, "gtk_get_micro_version"); //$NON-NLS-1$
		return major == null || minor == null || micro == null ? null
				: "%d.%d.%d".formatted(major, minor, micro); //$NON-NLS-1$
	}

	private static Integer intOf(String className, String method) {
		Object value = invoke(className, method);
		return value instanceof Integer number ? number : null;
	}

	/**
	 * Calls a no-argument static method by name.
	 *
	 * @return its result, or {@code null} when the class, the method or the call
	 *         is not available here
	 */
	private static Object invoke(String className, String methodName) {
		try {
			Class<?> type = Class.forName(className);
			Method method = type.getMethod(methodName);
			return method.invoke(null);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			// a missing internal class or a renamed method is the expected case off
			// GTK or on another SWT, and the field is simply absent from the answer
			return null;
		}
	}
}
