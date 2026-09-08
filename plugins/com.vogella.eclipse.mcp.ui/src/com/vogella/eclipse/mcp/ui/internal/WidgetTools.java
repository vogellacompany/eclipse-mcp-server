package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Answers what a widget is and how the CSS engine sees it.
 * <p>
 * Addressed by part id and a widget path rather than by screen coordinates. A
 * path survives a window resize and a restart, and a pixel does not, and a
 * client cannot point at anything in the first place.
 */
public final class WidgetTools {

	private WidgetTools() {
	}

	/** The default depth, deep enough for a view's own widgets and not for the whole window. */
	private static final int DEFAULT_DEPTH = 6;

	/** The root control of a part, or of a shell. */
	private static Control rootOf(String partId, String shellTitle, boolean includeToolbar) {
		Display display = PlatformUI.getWorkbench().getDisplay();
		if (partId == null || partId.isBlank()) {
			Shell shell = ScreenshotTools.Capture.findShell(display, shellTitle);
			return shell == null ? null : shell;
		}
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		if (page == null) {
			return null;
		}
		var part = Parts.part(page, partId);
		Control control = part == null ? null : ScreenshotTools.Capture.controlOf(part);
		return control != null && includeToolbar ? ScreenshotTools.Capture.stackOf(control) : control;
	}

	/**
	 * Walks a slash separated path from {@code root}.
	 * <p>
	 * A plain index is a child control; an {@code i} prefixed one is an item, as in
	 * {@code 2/i0} for the first button of the ToolBar at {@code 2}. Indices rather
	 * than names, because most SWT widgets have no stable name at all, and the tree
	 * dump is what a caller reads them from.
	 */
	static Widget resolve(Control root, String path) {
		if (path == null || path.isBlank() || "/".equals(path)) { //$NON-NLS-1$
			return root;
		}
		Widget current = root;
		for (String segment : path.split("/")) { //$NON-NLS-1$
			String step = segment.strip();
			if (step.isEmpty()) {
				continue;
			}
			boolean item = step.startsWith("i"); //$NON-NLS-1$
			boolean row = step.startsWith("r"); //$NON-NLS-1$
			int index;
			try {
				index = Integer.parseInt(item || row ? step.substring(1) : step);
			} catch (NumberFormatException e) {
				return null;
			}
			Widget[] candidates = row ? rowsOf(current)
					: item ? itemsOf(current)
							: current instanceof Composite composite ? composite.getChildren() : new Widget[0];
			if (index < 0 || index >= candidates.length) {
				return null;
			}
			current = candidates[index];
		}
		return current;
	}

	/**
	 * The items of a widget, which are not children and are therefore invisible to a
	 * walk over controls.
	 * <p>
	 * A ToolItem is an Item and not a Control, so the buttons of a view toolbar have
	 * no place in a control hierarchy at all, while the CSS engine models each of
	 * them as its own stylable element. Enumerating them is what lets the inspector
	 * address one, pseudo classes included.
	 */
	/**
	 * The rows of a Table or Tree, which an r prefixed path segment addresses.
	 * <p>
	 * A TreeItem answers its own children, so a nested row is addressable as
	 * {@code 0/0/1/r1/r2}. Without that a path could only ever name a top level
	 * row, and everything under a node was unreachable however it got expanded.
	 */
	static Widget[] rowsOf(Widget widget) {
		return switch (widget) {
		case org.eclipse.swt.widgets.Table table -> table.getItems();
		case org.eclipse.swt.widgets.Tree tree -> tree.getItems();
		case org.eclipse.swt.widgets.TreeItem row -> row.getItems();
		default -> new Widget[0];
		};
	}

	private static Widget[] itemsOf(Widget widget) {
		return switch (widget) {
		case org.eclipse.swt.widgets.ToolBar bar -> bar.getItems();
		case org.eclipse.swt.custom.CTabFolder folder -> folder.getItems();
		case org.eclipse.swt.widgets.TabFolder folder -> folder.getItems();
		case org.eclipse.swt.widgets.CoolBar bar -> bar.getItems();
		case org.eclipse.swt.widgets.ExpandBar bar -> bar.getItems();
		case org.eclipse.swt.widgets.Menu menu -> menu.getItems();
		// the columns rather than the rows: a table's rows are data, its columns are
		// what a stylesheet talks about
		case org.eclipse.swt.widgets.Table table -> table.getColumns();
		case org.eclipse.swt.widgets.Tree tree -> tree.getColumns();
		default -> new Widget[0];
		};
	}

	/** The bounds of a widget in the coordinates of a capture of {@code target}, {@code null} when it has none. */
	static Rectangle boundsRelativeTo(Display display, Widget widget, Control target) {
		if (widget == target) {
			Rectangle own = target.getBounds();
			return new Rectangle(0, 0, own.width, own.height);
		}
		Rectangle own = rectangleOf(widget);
		Control parent = parentOf(widget);
		if (own == null || parent == null) {
			return null;
		}
		return mapToCapture(display, parent, target, own);
	}

