package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IWorkbenchPart;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the context menu of a part without opening it on screen.
 */
public final class ContextMenuTool implements IMcpTool {

	private static final long UI_TIMEOUT_SECONDS = 15;

	@Override
	public String getName() {
		return "eclipse_get_context_menu"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports what a part's context menu would show for the current selection, with each entry's label, whether it is enabled, and the command behind it. Changes nothing and NOTHING APPEARS ON SCREEN: the menu is built and populated through the same events a right click sends, and torn down again, but it is never made visible, so no native grab is taken. That is the point: a context menu is a native window rather than an SWT shell, so eclipse_screenshot cannot capture it and eclipse_get_widget_tree cannot see it, because the Menu does not exist until something asks for it. This is how to check that a command is where a person would look for it and enabled for what is selected, which is not the same question as eclipse_run_workbench_command enablement: an item can be enabled and contributed to no menu at all. Set the selection first with eclipse_set_selection. Pass path to report one submenu, as in 'Team', which is also the cheap way to avoid building every submenu of a large menu."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "part":       {"type":"string","description":"Part id whose context menu to report, e.g. org.eclipse.ui.navigator.ProjectExplorer. Defaults to the active part. Several open parts share one id, every Java editor above all; the active or a visible one is chosen, and a part's TITLE from eclipse_list_ui_targets names a particular one."},
				    "path":       {"type":"string","description":"Report only this submenu, by label without its mnemonic, e.g. 'Team'. Nested submenus are separated by /, as in 'Team/Advanced'."},
				    "maxDepth":   {"type":"integer","default":2,"minimum":1,"maximum":6,"description":"How many levels of submenu to open. Every level costs a Show event on each submenu, which is what populates it."},
				    "maxResults": {"type":"integer","default":300,"minimum":1,"maximum":2000}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String partId = args.getString("part"); //$NON-NLS-1$
		String path = args.getString("path"); //$NON-NLS-1$
		int maxDepth = args.getInt("maxDepth", 2, 1, 6); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 300, 1, 2000); //$NON-NLS-1$
		return UiThread.call(UI_TIMEOUT_SECONDS, () -> collect(partId, path, maxDepth, maxResults));
	}

	private static JsonObject collect(String partId, String path, int maxDepth, int maxResults) {
		IWorkbenchPart part = SelectionTools.partFor(partId);
		if (part == null) {
			throw new IllegalArgumentException(partId == null
					? "There is no active part. Give 'part'." //$NON-NLS-1$
					: "No open part has the id '%s'. eclipse_list_ui_targets lists the open parts.".formatted(partId)); //$NON-NLS-1$
		}
		Control control = viewerControl(part);
		if (control == null) {
			throw new IllegalStateException(
					"The part '%s' has no control that could carry a context menu.".formatted(part.getSite().getId())); //$NON-NLS-1$
		}
		List<Menu> shown = new ArrayList<>();
		try {
			Menu menu = detect(control);
			if (menu == null) {
				return new JsonObject().put("part", part.getSite().getId()) //$NON-NLS-1$
						.put("found", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", //$NON-NLS-1$
								"The control answered no menu after a MenuDetect, so this part contributes no context menu, or it builds one only for a real mouse event."); //$NON-NLS-1$
			}
			show(menu, shown);
			JsonArray items = new JsonArray();
			// counted at every depth, like total, because the top level array holds
			// only the top level and comparing the two called an untruncated answer
			// truncated whenever a submenu was walked
			int[] total = { 0 };
			int[] emitted = { 0 };
			Menu start = menu;
			String reported = path;
			if (path != null && !path.isBlank()) {
				start = submenu(menu, path, shown);
				if (start == null) {
					return new JsonObject().put("part", part.getSite().getId()) //$NON-NLS-1$
							.put("found", Boolean.FALSE) //$NON-NLS-1$
							.put("path", path) //$NON-NLS-1$
							.put("reason", "No submenu '%s' in this context menu.".formatted(path)) //$NON-NLS-1$ //$NON-NLS-2$
							.put("topLevel", labels(menu)); //$NON-NLS-1$
				}
			}
			walk(start, 0, maxDepth, maxResults, items, total, emitted, shown);
			return new JsonObject().put("part", part.getSite().getId()) //$NON-NLS-1$
					.put("found", Boolean.TRUE) //$NON-NLS-1$
					.put("path", reported) //$NON-NLS-1$
					.put("total", Integer.valueOf(total[0])) //$NON-NLS-1$
					.put("reported", Integer.valueOf(emitted[0])) //$NON-NLS-1$
					.put("truncated", Boolean.valueOf(total[0] > emitted[0])) //$NON-NLS-1$
					.put("items", items) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"Built for the selection the part has now, and taken down again; nothing was shown on screen. An item with no command is a contribution that carries none, a separator or a submenu."); //$NON-NLS-1$
		} finally {
			// every menu that was told to show is told to hide, in reverse, so no
			// listener is left believing its menu is up
			for (int i = shown.size() - 1; i >= 0; i--) {
				hide(shown.get(i));
			}
		}
	}

	/** The control a right click would land on, which is the viewer's, not the part's composite. */
	private static Control viewerControl(IWorkbenchPart part) {
		if (part.getSite().getSelectionProvider() instanceof org.eclipse.jface.viewers.Viewer viewer
				&& viewer.getControl() != null && !viewer.getControl().isDisposed()) {
			return viewer.getControl();
		}
		Control root = ScreenshotTools.Capture.controlOf(part);
		return root == null ? null : withMenu(root, 0);
	}

	/** The first control under this one that has a menu, which is where the contribution went. */
	private static Control withMenu(Control control, int depth) {
		if (control.getMenu() != null) {
			return control;
		}
		if (depth > 6 || !(control instanceof org.eclipse.swt.widgets.Composite composite)) {
			return depth == 0 ? control : null;
		}
		for (Control child : composite.getChildren()) {
			Control found = withMenu(child, depth + 1);
			if (found != null) {
				return found;
			}
		}
		return depth == 0 ? control : null;
	}

	/**
	 * Asks the control for its menu the way a right click does.
	 * <p>
	 * The MenuManager builds the menu when the control reports a MenuDetect, so
	 * without this the control answers the menu it was last given, or none at all.
	 * The event carries the selection's own position, since a viewer decides what
	 * to contribute from where the click landed.
	 */
	private static Menu detect(Control control) {
		Event event = new Event();
		event.type = SWT.MenuDetect;
		event.widget = control;
		Point at = control.toDisplay(1, 1);
		event.x = at.x;
		event.y = at.y;
		control.notifyListeners(SWT.MenuDetect, event);
		return control.getMenu();
	}

	/** Populates a menu, which is what a dynamic contribution such as Team waits for. */
	private static void show(Menu menu, List<Menu> shown) {
		if (menu == null || menu.isDisposed() || shown.contains(menu)) {
			return;
		}
		shown.add(menu);
		Event event = new Event();
		event.type = SWT.Show;
		event.widget = menu;
		menu.notifyListeners(SWT.Show, event);
	}

	private static void hide(Menu menu) {
		if (menu == null || menu.isDisposed()) {
			return;
		}
		try {
			Event event = new Event();
			event.type = SWT.Hide;
			event.widget = menu;
			menu.notifyListeners(SWT.Hide, event);
			// it was never made visible, so this is belt and braces against a
			// contribution that showed it itself
			menu.setVisible(false);
		} catch (RuntimeException e) {
			// a menu disposed by its own listener is nothing to report
		}
	}

	private static Menu submenu(Menu menu, String path, List<Menu> shown) {
		Menu current = menu;
		for (String segment : path.split("/")) { //$NON-NLS-1$
			String wanted = segment.strip();
			Menu next = null;
			for (MenuItem item : current.getItems()) {
				if (label(item).equalsIgnoreCase(wanted) && item.getMenu() != null) {
					next = item.getMenu();
					break;
				}
			}
			if (next == null) {
				return null;
			}
			show(next, shown);
			current = next;
		}
		return current;
	}

	private static void walk(Menu menu, int depth, int maxDepth, int maxResults, JsonArray into, int[] total,
			int[] emitted, List<Menu> shown) {
		for (MenuItem item : menu.getItems()) {
			total[0]++;
			if (emitted[0] >= maxResults) {
				continue;
			}
			boolean separator = (item.getStyle() & SWT.SEPARATOR) != 0;
			boolean check = (item.getStyle() & (SWT.CHECK | SWT.RADIO)) != 0;
			JsonObject entry = new JsonObject().put("label", separator ? null : label(item)) //$NON-NLS-1$
					.put("separator", Boolean.valueOf(separator)) //$NON-NLS-1$
					.put("enabled", Boolean.valueOf(item.isEnabled())) //$NON-NLS-1$
					.put("command", commandOf(item)) //$NON-NLS-1$
					.put("selected", check ? Boolean.valueOf(item.getSelection()) : null) //$NON-NLS-1$
					.put("hasSubmenu", Boolean.valueOf(item.getMenu() != null)); //$NON-NLS-1$
			if (item.getMenu() != null && depth + 1 < maxDepth) {
				show(item.getMenu(), shown);
				JsonArray children = new JsonArray();
				walk(item.getMenu(), depth + 1, maxDepth, maxResults, children, total, emitted, shown);
				entry.put("items", children); //$NON-NLS-1$
			}
			into.add(entry);
			emitted[0]++;
		}
	}

	private static JsonArray labels(Menu menu) {
		JsonArray labels = new JsonArray();
		for (MenuItem item : menu.getItems()) {
			if ((item.getStyle() & SWT.SEPARATOR) == 0) {
				labels.add(label(item));
			}
		}
		return labels;
	}

	/** The label as a person reads it, without the mnemonic marker or the accelerator. */
	private static String label(MenuItem item) {
		String text = item.getText();
		if (text == null) {
			return ""; //$NON-NLS-1$
		}
		int tab = text.indexOf('\t');
		return (tab < 0 ? text : text.substring(0, tab)).replace("&", "").strip(); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * The command an item runs, or {@code null}.
	 * <p>
	 * The contribution behind the widget knows it, and there are two kinds in a
	 * workbench menu: the 3.x {@code CommandContributionItem} and the e4 handled
	 * item, whose class is internal. Both are asked through the shape they share
	 * rather than by importing the internal one.
	 */
	private static String commandOf(MenuItem item) {
		Object data = item.getData();
		if (data == null) {
			return null;
		}
		if (data instanceof org.eclipse.ui.menus.CommandContributionItem contribution
				&& contribution.getCommand() != null) {
			return contribution.getCommand().getId();
		}
		String fromModel = invokeChain(data, "getModel", "getCommand", "getElementId"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		return fromModel != null ? fromModel : invokeChain(data, "getCommand", "getId"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Walks a chain of no argument getters, answering null as soon as one is missing. */
	private static String invokeChain(Object start, String... methods) {
		Object current = start;
		for (String method : methods) {
			if (current == null) {
				return null;
			}
			try {
				current = current.getClass().getMethod(method).invoke(current);
			} catch (ReflectiveOperationException | RuntimeException e) {
				return null;
			}
		}
		return current == null ? null : String.valueOf(current);
	}
}
