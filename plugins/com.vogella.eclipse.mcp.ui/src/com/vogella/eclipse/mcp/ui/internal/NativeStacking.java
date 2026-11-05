package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

import com.vogella.eclipse.mcp.core.FileLocations;

/** The stacking order of shells on Windows, which {@code Display.getShells} does not follow. */
final class NativeStacking {

	private static final String OS = "org.eclipse.swt.internal.win32.OS"; //$NON-NLS-1$

	/** Stops a walk over a window list that changes under it. */
	private static final int MAX_WINDOWS = 20_000;

	private NativeStacking() {
	}

	/** The shell on top of the others, or the last one where the stacking order cannot be read. */
	static Shell topmost(List<Shell> shells) {
		if (shells.isEmpty()) {
			return null;
		}
		Shell last = shells.getLast();
		if (shells.size() == 1 || !FileLocations.isWindows()) {
			return last;
		}
		try {
			Class<?> os = Class.forName(OS, true, Shell.class.getClassLoader());
			Method getWindow = os.getMethod("GetWindow", long.class, int.class); //$NON-NLS-1$
			Integer above = Integer.valueOf(os.getField("GW_HWNDPREV").getInt(null)); //$NON-NLS-1$
			Field handle = Control.class.getField("handle"); //$NON-NLS-1$
			Map<Long, Shell> byWindow = new HashMap<>();
			for (Shell shell : shells) {
				byWindow.put(Long.valueOf(handle.getLong(shell)), shell);
			}
			Shell top = shells.getFirst();
			long window = handle.getLong(top);
			for (int i = 0; i < MAX_WINDOWS && window != 0; i++) {
				window = (Long) getWindow.invoke(null, Long.valueOf(window), above);
				Shell shell = byWindow.get(Long.valueOf(window));
				if (shell != null) {
					top = shell;
				}
			}
			return top;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return last;
		}
	}
}
