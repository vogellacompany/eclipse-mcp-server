package com.vogella.eclipse.mcp.ui.internal;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

import com.vogella.eclipse.mcp.core.json.JsonArray;

/**
 * Marks drawn onto a capture once it is taken, placed in points relative to the capture target.
 * <p>
 * The capture moves them along whenever the image it returns starts elsewhere than the target, and draws them after
 * the downscale. A plain capture has none.
 */
interface Marks {

	/** No marks at all. */
	Marks NONE = new Marks() {

		@Override
		public boolean isEmpty() {
			return true;
		}

		@Override
		public Marks moved(int dx, int dy, Rectangle canvas) {
			return this;
		}

		@Override
		public JsonArray draw(Display display, ImageData pixels, double scale) {
			return new JsonArray();
		}
	};

	/** Asks for no marks. */
	Request NONE_REQUESTED = (display, target) -> NONE;

	boolean isEmpty();

	/** The marks shifted by the offset, for an image that starts elsewhere than the target, clipped to the canvas. */
	Marks moved(int dx, int dy, Rectangle canvas);

	/** Draws the marks into the pixels at the given points to pixels scale, and describes what was drawn. */
	JsonArray draw(Display display, ImageData pixels, double scale);

	/** The marks a call asked for, resolved against the control being captured once it is known. */
	@FunctionalInterface
	interface Request {
		Marks resolve(Display display, Control target);
	}
}
