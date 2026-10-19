package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Decorations;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;

import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Delivers key, traversal and mouse input at SWT level through
 * {@code traverse} and {@code notifyListeners}, for when the window system
 * refuses posted events: an inactive application on Cocoa, a locked screen,
 * Wayland. Listeners see the events; the native widget's own handling, such as
 * a text field inserting a character, does not run.
 */
public final class SyntheticInput {

	static final String NOTE = "Delivered at SWT level (synthetic: true): listeners and traversal saw the events, the native widget's own handling did not run, so a text field does not insert characters this way; use eclipse_set_widget_text for text."; //$NON-NLS-1$

	/** How a tool delivers input. */
	enum Delivery {
		OS, AUTO, SYNTHETIC;

		static Delivery of(String value) {
			if (value == null) {
				return OS;
			}
			return switch (value.toLowerCase(Locale.ROOT)) {
			case "auto" -> AUTO; //$NON-NLS-1$
			case "synthetic" -> SYNTHETIC; //$NON-NLS-1$
			case "os" -> OS; //$NON-NLS-1$
			default -> throw new IllegalArgumentException("Unknown delivery '%s'; use os, auto or synthetic.".formatted(value)); //$NON-NLS-1$
			};
		}
	}

	static final String SCHEMA = "\"delivery\": {\"type\":\"string\",\"enum\":[\"os\",\"auto\",\"synthetic\"],\"default\":\"os\",\"description\":\"os posts real events through the window system. synthetic delivers them at SWT level through traverse and notifyListeners on the target, which works while the IDE is inactive or the screen is locked (Cocoa delivers no posted event to an inactive application), but only listeners see them, not the native widget. auto tries os and falls back to synthetic when the window system refuses. The answer says which path was used under delivery and synthetic.\"}"; //$NON-NLS-1$

	private SyntheticInput() {
	}

	/**
	 * The control a synthetic key goes to: the focus control, else the one the
	 * shell will give focus back to on activation, which is what an inactive
	 * Cocoa application has instead of a focus control.
	 */
	static Control keyTarget(Display display, Shell shell) {
		Control focus = display.getFocusControl();
		if (focus != null && !focus.isDisposed()) {
			return focus;
		}
		Shell candidate = shell != null ? shell : Shells.select(display, null);
		if (candidate == null) {
			return null;
		}
		try {
			Field field = Decorations.class.getDeclaredField("savedFocus"); //$NON-NLS-1$
			field.setAccessible(true);
			Object saved = field.get(candidate);
			if (saved instanceof Control control && !control.isDisposed()) {
				return control;
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// the field is internal; the shell itself is the answer then
		}
		return candidate;
	}

	/** Sends a keystroke to {@code target}, traversing first where the key is a traversal key, and reports it into {@code result}. */
	public static void key(Control target, List<Integer> modifiers, int keyCode, char character, JsonObject result) {
		int state = 0;
		for (Integer modifier : modifiers) {
			state |= modifier.intValue();
		}
		int detail = traversal(keyCode, state);
		boolean traversed = false;
		if (detail != SWT.TRAVERSE_NONE) {
			Event event = keyEvent(keyCode, character, state);
			event.detail = detail;
			event.doit = true;
			traversed = target.traverse(detail, event);
		}
		if (!traversed && !target.isDisposed()) {
			target.notifyListeners(SWT.KeyDown, keyEvent(keyCode, character, state));
			if (!target.isDisposed()) {
				target.notifyListeners(SWT.KeyUp, keyEvent(keyCode, character, state));
			}
		}
		result.put("traversed", detail == SWT.TRAVERSE_NONE ? null : Boolean.valueOf(traversed)); //$NON-NLS-1$
	}

	/** A press and release at {@code local}, in the control's own coordinates, with focus given first as a real click would. */
	public static void click(Control control, Point local, int button, JsonObject result) {
		boolean focused = control.setFocus();
		result.put("focused", Boolean.valueOf(focused)); //$NON-NLS-1$
		control.notifyListeners(SWT.MouseMove, mouseEvent(local, 0));
		if (button == 0 || control.isDisposed()) {
			return;
		}
		control.notifyListeners(SWT.MouseDown, mouseEvent(local, button));
		if (control.isDisposed()) {
			return;
		}
		control.notifyListeners(SWT.MouseUp, mouseEvent(local, button));
		if (button == 3 && !control.isDisposed()) {
			// the event a real right click raises; showing the menu is left to eclipse_get_context_menu
			Event detect = new Event();
			Point onScreen = control.toDisplay(local);
			detect.x = onScreen.x;
			detect.y = onScreen.y;
			detect.detail = SWT.MENU_MOUSE;
			detect.doit = true;
			control.notifyListeners(SWT.MenuDetect, detect);
			result.put("menuDetectDoit", Boolean.valueOf(detect.doit)); //$NON-NLS-1$
		}
	}

	/** The traversal a key would trigger, as SWT's own key handling maps it. */
	public static int traversal(int keyCode, int state) {
		return switch (keyCode) {
		case SWT.TAB -> (state & SWT.SHIFT) != 0 ? SWT.TRAVERSE_TAB_PREVIOUS : SWT.TRAVERSE_TAB_NEXT;
		case SWT.ESC -> SWT.TRAVERSE_ESCAPE;
		case SWT.CR -> SWT.TRAVERSE_RETURN;
		case SWT.PAGE_DOWN -> (state & SWT.CTRL) != 0 ? SWT.TRAVERSE_PAGE_NEXT : SWT.TRAVERSE_NONE;
		case SWT.PAGE_UP -> (state & SWT.CTRL) != 0 ? SWT.TRAVERSE_PAGE_PREVIOUS : SWT.TRAVERSE_NONE;
		default -> SWT.TRAVERSE_NONE;
		};
	}

	private static Event keyEvent(int keyCode, char character, int state) {
		Event event = new Event();
		event.keyCode = keyCode;
		event.character = character != 0 ? character
				: keyCode == SWT.CR || keyCode == SWT.TAB || keyCode == SWT.ESC || keyCode == SWT.BS
						|| keyCode == SWT.DEL || keyCode == ' ' ? (char) keyCode : 0;
		event.stateMask = state;
		event.doit = true;
		return event;
	}

	private static Event mouseEvent(Point local, int button) {
		Event event = new Event();
		event.x = local.x;
		event.y = local.y;
		event.button = button;
		event.count = button == 0 ? 0 : 1;
		return event;
	}
}
