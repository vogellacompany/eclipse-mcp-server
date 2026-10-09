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
		try (Arena arena = Arena.ofConfined()) {
			Linker linker = Linker.nativeLinker();
			SymbolLookup x11 = SymbolLookup.libraryLookup("libX11.so.6", arena); //$NON-NLS-1$
			SymbolLookup xtst = SymbolLookup.libraryLookup("libXtst.so.6", arena); //$NON-NLS-1$
			MethodHandle open = linker.downcallHandle(x11.findOrThrow("XOpenDisplay"), //$NON-NLS-1$
					FunctionDescriptor.of(ADDRESS, ADDRESS));
			MethodHandle sync = linker.downcallHandle(x11.findOrThrow("XSync"), //$NON-NLS-1$
					FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
			MethodHandle close = linker.downcallHandle(x11.findOrThrow("XCloseDisplay"), //$NON-NLS-1$
					FunctionDescriptor.of(JAVA_INT, ADDRESS));
			MethodHandle fake = linker.downcallHandle(xtst.findOrThrow("XTestFakeButtonEvent"), //$NON-NLS-1$
					FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG));
			MemorySegment connection = (MemorySegment) open.invokeExact(MemorySegment.NULL);
			if (connection.equals(MemorySegment.NULL)) {
				return new Pressed(0, "XOpenDisplay could not connect to " + System.getenv("DISPLAY")); //$NON-NLS-1$ //$NON-NLS-2$
			}
			try {
				int sent = 0;
				while (sent < times) {
					int pressed = (int) fake.invokeExact(connection, button, 1, 0L);
					int released = (int) fake.invokeExact(connection, button, 0, 0L);
					if (pressed == 0 || released == 0) {
						break;
					}
					sent++;
				}
				int synced = (int) sync.invokeExact(connection, 0);
				if (sent < times) {
					return new Pressed(sent, sent == 0 ? "the X server has no XTest extension" //$NON-NLS-1$
							: "the X server took %d of %d presses".formatted(Integer.valueOf(sent), Integer.valueOf(times))); //$NON-NLS-1$
				}
				return new Pressed(sent, synced >= 0 ? null : "XSync failed"); //$NON-NLS-1$
			} finally {
				int ignored = (int) close.invokeExact(connection);
			}
		} catch (Throwable e) {
			return new Pressed(0, "libXtst is not usable: " + e); //$NON-NLS-1$
		}
	}
}