	/**
	 * Maps a rectangle into the coordinates a capture of {@code target} has.
	 * <p>
	 * A {@code part} capture prints the part from its top left corner, which is
	 * where {@code Display.map} answers from, so the map is used as is. A stack
	 * capture is not trustworthy on a HiDPI monitor (see docs/platform-bugs.md),
	 * so no attempt is made to correct its client-area offset here.
	 */
	static Rectangle mapToCapture(Display display, Control from, Control target, Rectangle rectangle) {
		return display.map(from, target, rectangle);
	}

	private static Rectangle rectangleOf(Widget widget) {
		return switch (widget) {
		case Control control -> control.getBounds();
		case org.eclipse.swt.widgets.ToolItem item -> item.getBounds();
		case org.eclipse.swt.custom.CTabItem item -> item.getBounds();
		case org.eclipse.swt.widgets.TabItem item -> item.getBounds();
		case org.eclipse.swt.widgets.CoolItem item -> item.getBounds();
		case org.eclipse.swt.widgets.TableItem row -> row.getBounds();
		case org.eclipse.swt.widgets.TreeItem row -> row.getBounds();
		default -> null;
		};
	}

	/** The bounds of a control or of an item, {@code null} when the widget has none. */
	private static String bounds(Widget widget) {
		Rectangle rectangle = switch (widget) {
		case Control control -> control.getBounds();
		case org.eclipse.swt.widgets.ToolItem item -> item.getBounds();
		case org.eclipse.swt.custom.CTabItem item -> item.getBounds();
		case org.eclipse.swt.widgets.TabItem item -> item.getBounds();
		case org.eclipse.swt.widgets.CoolItem item -> item.getBounds();
		default -> null;
		};
		if (rectangle == null) {
			return null;
		}
		return rectangle.x + "," + rectangle.y + " " + rectangle.width + "x" + rectangle.height; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** The control a widget hangs under, which for an item is the widget that owns it. */
	private static Control parentOf(Widget widget) {
		return switch (widget) {
		case Control control -> control.getParent();
		case org.eclipse.swt.widgets.ToolItem item -> item.getParent();
		case org.eclipse.swt.custom.CTabItem item -> item.getParent();
		case org.eclipse.swt.widgets.TabItem item -> item.getParent();
		case org.eclipse.swt.widgets.CoolItem item -> item.getParent();
		case org.eclipse.swt.widgets.TableItem row -> row.getParent();
		case org.eclipse.swt.widgets.TreeItem row -> row.getParent();
		case org.eclipse.swt.widgets.TableColumn column -> column.getParent();
		case org.eclipse.swt.widgets.TreeColumn column -> column.getParent();
		default -> null;
		};
	}

	/**
	 * Where a widget sits, in the shell's client coordinates and on the screen.
	 * <p>
	 * Both, because neither answers on its own. The parent-relative bounds cannot
	 * be summed up the ancestor chain: a Group offsets its children by its label,
	 * so the total misses by the trim of every composite on the way. And the shell
	 * relative position is not where a synthetic click goes either, since it is
	 * measured from the shell's client area while the window manager's title bar
	 * sits above that; taking the two for the same thing lands a click one row off
	 * and looks like the bounds themselves were wrong.
	 */
	private static void addPlacement(JsonObject json, Widget widget) {
		Control parent = parentOf(widget);
		Rectangle own = rectangleOf(widget);
		if (own == null) {
			return;
		}
		Display display = widget.getDisplay();
		Shell shell = widget instanceof Control control ? control.getShell()
				: parent == null ? null : parent.getShell();
		if (shell == null) {
			return;
		}
		// a shell's own bounds are already display coordinates, and mapping them
		// from a parent it does not have would answer about the wrong window
		Rectangle inShell = widget == shell ? new Rectangle(0, 0, own.width, own.height)
				: parent == null ? null : display.map(parent, shell, own);
		if (inShell == null) {
			return;
		}
		json.put("boundsInShell", describe(inShell)) //$NON-NLS-1$
				.put("boundsInDisplay", describe(widget == shell ? own : display.map(parent, null, own))); //$NON-NLS-1$
	}

	/** Bounds in the {@code x,y widthxheight} form every tool here reports. */
	private static String describe(Rectangle rectangle) {
		return rectangle.x + "," + rectangle.y + " " + rectangle.width + "x" + rectangle.height; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** Whether a widget is a control or an item, because the two are not interchangeable. */
	private static String kindOf(Widget widget) {
		return widget instanceof Control ? "control" : "item"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Lists the widgets of a part, so their paths can be read off rather than guessed. */
	public static final class GetWidgetTree implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_get_widget_tree"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Lists the SWT widget hierarchy of a part or a shell, with each widget's class, bounds, CSS id and CSS class, and the path that addresses it. Changes nothing. This is the answer to 'what am I actually looking at', which otherwise has to be inferred from a screenshot or from reading somebody else's source, and it is where the paths for eclipse_inspect_widget come from. Filter by class to ask a narrow question, such as which Trees a view contains and what their ids are. Paths are slash separated indices, which is deliberate: most SWT widgets have no stable name, while an index survives a resize and a restart in a way screen coordinates do not. THREE COORDINATE SYSTEMS ARE REPORTED and they are not interchangeable: 'bounds' is relative to the widget's own parent and cannot be summed up the ancestor chain, since a Group offsets its children by its label; 'boundsInShell' is measured from the shell's CLIENT area, which is what a shell capture shows and what a highlight on one needs; 'boundsInDisplay' is absolute screen position, which is the only one a synthetic click or an external screen tool can use. ALL THREE ARE IN POINTS, not in device pixels, so on a scaled display an external tool that works in pixels, an X server among them, needs boundsInDisplay multiplied by the deviceZoom eclipse_get_display_info reports; a capture's capturedArea is in pixels and its areaInPoints is in these units. The shell's title bar sits above its client area, so adding the shell's own position to boundsInShell lands short by the height of the window decorations, which reads as bounds that are one row out. Set includeItems to enumerate Items as well, the buttons of a toolbar above all: a ToolItem is not a Control, so it appears in no walk over the control hierarchy, while the CSS engine styles each one as its own element."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "part":       {"type":"string","description":"Part id, from eclipse_list_ui_targets. Omit to walk a shell instead. Several open parts share one id, every Java editor above all; the active or a visible one is chosen, and a part's TITLE from eclipse_list_ui_targets names a particular one."},
					    "shellTitle": {"type":"string","description":"Shell to walk when no part is given, by title substring. Ambiguous when shells share a title; use 'shell'."},
					    "shell":      {"type":"string","description":"Shell independent of title: 'popup' for the content assist proposals, an index from eclipse_list_ui_targets ('1'), or its bounds ('151,334 402x255'). Wins over shellTitle."},
					    "path":       {"type":"string","description":"Start from this widget rather than the root, e.g. '0/2'."},
					    "filter":     {"type":"string","description":"Only report widgets whose simple class name contains this text, case insensitive, e.g. 'Tree' or 'ToolBar'. The walk still descends through everything."},
				    "includeToolbar": {"type":"boolean","default":false,"description":"Start from the surrounding part stack rather than the part. A view's toolbar is built in the stack's CTabFolder, not in the part, so it is in no plain part tree at all; this is how to reach it."},
					    "includeItems": {"type":"boolean","default":false,"description":"Also enumerate Items, which are not Controls and are therefore in no plain walk: ToolItems, CTabItems, TabItems, CoolItems, MenuItems and the columns of a Table or Tree. Their paths carry an i prefix, as in 2/i0, and that is the only way eclipse_inspect_widget can address one. Off by default because a Menu can be large."},
				    "includeRows": {"type":"boolean","default":false,"description":"Also enumerate the rows of a Table or Tree, with an r prefixed path (0/r2) that eclipse_inspect_widget, eclipse_set_selection and eclipse_expand_row accept and, beside the row bounds, boundsInShell mapped to the shell's client area so a row can be highlighted on a shell=popup screenshot, and boundsInDisplay for a click. selected marks the row the widget has selected. ONLY THE ROWS THE TREE HAS CREATED ARE ROWS: the children of a collapsed node do not exist yet and have no path, so a view that comes up collapsed reports two or three entries and looks complete. Each tree row therefore carries childCount and expanded, and a collapsed node with children says so; open it with eclipse_expand_row and ask again. The children of an expanded node ARE reported, nested under its own path. Off by default because a big Table has many rows."},
				    "maxDepth":   {"type":"integer","default":6,"minimum":1,"maximum":30,"description":"How far down the widget hierarchy to walk. A whole workbench window is dozens of levels deep, so the default stops well short of it."},
					    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String partId = args.getString("part"); //$NON-NLS-1$
			String shellTitle = args.getString("shell") != null ? args.getString("shell") : args.getString("shellTitle"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			String path = args.getString("path"); //$NON-NLS-1$
			String filter = args.getString("filter"); //$NON-NLS-1$
			int maxDepth = args.getInt("maxDepth", DEFAULT_DEPTH, 1, 30); //$NON-NLS-1$
			int maxResults = args.getInt("maxResults", 200, 1, 2000); //$NON-NLS-1$
			return UiThread.call(15, () -> collect(partId, shellTitle, path, filter, maxDepth, maxResults,
					args.getBoolean("includeToolbar", false), args.getBoolean("includeItems", false), //$NON-NLS-1$ //$NON-NLS-2$
					args.getBoolean("includeRows", false))); //$NON-NLS-1$
		}

		private static JsonObject collect(String partId, String shellTitle, String path, String filter, int maxDepth,
				int maxResults, boolean includeToolbar, boolean includeItems, boolean includeRows) {
			Control root = rootOf(partId, shellTitle, includeToolbar);
			if (root == null) {
				return new JsonObject().put("found", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", //$NON-NLS-1$
								"No such part or shell, or the part is not open. Use eclipse_list_ui_targets.");
			}
			Widget start = resolve(root, path);
			if (start == null) {
				return new JsonObject().put("found", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "The path '%s' does not resolve under this part.".formatted(path)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			String needle = filter == null ? null : filter.toLowerCase(Locale.ROOT);
			JsonArray widgets = new JsonArray();
			int[] total = { 0 };
			walk(start, path == null ? "" : path.strip(), 0, maxDepth, needle, maxResults, includeItems, includeRows, //$NON-NLS-1$
					widgets, total);
			return new JsonObject().put("found", Boolean.TRUE) //$NON-NLS-1$
					.put("root", start.getClass().getName()) //$NON-NLS-1$
					.put("total", Integer.valueOf(total[0])) //$NON-NLS-1$
					.put("truncated", Boolean.valueOf(total[0] > widgets.size())) //$NON-NLS-1$
					.put("widgets", widgets); //$NON-NLS-1$
		}

		private static void walk(Widget widget, String path, int depth, int maxDepth, String needle, int maxResults,
				boolean includeItems, boolean includeRows, JsonArray into, int[] total) {
			boolean wanted = needle == null
					|| widget.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains(needle);
			if (wanted) {
				total[0]++;
				if (into.size() < maxResults) {
					JsonObject node = CssStyling.describe(widget).put("path", path.isEmpty() ? "/" : path) //$NON-NLS-1$ //$NON-NLS-2$
							.put("kind", kindOf(widget)) //$NON-NLS-1$
							.put("class", widget.getClass().getName()) //$NON-NLS-1$
							.put("text", textOf(widget)) //$NON-NLS-1$
							.put("bounds", bounds(widget)) //$NON-NLS-1$
							.put("visible", widget instanceof Control control ? Boolean.valueOf(control.isVisible()) //$NON-NLS-1$
									: null);
					addPlacement(node, widget);
					into.add(node);
				}
			}
			if (depth >= maxDepth) {
				return;
			}
			if (includeItems) {
				Widget[] items = itemsOf(widget);
				for (int i = 0; i < items.length; i++) {
					walk(items[i], path.isEmpty() ? "i" + i : path + "/i" + i, depth + 1, maxDepth, needle, //$NON-NLS-1$ //$NON-NLS-2$
							maxResults, includeItems, includeRows, into, total);
				}
			}
			if (includeRows) {
				addRows(widget, path, needle, maxResults, into, total);
			}
			if (!(widget instanceof Composite composite)) {
				return;
			}
			Control[] children = composite.getChildren();
			for (int i = 0; i < children.length; i++) {
				walk(children[i], path.isEmpty() ? String.valueOf(i) : path + "/" + i, depth + 1, maxDepth, needle, //$NON-NLS-1$
						maxResults, includeItems, includeRows, into, total);
			}
		}

		/**
		 * The rows of a Table or Tree, with bounds and the shell-relative bounds.
		 * <p>
		 * Descends into the children of an EXPANDED tree node, so a nested row has a
		 * path of its own. A collapsed node is reported with childCount and expanded
		 * false rather than descended into, because SWT has not created its children
		 * yet and asking for them would answer zero either way. That distinction is
		 * the whole point: this used to report only top level rows, so a collapsed
		 * Outline looked like a tree with two entries and a caller had no way to tell
		 * a leaf from a node with everything it wanted underneath it.
		 */
		private static void addRows(Widget widget, String path, String needle, int maxResults, JsonArray into,
				int[] total) {
			org.eclipse.swt.widgets.Item[] rows;
			org.eclipse.swt.widgets.Control table;
			Set<Widget> selected = new HashSet<>();
			if (widget instanceof org.eclipse.swt.widgets.Table t) {
				rows = t.getItems();
				table = t;
				selected.addAll(Arrays.asList(t.getSelection()));
			} else if (widget instanceof org.eclipse.swt.widgets.Tree t) {
				rows = t.getItems();
				table = t;
				selected.addAll(Arrays.asList(t.getSelection()));
			} else {
				return;
			}
			// a row is reported even under a class filter that a Table did not match, so
			// the caller that asked for rows gets them; the filter still gates controls
			boolean wanted = needle == null || "tableitem".contains(needle) || "treeitem".contains(needle); //$NON-NLS-1$ //$NON-NLS-2$
			if (!wanted) {
				return;
			}
			emitRows(rows, table, path, selected, maxResults, into, total);
		}

		/** One level of rows, then the children of whichever of them are open. */
		private static void emitRows(org.eclipse.swt.widgets.Item[] rows, org.eclipse.swt.widgets.Control table,
				String path, Set<Widget> selected, int maxResults, JsonArray into, int[] total) {
			Shell shell = table.getShell();
			for (int i = 0; i < rows.length; i++) {
				String rowPath = (path.isEmpty() ? "" : path) + "/r" + i; //$NON-NLS-1$ //$NON-NLS-2$
				org.eclipse.swt.graphics.Rectangle rowBounds = rowBounds(rows[i]);
				if (rowBounds == null) {
					continue;
				}
				boolean node = rows[i] instanceof org.eclipse.swt.widgets.TreeItem;
				int children = node ? ((org.eclipse.swt.widgets.TreeItem) rows[i]).getItemCount() : 0;
				boolean expanded = node && ((org.eclipse.swt.widgets.TreeItem) rows[i]).getExpanded();
				total[0]++;
				if (into.size() < maxResults) {
					org.eclipse.swt.graphics.Rectangle inShell = table.getDisplay().map(table, shell, rowBounds);
					JsonObject row = new JsonObject().put("path", rowPath) //$NON-NLS-1$
							.put("kind", "row") //$NON-NLS-1$ //$NON-NLS-2$
							.put("class", rows[i].getClass().getName()) //$NON-NLS-1$
							.put("text", rows[i].getText()) //$NON-NLS-1$
							.put("selected", Boolean.valueOf(selected.contains(rows[i]))) //$NON-NLS-1$
							.put("bounds", //$NON-NLS-1$
									rowBounds.x + "," + rowBounds.y + " " + rowBounds.width + "x" + rowBounds.height) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							.put("boundsInShell", describe(inShell)) //$NON-NLS-1$
							.put("boundsInDisplay", describe(table.getDisplay().map(table, null, rowBounds))); //$NON-NLS-1$
					if (node) {
						row.put("childCount", Integer.valueOf(children)) //$NON-NLS-1$
								.put("expanded", Boolean.valueOf(expanded)); //$NON-NLS-1$
						if (children > 0 && !expanded) {
							row.put("collapsedNote", //$NON-NLS-1$
									"This node has children that are NOT rows yet, so no path here addresses them. Open it with eclipse_expand_row and ask again."); //$NON-NLS-1$
						}
					}
					into.add(row);
				}
				if (expanded && children > 0) {
					emitRows(((org.eclipse.swt.widgets.TreeItem) rows[i]).getItems(), table, rowPath, selected,
							maxResults, into, total);
				}
			}
		}

		private static org.eclipse.swt.graphics.Rectangle rowBounds(org.eclipse.swt.widgets.Item item) {
			return switch (item) {
			case org.eclipse.swt.widgets.TableItem row -> row.getBounds();
			case org.eclipse.swt.widgets.TreeItem row -> row.getBounds();
			default -> null;
			};
		}

		/** An item's label, which is usually the only readable thing about it. */
		private static String textOf(Widget widget) {
			return widget instanceof org.eclipse.swt.widgets.Item item
					&& item.getText() != null && !item.getText().isEmpty() ? item.getText() : null;
		}
	}

	/** Reports one widget, its ancestry and what the CSS engine computed for it. */
	/** Opens or closes a tree row, so what is under it becomes addressable. */
	public static final class ExpandRow implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_expand_row"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Expands or collapses a row of a Tree, addressed by the r prefixed path eclipse_get_widget_tree reports with includeRows. CHANGES WHAT IS ON SCREEN, and is visible to whoever is at the IDE. THIS IS WHAT MAKES A NESTED ELEMENT REACHABLE AT ALL: only the rows a tree has actually created are rows, so everything under a collapsed node has no path, and a view that comes up collapsed, the Java Outline among them, looks like a tree of two or three entries. A row reports childCount and expanded, so a node with hidden children is visible as such before it is opened. It fires the SWT Expand event as a real click does rather than only setting the flag, because a JFace viewer creates the child rows in its own listener and setting expanded alone leaves an open node with nothing under it. depth opens that many levels, so one call can reach a member two levels down. Ask eclipse_get_widget_tree with includeRows again afterwards: the paths of the new rows did not exist before this ran."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "required": ["path"],
					  "properties": {
					    "part":       {"type":"string","description":"Part id the path is rooted in, e.g. org.eclipse.ui.views.ContentOutline. Several open parts share one id, every Java editor above all; the active or a visible one is chosen, and a part's TITLE from eclipse_list_ui_targets names a particular one."},
					    "shellTitle": {"type":"string","description":"Shell to root the path in, by title substring; omit both for the active shell."},
					    "shell":      {"type":"string","description":"Shell independent of title: 'popup', an index from eclipse_list_ui_targets, or its bounds. Wins over shellTitle."},
					    "path":       {"type":"string","description":"Row path from eclipse_get_widget_tree with includeRows, such as 0/0/1/r1. A nested row is addressed by chaining, as in 0/0/1/r1/r2."},
					    "expand":     {"type":"boolean","default":true,"description":"False collapses the row instead."},
					    "depth":      {"type":"integer","default":1,"minimum":1,"maximum":10,"description":"How many levels to open below this row. 1 opens the row itself."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String partId = args.getString("part"); //$NON-NLS-1$
			String shellTitle = args.getString("shell") != null ? args.getString("shell") : args.getString("shellTitle"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			String path = args.getString("path"); //$NON-NLS-1$
			if (path == null || path.isBlank()) {
				return McpToolResult.error(
						"The argument 'path' is required; eclipse_get_widget_tree with includeRows reports the row paths."); //$NON-NLS-1$
			}
			boolean expand = args.getBoolean("expand", true); //$NON-NLS-1$
			int depth = args.getInt("depth", 1, 1, 10); //$NON-NLS-1$
			return UiThread.call(15, () -> apply(partId, shellTitle, path, expand, depth));
		}

		private static JsonObject apply(String partId, String shellTitle, String path, boolean expand, int depth) {
			Control root = rootOf(partId, shellTitle, false);
			if (root == null) {
				return new JsonObject().put("expanded", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "No such part or shell, or the part is not open. Use eclipse_list_ui_targets."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			Widget target = resolve(root, path);
			if (target == null) {
				return new JsonObject().put("expanded", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", //$NON-NLS-1$
								"The path '%s' does not resolve under this part. Only rows a tree has created have a path, so a row under a collapsed node cannot be named until its parent is opened." //$NON-NLS-1$
										.formatted(path));
			}
			if (!(target instanceof org.eclipse.swt.widgets.TreeItem row)) {
				return new JsonObject().put("expanded", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", //$NON-NLS-1$
								"'%s' is a %s, not a tree row. Row paths carry an r prefix and come from eclipse_get_widget_tree with includeRows." //$NON-NLS-1$
										.formatted(path, target.getClass().getSimpleName()));
			}
			int before = row.getItemCount();
			int opened = expand ? open(row, depth) : 0;
			if (!expand) {
				row.setExpanded(false);
			}
			return new JsonObject().put("expanded", Boolean.valueOf(row.getExpanded())) //$NON-NLS-1$
					.put("path", path) //$NON-NLS-1$
					.put("text", row.getText()) //$NON-NLS-1$
					.put("childCountBefore", Integer.valueOf(before)) //$NON-NLS-1$
					.put("childCount", Integer.valueOf(row.getItemCount())) //$NON-NLS-1$
					.put("rowsOpened", Integer.valueOf(opened)) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"The child rows exist now and did not before, so their paths are new: ask eclipse_get_widget_tree with includeRows again to read them, then eclipse_set_selection to select one."); //$NON-NLS-1$
		}

		/**
		 * Opens a row and, to {@code depth} levels, whatever it turned out to hold.
		 * <p>
		 * The Expand event is fired before the flag is set because a JFace viewer
		 * creates the child rows in its own SWT.Expand listener; setting the flag
		 * alone leaves a node that looks open with nothing under it, which is exactly
		 * the state a caller cannot tell from a genuine leaf.
		 */
		private static int open(org.eclipse.swt.widgets.TreeItem row, int depth) {
			if (depth <= 0) {
				return 0;
			}
			org.eclipse.swt.widgets.Event event = new org.eclipse.swt.widgets.Event();
			event.item = row;
			event.widget = row.getParent();
			row.getParent().notifyListeners(org.eclipse.swt.SWT.Expand, event);
			row.setExpanded(true);
			int opened = 1;
			for (org.eclipse.swt.widgets.TreeItem child : row.getItems()) {
				opened += open(child, depth - 1);
			}
			return opened;
		}
	}

	/** Selects a tab, whatever kind of editor happens to be behind it. */
	public static final class SelectTab implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_select_tab"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Selects a tab by its widget path, the one eclipse_get_widget_tree reports with includeItems. CHANGES WHAT IS ON SCREEN. This addresses the folder rather than the editor, which is what makes it work at all: a multi page editor may be a MultiPageEditorPart, or a FormEditor, or neither, and the e4 model editor for instance builds its Form, List and XMI pages into a CTabFolder of its own, so no editor API reaches them. A page that is not selected is not rendered: eclipse_screenshot refuses it and eclipse_get_widget_tree reports it with zero bounds, so selecting it first is the only way to see it. The selection listeners are notified as if a person had clicked, because setSelection alone moves the highlight without telling the editor to build the page. Pass the path of a CTabItem or TabItem, as in 0/0/0/i2, or the path of the folder plus 'index'."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "required": ["path"],
					  "properties": {
					    "part":        {"type":"string","description":"Part id the path is rooted in. Use eclipse_list_ui_targets. Several open parts share one id, every Java editor above all; the active or a visible one is chosen, and a part's TITLE from eclipse_list_ui_targets names a particular one."},
					    "shellTitle":  {"type":"string","description":"Shell to root the path in, by title substring; omit both for the active shell."},
					    "shell":       {"type":"string","description":"Shell independent of title: 'popup', an index from eclipse_list_ui_targets, or its bounds. Wins over shellTitle."},
					    "path":        {"type":"string","description":"Path of the tab item, such as 0/0/0/i2, or of the folder itself when 'index' is given."},
					    "index":       {"type":"integer","minimum":0,"maximum":200,"description":"Item index, when 'path' names the folder rather than the item."},
					    "notify":      {"type":"boolean","default":true,"description":"Also fire the selection event. Without it the tab looks selected while the editor never builds the page, which is the usual reason a screenshot then shows nothing."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String partId = args.getString("part"); //$NON-NLS-1$
			String shellTitle = args.getString("shell") != null ? args.getString("shell") : args.getString("shellTitle"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			String path = args.getString("path"); //$NON-NLS-1$
			if (path == null) {
				return McpToolResult.error("The argument 'path' is required; eclipse_get_widget_tree with includeItems reports the paths."); //$NON-NLS-1$
			}
			Integer index = args.has("index") ? Integer.valueOf(args.getInt("index", 0, 0, 200)) : null; //$NON-NLS-1$ //$NON-NLS-2$
			return UiThread.call(15,
					() -> select(partId, shellTitle, path, index, args.getBoolean("notify", true))); //$NON-NLS-1$
		}

		private static JsonObject select(String partId, String shellTitle, String path, Integer index,
				boolean notify) {
			Control root = rootOf(partId, shellTitle, false);
			if (root == null) {
				return new JsonObject().put("selected", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "No such part or shell, or the part is not open. Use eclipse_list_ui_targets."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			Widget target = resolve(root, path);
			if (target == null) {
				return new JsonObject().put("selected", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "The path '%s' does not resolve under this part.".formatted(path)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (index != null) {
				Widget[] items = itemsOf(target);
				if (index.intValue() >= items.length) {
					return new JsonObject().put("selected", Boolean.FALSE) //$NON-NLS-1$
							.put("reason", "That folder has %d items, so index %d is out of range." //$NON-NLS-1$
									.formatted(Integer.valueOf(items.length), index));
				}
				target = items[index.intValue()];
			}
			return switch (target) {
			case org.eclipse.swt.custom.CTabItem item -> apply(item.getParent(), item, notify);
			case org.eclipse.swt.widgets.TabItem item -> apply(item.getParent(), item, notify);
			default -> new JsonObject().put("selected", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", //$NON-NLS-1$
							"'%s' is a %s, not a tab item. Ask eclipse_get_widget_tree with includeItems for the item paths, which carry an i prefix." //$NON-NLS-1$
									.formatted(path, target.getClass().getSimpleName()));
			};
		}

		private static JsonObject apply(org.eclipse.swt.custom.CTabFolder folder,
				org.eclipse.swt.custom.CTabItem item, boolean notify) {
			int before = folder.getSelectionIndex();
			folder.setSelection(item);
			if (notify) {
				fire(folder, item);
			}
			folder.layout(true, true);
			return report(before, folder.getSelectionIndex(), item.getText(), notify);
		}

		private static JsonObject apply(org.eclipse.swt.widgets.TabFolder folder,
				org.eclipse.swt.widgets.TabItem item, boolean notify) {
			int before = folder.getSelectionIndex();
			folder.setSelection(item);
			if (notify) {
				fire(folder, item);
			}
			folder.layout(true, true);
			return report(before, folder.getSelectionIndex(), item.getText(), notify);
		}

		/**
		 * The part that setSelection does not do.
		 * <p>
		 * SWT fires nothing for a programmatic selection, and an editor that builds
		 * its page in the selection listener therefore leaves the page empty: the tab
		 * is highlighted, the content is not there, and a screenshot shows the gap
		 * rather than the page.
		 */
		private static void fire(Widget folder, Widget item) {
			org.eclipse.swt.widgets.Event event = new org.eclipse.swt.widgets.Event();
			event.widget = folder;
			event.item = item;
			folder.notifyListeners(org.eclipse.swt.SWT.Selection, event);
		}

		private static JsonObject report(int before, int after, String text, boolean notified) {
			return new JsonObject().put("selected", Boolean.TRUE) //$NON-NLS-1$
					.put("previousIndex", Integer.valueOf(before)) //$NON-NLS-1$
					.put("index", Integer.valueOf(after)) //$NON-NLS-1$
					.put("text", text) //$NON-NLS-1$
					.put("listenersNotified", Boolean.valueOf(notified)) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"The page is rendered now, so eclipse_screenshot and eclipse_get_widget_tree report it with real bounds. Selecting another tab hides it again the same way."); //$NON-NLS-1$
		}
	}

	public static final class InspectWidget implements IMcpTool {

		private static final List<String> DEFAULT_PROPERTIES = List.of("background-color", "color", //$NON-NLS-1$ //$NON-NLS-2$
				"font-family", "font-size", "font-weight", "border-color", "swt-selected-tab-fill"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

		@Override
		public String getName() {
			return "eclipse_inspect_widget"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Reports one widget: its SWT class, the CSS element it maps to, its CSS id and classes, its bounds, its ancestor chain, and what the CSS engine resolved for each property asked about. Changes nothing. This is what turns a colour on screen into an explanation, and in particular it shows a '#token' reference that resolved to nothing, which otherwise only shows up as something rendering black or white. Address the widget with a path from eclipse_get_widget_tree, an i prefixed segment for an item. A ToolItem is reachable this way and only this way, which is what makes the pseudo parameter usable for a question like whether a toggle computes differently when checked. Read 'computed' together with 'declared': computed is the widget's live value, which the engine reads back from SWT and which therefore always answers something, so a colour there is no evidence that a rule set it; declared is what the matching rules set for this element, cssDeclaration is that whole merged declaration, and origin says 'css' or 'widget' per property. That is how a themed colour is told apart from the window system's default, and how an 'inherit' that resolved to the wrong parent is spotted. Which rule and which stylesheet it came from is still not reported: the engine keeps the merged declaration and not its source. A control that paints a background image reports backgroundImage, and on such a control nothing reported here is what is on screen: a CTabFolder handing its children a gradient clears their background first, so the computed colour is the window system's default rather than the theme's, and reading it as the colour under the image is the mistake this flag exists to prevent. Use eclipse_apply_css to test a rule against the running IDE and inspect again."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "part":       {"type":"string","description":"Part id, from eclipse_list_ui_targets. Omit to address a shell instead. Several open parts share one id, every Java editor above all; the active or a visible one is chosen, and a part's TITLE from eclipse_list_ui_targets names a particular one."},
					    "shellTitle": {"type":"string","description":"Shell when no part is given, by title substring."},
					    "shell":      {"type":"string","description":"Shell independent of title: 'popup', an index from eclipse_list_ui_targets, or its bounds. Wins over shellTitle."},
					    "path":       {"type":"string","description":"Slash separated indices from eclipse_get_widget_tree, e.g. '0/2/1'. An i prefixed segment is an item rather than a child control, as in '2/i0' for the first button of a toolbar. Omit for the root."},
					    "properties": {"type":"array","items":{"type":"string"},"description":"CSS properties to resolve. Defaults to the colour and font ones plus swt-selected-tab-fill."},
					    "pseudo":     {"type":"string","description":"Pseudo class to resolve against, e.g. 'hover', 'selected', 'checked'. A property that only differs under a pseudo class reads as unset without this."},
				    "includeToolbar": {"type":"boolean","default":false,"description":"Start from the surrounding part stack rather than the part. A view's toolbar is built in the stack's CTabFolder, not in the part, so it is in no plain part tree at all; this is how to reach it."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String partId = args.getString("part"); //$NON-NLS-1$
			String shellTitle = args.getString("shell") != null ? args.getString("shell") : args.getString("shellTitle"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			String path = args.getString("path"); //$NON-NLS-1$
			String pseudo = args.getString("pseudo"); //$NON-NLS-1$
			List<String> properties = new ArrayList<>();
			if (arguments != null && arguments.get("properties") instanceof List<?> list) { //$NON-NLS-1$
				list.forEach(value -> properties.add(String.valueOf(value)));
			}
			if (properties.isEmpty()) {
				properties.addAll(DEFAULT_PROPERTIES);
			}
			return UiThread.call(15, () -> inspect(partId, shellTitle, path, properties, pseudo,
					args.getBoolean("includeToolbar", false))); //$NON-NLS-1$
		}

		private static JsonObject inspect(String partId, String shellTitle, String path, List<String> properties,
				String pseudo, boolean includeToolbar) {
			// items included unconditionally here: a path that names one is the caller
			// saying it wants that item, and refusing it for want of a flag is noise
			Control root = rootOf(partId, shellTitle, includeToolbar);
			if (root == null) {
				return new JsonObject().put("found", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", //$NON-NLS-1$
								"No such part or shell, or the part is not open. Use eclipse_list_ui_targets.");
			}
			Widget control = resolve(root, path);
			if (control == null) {
				return new JsonObject().put("found", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", //$NON-NLS-1$
								"The path '%s' does not resolve. Read a current one from eclipse_get_widget_tree."
										.formatted(path));
			}
			JsonArray ancestors = new JsonArray();
			for (Control parent = parentOf(control); parent != null; parent = parent.getParent()) {
				ancestors.add(CssStyling.describe(parent).put("class", parent.getClass().getName())); //$NON-NLS-1$
			}
			JsonObject result = CssStyling.describe(control).put("found", Boolean.TRUE) //$NON-NLS-1$
					.put("path", path == null || path.isBlank() ? "/" : path.strip()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("kind", kindOf(control)) //$NON-NLS-1$
					.put("class", control.getClass().getName()) //$NON-NLS-1$
					.put("bounds", bounds(control)) //$NON-NLS-1$
					.put("visible", control instanceof Control visible ? Boolean.valueOf(visible.isVisible()) : null) //$NON-NLS-1$
					.put("pseudo", pseudo); //$NON-NLS-1$
			CssStyling.styles(control, properties, pseudo, result);
			if (control instanceof Control painted && painted.getBackgroundImage() != null) {
				// the image is on screen and the reported colour is not, and it is not the
				// colour under the image either: CTabFolder.updateBkImages clears it
				result.put("backgroundImage", Boolean.TRUE) //$NON-NLS-1$
						.put("backgroundImageNote", //$NON-NLS-1$
								"This control paints a background image, so nothing reported here is what is on screen. Do not read the computed background-color as the colour under the image either: a CTabFolder handing its children a gradient calls setBackground(null) on each of them first, so the value read back is the window system's default for that widget and has no relationship to the theme. Three different colours are then in play for one widget, and the computed one is the least relevant.");
			}
			return result.put("ancestors", ancestors) //$NON-NLS-1$
					.put("matchedRulesNote", //$NON-NLS-1$
							"Which rules matched, and from which stylesheet, is not reported: the engine keeps the merged declaration and not the rules it came from. 'declared' is that declaration, so it says whether a rule decided the property; finding the rule itself still means reading the stylesheets.");
		}
	}
}
