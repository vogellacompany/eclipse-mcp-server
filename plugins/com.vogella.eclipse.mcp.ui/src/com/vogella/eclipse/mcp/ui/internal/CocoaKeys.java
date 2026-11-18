package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Method;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;

/**
 * Whether a character needs Shift on the current Cocoa keyboard layout. SWT's {@code Display.post} on Cocoa finds the
 * key for a character in the layout but never presses Shift for it, so a posted "!" arrives as "1" unless the caller
 * posts Shift itself. Reaches SWT's Cocoa internals by name, so the bundle stays cross platform.
 */
final class CocoaKeys {

	static final boolean COCOA = "cocoa".equals(SWT.getPlatform()); //$NON-NLS-1$

	private static final int SHIFT_STATE = (1 << 9) >> 8 & 0xFF;

	private CocoaKeys() {
	}

	/**
	 * {@code true} when the layout types {@code character} only with Shift, mirroring the two passes of
	 * {@code Display.post}; for an unreadable layout an upper case letter is taken to need it. UI thread only.
	 */
	static boolean needsShift(char character) {
		try {
			Class<?> os = Class.forName("org.eclipse.swt.internal.cocoa.OS", true, Display.class.getClassLoader()); //$NON-NLS-1$
			Method layoutOf = Display.class.getDeclaredMethod("getCurrentKeyLayout"); //$NON-NLS-1$
			layoutOf.setAccessible(true);
			long layout = ((Long) layoutOf.invoke(null)).longValue();
			if (layout == 0) {
				return fallback(character);
			}
			int keyboard = ((Byte) os.getMethod("LMGetKbdType").invoke(null)).intValue(); //$NON-NLS-1$
			Method translate = os.getMethod("UCKeyTranslate", long.class, short.class, short.class, int.class, int.class, //$NON-NLS-1$
					int.class, int[].class, int.class, long[].class, char[].class);
			if (typedBy(translate, layout, keyboard, 0, character)) {
				return false;
			}
			return typedBy(translate, layout, keyboard, SHIFT_STATE, character);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return fallback(character);
		}
	}

	private static boolean typedBy(Method translate, long layout, int keyboard, int modifiers, char character)
			throws ReflectiveOperationException {
		char[] output = new char[256];
		for (short key = 0; key <= 0x7F; key++) {
			translate.invoke(null, Long.valueOf(layout), Short.valueOf(key), Short.valueOf((short) 0),
					Integer.valueOf(modifiers), Integer.valueOf(keyboard), Integer.valueOf(0), new int[1],
					Integer.valueOf(output.length), new long[1], output);
			if (output[0] == character) {
				return true;
			}
		}
		return false;
	}

	private static boolean fallback(char character) {
		return Character.isUpperCase(character) && Character.toLowerCase(character) != character;
	}
}
