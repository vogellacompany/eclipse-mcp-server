package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Field;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Shell;

/**
 * Whether the window system has iconified a shell, read from GTK rather than
 * from {@code Shell.getMinimized}.
 * <p>
 * On GTK {@code getMinimized} returns the flag {@code setMinimized} stored, and
 * only a window-state event corrects it, so without a window manager to honour
 * the iconify it reports a minimize that never happened. SWT's GTK internals
 * exist on one window system only and are reached by name.
 */
public final class NativeWindowState {

	private static final String GTK = "org.eclipse.swt.internal.gtk.GTK"; //$NON-NLS-1$
	private static final String GTK3 = "org.eclipse.swt.internal.gtk3.GTK3"; //$NON-NLS-1$
	private static final String GTK4 = "org.eclipse.swt.internal.gtk4.GTK4"; //$NON-NLS-1$
	private static final String GDK = "org.eclipse.swt.internal.gtk.GDK"; //$NON-NLS-1$

	private NativeWindowState() {
	}

	/** Whether GTK reports the shell iconified, or null off GTK or when it cannot be read. */
	public static Boolean minimized(Shell shell) {
		if (!"gtk".equals(SWT.getPlatform())) { //$NON-NLS-1$
			return null;
		}
		try {
			ClassLoader loader = Shell.class.getClassLoader();
			Field handleField = Shell.class.getDeclaredField("shellHandle"); //$NON-NLS-1$
			handleField.setAccessible(true);
			Long handle = Long.valueOf(handleField.getLong(shell));
			Class<?> gdk = Class.forName(GDK, true, loader);
			if (Class.forName(GTK, true, loader).getField("GTK4").getBoolean(null)) { //$NON-NLS-1$
				Class<?> gtk4 = Class.forName(GTK4, true, loader);
				long surface = (Long) gtk4.getMethod("gtk_native_get_surface", long.class).invoke(null, handle); //$NON-NLS-1$
				if (surface == 0) {
					return null;
				}
				int state = (Integer) gtk4.getMethod("gdk_toplevel_get_state", long.class) //$NON-NLS-1$
						.invoke(null, Long.valueOf(surface));
				return Boolean.valueOf((state & gdk.getField("GDK_SURFACE_STATE_MINIMIZED").getInt(null)) != 0); //$NON-NLS-1$
			}
			long window = (Long) Class.forName(GTK3, true, loader).getMethod("gtk_widget_get_window", long.class) //$NON-NLS-1$
					.invoke(null, handle);
			if (window == 0) {
				return null;
			}
			int state = (Integer) gdk.getMethod("gdk_window_get_state", long.class).invoke(null, Long.valueOf(window)); //$NON-NLS-1$
			return Boolean.valueOf((state & gdk.getField("GDK_WINDOW_STATE_ICONIFIED").getInt(null)) != 0); //$NON-NLS-1$
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}
}
