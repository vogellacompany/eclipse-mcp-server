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
 * SWT's {@code Display.post} for a button builds a GdkEventButton and puts it
 * on GDK's queue, and GTK3 drops it: on Xvfb a posted left click on a tab
 * changed nothing while an XTest click at the same point selected it. XTest
 * goes through the X server, so the event arrives the way a real one does.
 * The pointer itself is still moved through SWT, which scales points to pixels.
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
				return "XOpenDisplay could not connect to " + System.getenv("DISPLAY"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			try {
				int pressed = (int) fake.invokeExact(connection, button, 1, 0L);
				int released = (int) fake.invokeExact(connection, button, 0, 0L);
				int synced = (int) sync.invokeExact(connection, 0);
				return pressed != 0 && released != 0 && synced >= 0 ? null
						: "the X server has no XTest extension"; //$NON-NLS-1$
			} finally {
				int ignored = (int) close.invokeExact(connection);
			}
		} catch (Throwable e) {
			return "libXtst is not usable: " + e; //$NON-NLS-1$
		}
	}
}
