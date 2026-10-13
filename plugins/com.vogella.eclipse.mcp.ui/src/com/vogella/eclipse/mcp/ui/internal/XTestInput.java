package com.vogella.eclipse.mcp.ui.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import org.eclipse.swt.SWT;

/**
 * Every call into libXtst, which is how a mouse button reaches a GTK3 widget.
 * <p>
 * GTK3 drops the button events {@code Display.post} queues on GDK, while XTest goes through
 * the X server like a real click. The pointer is still moved through SWT, which scales points to pixels.
 */
final class XTestInput {

	private XTestInput() {
	}

	/** Why XTest cannot be used here, or {@code null} when it can. */
	static String unavailableReason() {
		if (!"gtk".equals(SWT.getPlatform())) { //$NON-NLS-1$
			return "not GTK"; //$NON-NLS-1$
		}
		String display = System.getenv("DISPLAY"); //$NON-NLS-1$
		if (display == null || display.isBlank()) {
			return "no X11 DISPLAY"; //$NON-NLS-1$
		}
		return null;
	}

	/**
	 * Presses and releases a button wherever the pointer is.
	 *
	 * @return {@code null} when the server accepted both events, otherwise why not
	 */
	static String click(int button) {
		return press(button, 1).failure();
	}

	/** How many presses the X server took, and why not all of them when it did not. */
	record Pressed(int sent, String failure) {
	}

	/** Presses and releases a button {@code times} times, which for buttons 4 to 7 is one wheel notch each. */
	static Pressed press(int button, int times) {
		Object outcome = withConnection((x, connection) -> {
			MethodHandle fake = x.fakeButton();
			int sent = 0;
			while (sent < times) {
				int pressed = (int) fake.invokeExact(connection, button, 1, 0L);
				int released = (int) fake.invokeExact(connection, button, 0, 0L);
				if (pressed != 0 && released == 0) {
					// retry once so the button is not left logically down on the server
					released = (int) fake.invokeExact(connection, button, 0, 0L);
				}
				if (pressed == 0 || released == 0) {
					break;
				}
				sent++;
			}
			int synced = (int) x.sync().invokeExact(connection, 0);
			if (sent < times) {
				return new Pressed(sent, sent == 0 ? "the X server has no XTest extension" //$NON-NLS-1$
						: "the X server took %d of %d presses".formatted(Integer.valueOf(sent), Integer.valueOf(times))); //$NON-NLS-1$
			}
			return new Pressed(sent, synced >= 0 ? null : "XSync failed"); //$NON-NLS-1$
		});
		return outcome instanceof Pressed pressed ? pressed : new Pressed(0, (String) outcome);
	}

	/**
	 * Presses or releases one button and leaves it that way, which is what a drag needs.
	 *
	 * @return {@code null} when the server took the event, otherwise why not
	 */
	static String button(int button, boolean down) {
		Object outcome = withConnection((x, connection) -> {
			int sent = (int) x.fakeButton().invokeExact(connection, button, down ? 1 : 0, 0L);
			if (sent == 0 && !down) {
				// retry once so the button is not left logically down on the server
				sent = (int) x.fakeButton().invokeExact(connection, button, 0, 0L);
			}
			int synced = (int) x.sync().invokeExact(connection, 0);
			return sent == 0 ? "the X server has no XTest extension" : synced >= 0 ? null : "XSync failed"; //$NON-NLS-1$ //$NON-NLS-2$
		});
		return outcome instanceof String failure ? failure : null;
	}

	/**
	 * Moves the pointer to a screen pixel through the X server, so a held button shows in the motion's state.
	 *
	 * @return {@code null} when the server took the event, otherwise why not
	 */
	static String move(int x, int y) {
		Object outcome = withConnection((xlib, connection) -> {
			int sent = (int) xlib.fakeMotion().invokeExact(connection, -1, x, y, 0L);
			int synced = (int) xlib.sync().invokeExact(connection, 0);
			return sent == 0 ? "the X server has no XTest extension" : synced >= 0 ? null : "XSync failed"; //$NON-NLS-1$ //$NON-NLS-2$
		});
		return outcome instanceof String failure ? failure : null;
	}

