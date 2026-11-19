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

	/** SWT's Cocoa internals, looked up once; {@code null} when any of them is missing. */
	private static final Method[] NATIVES = COCOA ? lookUp() : null;

	private CocoaKeys() {
	}

	/** {@code getCurrentKeyLayout}, {@code LMGetKbdType} and {@code UCKeyTranslate}, or {@code null}. */
	private static Method[] lookUp() {
		try {
			Class<?> os = Class.forName("org.eclipse.swt.internal.cocoa.OS", true, Display.class.getClassLoader()); //$NON-NLS-1$
			Method layoutOf = Display.class.getDeclaredMethod("getCurrentKeyLayout"); //$NON-NLS-1$
			layoutOf.setAccessible(true);
			return new Method[] { layoutOf, os.getMethod("LMGetKbdType"), //$NON-NLS-1$
					os.getMethod("UCKeyTranslate", long.class, short.class, short.class, int.class, int.class, //$NON-NLS-1$
							int.class, int[].class, int.class, long[].class, char[].class) };
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	/**
	 * Whether the layout types {@code character} only with Shift, mirroring the two passes of {@code Display.post}:
	 * {@code TRUE} or {@code FALSE} when the layout was read, {@code null} when it could not be, in which case a caller
	 * falls back to {@link #fallback(char)} and says so. UI thread only.
	 */
	static Boolean needsShift(char character) {
		if (NATIVES == null) {
			return null;
		}
		try {
			long layout = ((Long) NATIVES[0].invoke(null)).longValue();
			if (layout == 0) {
				return null;
			}
			int keyboard = ((Byte) NATIVES[1].invoke(null)).intValue();
			if (typedBy(NATIVES[2], layout, keyboard, 0, character)) {
				return Boolean.FALSE;
			}
			return Boolean.valueOf(typedBy(NATIVES[2], layout, keyboard, SHIFT_STATE, character));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
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

	/** The guess for an unreadable layout: an upper case letter needs Shift. */
	static boolean fallback(char character) {
		return Character.isUpperCase(character) && Character.toLowerCase(character) != character;
	}
}
