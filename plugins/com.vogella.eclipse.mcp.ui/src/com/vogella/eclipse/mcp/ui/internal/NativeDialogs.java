package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The Windows dialogs SWT has no shell for, such as a file picker or a message box, which hold a handler
 * while their own message loop answers Display probes. Elsewhere this finds nothing.
 */
public final class NativeDialogs {

	private static final String OS = "org.eclipse.swt.internal.win32.OS"; //$NON-NLS-1$

	private static final String DIALOG_CLASS = "#32770"; //$NON-NLS-1$

	private static final String BUTTON_CLASS = "Button"; //$NON-NLS-1$

	private static final int GW_HWNDNEXT = 2;

	private static final int GW_CHILD = 5;

	private static final int GW_ENABLEDPOPUP = 6;

	private static final int WM_CLOSE = 0x10;

	private static final int WM_COMMAND = 0x111;

	private static final int GWL_ID = -12;

	/** A native dialog with its title and its buttons, by label without mnemonics. */
	public record Dialog(long handle, String title, Map<String, Long> buttons) {

		public JsonObject describe() {
			JsonArray labels = new JsonArray();
			buttons.keySet().forEach(labels::add);
			return new JsonObject().put("title", title).put("native", Boolean.TRUE).put("buttons", labels); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
	}

	private NativeDialogs() {
	}

	/** The native dialogs owned by this display's visible shells, on the UI thread. */
	public static List<Dialog> list(Display display) {
		List<Dialog> dialogs = new ArrayList<>();
		if (!FileLocations.isWindows()) {
			return dialogs;
		}
		try {
			Class<?> os = Class.forName(OS, true, Shell.class.getClassLoader());
			Method getWindow = os.getMethod("GetWindow", long.class, int.class); //$NON-NLS-1$
			Set<Long> ownShells = new HashSet<>();
			Shell[] shells = display.getShells();
			for (Shell shell : shells) {
				ownShells.add(Long.valueOf(handleOf(shell)));
			}
			Set<Long> seen = new HashSet<>();
			for (Shell shell : shells) {
				if (!shell.isVisible()) {
					continue;
				}
				long popup = (Long) getWindow.invoke(null, Long.valueOf(handleOf(shell)), Integer.valueOf(GW_ENABLEDPOPUP));
				if (popup != 0 && !ownShells.contains(Long.valueOf(popup)) && seen.add(Long.valueOf(popup))
						&& DIALOG_CLASS.equals(className(os, popup))
						&& Boolean.TRUE.equals(os.getMethod("IsWindowVisible", long.class).invoke(null, Long.valueOf(popup)))) { //$NON-NLS-1$
					dialogs.add(new Dialog(popup, text(os, popup), buttons(os, getWindow, popup)));
				}
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return dialogs;
		}
		return dialogs;
	}

	/** The native dialogs open now that are not among {@code before}, on the UI thread. */
	public static List<Dialog> listNew(Display display, Set<Long> before) {
		return list(display).stream().filter(dialog -> !before.contains(Long.valueOf(dialog.handle()))).toList();
	}

	/** The handles of the native dialogs open now, on the UI thread. */
	public static Set<Long> handles(Display display) {
		Set<Long> handles = new HashSet<>();
		list(display).forEach(dialog -> handles.add(Long.valueOf(dialog.handle())));
		return handles;
	}

	/** Asks the dialog to close, which cancels it, and answers whether the request was posted. */
	public static boolean close(Dialog dialog) {
		return post(dialog.handle(), WM_CLOSE);
	}

	/**
	 * Presses a button of the dialog, and answers whether the request was posted. It sends the command the
	 * button would send, since BM_CLICK does nothing while the dialog is not the active window.
	 */
	public static boolean press(Dialog dialog, long button) {
		try {
			Class<?> os = Class.forName(OS, true, Shell.class.getClassLoader());
			int id = (Integer) os.getMethod("GetWindowLong", long.class, int.class) //$NON-NLS-1$
					.invoke(null, Long.valueOf(button), Integer.valueOf(GWL_ID));
			return post(dialog.handle(), WM_COMMAND, id & 0xFFFF, button);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return false;
		}
	}

	private static boolean post(long window, int message) {
		return post(window, message, 0, 0);
	}

	/** Posted rather than sent, since a sent message would run the dialog's reaction inside this call. */
	private static boolean post(long window, int message, long wParam, long lParam) {
		try {
			Class<?> os = Class.forName(OS, true, Shell.class.getClassLoader());
			return Boolean.TRUE.equals(os.getMethod("PostMessage", long.class, int.class, long.class, long.class) //$NON-NLS-1$
					.invoke(null, Long.valueOf(window), Integer.valueOf(message), Long.valueOf(wParam), Long.valueOf(lParam)));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return false;
		}
	}

	private static Map<String, Long> buttons(Class<?> os, Method getWindow, long dialog) throws ReflectiveOperationException {
		Map<String, Long> buttons = new LinkedHashMap<>();
		long child = (Long) getWindow.invoke(null, Long.valueOf(dialog), Integer.valueOf(GW_CHILD));
		while (child != 0) {
			if (BUTTON_CLASS.equals(className(os, child))) {
				String label = text(os, child).replace("&", "").strip(); //$NON-NLS-1$ //$NON-NLS-2$
				if (!label.isEmpty()) {
					buttons.put(label, Long.valueOf(child));
				}
			}
			child = (Long) getWindow.invoke(null, Long.valueOf(child), Integer.valueOf(GW_HWNDNEXT));
		}
		return buttons;
	}

	private static String className(Class<?> os, long window) throws ReflectiveOperationException {
		char[] name = new char[64];
		int read = (Integer) os.getMethod("GetClassName", long.class, char[].class, int.class) //$NON-NLS-1$
				.invoke(null, Long.valueOf(window), name, Integer.valueOf(name.length));
		return new String(name, 0, Math.max(read, 0));
	}

	private static String text(Class<?> os, long window) throws ReflectiveOperationException {
		char[] title = new char[512];
		int read = (Integer) os.getMethod("GetWindowText", long.class, char[].class, int.class) //$NON-NLS-1$
				.invoke(null, Long.valueOf(window), title, Integer.valueOf(title.length));
		return new String(title, 0, Math.max(read, 0));
	}

	private static long handleOf(Shell shell) throws ReflectiveOperationException {
		return shell.getClass().getField("handle").getLong(shell); //$NON-NLS-1$
	}
}
