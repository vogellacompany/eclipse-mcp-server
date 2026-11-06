package com.vogella.eclipse.mcp.ui.internal;

import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.vogella.eclipse.mcp.core.FileLocations;

/**
 * Every reference to SWT's win32 internals, which is what makes a raise stick
 * on Windows, and to the GTK activation state that tells a granted raise from
 * a requested one.
 * <p>
 * {@code Shell.forceActive} ends in {@code SetForegroundWindow}, and Windows
 * refuses that to a process that does not already own the foreground: the call
 * returns, the taskbar button flashes, and nothing comes forward. So a raise
 * driven from here reported success while the window in front stayed in front,
 * and a screen read then photographed that instead.
 * <p>
 * The way round it is the one Windows itself documents as the exception:
 * attaching this thread's input queue to the queue of the thread that owns the
 * foreground makes the two count as one input context for the length of the
 * attachment, so the raise is no longer coming from a stranger. Attach, raise,
 * detach. While attached, input-state calls on either thread synchronize on
 * the shared queue, so a foreground thread that is not pumping messages would
 * block this one; a hung window is therefore never attached to, and the cost
 * is the taskbar flash the plain call always produced.
 * <p>
 * {@code org.eclipse.swt.internal.win32.OS} exists on one window system only,
 * so it is reached by name here, the way {@link DisplayScaling} reaches
 * DPIUtil, and {@code AttachThreadInput}, which it no longer has, is called
 * in user32 directly. Anywhere else this costs a reason string rather than a
 * link error.
 */
public final class NativeForeground {

	private static final String OS = "org.eclipse.swt.internal.win32.OS"; //$NON-NLS-1$

	private static final String GTK = "org.eclipse.swt.internal.gtk.GTK"; //$NON-NLS-1$

	private static final int GA_ROOT = 2;

	private NativeForeground() {
	}

	/** Whether this window system has a native raise worth trying. */
	static boolean isSupported() {
		return FileLocations.isWindows();
	}

	/**
	 * Raises the shell past the foreground lock.
	 *
	 * @return {@code null} when the native raise ran, otherwise why it could not,
	 *         which is not the same as the window having come forward: Windows
	 *         can still refuse, and only the caller re-reading the active shell
	 *         knows that
	 */
	static String raise(Shell shell) {
		if (!isSupported()) {
			return "this is not Windows, so there is no foreground lock to work around"; //$NON-NLS-1$
		}
		try {
			Class<?> os = Class.forName(OS, true, Shell.class.getClassLoader());
			long window = handleOf(shell);
			if (window == 0) {
				return "the shell has no native window handle"; //$NON-NLS-1$
			}
			Method setForeground = os.getMethod("SetForegroundWindow", long.class); //$NON-NLS-1$
			long owner = (Long) os.getMethod("GetForegroundWindow").invoke(null); //$NON-NLS-1$
			if (owner == 0 || owner == window) {
				// nobody holds the foreground, or we already do, and attaching to our
				// own input queue is the one call that is documented to fail
				setForeground.invoke(null, Long.valueOf(window));
				return null;
			}
			if (Boolean.TRUE.equals(os.getMethod("IsHungAppWindow", long.class) //$NON-NLS-1$
					.invoke(null, Long.valueOf(owner)))) {
				setForeground.invoke(null, Long.valueOf(window));
				return "the window in front is not responding, and attaching to its input queue would hang this IDE with it"; //$NON-NLS-1$
			}
			int ownerThread = (Integer) os
					.getMethod("GetWindowThreadProcessId", long.class, int[].class) //$NON-NLS-1$
					.invoke(null, Long.valueOf(owner), null);
			int ourThread = (Integer) os.getMethod("GetCurrentThreadId").invoke(null); //$NON-NLS-1$
			boolean foreign = ownerThread != 0 && ownerThread != ourThread;
			boolean attached = false;
			String plainReason = foreign
					? "Windows refused to attach to the foreground thread's input queue, so the raise went out as a plain request" //$NON-NLS-1$
					: null;
			try {
				attached = foreign && attachInput(ourThread, ownerThread, true);
			} catch (RuntimeException | LinkageError e) {
				plainReason = "AttachThreadInput could not be called, so the raise went out as a plain request: " + e; //$NON-NLS-1$
			}
			try {
				setForeground.invoke(null, Long.valueOf(window));
			} finally {
				// a shared input queue left behind would make this IDE's keyboard state
				// follow another process for as long as it lives
				if (attached) {
					attachInput(ourThread, ownerThread, false);
				}
			}
			return attached ? null : plainReason;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return "SWT's win32 internals could not be reached: " + e; //$NON-NLS-1$
		}
	}

	private static volatile MethodHandle attachThreadInput;

