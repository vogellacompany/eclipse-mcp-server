package com.vogella.eclipse.mcp.ui.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Moves a shell onto a given monitor, on every platform.
 * <p>
 * Setting the bounds is enough everywhere except Wayland, where a client cannot
 * position a toplevel and only {@code xdg_toplevel.set_fullscreen}, which names
 * an output, moves it; GTK 3 exposes that as
 * {@code gtk_window_fullscreen_on_monitor}, which SWT does not bind, so it is
 * called through the foreign function API. On Wayland SWT's own monitor answer
 * follows the position the client asked for, not where the compositor put the
 * window, so arrival is read from the window's scale factor instead.
 * <p>
 * Every wait happens on the calling thread between short UI runnables, so the UI
 * thread is never held for longer than one step.
 */
public final class MonitorPlacement {

	/** Runs one short step on the UI thread and returns when it is done. */
	@FunctionalInterface
	public interface Ui {
		void run(Runnable step);
	}

	private static final String GTK = "org.eclipse.swt.internal.gtk.GTK"; //$NON-NLS-1$

	private static final String OS = "org.eclipse.swt.internal.gtk.OS"; //$NON-NLS-1$

	private final Ui ui;
	private final Shell shell;
	private final int index;
	private Monitor target;
	private boolean wayland;
	private boolean scaleTellsMonitor;
	private final IProgressMonitor monitor;
	private final long budgetEnd = System.currentTimeMillis() + CallBudget.maxWaitSeconds() * 1000L;

	private MonitorPlacement(Ui ui, Shell shell, int index, IProgressMonitor monitor) {
		this.monitor = monitor;
		this.ui = ui;
		this.shell = shell;
		this.index = index;
	}

	/** Moves the shell onto the monitor with this index and reports where it ended up. */
	public static JsonObject move(Ui ui, Shell shell, int index, Integer x, Integer y, Integer width, Integer height,
			Boolean maximized) {
		return move(ui, shell, index, x, y, width, height, maximized, null);
	}

	/** As above, but the waits end early when the monitor is cancelled or the call budget is spent. */
	public static JsonObject move(Ui ui, Shell shell, int index, Integer x, Integer y, Integer width, Integer height,
			Boolean maximized, IProgressMonitor monitor) {
		return new MonitorPlacement(ui, shell, index, monitor).move(x, y, width, height, maximized);
	}

