package com.vogella.eclipse.mcp.ui.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;

import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Real pointer input on native GTK Wayland under GNOME, through mutter's
 * {@code org.gnome.Mutter.RemoteDesktop} session on the session bus, called through libgio.
 * <p>
 * A Wayland client cannot warp the pointer or learn where its window is on screen, so the pointer is moved
 * relatively and corrected by what the IDE reads back, which Wayland only reports over this IDE's own windows.
 */
final class MutterRemoteDesktop {

	static final String METHOD = "mutter-remote-desktop"; //$NON-NLS-1$

	private static final String BUS_NAME = "org.gnome.Mutter.RemoteDesktop"; //$NON-NLS-1$

	private static final String SESSION_INTERFACE = "org.gnome.Mutter.RemoteDesktop.Session"; //$NON-NLS-1$

	/** BTN_LEFT from linux/input-event-codes.h; middle and right follow as 274 and 273. */
	private static final int BTN_LEFT = 272;

	private static final int KEY_LEFTSHIFT = 42;

	private static final int MAX_CORRECTIONS = 6;

	private static final long READ_WAIT_MILLIS = 200;

	/** How long an unused session stays open, since GNOME shows a remote access indicator while one runs. */
	private static final long IDLE_SECONDS = 60;

	private static final Object LOCK = new Object();

	private static ScheduledExecutorService idle;

	private static volatile Gio gio;

	private static MemorySegment bus;

	private static String session;

	private static ScheduledFuture<?> idleStop;

	private static long lastUse;

	/** Buttons this session holds down, by SWT button number, so a stuck one can be released. */
	private static int held;

	private MutterRemoteDesktop() {
	}

	/** Whether the IDE runs on native Wayland, where only mutter's session on GNOME delivers real pointer input; elsewhere opening it fails and says so. */
	static boolean applies() {
		return MonitorPlacement.isWayland();
	}

	/** Where the pointer was read, the control under it, and why it is not at the point when it is not. */
	record Arrival(Point pointer, Control under, String failure) {
	}

	/**
	 * Moves the pointer onto a display point and reads it back. Called off the UI thread, since the IDE only
	 * learns the new position once its event loop has dispatched the compositor's motion event.
	 */
	static Arrival moveTo(Point target, JsonObject result) {
		Read read = read();
		if (read.failure() != null) {
			return new Arrival(null, null, read.failure());
		}
		result.put("previousPointer", read.pointer().x + "," + read.pointer().y); //$NON-NLS-1$ //$NON-NLS-2$
		Point at = read.pointer();
		int corrections = 0;
		boolean recentred = false;
		while (!PointerWait.isAt(at, target)) {
			if (corrections++ == MAX_CORRECTIONS) {
				return new Arrival(at, read.under(), "the pointer did not settle on %d,%d after %d relative moves" //$NON-NLS-1$
						.formatted(Integer.valueOf(target.x), Integer.valueOf(target.y), Integer.valueOf(MAX_CORRECTIONS)));
			}
			String failure = motion(target.x - at.x, target.y - at.y);
			if (failure != null) {
				return new Arrival(at, read.under(), failure);
			}
			Read next = readChange(at);
			if (next.failure() != null) {
				return new Arrival(at, null, next.failure());
			}
			if (next.pointer().equals(at)) {
				// Wayland only reports the pointer over this IDE's windows, so the read is stale outside them
				if (recentred) {
					return new Arrival(at, null, "the pointer is outside this IDE's windows, where Wayland does not tell the IDE where it is, and moving it to the screen centre did not bring it over one; make the IDE window cover the centre of the screen, as when maximized. If it already does, the window may not be on screen at all, since a window the compositor never showed gets the pointer nowhere. eclipse_click with delivery synthetic reaches the widget without the pointer"); //$NON-NLS-1$
				}
				recentred = true;
				failure = toScreenCentre();
				if (failure != null) {
					return new Arrival(at, null, failure);
				}
				next = readChange(at);
				if (next.failure() != null) {
					return new Arrival(at, null, next.failure());
				}
				result.put("recentred", Boolean.TRUE); //$NON-NLS-1$
			}
			read = next;
			at = read.pointer();
		}
		result.put("corrections", Integer.valueOf(corrections)); //$NON-NLS-1$
		return new Arrival(at, read.under(), null);
	}

