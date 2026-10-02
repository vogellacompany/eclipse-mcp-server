package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Item;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Widget;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Presses a Button or ToolItem through its own listeners, without OS input or focus.
 */
public final class PressWidgetTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_press_widget"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Presses a Button or a ToolItem, addressed by part or shell plus the path eclipse_get_widget_tree reports (a ToolItem as an item path such as 0/i2, listed with includeItems). CHANGES WHAT THE IDE DOES, which is whatever the button's listeners do: Apply applies, OK closes a dialog. It sends the Selection event to the widget's own listeners rather than going through the window system, so it works without OS focus, on native Wayland, under a compositing desktop and inside modal dialogs, where eclipse_click and eclipse_press_key refuse; it therefore tests the button's behaviour, not the platform's mouse handling. A push button is pressed. A check box or toggle flips its state first, or takes 'selected' when given. A radio button is selected and, as SWT does for a click, the other radio buttons of its group are deselected and told so; a ToolItem radio group is its run of adjacent radio items. A disabled or invisible widget is refused. The answer reports the selection before and after, how many Selection listeners the widget had, and whether the press disposed the widget or closed its shell (widgetDisposed, shellClosed). A listener that opens a modal dialog keeps the press from returning; the call then answers timedOut and the dialog can be handled with eclipse_list_ui_targets and eclipse_dismiss_dialog. A dialog button addressed by its label is also reachable through eclipse_dismiss_dialog."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "part":           {"type":"string","description":"Part id the path is rooted in. Use eclipse_list_ui_targets."},
				    "shellTitle":     {"type":"string","description":"Shell to root the path in, by title substring; omit both for the active shell."},
				    "shell":          {"type":"string","description":"Shell independent of title: 'popup', an index from eclipse_list_ui_targets, or its bounds. Wins over shellTitle."},
				    "path":           {"type":"string","description":"Widget path from eclipse_get_widget_tree, such as 1 or 0/i2 for a ToolItem."},
				    "selected":       {"type":"boolean","description":"Check box, toggle or check ToolItem only: the state to set instead of flipping it. A radio is always selected."},
				    "timeoutSeconds": {"type":"integer","minimum":1,"maximum":25,"default":10,"description":"How long to wait for the listeners, which do not return while one shows a modal dialog."}
				  },
				  "required": ["path"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	private record Request(String partId, String shell, String path, Boolean selected) {
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path"); //$NON-NLS-1$
		if (path == null || path.isBlank()) {
			return McpToolResult.error("The argument 'path' is required; eclipse_get_widget_tree reports the paths."); //$NON-NLS-1$
		}
		Boolean selected = args.has("selected") ? Boolean.valueOf(args.getBoolean("selected", false)) : null; //$NON-NLS-1$ //$NON-NLS-2$
		int timeout = args.getInt("timeoutSeconds", 10, 1, 25); //$NON-NLS-1$
		Request request = new Request(args.getString("part"), Shells.spec(args), path, selected); //$NON-NLS-1$
		UiThread.TimedOutcome outcome = UiThread.timed(timeout, () -> press(request));
		if (outcome.error() != null) {
			return McpToolResult.error(outcome.error());
		}
		if (outcome.timedOut()) {
			return McpToolResult.of(new JsonObject().put("pressed", Boolean.TRUE).put("timedOut", Boolean.TRUE) //$NON-NLS-1$ //$NON-NLS-2$
					.put("waitedSeconds", Integer.valueOf(timeout)) //$NON-NLS-1$
					.put("note", "The listeners did not return within the wait, most likely because one opened a modal dialog. Use eclipse_list_ui_targets to see it and eclipse_dismiss_dialog to answer it.") //$NON-NLS-1$ //$NON-NLS-2$
					.toString());
		}
		return McpToolResult.of(outcome.value().toString());
	}

	private static JsonObject press(Request request) {
		Control root = WidgetTools.rootOf(request.partId(), request.shell(), false);
		if (root == null) {
			return refusal("No such part or shell, or the part is not open. Use eclipse_list_ui_targets."); //$NON-NLS-1$
		}
		Widget target = WidgetTools.resolve(root, request.path());
		if (target == null) {
			return refusal("The path '%s' does not resolve under this part or shell.".formatted(request.path())); //$NON-NLS-1$
		}
		if (!(target instanceof Button || target instanceof ToolItem)) {
			return refusal("'%s' is a %s, not a Button or ToolItem.%s".formatted(request.path(), //$NON-NLS-1$
					target.getClass().getSimpleName(), target instanceof Control
							? " eclipse_set_widget_text drives text fields and combos." //$NON-NLS-1$
							: "")); //$NON-NLS-1$
		}
		Control owner = target instanceof ToolItem item ? item.getParent() : (Control) target;
		if (!enabled(target)) {
			return refusal("The %s at '%s' is disabled.".formatted(kind(target), request.path())); //$NON-NLS-1$
		}
		if (!owner.isVisible()) {
			return refusal("The %s at '%s' is not visible.".formatted(kind(target), request.path())); //$NON-NLS-1$
		}
		int style = target.getStyle();
		boolean radio = (style & SWT.RADIO) != 0;
		boolean twoState = (style & (SWT.CHECK | SWT.TOGGLE)) != 0;
		if (request.selected() != null && !twoState) {
			return refusal("'selected' applies to a check box, toggle or check ToolItem, and this is a %s." //$NON-NLS-1$
					.formatted(kind(target)));
		}
		Shell shell = owner.getShell();
		boolean before = selection(target);
		int listeners = target.getListeners(SWT.Selection).length;
		JsonObject result = new JsonObject().put("widget", kind(target)).put("label", label(target)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("selectionListeners", Integer.valueOf(listeners)); //$NON-NLS-1$
		if (radio || twoState) {
			result.put("selectedBefore", Boolean.valueOf(before)); //$NON-NLS-1$
		}
		int deselected = 0;
		if (radio) {
			for (Widget sibling : radioGroup(target)) {
				if (!sibling.isDisposed() && selection(sibling)) {
					select(sibling, false);
					sibling.notifyListeners(SWT.Selection, new Event());
					deselected++;
				}
			}
			select(target, true);
		} else if (twoState) {
			select(target, request.selected() != null ? request.selected().booleanValue() : !before);
		}
		if (!target.isDisposed()) {
			target.notifyListeners(SWT.Selection, new Event());
		}
		if (deselected > 0) {
			result.put("radioDeselected", Integer.valueOf(deselected)); //$NON-NLS-1$
		}
		boolean disposed = target.isDisposed();
		result.put("pressed", Boolean.TRUE).put("widgetDisposed", Boolean.valueOf(disposed)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("shellClosed", Boolean.valueOf(shell.isDisposed())); //$NON-NLS-1$
		if (!disposed && (radio || twoState)) {
			result.put("selectedAfter", Boolean.valueOf(selection(target))); //$NON-NLS-1$
		}
		if (listeners == 0) {
			result.put("note", "The widget has no Selection listener, so the press reached nothing; the action may hang off a mouse listener instead, which only eclipse_click reaches."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return result;
	}

	/** The other radio widgets SWT would deselect for a click on {@code target}. */
	private static List<Widget> radioGroup(Widget target) {
		List<Widget> group = new ArrayList<>();
		if (target instanceof Button button) {
			Composite parent = button.getParent();
			if ((parent.getStyle() & SWT.NO_RADIO_GROUP) != 0) {
				return group;
			}
			for (Control child : parent.getChildren()) {
				if (child != button && child instanceof Button other && (other.getStyle() & SWT.RADIO) != 0) {
					group.add(other);
				}
			}
		} else if (target instanceof ToolItem toolItem) {
			ToolBar bar = toolItem.getParent();
			ToolItem[] items = bar.getItems();
			int at = bar.indexOf(toolItem);
			for (int i = at - 1; i >= 0 && (items[i].getStyle() & SWT.RADIO) != 0; i--) {
				group.add(items[i]);
			}
			for (int i = at + 1; i < items.length && (items[i].getStyle() & SWT.RADIO) != 0; i++) {
				group.add(items[i]);
			}
		}
		return group;
	}

	private static boolean enabled(Widget widget) {
		return widget instanceof ToolItem item ? item.isEnabled() && item.getParent().isEnabled()
				: ((Control) widget).isEnabled();
	}

	private static boolean selection(Widget widget) {
		return widget instanceof ToolItem item ? item.getSelection() : ((Button) widget).getSelection();
	}

	private static void select(Widget widget, boolean selected) {
		if (widget instanceof ToolItem item) {
			item.setSelection(selected);
		} else {
			((Button) widget).setSelection(selected);
		}
	}

	private static String label(Widget widget) {
		String text = widget instanceof Item item ? item.getText() : ((Button) widget).getText();
		if ((text == null || text.isEmpty()) && widget instanceof ToolItem item) {
			text = item.getToolTipText();
		}
		return text == null ? "" : text.replace("&", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private static String kind(Widget widget) {
		int style = widget.getStyle();
		String type = (style & SWT.RADIO) != 0 ? "radio" //$NON-NLS-1$
				: (style & SWT.CHECK) != 0 ? "check" //$NON-NLS-1$
						: (style & SWT.TOGGLE) != 0 ? "toggle" //$NON-NLS-1$
								: (style & SWT.DROP_DOWN) != 0 ? "drop-down" : "push"; //$NON-NLS-1$ //$NON-NLS-2$
		return type + " " + widget.getClass().getSimpleName(); //$NON-NLS-1$
	}

	private static JsonObject refusal(String reason) {
		return new JsonObject().put("pressed", Boolean.FALSE).put("reason", reason); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
