package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Selects an entry of the menu bar or of a part's context menu by its labels.
 * <p>
 * A native menu is not an SWT control, so the pointer cannot be checked against
 * it and {@code eclipse_click} refuses there, and on Windows it runs a modal
 * loop of its own. Driving the SWT model instead sends the same events a person
 * causes, Show to populate each menu on the way and Selection on the item,
 * without the menu ever appearing, so neither the pointer nor the window system
 * is involved.
 */
public final class SelectMenuItemTool implements IMcpTool {

	private static final long UI_TIMEOUT_SECONDS = 15;

	@Override
	public String getName() {
		return "eclipse_select_menu_item"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Selects an entry of the main menu bar or of a part's context menu by its labels, e.g. 'File/New/Project...'. CHANGES WHAT THE IDE DOES, which is whatever that menu entry does, including opening a dialog. The menus are driven through SWT rather than the screen: each menu on the path is populated with the Show event a real opening sends, and the entry receives the Selection event a real pick sends, so the same handlers run while no native menu appears, the pointer does not move and the IDE need not be in front. This is the way through native menus, which eclipse_click refuses because a menu is no SWT control. The selection is dispatched after this call returns, so an entry that opens a modal dialog cannot hold the call; check the effect with eclipse_list_ui_targets, and answer a dialog with eclipse_dismiss_dialog. A check or radio entry is toggled first, as a real pick does. A disabled entry, a separator and a submenu are refused, and a path that does not resolve is answered with the labels that exist at that level. dryRun true resolves the path and reports the entry, or lists a menu's entries when the path names a submenu or is empty, and selects nothing. For a context menu set the selection first with eclipse_set_selection; eclipse_get_context_menu reports a whole context menu with commands."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "path":   {"type":"string","description":"Labels from the top, separated by /, without mnemonics or accelerators, matched case insensitively, e.g. 'File/New/Project...' or 'Team/Pull'. A trailing '...' may be left off. Empty with dryRun lists the top level."},
				    "part":   {"type":"string","description":"Use this part's context menu instead of the menu bar, by part id from eclipse_list_ui_targets. Several parts with one id: id@editor input path or title."},
				    "contextMenu": {"type":"boolean","default":false,"description":"Use the active part's context menu instead of the menu bar. Implied by 'part'."},
				    "dryRun": {"type":"boolean","default":false,"description":"Resolve and report without selecting anything."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path", ""); //$NON-NLS-1$ //$NON-NLS-2$
		String partId = args.getString("part"); //$NON-NLS-1$
		boolean context = partId != null || args.getBoolean("contextMenu", false); //$NON-NLS-1$
		boolean dryRun = args.getBoolean("dryRun", false); //$NON-NLS-1$
		if (path.isBlank() && !dryRun) {
			return McpToolResult.error("Give the 'path' of the entry to select, e.g. 'File/New/Project...', or pass dryRun true to list the top level."); //$NON-NLS-1$
		}
		return UiThread.call(UI_TIMEOUT_SECONDS, () -> select(path, partId, context, dryRun));
	}

	private static JsonObject select(String path, String partId, boolean context, boolean dryRun) {
		List<Menu> shown = new ArrayList<>();
		JsonObject result = new JsonObject().put("path", path) //$NON-NLS-1$
				.put("menu", context ? "context" : "menuBar"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		MenuItem chosen = null;
		try {
			Menu current = context ? contextMenu(partId, result) : menuBar();
			if (current == null) {
				return result.put("selected", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", context //$NON-NLS-1$
								? "The part answered no context menu, or there is no such part. eclipse_list_ui_targets lists the open parts." //$NON-NLS-1$
								: "No workbench window has a menu bar."); //$NON-NLS-1$
			}
			if (context) {
				ContextMenuTool.show(current, shown);
			}
			String[] segments = path.isBlank() ? new String[0] : path.split("/"); //$NON-NLS-1$
			for (int i = 0; i < segments.length; i++) {
				MenuItem item = find(current, segments[i].strip());
				if (item == null) {
					return result.put("selected", Boolean.FALSE) //$NON-NLS-1$
							.put("reason", "No entry '%s' under '%s'.".formatted(segments[i].strip(), //$NON-NLS-1$
									i == 0 ? "the top level" : String.join("/", List.of(segments).subList(0, i)))) //$NON-NLS-1$ //$NON-NLS-2$
							.put("available", entries(current)); //$NON-NLS-1$
				}
				boolean last = i == segments.length - 1;
				if (!last) {
					if (item.getMenu() == null) {
						return result.put("selected", Boolean.FALSE) //$NON-NLS-1$
								.put("reason", "'%s' is an entry, not a submenu, so the path cannot go past it." //$NON-NLS-1$
										.formatted(ContextMenuTool.label(item)));
					}
					current = item.getMenu();
					ContextMenuTool.show(current, shown);
					continue;
				}
				result.put("entry", describe(item)); //$NON-NLS-1$
				if (item.getMenu() != null) {
					ContextMenuTool.show(item.getMenu(), shown);
					result.put("available", entries(item.getMenu())); //$NON-NLS-1$
					if (dryRun) {
						return result.put("selected", Boolean.FALSE).put("dryRun", Boolean.TRUE); //$NON-NLS-1$ //$NON-NLS-2$
					}
					return result.put("selected", Boolean.FALSE) //$NON-NLS-1$
							.put("reason", "'%s' is a submenu; add the entry under it to the path.".formatted(ContextMenuTool.label(item))); //$NON-NLS-1$
				}
				chosen = item;
			}
			if (chosen == null) {
				// an empty path with dryRun: the top level
				return result.put("selected", Boolean.FALSE).put("dryRun", Boolean.TRUE) //$NON-NLS-1$ //$NON-NLS-2$
						.put("available", entries(current)); //$NON-NLS-1$
			}
			if ((chosen.getStyle() & SWT.SEPARATOR) != 0) {
				chosen = null;
				return result.put("selected", Boolean.FALSE).put("reason", "That is a separator."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			if (!chosen.isEnabled()) {
				String label = ContextMenuTool.label(chosen);
				chosen = null;
				return result.put("selected", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "'%s' is disabled for the current state, so a person could not pick it either." //$NON-NLS-1$
								.formatted(label));
			}
			if (dryRun) {
				chosen = null;
				return result.put("selected", Boolean.FALSE).put("dryRun", Boolean.TRUE) //$NON-NLS-1$ //$NON-NLS-2$
						.put("note", "Resolved and enabled; nothing was selected."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			toggle(chosen);
			result.put("selected", Boolean.TRUE) //$NON-NLS-1$
					.put("checkedAfter", (chosen.getStyle() & (SWT.CHECK | SWT.RADIO)) != 0 //$NON-NLS-1$
							? Boolean.valueOf(chosen.getSelection())
							: null)
					.put("note", //$NON-NLS-1$
							"The selection is dispatched once this call returns. Verify what it did with eclipse_list_ui_targets or eclipse_screenshot; a dialog it opened is answered with eclipse_dismiss_dialog."); //$NON-NLS-1$
			dispatch(chosen, shown);
			return result;
		} finally {
			// the selection hides its menus itself, after it has run
			if (chosen == null) {
				hideAll(shown);
			}
		}
	}

	private static Menu menuBar() {
		if (!Workbenches.ide()) {
			Shell shell = Workbenches.activeWindowShell();
			return shell == null ? null : shell.getMenuBar();
		}
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window == null && PlatformUI.getWorkbench().getWorkbenchWindows().length > 0) {
			// no active window is what an IDE that is not in front has
			window = PlatformUI.getWorkbench().getWorkbenchWindows()[0];
		}
		return window == null ? null : window.getShell().getMenuBar();
	}

	private static Menu contextMenu(String partId, JsonObject result) {
		if (!Workbenches.ide()) {
			Control control = partId == null ? null : Workbenches.e4Part(partId);
			if (control == null) {
				return null;
			}
			result.put("part", partId); //$NON-NLS-1$
			return firstMenu(control);
		}
		IWorkbenchPart part = SelectionTools.partFor(partId);
		if (part == null) {
			return null;
		}
		result.put("part", part.getSite().getId()); //$NON-NLS-1$
		Control control = ContextMenuTool.viewerControl(part);
		return control == null ? null : ContextMenuTool.detect(control);
	}

	/** An E4 part registers its context menu on a control inside it, often the viewer's, not on the part itself. */
	private static Menu firstMenu(Control control) {
		Menu menu = ContextMenuTool.detect(control);
		if (menu != null) {
			return menu;
		}
		if (control instanceof org.eclipse.swt.widgets.Composite composite) {
			for (Control child : composite.getChildren()) {
				menu = firstMenu(child);
				if (menu != null) {
					return menu;
				}
			}
		}
		return null;
	}

	private static MenuItem find(Menu menu, String wanted) {
		String bare = withoutEllipsis(wanted);
		for (MenuItem item : menu.getItems()) {
			if ((item.getStyle() & SWT.SEPARATOR) == 0
					&& withoutEllipsis(ContextMenuTool.label(item)).equalsIgnoreCase(bare)) {
				return item;
			}
		}
		return null;
	}

	private static String withoutEllipsis(String label) {
		String stripped = label.strip();
		if (stripped.endsWith("...")) { //$NON-NLS-1$
			return stripped.substring(0, stripped.length() - 3).strip();
		}
		return stripped.endsWith("…") ? stripped.substring(0, stripped.length() - 1).strip() : stripped; //$NON-NLS-1$
	}

	private static JsonArray entries(Menu menu) {
		JsonArray entries = new JsonArray();
		for (MenuItem item : menu.getItems()) {
			if ((item.getStyle() & SWT.SEPARATOR) == 0) {
				entries.add(describe(item));
			}
		}
		return entries;
	}

	private static JsonObject describe(MenuItem item) {
		boolean check = (item.getStyle() & (SWT.CHECK | SWT.RADIO)) != 0;
		return new JsonObject().put("label", ContextMenuTool.label(item)) //$NON-NLS-1$
				.put("enabled", Boolean.valueOf(item.isEnabled())) //$NON-NLS-1$
				.put("command", ContextMenuTool.commandOf(item)) //$NON-NLS-1$
				.put("checked", check ? Boolean.valueOf(item.getSelection()) : null) //$NON-NLS-1$
				.put("hasSubmenu", Boolean.valueOf(item.getMenu() != null)); //$NON-NLS-1$
	}

	/**
	 * What the window system does to a check or radio entry before it reports the
	 * pick: a check flips, and a radio is set while the rest of its run of radio
	 * entries is cleared without an event of their own, as SWT's win32 selectRadio
	 * does.
	 */
	private static void toggle(MenuItem item) {
		if ((item.getStyle() & SWT.CHECK) != 0) {
			item.setSelection(!item.getSelection());
		} else if ((item.getStyle() & SWT.RADIO) != 0) {
			Menu parent = item.getParent();
			if ((parent.getStyle() & SWT.NO_RADIO_GROUP) == 0) {
				MenuItem[] items = parent.getItems();
				int index = parent.indexOf(item);
				for (int i = index - 1; i >= 0 && (items[i].getStyle() & SWT.RADIO) != 0; i--) {
					items[i].setSelection(false);
				}
				for (int i = index + 1; i < items.length && (items[i].getStyle() & SWT.RADIO) != 0; i++) {
					items[i].setSelection(false);
				}
			}
			item.setSelection(true);
		}
	}

	/**
	 * Sends the Selection after this call has returned, then hides the menus.
	 * <p>
	 * Queued rather than run through {@code UiThread.exec}, because the point is
	 * that the call does not wait: an entry that opens a modal dialog holds the UI
	 * thread inside its handler, and the answer has to be out by then. Hidden
	 * after the selection rather than before, because the e4 menu renderer removes
	 * dynamic contributions when a menu hides, which would dispose the very entry
	 * about to be selected.
	 */
	private static void dispatch(MenuItem item, List<Menu> shown) {
		item.getDisplay().asyncExec(() -> {
			try {
				if (!item.isDisposed()) {
					Event event = new Event();
					event.type = SWT.Selection;
					event.widget = item;
					item.notifyListeners(SWT.Selection, event);
				}
			} finally {
				hideAll(shown);
			}
		});
	}

	private static void hideAll(List<Menu> shown) {
		for (int i = shown.size() - 1; i >= 0; i--) {
			ContextMenuTool.hide(shown.get(i));
		}
	}
}
