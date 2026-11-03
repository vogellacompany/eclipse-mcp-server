package com.vogella.eclipse.mcp.ui.internal;

import java.util.function.BooleanSupplier;

import org.eclipse.swt.widgets.Control;

/**
 * Focus calls that answer false rather than throw when SWT on win32 fails on a
 * push button its FocusIn listener disposed (see docs/platform-bugs.md).
 */
public final class FocusCalls {

	private FocusCalls() {
	}

	public static boolean force(Control control) {
		return guarded(control, control::forceFocus);
	}

	public static boolean set(Control control) {
		return guarded(control, control::setFocus);
	}

	private static boolean guarded(Control control, BooleanSupplier focus) {
		try {
			return focus.getAsBoolean();
		} catch (NullPointerException e) {
			if (control.isDisposed() && isSwtBug(e)) {
				return false;
			}
			throw e;
		}
	}

	/** Tells SWT's failure from one in the application's own listener, which has to surface. */
	private static boolean isSwtBug(NullPointerException e) {
		StackTraceElement[] stack = e.getStackTrace();
		return stack.length > 1 && "org.eclipse.swt.widgets.Control".equals(stack[0].getClassName()) //$NON-NLS-1$
				&& "menuShell".equals(stack[0].getMethodName()) //$NON-NLS-1$
				&& "org.eclipse.swt.widgets.Button".equals(stack[1].getClassName()) //$NON-NLS-1$
				&& "WM_SETFOCUS".equals(stack[1].getMethodName()); //$NON-NLS-1$
	}
}
