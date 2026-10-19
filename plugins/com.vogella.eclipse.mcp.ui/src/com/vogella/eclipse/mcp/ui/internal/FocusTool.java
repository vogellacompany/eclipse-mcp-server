package com.vogella.eclipse.mcp.ui.internal;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports where the keyboard focus is, without sending any input.
 */
public final class FocusTool implements IMcpTool {

	private static final int MAX_TEXT = 200;

	@Override
	public String getName() {
		return "eclipse_get_focus"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports the keyboard focus without sending any input: the focus control with its class, text, message hint, the text of the Label just before it, its path from its shell as eclipse_get_widget_tree prints it, bounds, boundsInDisplay and nativeHandle; the active shell with title, index as eclipse_list_ui_targets numbers it and bounds; whether the IDE owns the foreground; and focusCues, whether the window system currently draws keyboard focus indicators. Read-only. Use it to assert that a dialog put the focus where it should, for example on the Preferences filter field, where posting input is unavailable or would change the answer. focusCues is read from WM_QUERYUISTATE on Windows and is null elsewhere, since GTK and Cocoa offer SWT no equivalent."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {},
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (!Workbenches.running()) {
			return McpToolResult.error("There is no running workbench."); //$NON-NLS-1$
		}
		Display display = Workbenches.display();
		return UiThread.call(10, () -> describe(display));
	}

	static JsonObject describe(Display display) {
		Control focus = display.getFocusControl();
		Shell active = display.getActiveShell();
		return new JsonObject().put("focusControl", focus == null || focus.isDisposed() ? null : control(focus)) //$NON-NLS-1$
				.put("activeShell", active == null ? null : shell(display, active)) //$NON-NLS-1$
				.put("foreground", Boolean.valueOf(NativeForeground.isForeground(display))) //$NON-NLS-1$
				.put("focusCues", focusCues(focus != null ? focus.getShell() : active)); //$NON-NLS-1$
	}

	private static JsonObject control(Control focus) {
		Shell shell = focus.getShell();
		JsonObject json = new JsonObject().put("class", focus.getClass().getName()) //$NON-NLS-1$
				.put("text", clip(textOf(focus))) //$NON-NLS-1$
				.put("message", focus instanceof org.eclipse.swt.widgets.Text text ? blankToNull(text.getMessage()) : null) //$NON-NLS-1$
				.put("label", labelOf(focus)) //$NON-NLS-1$
				.put("toolTip", blankToNull(focus.getToolTipText())) //$NON-NLS-1$
				.put("path", WidgetTools.pathOf(shell, focus)) //$NON-NLS-1$
				.put("shellTitle", shell.getText()) //$NON-NLS-1$
				.put("bounds", Overlays.describe(focus.getBounds())) //$NON-NLS-1$
				.put("boundsInDisplay", Overlays.describe( //$NON-NLS-1$
						focus.getDisplay().map(focus.getParent(), null, focus.getBounds())))
				.put("nativeHandle", ControlMetrics.hasNativeHandle(focus)); //$NON-NLS-1$
		return json;
	}

	private static JsonObject shell(Display display, Shell shell) {
		List<Shell> shells = Shells.ordered(display);
		return new JsonObject().put("title", shell.getText()) //$NON-NLS-1$
				.put("index", Integer.valueOf(shells.indexOf(shell))) //$NON-NLS-1$
				.put("bounds", Overlays.describe(shell.getBounds())) //$NON-NLS-1$
				.put("modal", Boolean.valueOf((shell.getStyle() //$NON-NLS-1$
						& (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL)) != 0));
	}

	private static String textOf(Control control) {
		return switch (control) {
		case org.eclipse.swt.widgets.Text field -> field.getText();
		case org.eclipse.swt.custom.StyledText field -> field.getText();
		case org.eclipse.swt.widgets.Combo combo -> combo.getText();
		case org.eclipse.swt.widgets.Button button -> button.getText();
		case org.eclipse.swt.widgets.Link link -> link.getText();
		default -> null;
		};
	}

	/** The text of the nearest Label before the control among its siblings, which is how a form names a field. */
	private static String labelOf(Control control) {
		if (control.getParent() == null) {
			return null;
		}
		Control[] siblings = control.getParent().getChildren();
		for (int i = List.of(siblings).indexOf(control) - 1; i >= 0; i--) {
			if (siblings[i] instanceof Label label && !label.getText().isBlank()) {
				return label.getText().replace("&", "").strip(); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return null;
	}

	/** Whether Windows draws focus rectangles in this shell right now; null off Windows or when unreadable. */
	static Boolean focusCues(Shell shell) {
		if (shell == null || !"win32".equals(SWT.getPlatform())) { //$NON-NLS-1$
			return null;
		}
		try {
			long handle = shell.getClass().getField("handle").getLong(shell); //$NON-NLS-1$
			Class<?> os = Class.forName("org.eclipse.swt.internal.win32.OS", true, Display.class.getClassLoader()); //$NON-NLS-1$
			int query = os.getField("WM_QUERYUISTATE").getInt(null); //$NON-NLS-1$
			int hide = os.getField("UISF_HIDEFOCUS").getInt(null); //$NON-NLS-1$
			long state = ((Long) os.getMethod("SendMessage", long.class, int.class, long.class, long.class) //$NON-NLS-1$
					.invoke(null, Long.valueOf(handle), Integer.valueOf(query), Long.valueOf(0), Long.valueOf(0)))
					.longValue();
			return Boolean.valueOf((state & hide) == 0);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	private static String clip(String text) {
		String value = blankToNull(text);
		return value == null || value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT) + "..."; //$NON-NLS-1$
	}

	private static String blankToNull(String text) {
		return text == null || text.isEmpty() ? null : text;
	}
}