	/** Moves the pointer by a distance in display points, with no read back, as a drag does between its reads. */
	static String motion(double dx, double dy) {
		return call("NotifyPointerMotionRelative", Args.doubles(dx, dy)); //$NON-NLS-1$
	}

	/** Presses or releases an SWT button number. */
	static String button(int button, boolean down) {
		String failure = call("NotifyPointerButton", Args.button(code(button), down)); //$NON-NLS-1$
		if (failure == null) {
			synchronized (LOCK) {
				held = down ? held | 1 << button : held & ~(1 << button);
			}
		}
		return failure;
	}

	/** Whether this session left {@code button} down. */
	static boolean isDown(int button) {
		synchronized (LOCK) {
			return (held & 1 << button) != 0;
		}
	}

	/** Presses and releases a button wherever the pointer is. */
	static String click(int button) {
		String failure = button(button, true);
		if (failure != null) {
			return failure;
		}
		String release = button(button, false);
		// retry once so the button is not left logically down
		return release == null ? null : button(button, false);
	}

	/** Ends the session, which also lets go of anything it holds. */
	static void stop() {
		synchronized (LOCK) {
			if (idleStop != null) {
				idleStop.cancel(false);
				idleStop = null;
			}
			if (session != null) {
				invoke(session, SESSION_INTERFACE, "Stop", MemorySegment.NULL); //$NON-NLS-1$
				session = null;
				held = 0;
			}
		}
	}

	private static int code(int button) {
		return switch (button) {
		case 2 -> BTN_LEFT + 2;
		case 3 -> BTN_LEFT + 1;
		default -> BTN_LEFT;
		};
	}

	/** Pins the pointer to the bottom right corner, which unlike the top left is no hot corner, then moves it to the centre. */
	private static String toScreenCentre() {
		AtomicReference<org.eclipse.swt.graphics.Rectangle> layout = new AtomicReference<>();
		UiThread.Outcome outcome = UiThread.run(5, () -> {
			org.eclipse.swt.graphics.Rectangle union = null;
			for (Monitor monitor : Workbenches.display().getMonitors()) {
				union = union == null ? monitor.getBounds() : union.union(monitor.getBounds());
			}
			layout.set(union);
			return new JsonObject();
		});
		if (outcome.error() != null) {
			return outcome.error();
		}
		org.eclipse.swt.graphics.Rectangle union = layout.get();
		if (union == null) {
			return "the display reports no monitor"; //$NON-NLS-1$
		}
		String failure = motion(100_000, 100_000);
		return failure != null ? failure : motion(-union.width / 2.0, -union.height / 2.0);
	}

	private record Read(Point pointer, Control under, String failure) {
	}

	private static Read read() {
		AtomicReference<Read> read = new AtomicReference<>();
		UiThread.Outcome outcome = UiThread.run(5, () -> {
			Display display = Workbenches.display();
			read.set(new Read(display.getCursorLocation(), display.getCursorControl(), null));
			return new JsonObject();
		});
		return outcome.error() != null ? new Read(null, null, outcome.error()) : read.get();
	}

