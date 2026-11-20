package com.vogella.eclipse.mcp.ui.internal;

import org.eclipse.swt.graphics.Rectangle;

/**
 * The {@code x,y wxh} form every tool reports bounds in, and that {@link Shells#boundsOf(String)} reads back.
 */
public final class Bounds {

	private Bounds() {
	}

	public static String describe(Rectangle r) {
		return r.x + "," + r.y + " " + r.width + "x" + r.height; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}
}
