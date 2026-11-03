package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Scrollable;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Slider;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.Widget;

/**
 * Names a widget's SWT style bits.
 * <p>
 * The constants share values across widget classes ({@code READ_ONLY} and
 * {@code PUSH} are one bit), so the names that apply depend on the class.
 */
public final class WidgetStyle {

	private WidgetStyle() {
	}

	/** The names of the style bits set on this widget that its class gives a meaning to. */
	public static List<String> names(Widget widget) {
		int style = widget.getStyle();
		List<String> names = new ArrayList<>();
		meanings(widget, style).forEach((name, bit) -> {
			if ((style & bit.intValue()) != 0) {
				names.add(name);
			}
		});
		return names;
	}

	private static Map<String, Integer> meanings(Widget widget, int style) {
		Map<String, Integer> bits = new LinkedHashMap<>();
		if (widget instanceof ToolItem) {
			add(bits, "PUSH", SWT.PUSH, "CHECK", SWT.CHECK, "RADIO", SWT.RADIO, "SEPARATOR", SWT.SEPARATOR, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					"DROP_DOWN", SWT.DROP_DOWN); //$NON-NLS-1$
			return bits;
		}
		specific(widget, style, bits);
		if (widget instanceof Control) {
			add(bits, "BORDER", SWT.BORDER, "LEFT_TO_RIGHT", SWT.LEFT_TO_RIGHT, "RIGHT_TO_LEFT", SWT.RIGHT_TO_LEFT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		// a search field uses the bits of the scroll bars for its icons
		if (widget instanceof Scrollable && !(widget instanceof Text && (style & SWT.SEARCH) != 0)) {
			add(bits, "H_SCROLL", SWT.H_SCROLL, "V_SCROLL", SWT.V_SCROLL); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (widget instanceof Composite) {
			add(bits, "NO_BACKGROUND", SWT.NO_BACKGROUND, "NO_FOCUS", SWT.NO_FOCUS, //$NON-NLS-1$ //$NON-NLS-2$
					"NO_REDRAW_RESIZE", SWT.NO_REDRAW_RESIZE, "NO_MERGE_PAINTS", SWT.NO_MERGE_PAINTS, //$NON-NLS-1$ //$NON-NLS-2$
					"NO_RADIO_GROUP", SWT.NO_RADIO_GROUP, "EMBEDDED", SWT.EMBEDDED, //$NON-NLS-1$ //$NON-NLS-2$
					"DOUBLE_BUFFERED", SWT.DOUBLE_BUFFERED); //$NON-NLS-1$
		}
		return bits;
	}

	private static void specific(Widget widget, int style, Map<String, Integer> bits) {
		if (widget instanceof Sash || widget instanceof SashForm) {
			add(bits, "HORIZONTAL", SWT.HORIZONTAL, "VERTICAL", SWT.VERTICAL, "SMOOTH", SWT.SMOOTH); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		} else if (widget instanceof ProgressBar) {
			add(bits, "HORIZONTAL", SWT.HORIZONTAL, "VERTICAL", SWT.VERTICAL, "SMOOTH", SWT.SMOOTH, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					"INDETERMINATE", SWT.INDETERMINATE); //$NON-NLS-1$
		} else if (widget instanceof Scale || widget instanceof Slider) {
			add(bits, "HORIZONTAL", SWT.HORIZONTAL, "VERTICAL", SWT.VERTICAL); //$NON-NLS-1$ //$NON-NLS-2$
		} else if (widget instanceof Button) {
			add(bits, "ARROW", SWT.ARROW, "CHECK", SWT.CHECK, "PUSH", SWT.PUSH, "RADIO", SWT.RADIO, "TOGGLE", SWT.TOGGLE, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
					"FLAT", SWT.FLAT, "WRAP", SWT.WRAP, "LEFT", SWT.LEFT, "RIGHT", SWT.RIGHT, "CENTER", SWT.CENTER); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		} else if (widget instanceof Label) {
			add(bits, "SEPARATOR", SWT.SEPARATOR, "HORIZONTAL", SWT.HORIZONTAL, "VERTICAL", SWT.VERTICAL, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					"SHADOW_IN", SWT.SHADOW_IN, "SHADOW_OUT", SWT.SHADOW_OUT, "SHADOW_NONE", SWT.SHADOW_NONE, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					"WRAP", SWT.WRAP, "LEFT", SWT.LEFT, "RIGHT", SWT.RIGHT, "CENTER", SWT.CENTER); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		} else if (widget instanceof Text) {
			add(bits, "SINGLE", SWT.SINGLE, "MULTI", SWT.MULTI, "READ_ONLY", SWT.READ_ONLY, "WRAP", SWT.WRAP, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					"PASSWORD", SWT.PASSWORD, "SEARCH", SWT.SEARCH, "LEFT", SWT.LEFT, "RIGHT", SWT.RIGHT, "CENTER", SWT.CENTER); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			if ((style & SWT.SEARCH) != 0) {
				add(bits, "ICON_SEARCH", SWT.ICON_SEARCH, "ICON_CANCEL", SWT.ICON_CANCEL); //$NON-NLS-1$ //$NON-NLS-2$
			}
		} else if (widget instanceof StyledText) {
			add(bits, "SINGLE", SWT.SINGLE, "MULTI", SWT.MULTI, "READ_ONLY", SWT.READ_ONLY, "WRAP", SWT.WRAP, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					"FULL_SELECTION", SWT.FULL_SELECTION); //$NON-NLS-1$
		} else if (widget instanceof Combo || widget instanceof CCombo) {
			add(bits, "DROP_DOWN", SWT.DROP_DOWN, "READ_ONLY", SWT.READ_ONLY, "SIMPLE", SWT.SIMPLE, "FLAT", SWT.FLAT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		} else if (widget instanceof Table || widget instanceof Tree) {
			add(bits, "SINGLE", SWT.SINGLE, "MULTI", SWT.MULTI, "CHECK", SWT.CHECK, "FULL_SELECTION", SWT.FULL_SELECTION, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					"HIDE_SELECTION", SWT.HIDE_SELECTION, "VIRTUAL", SWT.VIRTUAL, "NO_SCROLL", SWT.NO_SCROLL); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		} else if (widget instanceof org.eclipse.swt.widgets.List) {
			add(bits, "SINGLE", SWT.SINGLE, "MULTI", SWT.MULTI); //$NON-NLS-1$ //$NON-NLS-2$
		} else if (widget instanceof ToolBar) {
			add(bits, "FLAT", SWT.FLAT, "WRAP", SWT.WRAP, "RIGHT", SWT.RIGHT, "HORIZONTAL", SWT.HORIZONTAL, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					"VERTICAL", SWT.VERTICAL, "SHADOW_OUT", SWT.SHADOW_OUT); //$NON-NLS-1$ //$NON-NLS-2$
		} else if (widget instanceof CTabFolder) {
			add(bits, "TOP", SWT.TOP, "BOTTOM", SWT.BOTTOM, "FLAT", SWT.FLAT, "CLOSE", SWT.CLOSE, "SINGLE", SWT.SINGLE, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
					"MULTI", SWT.MULTI); //$NON-NLS-1$
		} else if (widget instanceof TabFolder) {
			add(bits, "TOP", SWT.TOP, "BOTTOM", SWT.BOTTOM); //$NON-NLS-1$ //$NON-NLS-2$
		} else if (widget instanceof Group) {
			add(bits, "SHADOW_IN", SWT.SHADOW_IN, "SHADOW_OUT", SWT.SHADOW_OUT, "SHADOW_NONE", SWT.SHADOW_NONE, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					"SHADOW_ETCHED_IN", SWT.SHADOW_ETCHED_IN, "SHADOW_ETCHED_OUT", SWT.SHADOW_ETCHED_OUT); //$NON-NLS-1$ //$NON-NLS-2$
		} else if (widget instanceof Shell) {
			add(bits, "CLOSE", SWT.CLOSE, "TITLE", SWT.TITLE, "MIN", SWT.MIN, "MAX", SWT.MAX, "RESIZE", SWT.RESIZE, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
					"TOOL", SWT.TOOL, "ON_TOP", SWT.ON_TOP, "NO_TRIM", SWT.NO_TRIM, "SHEET", SWT.SHEET, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					"PRIMARY_MODAL", SWT.PRIMARY_MODAL, "APPLICATION_MODAL", SWT.APPLICATION_MODAL, //$NON-NLS-1$ //$NON-NLS-2$
					"SYSTEM_MODAL", SWT.SYSTEM_MODAL); //$NON-NLS-1$
		}
	}

	private static void add(Map<String, Integer> bits, Object... namesAndBits) {
		for (int i = 0; i < namesAndBits.length; i += 2) {
			bits.put((String) namesAndBits[i], (Integer) namesAndBits[i + 1]);
		}
	}
}