	/** Reads until the pointer moved away from {@code from}, or the wait ran out. */
	private static Read readChange(Point from) {
		long end = System.currentTimeMillis() + READ_WAIT_MILLIS;
		Read read = read();
		while (read.failure() == null && read.pointer().equals(from) && System.currentTimeMillis() < end) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			read = read();
		}
		return read;
	}

	/** Calls a method on the session, opening one first when there is none. */
	private static String call(String method, Args args) {
		synchronized (LOCK) {
			String failure = ensureSession();
			if (failure != null) {
				return failure;
			}
			failure = invoke(session, SESSION_INTERFACE, method, args);
			if (failure != null && (failure.contains("UnknownObject") || failure.contains("UnknownMethod"))) { //$NON-NLS-1$ //$NON-NLS-2$
				// mutter closed the session
				session = null;
				failure = ensureSession();
				if (failure == null) {
					failure = invoke(session, SESSION_INTERFACE, method, args);
				}
			}
			scheduleIdleStop();
			return failure;
		}
	}

	private static void scheduleIdleStop() {
		if (idleStop != null) {
			idleStop.cancel(false);
		}
		if (idle == null) {
			idle = Executors.newSingleThreadScheduledExecutor(work -> {
				Thread thread = new Thread(work, "MCP RemoteDesktop idle stop"); //$NON-NLS-1$
				thread.setDaemon(true);
				return thread;
			});
		}
		lastUse = System.nanoTime();
		idleStop = idle.schedule(MutterRemoteDesktop::stopWhenIdle, IDLE_SECONDS, TimeUnit.SECONDS);
	}

	/** Stops the session unless a call used it since this stop was scheduled, which cancelling cannot prevent once it runs. */
	private static void stopWhenIdle() {
		synchronized (LOCK) {
			if (System.nanoTime() - lastUse >= TimeUnit.SECONDS.toNanos(IDLE_SECONDS)) {
				stop();
			}
		}
	}

	private static String ensureSession() {
		if (session != null) {
			return null;
		}
		try {
			Gio g = gio();
			if (bus == null) {
				try (Arena arena = Arena.ofConfined()) {
					MemorySegment error = arena.allocate(ADDRESS);
					// G_BUS_TYPE_SESSION
					MemorySegment connection = (MemorySegment) g.busGet().invokeExact(2, MemorySegment.NULL, error);
					if (connection.equals(MemorySegment.NULL)) {
						return "no session bus: " + errorMessage(g, error); //$NON-NLS-1$
					}
					bus = connection;
				}
			}
			AtomicReference<String> path = new AtomicReference<>();
			String failure = invoke("/org/gnome/Mutter/RemoteDesktop", BUS_NAME, "CreateSession", MemorySegment.NULL, //$NON-NLS-1$ //$NON-NLS-2$
					reply -> {
						MemorySegment child = (MemorySegment) g.childValue().invokeExact(reply, 0L);
						MemorySegment chars = (MemorySegment) g.getString().invokeExact(child, MemorySegment.NULL);
						path.set(chars.reinterpret(Long.MAX_VALUE).getString(0));
						g.unref().invokeExact(child);
					});
			if (failure != null) {
				return "mutter refused a RemoteDesktop session, which only GNOME offers; on another Wayland compositor run the IDE on an X11 display without a compositor, such as Xvfb: " + failure; //$NON-NLS-1$
			}
			failure = invoke(path.get(), SESSION_INTERFACE, "Start", MemorySegment.NULL); //$NON-NLS-1$
			if (failure != null) {
				invoke(path.get(), SESSION_INTERFACE, "Stop", MemorySegment.NULL); //$NON-NLS-1$
				return "mutter did not start the RemoteDesktop session: " + failure; //$NON-NLS-1$
			}
			session = path.get();
			// windows get no keyboard focus from a session that never sent a key
			invoke(session, SESSION_INTERFACE, "NotifyKeyboardKeycode", Args.key(KEY_LEFTSHIFT, true)); //$NON-NLS-1$
			invoke(session, SESSION_INTERFACE, "NotifyKeyboardKeycode", Args.key(KEY_LEFTSHIFT, false)); //$NON-NLS-1$
			return null;
		} catch (Throwable e) {
			return "libgio is not usable: " + e; //$NON-NLS-1$
		}
	}

	private interface ReplyReader {
		void read(MemorySegment reply) throws Throwable;
	}

	private static String invoke(String path, String iface, String method, MemorySegment parameters) {
		return invoke(path, iface, method, parameters, null);
	}

	/** One synchronous call to mutter; GDBus serves the connection from its own thread, so any thread may call. */
	private static String invoke(String path, String iface, String method, MemorySegment parameters, ReplyReader reader) {
		try (Arena arena = Arena.ofConfined()) {
			Gio g = gio();
			MemorySegment error = arena.allocate(ADDRESS);
			MemorySegment reply = (MemorySegment) g.call().invokeExact(bus, arena.allocateFrom(BUS_NAME),
					arena.allocateFrom(path), arena.allocateFrom(iface), arena.allocateFrom(method), parameters,
					MemorySegment.NULL, 0, 5000, MemorySegment.NULL, error);
			if (reply.equals(MemorySegment.NULL)) {
				return errorMessage(g, error);
			}
			try {
				if (reader != null) {
					reader.read(reply);
				}
			} finally {
				g.unref().invokeExact(reply);
			}
			return null;
		} catch (Throwable e) {
			return "libgio is not usable: " + e; //$NON-NLS-1$
		}
	}

	private static String errorMessage(Gio g, MemorySegment errorSlot) throws Throwable {
		MemorySegment error = errorSlot.get(ADDRESS, 0);
		if (error.equals(MemorySegment.NULL)) {
			return "unknown D-Bus failure"; //$NON-NLS-1$
		}
		// GError is a guint32 domain, a gint code and the message pointer
		MemorySegment struct = error.reinterpret(16);
		String message = struct.get(ADDRESS, 8).reinterpret(Long.MAX_VALUE).getString(0);
		g.errorFree().invokeExact(error);
		return message;
	}

	/** A GVariant tuple of arguments, built per call because the call consumes its floating reference. */
	private interface Args {
		MemorySegment build() throws Throwable;

		static Args doubles(double a, double b) {
			return () -> {
				Gio g = gio();
				return tuple(g, (MemorySegment) g.newDouble().invokeExact(a), (MemorySegment) g.newDouble().invokeExact(b));
			};
		}

		static Args button(int code, boolean down) {
			return () -> {
				Gio g = gio();
				return tuple(g, (MemorySegment) g.newInt32().invokeExact(code),
						(MemorySegment) g.newBoolean().invokeExact(down ? 1 : 0));
			};
		}

		static Args key(int code, boolean down) {
			return () -> {
				Gio g = gio();
				return tuple(g, (MemorySegment) g.newUint32().invokeExact(code),
						(MemorySegment) g.newBoolean().invokeExact(down ? 1 : 0));
			};
		}

		private static MemorySegment tuple(Gio g, MemorySegment first, MemorySegment second) throws Throwable {
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment children = arena.allocate(ADDRESS, 2);
				children.setAtIndex(ADDRESS, 0, first);
				children.setAtIndex(ADDRESS, 1, second);
				return (MemorySegment) g.newTuple().invokeExact(children, 2L);
			}
		}
	}

	private static String invoke(String path, String iface, String method, Args args) {
		try {
			return invoke(path, iface, method, args.build());
		} catch (Throwable e) {
			return "libgio is not usable: " + e; //$NON-NLS-1$
		}
	}

	/** The libgio and libglib entry points this class uses. */
	private record Gio(MethodHandle busGet, MethodHandle call, MethodHandle newDouble, MethodHandle newInt32,
			MethodHandle newUint32, MethodHandle newBoolean, MethodHandle newTuple, MethodHandle childValue,
			MethodHandle getString, MethodHandle unref, MethodHandle errorFree) {
	}

	private static Gio gio() {
		Gio g = gio;
		if (g == null) {
			Linker linker = Linker.nativeLinker();
			SymbolLookup lib = SymbolLookup.libraryLookup("libgio-2.0.so.0", Arena.global()) //$NON-NLS-1$
					.or(SymbolLookup.libraryLookup("libglib-2.0.so.0", Arena.global())); //$NON-NLS-1$
			g = new Gio(linker.downcallHandle(lib.findOrThrow("g_bus_get_sync"), //$NON-NLS-1$
					FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS)),
					linker.downcallHandle(lib.findOrThrow("g_dbus_connection_call_sync"), //$NON-NLS-1$
							FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
									JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)),
					linker.downcallHandle(lib.findOrThrow("g_variant_new_double"), //$NON-NLS-1$
							FunctionDescriptor.of(ADDRESS, JAVA_DOUBLE)),
					linker.downcallHandle(lib.findOrThrow("g_variant_new_int32"), //$NON-NLS-1$
							FunctionDescriptor.of(ADDRESS, JAVA_INT)),
					linker.downcallHandle(lib.findOrThrow("g_variant_new_uint32"), //$NON-NLS-1$
							FunctionDescriptor.of(ADDRESS, JAVA_INT)),
					linker.downcallHandle(lib.findOrThrow("g_variant_new_boolean"), //$NON-NLS-1$
							FunctionDescriptor.of(ADDRESS, JAVA_INT)),
					linker.downcallHandle(lib.findOrThrow("g_variant_new_tuple"), //$NON-NLS-1$
							FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG)),
					linker.downcallHandle(lib.findOrThrow("g_variant_get_child_value"), //$NON-NLS-1$
							FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG)),
					linker.downcallHandle(lib.findOrThrow("g_variant_get_string"), //$NON-NLS-1$
							FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)),
					linker.downcallHandle(lib.findOrThrow("g_variant_unref"), //$NON-NLS-1$
							FunctionDescriptor.ofVoid(ADDRESS)),
					linker.downcallHandle(lib.findOrThrow("g_error_free"), //$NON-NLS-1$
							FunctionDescriptor.ofVoid(ADDRESS)));
			gio = g;
		}
		return g;
	}
}