	private JsonObject move(Integer x, Integer y, Integer width, Integer height, Boolean maximized) {
		JsonObject[] refusal = new JsonObject[1];
		Rectangle[] before = new Rectangle[1];
		Rectangle[] size = new Rectangle[1];
		boolean[] wasMaximized = new boolean[1];
		int[] previousMonitor = new int[1];
		ui.run(() -> {
			if (shell.isDisposed()) {
				refusal[0] = new JsonObject().put("changed", Boolean.FALSE).put("shellClosed", Boolean.TRUE); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			Display display = shell.getDisplay();
			Monitor[] monitors = display.getMonitors();
			if (index < 0 || index >= monitors.length) {
				refusal[0] = new JsonObject().put("changed", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "No monitor %d; this display has %d. Use eclipse_get_display_info." //$NON-NLS-1$ //$NON-NLS-2$
								.formatted(Integer.valueOf(index), Integer.valueOf(monitors.length)));
				return;
			}
			target = monitors[index];
			wayland = isWayland();
			Monitor current = shell.getMonitor();
			scaleTellsMonitor = wayland && onlyMonitorAtItsZoom(monitors, target);
			before[0] = shell.getBounds();
			wasMaximized[0] = shell.getMaximized();
			previousMonitor[0] = indexOf(display, current);
			Rectangle area = target.getClientArea();
			int w = width == null ? Math.min(before[0].width, area.width) : width.intValue();
			int h = height == null ? Math.min(before[0].height, area.height) : height.intValue();
			int left = area.x + (x == null ? Math.max(0, (area.width - w) / 2) : x.intValue());
			int top = area.y + (y == null ? Math.max(0, (area.height - h) / 2) : y.intValue());
			size[0] = new Rectangle(left, top, w, h);
			if (wasMaximized[0]) {
				shell.setMaximized(false);
			}
			// on Wayland the position is ignored and only the size takes effect
			shell.setBounds(size[0]);
		});
		if (refusal[0] != null) {
			return refusal[0];
		}
		String placement = "bounds"; //$NON-NLS-1$
		String refused = null;
		// SWT on Wayland reports the requested position as arrived, so the bounds are not trusted there
		if (!alreadyThere()) {
			String[] why = new String[1];
			ui.run(() -> why[0] = fullscreen(true));
			refused = why[0];
			if (refused == null) {
				placement = "fullscreenOnMonitor"; //$NON-NLS-1$
				waitUntil(this::arrived, scaleTellsMonitor ? 2000 : 500);
				ui.run(() -> fullscreen(false));
				waitUntil(this::arrived, 1000);
			}
		}
		JsonObject[] result = new JsonObject[1];
		String used = placement;
		String reason = refused;
		ui.run(() -> {
			if (shell.isDisposed()) {
				result[0] = new JsonObject().put("changed", Boolean.TRUE).put("shellClosed", Boolean.TRUE); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			if (wayland) {
				shell.setSize(size[0].width, size[0].height);
			} else {
				shell.setBounds(size[0]);
			}
			if (maximized == null ? wasMaximized[0] : maximized.booleanValue()) {
				shell.setMaximized(true);
			}
			shell.layout(true, true);
			result[0] = describe(before[0], wasMaximized[0], previousMonitor[0], used, reason);
		});
		return result[0];
	}

	/** On Wayland arrival is read once, since the bounds say nothing there; elsewhere the bounds get a moment. */
	private boolean alreadyThere() {
		if (!wayland) {
			return waitUntil(this::arrived, 1000);
		}
		boolean[] there = new boolean[1];
		ui.run(() -> there[0] = arrived());
		return there[0];
	}

	private JsonObject describe(Rectangle before, boolean wasMaximized, int previousMonitor, String placement,
			String refused) {
		Boolean arrived = arrivedOrUnknown();
		JsonObject result = new JsonObject().put("changed", Boolean.TRUE) //$NON-NLS-1$
				.put("title", shell.getText()) //$NON-NLS-1$
				.put("previousBounds", Overlays.describe(before)) //$NON-NLS-1$
				.put("previousMaximized", Boolean.valueOf(wasMaximized)) //$NON-NLS-1$
				.put("requestedMonitor", Integer.valueOf(index)) //$NON-NLS-1$
				.put("requestedMonitorZoom", Integer.valueOf(target.getZoom())) //$NON-NLS-1$
				.put("placement", placement) //$NON-NLS-1$
				.put("onRequestedMonitor", arrived) //$NON-NLS-1$
				.put("bounds", Overlays.describe(shell.getBounds())) //$NON-NLS-1$
				.put("maximized", Boolean.valueOf(shell.getMaximized())); //$NON-NLS-1$
		Integer scale = scaleFactor();
		if (scale != null) {
			result.put("windowScaleFactor", scale); //$NON-NLS-1$
		}
		Integer deviceZoom = DisplayScaling.deviceZoom();
		if (deviceZoom != null) {
			result.put("deviceZoom", deviceZoom); //$NON-NLS-1$
		}
		if (wayland) {
			result.put("verifiedBy", scaleTellsMonitor ? "windowScaleFactor" : "nothing") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.put("waylandNote", scaleTellsMonitor //$NON-NLS-1$
							? "On Wayland the window's position is not visible to the client, so arrival is read from its scale factor, which follows the output the compositor put it on." //$NON-NLS-1$
							: "On Wayland the window's position is not visible to the client and another monitor shares the target's scale factor, so nothing here can confirm the move: onRequestedMonitor is null, and a screenshot of the target monitor is the check."); //$NON-NLS-1$
		} else {
			result.put("previousMonitor", Integer.valueOf(previousMonitor)) //$NON-NLS-1$
					.put("monitor", Integer.valueOf(indexOf(shell.getDisplay(), shell.getMonitor()))); //$NON-NLS-1$
		}
		if (Boolean.FALSE.equals(arrived)) {
			result.put("reason", refused != null ? refused : "The window system kept the window where it was."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return result.put("note", wayland //$NON-NLS-1$
				? "Pass previousMaximized back to restore the window state; the monitor it came from is not visible to the client on Wayland." //$NON-NLS-1$
				: "Pass the monitor it came from, and previousMaximized, back to put the window as it was."); //$NON-NLS-1$
	}

	/** Polls the condition on the UI thread, sleeping on this one in between. */
	private boolean waitUntil(BooleanSupplier condition, long millis) {
		long deadline = System.currentTimeMillis() + millis;
		boolean[] met = new boolean[1];
		while (true) {
			ui.run(() -> met[0] = condition.getAsBoolean());
			if (met[0] || System.currentTimeMillis() > deadline || System.currentTimeMillis() > budgetEnd
					|| monitor != null && monitor.isCanceled()) {
				return met[0];
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
	}

	private boolean arrived() {
		return Boolean.TRUE.equals(arrivedOrUnknown());
	}

	/** Whether the shell is on the target monitor, or {@code null} where that cannot be told. */
	private Boolean arrivedOrUnknown() {
		if (shell.isDisposed()) {
			return Boolean.FALSE;
		}
		if (!wayland) {
			return Boolean.valueOf(shell.getMonitor().getBounds().equals(target.getBounds()));
		}
		if (!scaleTellsMonitor) {
			return null;
		}
		Integer scale = scaleFactor();
		return scale == null ? null : Boolean.valueOf(scale.intValue() * 100 == target.getZoom());
	}

	/** The GTK scale factor of the shell's window, or {@code null} off GTK. */
	private Integer scaleFactor() {
		if (!"gtk".equals(SWT.getPlatform()) || shell.isDisposed()) { //$NON-NLS-1$
			return null;
		}
		try {
			Object value = Class.forName(GTK, true, Shell.class.getClassLoader())
					.getMethod("gtk_widget_get_scale_factor", long.class) //$NON-NLS-1$
					.invoke(null, Long.valueOf(shellHandle()));
			return value instanceof Integer number ? number : null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	private long shellHandle() throws ReflectiveOperationException {
		Field handle = Shell.class.getDeclaredField("shellHandle"); //$NON-NLS-1$
		handle.setAccessible(true);
		return handle.getLong(shell);
	}

	/**
	 * Fullscreens the shell on the target monitor, or leaves fullscreen again,
	 * which keeps it on that monitor.
	 *
	 * @return {@code null} when the call was made, otherwise why not
	 */
	private String fullscreen(boolean on) {
		if (!"gtk".equals(SWT.getPlatform())) { //$NON-NLS-1$
			return "not GTK, and setting the bounds did not reach the monitor"; //$NON-NLS-1$
		}
		String version = DisplayScaling.gtkVersion();
		if (version == null || !version.startsWith("3.")) { //$NON-NLS-1$
			return "GTK " + version + " has no gtk_window_fullscreen_on_monitor taking an index"; //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (shell.isDisposed()) {
			return "the shell was closed"; //$NON-NLS-1$
		}
		try {
			MemorySegment window = MemorySegment.ofAddress(shellHandle());
			Linker linker = Linker.nativeLinker();
			// the library is already loaded by SWT, so this only finds its symbols
			SymbolLookup gtk = SymbolLookup.libraryLookup("libgtk-3.so.0", Arena.global()); //$NON-NLS-1$
			if (!on) {
				MethodHandle unfullscreen = linker.downcallHandle(gtk.findOrThrow("gtk_window_unfullscreen"), //$NON-NLS-1$
						FunctionDescriptor.ofVoid(ADDRESS));
				unfullscreen.invokeExact(window);
				return null;
			}
			MethodHandle screenOf = linker.downcallHandle(gtk.findOrThrow("gtk_window_get_screen"), //$NON-NLS-1$
					FunctionDescriptor.of(ADDRESS, ADDRESS));
			MethodHandle fullscreen = linker.downcallHandle(gtk.findOrThrow("gtk_window_fullscreen_on_monitor"), //$NON-NLS-1$
					FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
			MemorySegment screen = (MemorySegment) screenOf.invokeExact(window);
			fullscreen.invokeExact(window, screen, index);
			return null;
		} catch (Throwable e) {
			return "gtk_window_fullscreen_on_monitor is not usable: " + e; //$NON-NLS-1$
		}
	}

	private static boolean isWayland() {
		if (!"gtk".equals(SWT.getPlatform())) { //$NON-NLS-1$
			return false;
		}
		try {
			Object value = Class.forName(OS, true, Shell.class.getClassLoader()).getMethod("isWayland").invoke(null); //$NON-NLS-1$
			return Boolean.TRUE.equals(value);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return false;
		}
	}

	private static boolean onlyMonitorAtItsZoom(Monitor[] monitors, Monitor target) {
		for (Monitor other : monitors) {
			if (other != target && other.getZoom() == target.getZoom()) {
				return false;
			}
		}
		return true;
	}

	/** Index of the monitor in {@link Display#getMonitors()}, or -1. */
	static int indexOf(Display display, Monitor monitor) {
		Monitor[] all = display.getMonitors();
		for (int i = 0; i < all.length; i++) {
			if (all[i].getBounds().equals(monitor.getBounds())) {
				return i;
			}
		}
		return -1;
	}
}