	/** Attaches or detaches two threads' input queues, called directly since SWT's {@code OS} no longer has it. */
	public static boolean attachInput(int thread, int toThread, boolean attach) {
		MethodHandle handle = attachThreadInput;
		if (handle == null) {
			handle = Linker.nativeLinker().downcallHandle(
					SymbolLookup.libraryLookup("user32.dll", Arena.global()).findOrThrow("AttachThreadInput"), //$NON-NLS-1$ //$NON-NLS-2$
					FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
			attachThreadInput = handle;
		}
		try {
			return (int) handle.invokeExact(thread, toThread, attach ? 1 : 0) != 0;
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable e) {
			// a downcall declares Throwable but throws nothing checked
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Whether this IDE is the application in front.
	 * <p>
	 * On Windows {@code getActiveShell} reads {@code GetActiveWindow}, which is the
	 * active window of this thread's own input queue, not of the screen: a refused
	 * {@code SetForegroundWindow} can still activate the shell inside the process,
	 * so a raise reported foreground true while another program stayed in front.
	 * {@code GetForegroundWindow} is the screen's answer.
	 * On Cocoa it is {@code NSApplication.isActive}.
	 */
	static boolean isForeground(Display display) {
		Boolean owned = ownsForeground();
		if (owned == null) {
			owned = gtkActivated(display);
		}
		if (owned == null) {
			owned = NativeAppKit.active();
		}
		return owned != null ? owned.booleanValue() : display.getActiveShell() != null;
	}

	/**
	 * On GTK, whether the window system has confirmed that a shell of this display
	 * is active, or null off GTK.
	 * <p>
	 * {@code Shell.bringToTop} sets {@code Display.activeShell} as soon as it has
	 * asked for focus and marks it {@code activePending}, which only a focus-in
	 * event clears, so {@code getActiveShell} after {@code forceActive} reads back
	 * the request. GNOME on Wayland refuses the request to a window the user has
	 * not clicked, and the tools then reported a foreground they did not have.
	 * {@code gtk_window_is_active} follows the compositor's own state.
	 */
	private static Boolean gtkActivated(Display display) {
		if (!"gtk".equals(SWT.getPlatform())) { //$NON-NLS-1$
			return null;
		}
		try {
			Field pending = Display.class.getDeclaredField("activePending"); //$NON-NLS-1$
			pending.setAccessible(true);
			if (display.getActiveShell() != null && !pending.getBoolean(display)) {
				return Boolean.TRUE;
			}
			Method isActive = Class.forName(GTK, true, Shell.class.getClassLoader())
					.getMethod("gtk_window_is_active", long.class); //$NON-NLS-1$
			Field handle = Shell.class.getDeclaredField("shellHandle"); //$NON-NLS-1$
			handle.setAccessible(true);
			for (Shell shell : display.getShells()) {
				if (!shell.isDisposed() && shell.isVisible()
						&& Boolean.TRUE.equals(isActive.invoke(null, Long.valueOf(handle.getLong(shell))))) {
					return Boolean.TRUE;
				}
			}
			return Boolean.FALSE;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** Whether a window of this process holds the foreground, or null where that cannot be asked. */
	private static Boolean ownsForeground() {
		if (!isSupported()) {
			return null;
		}
		try {
			Class<?> os = Class.forName(OS, true, Shell.class.getClassLoader());
			long owner = (Long) os.getMethod("GetForegroundWindow").invoke(null); //$NON-NLS-1$
			if (owner == 0) {
				return Boolean.FALSE;
			}
			int[] process = new int[1];
			os.getMethod("GetWindowThreadProcessId", long.class, int[].class) //$NON-NLS-1$
					.invoke(null, Long.valueOf(owner), process);
			return Boolean.valueOf(process[0] == ProcessHandle.current().pid());
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** The title of another process's top-level window under the pointer, or null where that cannot be told or it has none. */
	static String otherWindowAtPointer() {
		if (!isSupported()) {
			return null;
		}
		try {
			ClassLoader loader = Shell.class.getClassLoader();
			Class<?> os = Class.forName(OS, true, loader);
			Class<?> pointType = Class.forName("org.eclipse.swt.internal.win32.POINT", true, loader); //$NON-NLS-1$
			Object point = pointType.getConstructor().newInstance();
			if (!Boolean.TRUE.equals(os.getMethod("GetCursorPos", pointType).invoke(null, point))) { //$NON-NLS-1$
				return null;
			}
			long window = (Long) os.getMethod("WindowFromPoint", pointType).invoke(null, point); //$NON-NLS-1$
			if (window == 0) {
				return null;
			}
			long root = (Long) os.getMethod("GetAncestor", long.class, int.class) //$NON-NLS-1$
					.invoke(null, Long.valueOf(window), Integer.valueOf(GA_ROOT));
			int[] process = new int[1];
			os.getMethod("GetWindowThreadProcessId", long.class, int[].class) //$NON-NLS-1$
					.invoke(null, Long.valueOf(root), process);
			if (process[0] == ProcessHandle.current().pid()) {
				return null;
			}
			int length = (Integer) os.getMethod("GetWindowTextLength", long.class).invoke(null, Long.valueOf(root)); //$NON-NLS-1$
			if (length <= 0) {
				return null;
			}
			char[] title = new char[length + 1];
			int read = (Integer) os.getMethod("GetWindowText", long.class, char[].class, int.class) //$NON-NLS-1$
					.invoke(null, Long.valueOf(root), title, Integer.valueOf(title.length));
			return read <= 0 ? null : new String(title, 0, read);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	private static long handleOf(Shell shell) throws ReflectiveOperationException {
		Field handle = shell.getClass().getField("handle"); //$NON-NLS-1$
		return handle.getLong(shell);
	}
}
