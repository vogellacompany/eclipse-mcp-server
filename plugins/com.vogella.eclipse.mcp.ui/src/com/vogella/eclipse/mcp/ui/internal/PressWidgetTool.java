package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Item;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Presses a Button, ToolItem or Tree or Table column header, or selects a Tree or Table row, through the widget's own listeners, without OS input or focus.
 */
public final class PressWidgetTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_press_widget"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Presses a Button, a ToolItem or a column header of a Tree or Table, or selects a row of a Tree or Table, addressed by part or shell plus the path eclipse_get_widget_tree reports (a ToolItem or column as an item path such as 0/i2, listed with includeItems; a row as an r path such as 0/r25, listed with includeRows); pass label to have a stale path refused rather than pressing whatever now sits there. CHANGES WHAT THE IDE DOES, which is whatever the button's listeners do: Apply applies, OK closes a dialog. It sends the Selection event to the widget's own listeners rather than going through the window system, so it works without OS focus, on native Wayland, under a compositing desktop and inside modal dialogs, where eclipse_click and eclipse_press_key refuse; it therefore tests the button's behaviour, not the platform's mouse handling. Pass focus true to give the keyboard focus to the pressed Button, the ToolBar of a ToolItem or the Tree or Table of a row or column header first, for handlers that act on the focus control; the answer reports focusControlBefore, focusControlAfter, focused and shellActive. A push button is pressed. A check box or toggle flips its state first, or takes 'selected' when given. A radio button is selected and, as SWT does for a click, the other radio buttons of its group are deselected and told so; a ToolItem radio group is its run of adjacent radio items. A ROW is selected the way a click selects it: its parents are expanded, it is scrolled into view, the selection of its Tree or Table is set to it and Selection is sent to that widget with the row as event item, which reaches views that have no ISelectionProvider and rows scrolled out of sight where eclipse_click misses; defaultSelection also sends DefaultSelection, what a double click or Enter sends, which usually opens the row. The answer for a row reports selectedItem and selectionHoldsItem. A COLUMN header is pressed by sending Selection to the column, as a click on it does, which is what sorts a JFace viewer; the answer reports sortColumn (its text or null) and sortDirection (up, down or none) of the Tree or Table before and after. A disabled, invisible or separator widget, or a row or column of a disabled or invisible Tree or Table, is refused. The answer reports the selection before and after, how many Selection listeners the widget had, and whether the press disposed the widget or closed its shell (widgetDisposed, shellClosed). A listener that opens a modal dialog keeps the press from returning; the call then answers timedOut, the press is not withdrawn, and the dialog can be handled with eclipse_list_ui_targets and eclipse_dismiss_dialog. A dialog button addressed by its label is also reachable through eclipse_dismiss_dialog."; //$NON-NLS-1$
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
				    "path":           {"type":"string","description":"Widget path from eclipse_get_widget_tree, such as 1, 0/i2 for a ToolItem or a Tree or Table column header, 0/r25 for a Tree or Table row."},
				    "label":          {"type":"string","description":"The label the widget is expected to have, matched case insensitively; the press is refused when it differs, which guards against a path that went stale since eclipse_get_widget_tree. For a row or column it is its text."},
				    "defaultSelection": {"type":"boolean","default":false,"description":"Row only: also send DefaultSelection after Selection, as a double click or Enter does."},
				    "selected":       {"type":"boolean","description":"Check box, toggle or check ToolItem only: the state to set instead of flipping it. Not accepted for a radio button, which a press always selects."},
				    "focus":          {"type":"boolean","default":false,"description":"Call forceFocus on the Button, the ToolBar of a ToolItem or the Tree or Table of a row or column header before pressing, after the refusal checks."},
				    "timeoutSeconds": {"type":"integer","minimum":1,"maximum":25,"default":10,"description":"How long to wait for the listeners, which do not return while one shows a modal dialog."}
				  },
				  "required": ["path"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	private record Request(String partId, String shell, String path, String label, Boolean selected,
			boolean defaultSelection, boolean focus) {
	}

	/** Asks for the keyboard focus to be moved first; {@code root} is what the reported paths are relative to. */
	public record Focus(Control root) {
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
		Request request = new Request(args.getString("part"), Shells.spec(args), path, args.getString("label"), //$NON-NLS-1$
				selected, args.getBoolean("defaultSelection", false), args.getBoolean("focus", false)); //$NON-NLS-1$ //$NON-NLS-2$
		UiThread.TimedOutcome outcome = UiThread.timed(timeout, () -> press(request));
		if (outcome.error() != null) {
			return McpToolResult.error(outcome.error());
		}
		if (outcome.timedOut()) {
			// the request is not withdrawn, so a press still queued behind a busy UI thread happens later
			return McpToolResult.of(new JsonObject().put("timedOut", Boolean.TRUE) //$NON-NLS-1$
					.put("waitedSeconds", Integer.valueOf(timeout)) //$NON-NLS-1$
					.put("note", "No answer within the wait. Either a listener opened a modal dialog and is waiting in it, or the UI thread was busy and the press still runs once it is free. Use eclipse_list_ui_targets to see a dialog and eclipse_dismiss_dialog to answer it.") //$NON-NLS-1$ //$NON-NLS-2$
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
		if (target == null || target.isDisposed()) {
			return refusal("The path '%s' does not resolve under this part or shell.".formatted(request.path())); //$NON-NLS-1$
		}
		if (target instanceof TreeItem || target instanceof TableItem) {
			if (request.selected() != null) {
				return refusal("'selected' applies to a check box, toggle or check ToolItem, and this is a row; a row press always selects it."); //$NON-NLS-1$
			}
			return pressRow((Item) target, request.path(), request.label(), request.defaultSelection(),
					request.focus() ? new Focus(root) : null);
		}
		if (target instanceof TableColumn || target instanceof TreeColumn) {
			if (request.selected() != null || request.defaultSelection()) {
				return refusal("'%s' does not apply to a column header; a press only sends Selection." //$NON-NLS-1$
						.formatted(request.selected() != null ? "selected" : "defaultSelection")); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return pressColumn((Item) target, request.path(), request.label(), request.focus() ? new Focus(root) : null);
		}
		return pressButton(request, target, root);
	}

	/** Presses a Button or ToolItem, first moving the focus to it (its ToolBar for a ToolItem) when asked. */
	public static JsonObject pressButton(Widget target, String path, Focus focus) {
		return pressButton(new Request(null, null, path, null, null, false, focus != null), target,
				focus != null ? focus.root() : null);
	}

	private static JsonObject pressButton(Request request, Widget target, Control root) {
		if (!(target instanceof Button || target instanceof ToolItem)) {
			return refusal("'%s' is a %s, not a Button or ToolItem.%s".formatted(request.path(), //$NON-NLS-1$
					target.getClass().getSimpleName(), target instanceof Tree || target instanceof Table
							? " Address a row as an r path (includeRows) or a column header as an i path (includeItems)." //$NON-NLS-1$
							: target instanceof Control
									? " eclipse_set_widget_text drives text fields and combos." //$NON-NLS-1$
									: "")); //$NON-NLS-1$
		}
		if ((target.getStyle() & SWT.SEPARATOR) != 0) {
			return refusal("'%s' is a separator, which cannot be pressed.".formatted(request.path())); //$NON-NLS-1$
		}
		if (request.defaultSelection()) {
			return refusal("'defaultSelection' applies to a row of a Tree or Table, and this is a %s.".formatted(kind(target))); //$NON-NLS-1$
		}
		if (request.label() != null && !label(target).equalsIgnoreCase(request.label().replace("&", "").trim())) { //$NON-NLS-1$ //$NON-NLS-2$
			return refusal("The %s at '%s' is labelled '%s', not '%s'; the path may have changed since the widget tree was read." //$NON-NLS-1$
					.formatted(kind(target), request.path(), label(target), request.label()));
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
		if (request.focus()) {
			focus(result, owner, root, target instanceof ToolItem);
			if (target.isDisposed()) {
				return focusDisposed(result, shell);
			}
		}
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
			if (target.isDisposed()) {
				return result.put("pressed", Boolean.FALSE).put("widgetDisposed", Boolean.TRUE) //$NON-NLS-1$ //$NON-NLS-2$
						.put("shellClosed", Boolean.valueOf(shell.isDisposed())) //$NON-NLS-1$
						.put("radioDeselected", Integer.valueOf(deselected)) //$NON-NLS-1$
						.put("reason", "A listener of a deselected radio button disposed this one before it could be selected."); //$NON-NLS-1$ //$NON-NLS-2$
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

	/** Sends Selection to a column header, as a click on it does, and reports the sort state around it. */
	public static JsonObject pressColumn(Item column, String path, String label) {
		return pressColumn(column, path, label, null);
	}

	/** As {@link #pressColumn(Item, String, String)}, first moving the focus to the owning Tree or Table when asked. */
	public static JsonObject pressColumn(Item column, String path, String label, Focus focus) {
		if (!(column instanceof TableColumn || column instanceof TreeColumn)) {
			return refusal("'%s' is not a Tree or Table column.".formatted(path)); //$NON-NLS-1$
		}
		Control owner = column instanceof TreeColumn tree ? tree.getParent() : ((TableColumn) column).getParent();
		if (owner.isDisposed() || column.isDisposed()) {
			return refusal("The column at '%s' or its %s is already disposed.".formatted(path, owner.getClass().getSimpleName())); //$NON-NLS-1$
		}
		String text = column.getText();
		String name = owner.getClass().getSimpleName();
		if (!matchesLabel(text, label)) {
			return refusal("The column at '%s' reads '%s', not '%s'; the path may have changed since the widget tree was read." //$NON-NLS-1$
					.formatted(path, text, label));
		}
		if (!owner.isEnabled()) {
			return refusal("The %s holding the column at '%s' is disabled.".formatted(name, path)); //$NON-NLS-1$
		}
		if (!owner.isVisible()) {
			return refusal("The %s holding the column at '%s' is not visible.".formatted(name, path)); //$NON-NLS-1$
		}
		if (!(owner instanceof Tree tree ? tree.getHeaderVisible() : ((Table) owner).getHeaderVisible())) {
			return refusal("The %s holding the column at '%s' has its header hidden, so no click could reach it.".formatted(name, path)); //$NON-NLS-1$
		}
		if ((column instanceof TreeColumn tree ? tree.getWidth() : ((TableColumn) column).getWidth()) == 0) {
			return refusal("The column at '%s' has zero width, so no click could reach it.".formatted(path)); //$NON-NLS-1$
		}
		Shell shell = owner.getShell();
		int listeners = column.getListeners(SWT.Selection).length;
		JsonObject result = new JsonObject().put("widget", column.getClass().getSimpleName()) //$NON-NLS-1$
				.put("label", text.replace("&", "").trim()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("selectionListeners", Integer.valueOf(listeners)); //$NON-NLS-1$
		if (focus != null) {
			focus(result, owner, focus.root(), false);
			if (owner.isDisposed() || column.isDisposed()) {
				return focusDisposed(result, shell);
			}
		}
		putSort(result, "Before", owner); //$NON-NLS-1$
		column.notifyListeners(SWT.Selection, new Event());
		boolean disposed = owner.isDisposed() || column.isDisposed();
		result.put("pressed", Boolean.TRUE).put("widgetDisposed", Boolean.valueOf(disposed)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("shellClosed", Boolean.valueOf(shell.isDisposed())); //$NON-NLS-1$
		if (!owner.isDisposed()) {
			putSort(result, "After", owner); //$NON-NLS-1$
		}
		if (listeners == 0) {
			result.put("note", "The column has no Selection listener, so the press reached nothing; the sorting may hang off a mouse listener instead, which only eclipse_click reaches."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return result;
	}

	/** Whether an item's text matches the expected label, raw as eclipse_get_widget_tree reports it or without mnemonics. */
	private static boolean matchesLabel(String text, String label) {
		if (label == null || label.isBlank()) {
			return true;
		}
		String wanted = label.trim();
		return text.trim().equalsIgnoreCase(wanted)
				|| text.replace("&", "").trim().equalsIgnoreCase(wanted.replace("&", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	private static void putSort(JsonObject result, String suffix, Control owner) {
		Item column = owner instanceof Tree tree ? tree.getSortColumn() : ((Table) owner).getSortColumn();
		int direction = owner instanceof Tree tree ? tree.getSortDirection() : ((Table) owner).getSortDirection();
		boolean live = column != null && !column.isDisposed();
		result.put("sortColumn" + suffix, live ? column.getText().replace("&", "").trim() : null) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("sortDirection" + suffix, direction == SWT.UP ? "up" : direction == SWT.DOWN ? "down" : "none"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
	}

	/** Selects a row and notifies its Tree or Table as SWT does for a click. */
	public static JsonObject pressRow(Item row, String path, String label, boolean defaultSelection) {
		return pressRow(row, path, label, defaultSelection, null);
	}

	/** As {@link #pressRow(Item, String, String, boolean)}, first moving the focus to the owning Tree or Table when asked. */
	public static JsonObject pressRow(Item row, String path, String label, boolean defaultSelection, Focus focus) {
		if (!(row instanceof TreeItem || row instanceof TableItem)) {
			return refusal("'%s' is not a Tree or Table row.".formatted(path)); //$NON-NLS-1$
		}
		Control owner = row instanceof TreeItem tree ? tree.getParent() : ((TableItem) row).getParent();
		if (owner.isDisposed() || row.isDisposed()) {
			return refusal("The row at '%s' or its %s is already disposed.".formatted(path, owner.getClass().getSimpleName())); //$NON-NLS-1$
		}
		String text = row.getText();
		String name = owner.getClass().getSimpleName();
		if (!matchesLabel(text, label)) {
			return refusal("The row at '%s' reads '%s', not '%s'; the path may have changed since the widget tree was read." //$NON-NLS-1$
					.formatted(path, text, label));
		}
		if (!owner.isEnabled()) {
			return refusal("The %s holding the row at '%s' is disabled.".formatted(name, path)); //$NON-NLS-1$
		}
		if (!owner.isVisible()) {
			return refusal("The %s holding the row at '%s' is not visible.".formatted(name, path)); //$NON-NLS-1$
		}
		Shell shell = owner.getShell();
		int listeners = owner.getListeners(SWT.Selection).length;
		JsonObject result = new JsonObject().put("widget", row.getClass().getSimpleName()) //$NON-NLS-1$
				.put("selectedItem", text) //$NON-NLS-1$
				.put("selectionListeners", Integer.valueOf(listeners)); //$NON-NLS-1$
		if (focus != null) {
			focus(result, owner, focus.root(), false);
			if (owner.isDisposed() || row.isDisposed()) {
				return focusDisposed(result, shell);
			}
		}
		if (row instanceof TreeItem item) {
			Tree tree = item.getParent();
			for (TreeItem parent = item.getParentItem(); parent != null; parent = parent.getParentItem()) {
				if (!parent.getExpanded()) {
					Event expand = new Event();
					expand.item = parent;
					// before the flag, as eclipse_expand_row does: a lazy viewer fills the node in this listener
					tree.notifyListeners(SWT.Expand, expand);
					if (tree.isDisposed() || parent.isDisposed()) {
						return vanished(row, text, listeners, shell, path);
					}
					parent.setExpanded(true);
				}
			}
			if (item.isDisposed()) {
				return vanished(row, text, listeners, shell, path);
			}
			tree.showItem(item);
			tree.setSelection(item);
		} else {
			Table table = ((TableItem) row).getParent();
			table.showItem((TableItem) row);
			table.setSelection((TableItem) row);
		}
		Event event = new Event();
		event.item = row;
		owner.notifyListeners(SWT.Selection, event);
		if (defaultSelection && !owner.isDisposed() && !row.isDisposed()) {
			Event open = new Event();
			open.item = row;
			owner.notifyListeners(SWT.DefaultSelection, open);
		}
		boolean disposed = owner.isDisposed() || row.isDisposed();
		result.put("pressed", Boolean.TRUE) //$NON-NLS-1$
				.put("widgetDisposed", Boolean.valueOf(disposed)) //$NON-NLS-1$
				.put("shellClosed", Boolean.valueOf(shell.isDisposed())); //$NON-NLS-1$
		if (!disposed) {
			Widget[] selected = owner instanceof Tree tree ? tree.getSelection() : ((Table) owner).getSelection();
			result.put("selectionHoldsItem", Boolean.valueOf(Arrays.asList(selected).contains(row))); //$NON-NLS-1$
		}
		if (listeners == 0) {
			result.put("note", "The widget has no Selection listener, so the selection reached nothing; the action may hang off a mouse listener instead, which only eclipse_click reaches."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return result;
	}

	/** Forces the focus onto {@code control} and reports the focus control around it, paths relative to {@code root}. */
	private static void focus(JsonObject result, Control control, Control root, boolean viaToolBar) {
		Display display = control.getDisplay();
		JsonObject before = describe(display.getFocusControl(), root);
		boolean focused = control.forceFocus();
		// sampled after the attempt so it belongs to the same moment as focusControlAfter
		boolean active = !control.isDisposed() && control.getShell() == display.getActiveShell()
				&& NativeForeground.isForeground(display);
		result.put("focusControlBefore", before) //$NON-NLS-1$
				.put("focusControlAfter", describe(display.getFocusControl(), root)) //$NON-NLS-1$
				.put("focused", Boolean.valueOf(focused)) //$NON-NLS-1$
				.put("shellActive", Boolean.valueOf(active)); //$NON-NLS-1$
		if (!focused) {
			result.put("focusNote", viaToolBar && active //$NON-NLS-1$
					? "Focus was refused although the IDE is the active window; a ToolBar does not always take the keyboard focus." //$NON-NLS-1$
					: "Focus was refused, most likely because the IDE is not the foreground window; eclipse_set_ide_visibility with visible=true raises it."); //$NON-NLS-1$
		}
	}

	private static JsonObject focusDisposed(JsonObject result, Shell shell) {
		return result.put("pressed", Boolean.FALSE).put("widgetDisposed", Boolean.TRUE) //$NON-NLS-1$ //$NON-NLS-2$
				.put("shellClosed", Boolean.valueOf(shell.isDisposed())) //$NON-NLS-1$
				.put("reason", "A focus listener disposed the widget before it could be pressed."); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static JsonObject describe(Control control, Control root) {
		if (control == null || control.isDisposed()) {
			return null;
		}
		return new JsonObject().put("class", control.getClass().getSimpleName()) //$NON-NLS-1$
				.put("path", WidgetTools.pathOf(root, control)); //$NON-NLS-1$
	}

	private static JsonObject vanished(Item row, String text, int listeners, Shell shell, String path) {
		return new JsonObject().put("widget", row.getClass().getSimpleName()) //$NON-NLS-1$
				.put("selectedItem", text) //$NON-NLS-1$
				.put("selectionListeners", Integer.valueOf(listeners)) //$NON-NLS-1$
				.put("pressed", Boolean.FALSE).put("widgetDisposed", Boolean.TRUE) //$NON-NLS-1$ //$NON-NLS-2$
				.put("shellClosed", Boolean.valueOf(shell.isDisposed())) //$NON-NLS-1$
				.put("reason", "A listener disposed the tree or the row at '%s' while its parents were being expanded.".formatted(path)); //$NON-NLS-1$ //$NON-NLS-2$
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
		return text == null ? "" : text.replace("&", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