	/** Whether the X server holds {@code button} down, or {@code null} when the pointer cannot be queried. */
	static Boolean isDown(int button) {
		Object outcome = withConnection((x, connection) -> {
			try (Arena arena = Arena.ofConfined()) {
				long root = (long) x.rootWindow().invokeExact(connection);
				MemorySegment windows = arena.allocate(JAVA_LONG, 2);
				MemorySegment ints = arena.allocate(JAVA_INT, 5);
				int same = (int) x.queryPointer().invokeExact(connection, root, windows, windows.asSlice(8),
						ints, ints.asSlice(4), ints.asSlice(8), ints.asSlice(12), ints.asSlice(16));
				// Button1Mask is 1 << 8, the following buttons the bits above it
				return same == 0 ? null : Boolean.valueOf((ints.getAtIndex(JAVA_INT, 4) & (1 << (7 + button))) != 0);
			}
		});
		return outcome instanceof Boolean down ? down : null;
	}

	/** The libX11 and libXtst entry points this class uses. */
	private record X(MethodHandle open, MethodHandle close, MethodHandle sync, MethodHandle fakeButton,
			MethodHandle fakeMotion, MethodHandle rootWindow, MethodHandle queryPointer) {
	}

	private interface XCall {
		Object run(X x, MemorySegment connection) throws Throwable;
	}

	private static volatile X handles;

	/** The entry points, looked up once, since a drag calls in here for every step. */
	private static X handles() {
		X x = handles;
		if (x == null) {
			Linker linker = Linker.nativeLinker();
			SymbolLookup x11 = SymbolLookup.libraryLookup("libX11.so.6", Arena.global()); //$NON-NLS-1$
			SymbolLookup xtst = SymbolLookup.libraryLookup("libXtst.so.6", Arena.global()); //$NON-NLS-1$
			x = new X(linker.downcallHandle(x11.findOrThrow("XOpenDisplay"), //$NON-NLS-1$
					FunctionDescriptor.of(ADDRESS, ADDRESS)),
					linker.downcallHandle(x11.findOrThrow("XCloseDisplay"), //$NON-NLS-1$
							FunctionDescriptor.of(JAVA_INT, ADDRESS)),
					linker.downcallHandle(x11.findOrThrow("XSync"), //$NON-NLS-1$
							FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)),
					linker.downcallHandle(xtst.findOrThrow("XTestFakeButtonEvent"), //$NON-NLS-1$
							FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG)),
					linker.downcallHandle(xtst.findOrThrow("XTestFakeMotionEvent"), //$NON-NLS-1$
							FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG)),
					linker.downcallHandle(x11.findOrThrow("XDefaultRootWindow"), //$NON-NLS-1$
							FunctionDescriptor.of(JAVA_LONG, ADDRESS)),
					linker.downcallHandle(x11.findOrThrow("XQueryPointer"), //$NON-NLS-1$
							FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
									ADDRESS, ADDRESS, ADDRESS)));
			handles = x;
		}
		return x;
	}

	/** Runs {@code call} on a fresh connection to the X server; a String answer is a failure. */
	private static Object withConnection(XCall call) {
		try {
			X x = handles();
			MemorySegment connection = (MemorySegment) x.open().invokeExact(MemorySegment.NULL);
			if (connection.equals(MemorySegment.NULL)) {
				return "XOpenDisplay could not connect to " + System.getenv("DISPLAY"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			try {
				return call.run(x, connection);
			} finally {
				int ignored = (int) x.close().invokeExact(connection);
			}
		} catch (Throwable e) {
			return "libXtst is not usable: " + e; //$NON-NLS-1$
		}
	}
}
