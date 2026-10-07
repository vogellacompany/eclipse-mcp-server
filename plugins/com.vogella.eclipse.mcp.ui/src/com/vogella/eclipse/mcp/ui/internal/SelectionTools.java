package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.services.IEvaluationService;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reads and sets the workbench selection, which is what command enablement is
 * decided from.
 */
public final class SelectionTools {

	private static final long UI_TIMEOUT_SECONDS = 15;

	/** A Java element's toString prints its whole subtree, so labels are capped. */
	private static final int MAX_LABEL = 200;

	private SelectionTools() {
	}

	/**
	 * A workspace path or a project name, for a caller that has no part to resolve
	 * a widget row against. {@code null} when nothing of that name exists.
	 */
	public static Object resolveResource(String spec) {
		String value = spec == null ? "" : spec.strip(); //$NON-NLS-1$
		if (value.isEmpty()) {
			return null;
		}
		if (value.startsWith("/")) { //$NON-NLS-1$
			return ResourcesPlugin.getWorkspace().getRoot().findMember(value);
		}
		var project = ResourcesPlugin.getWorkspace().getRoot().getProject(value);
		return project.exists() ? project : null;
	}

	/** One selected object as the other tools report it. */
	public static JsonObject describe(Object element) {
		IResource resource = element == null ? null : Adapters.adapt(element, IResource.class);
		return new JsonObject().put("class", element == null ? null : element.getClass().getName()) //$NON-NLS-1$
				.put("label", label(element, resource)) //$NON-NLS-1$
				.put("adaptsToResource", Boolean.valueOf(resource != null)) //$NON-NLS-1$
				.put("path", resource == null ? null : resource.getFullPath().toString()) //$NON-NLS-1$
				.put("project", resource == null || resource.getProject() == null ? null //$NON-NLS-1$
						: resource.getProject().getName())
				.put("accessible", resource == null ? null : Boolean.valueOf(resource.isAccessible())); //$NON-NLS-1$
	}

	/** What the handler framework is looking at, which is not always the active part's viewer. */
	private static JsonObject describeSelection(String source, Object selection) {
		JsonObject result = new JsonObject().put("source", source); //$NON-NLS-1$
		if (!(selection instanceof ISelection s)) {
			return result.put("kind", selection == null ? null : selection.getClass().getName()) //$NON-NLS-1$
					.put("empty", Boolean.TRUE);
		}
		result.put("kind", s.getClass().getName()).put("empty", Boolean.valueOf(s.isEmpty())); //$NON-NLS-1$ //$NON-NLS-2$
		if (!(s instanceof IStructuredSelection structured)) {
			return result;
		}
		JsonArray elements = new JsonArray();
		for (Object element : structured) {
			elements.add(describe(element));
		}
		return result.put("size", Integer.valueOf(structured.size())).put("elements", elements); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * A short label, never the element's own {@code toString}.
	 * <p>
	 * A Java element prints its whole subtree from {@code toString}, which turned
	 * one two-element selection into a half-megabyte answer. The resource name, or
	 * what the workbench itself would show, is what a caller reads anyway.
	 */
	private static String label(Object element, IResource resource) {
		if (element == null) {
			return null;
		}
		if (resource != null) {
			return resource.getFullPath().toString();
		}
		org.eclipse.ui.model.IWorkbenchAdapter adapter = Adapters.adapt(element,
				org.eclipse.ui.model.IWorkbenchAdapter.class);
		if (adapter != null) {
			String label = adapter.getLabel(element);
			if (label != null && !label.isBlank()) {
				return truncate(label);
			}
		}
		return truncate(String.valueOf(element));
	}

	private static String truncate(String value) {
		return value.length() <= MAX_LABEL ? value : value.substring(0, MAX_LABEL) + "..."; //$NON-NLS-1$
	}

	/** The part a tool argument names, or the active part when none is given. */
	static IWorkbenchPart partFor(String partId) {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		if (page == null) {
			return null;
		}
		if (partId == null || partId.isBlank()) {
			return page.getActivePart();
		}
		IWorkbenchPartReference reference = PartAddress.find(page, partId);
		return reference == null ? null : reference.getPart(true);
	}

	/** The selection as the handler framework sees it, plus the part it came from. */
	private static JsonObject currentState(String note) {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		IWorkbenchPart active = page == null ? null : page.getActivePart();
		JsonObject result = new JsonObject()
				.put("activePart", active == null ? null : active.getSite().getId()) //$NON-NLS-1$
				.put("activePartTitle", active == null ? null : active.getTitle()); //$NON-NLS-1$
		Object evaluated = null;
		try {
			IEvaluationService service = PlatformUI.getWorkbench().getService(IEvaluationService.class);
			if (service != null) {
				evaluated = service.getCurrentState()
						.getVariable(org.eclipse.ui.ISources.ACTIVE_CURRENT_SELECTION_NAME);
			}
		} catch (RuntimeException e) {
			// reported as an absent evaluation selection rather than failing the call
		}
		result.put("handlerSelection", describeSelection("evaluationContext", evaluated)); //$NON-NLS-1$ //$NON-NLS-2$
		ISelection serviceSelection = page == null ? null : page.getSelection();
		result.put("serviceSelection", describeSelection("selectionService", serviceSelection)); //$NON-NLS-1$ //$NON-NLS-2$
		if (note != null) {
			result.put("note", note); //$NON-NLS-1$
		}
		return result;
	}

	/** Reports the current selection. */
	public static final class GetSelection implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_get_selection"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Reports the workbench selection as the handler framework sees it, which is what command enablement is decided from, and the part it came from. Changes nothing. TWO SELECTIONS ARE REPORTED ON PURPOSE: handlerSelection is the ACTIVE_CURRENT_SELECTION variable of the evaluation context, which is what an enabledWhen expression is tested against, and serviceSelection is what the active page's selection service holds. They disagree whenever a part has not published its selection into the context yet, and an enablement question answered from the wrong one is answered wrongly. Each element carries its class, label, whether it adapts to IResource, and the resource's path, project and accessibility, since most enablement expressions test exactly those. Use eclipse_set_selection to put a selection in place first."; //$NON-NLS-1$
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
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> currentState(null));
		}
	}

	/** Sets the selection of a part. */
	public static final class SetSelection implements IMcpTool {

		private static final java.util.regex.Pattern ROW_PATH = java.util.regex.Pattern.compile("[0-9]+(/[ir]?[0-9]+)*"); //$NON-NLS-1$

		@Override
		public String getName() {
			return "eclipse_set_selection"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Sets the selection of a view or editor through its own selection provider, the way clicking rows would, so a command's enablement can then be asked for that selection with eclipse_run_workbench_command. CHANGES WHAT IS SELECTED IN THE IDE, which is visible to whoever is at it, and the previous selection is reported so it can be put back. A part whose provider only relays its viewer, the e4 model editor among them, gets its row paths selected on the Tree or Table, and an empty array cleared on its Tree, the way a click does, so the highlight and the details follow, and selectedThroughWidget says so; mixing row paths with workspace paths there falls back to the provider. This is the way to build a selection that no key can reach here: a view may register no Select All handler, and eclipse_press_key cannot deliver Ctrl+A on a backgrounded Wayland session. Elements are addressed as workspace paths ('/org.eclipse.compare'), project names ('g'), or widget tree row paths ('0/0/0/r7') from eclipse_get_widget_tree with includeRows, which is what reaches an element that is not a resource. A closed project resolves like any other, since whether the selection may contain one is exactly what an enablement test is about. THE ANSWER REPORTS WHAT THE SELECTION SERVICE HOLDS AFTERWARDS rather than what was requested, because a viewer silently drops an element it does not have, and a selection that did not take would otherwise be visible only as a wrong enablement answer later."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "part":     {"type":"string","description":"Part id whose selection to set, e.g. org.eclipse.jdt.ui.PackageExplorer. Defaults to the active part. Several parts with one id: id@editor input path or title."},
					    "elements": {"type":"array","items":{"type":"string"},"description":"What to select: workspace paths ('/org.eclipse.compare'), project names ('g'), or widget tree row paths ('0/0/1/r4'). A ROW PATH COMES FROM eclipse_get_widget_tree WITH includeRows TRUE, which is a different flag from includeItems: includeItems enumerates ToolItems and CTabItems and reports no tree rows at all, so a tree looks empty and the path has to be guessed. Guessing does not work, and a row path that names nothing is reported in unresolved rather than selected. An empty array clears the selection."},
					    "reveal":   {"type":"boolean","default":true,"description":"Scroll the viewer to the selection."},
					    "activate": {"type":"boolean","default":true,"description":"Activate the part first, so the selection reaches the handler evaluation context. Without it a command asked afterwards may still see the old active part's selection."}
					  },
					  "required": ["elements"],
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			if (!(arguments != null && arguments.get("elements") instanceof List<?> requested)) { //$NON-NLS-1$
				return McpToolResult.error("Give 'elements' as an array of workspace paths, project names or row paths."); //$NON-NLS-1$
			}
			String partId = args.getString("part"); //$NON-NLS-1$
			boolean reveal = args.getBoolean("reveal", true); //$NON-NLS-1$
			boolean activate = args.getBoolean("activate", true); //$NON-NLS-1$
			List<String> specs = new ArrayList<>();
			requested.forEach(value -> specs.add(String.valueOf(value)));
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> {
				IWorkbenchPart part = partFor(partId);
				if (part == null) {
					throw new IllegalArgumentException(partId == null
							? "There is no active part to select in. Give 'part'." //$NON-NLS-1$
							: "No open part has the id '%s'. eclipse_list_ui_targets lists the open parts." //$NON-NLS-1$
									.formatted(partId));
				}
				ISelectionProvider provider = part.getSite().getSelectionProvider();
				if (provider == null) {
					throw new IllegalArgumentException(
							"The part '%s' has no selection provider, so nothing can be selected in it. A Tree or Table row of a custom view is selected with eclipse_press_widget and a row path from eclipse_get_widget_tree with includeRows." //$NON-NLS-1$
									.formatted(part.getSite().getId()));
				}
				JsonObject before = describeSelection("beforeSetting", provider.getSelection()); //$NON-NLS-1$
				JsonArray unresolved = new JsonArray();
				List<Object> elements = new ArrayList<>();
				List<org.eclipse.swt.widgets.Item> rows = new ArrayList<>();
				for (String spec : specs) {
					org.eclipse.swt.widgets.Item item = itemAt(spec, part);
					Object resolved = item != null ? item.getData() : resolve(spec, part);
					if (resolved == null) {
						unresolved.add(spec);
					} else {
						elements.add(resolved);
						if (item instanceof org.eclipse.swt.widgets.TreeItem || item instanceof org.eclipse.swt.widgets.TableItem) {
							rows.add(item);
						}
					}
				}
				if (activate) {
					part.getSite().getPage().activate(part);
				}
				// a viewer selects its own model objects (the Package Explorer holds an IJavaProject for an IProject) and setSelection silently drops
				// what it does not recognise, so the requested resources are matched against what the viewer shows
				List<Object> asShown = matchToViewer(elements, part);
				ISelection selection = asShown.isEmpty() ? StructuredSelection.EMPTY
						: new StructuredSelection(asShown);
				// only a Viewer can be told to reveal; a plain provider takes the
				// selection alone, and reporting reveal as done would be a small lie
				boolean revealed = false;
				boolean throughWidget = false;
				if (provider instanceof org.eclipse.jface.viewers.Viewer viewer) {
					viewer.setSelection(selection, reveal);
					revealed = reveal;
				} else if (!rows.isEmpty() && rows.size() == elements.size() && selectRows(rows, reveal)) {
					// a provider that only relays its viewer, as the e4 model editor's does, moves neither highlight nor details
					throughWidget = true;
					revealed = reveal;
				} else if (specs.isEmpty() && clearRows(part)) {
					throughWidget = true;
				} else {
					provider.setSelection(selection);
				}
				// the rows may belong to a tree that does not feed this provider, which then still has to be told
				if (throughWidget && !holds(provider.getSelection(), asShown)) {
					provider.setSelection(selection);
				}
				JsonObject result = new JsonObject().put("part", part.getSite().getId()) //$NON-NLS-1$
						.put("revealed", Boolean.valueOf(revealed)) //$NON-NLS-1$
						.put("selectedThroughWidget", Boolean.valueOf(throughWidget)) //$NON-NLS-1$
						.put("requested", Integer.valueOf(specs.size())) //$NON-NLS-1$
						.put("resolved", Integer.valueOf(elements.size())) //$NON-NLS-1$
						.put("matchedToViewerElements", Integer.valueOf(asShown.size())) //$NON-NLS-1$
						.put("unresolved", unresolved) //$NON-NLS-1$
						.put("previousSelection", before); //$NON-NLS-1$
				result.put("after", currentState( //$NON-NLS-1$
						"This is what the selection service and the evaluation context hold now, not what was asked for; a viewer drops an element it does not have.")); //$NON-NLS-1$
				if (unresolved.size() > 0) {
					result.put("unresolvedNote", //$NON-NLS-1$
							"Those could not be resolved and are not in the selection. A workspace path starts with '/', a project name does not, and a row path comes from eclipse_get_widget_tree with includeRows."); //$NON-NLS-1$
				}
				return result;
			});
		}

		/**
		 * The objects the viewer itself holds for the requested elements.
		 * <p>
		 * A resource is matched to the tree item whose data adapts to the same
		 * resource, so the model object the viewer put there is what gets selected.
		 * Anything with no item of its own is kept as it was, since a provider that
		 * is not a tree may well accept it.
		 */
		private static List<Object> matchToViewer(List<Object> requested, IWorkbenchPart part) {
			org.eclipse.swt.widgets.Tree tree = treeOf(part);
			if (tree == null || requested.isEmpty()) {
				return requested;
			}
			List<Object> shown = new ArrayList<>();
			for (Object element : requested) {
				IResource resource = Adapters.adapt(element, IResource.class);
				Object match = resource == null ? null : findInTree(tree.getItems(), resource, 0);
				shown.add(match == null ? element : match);
			}
			return shown;
		}

		/** The data of the item that stands for this resource, searched a few levels deep. */
		private static Object findInTree(org.eclipse.swt.widgets.TreeItem[] items, IResource resource, int depth) {
			if (depth > 2) {
				return null;
			}
			for (org.eclipse.swt.widgets.TreeItem item : items) {
				IResource shown = item.getData() == null ? null : Adapters.adapt(item.getData(), IResource.class);
				if (shown != null && shown.getFullPath().equals(resource.getFullPath())) {
					return item.getData();
				}
			}
			for (org.eclipse.swt.widgets.TreeItem item : items) {
				Object found = findInTree(item.getItems(), resource, depth + 1);
				if (found != null) {
					return found;
				}
			}
			return null;
		}

		private static org.eclipse.swt.widgets.Tree treeOf(IWorkbenchPart part) {
			org.eclipse.swt.widgets.Control control = ScreenshotTools.Capture.controlOf(part);
			return control == null ? null : firstTree(control, 0);
		}

		private static org.eclipse.swt.widgets.Tree firstTree(org.eclipse.swt.widgets.Control control, int depth) {
			if (control instanceof org.eclipse.swt.widgets.Tree tree) {
				return tree;
			}
			if (depth > 6 || !(control instanceof org.eclipse.swt.widgets.Composite composite)) {
				return null;
			}
			for (org.eclipse.swt.widgets.Control child : composite.getChildren()) {
				org.eclipse.swt.widgets.Tree found = firstTree(child, depth + 1);
				if (found != null) {
					return found;
				}
			}
			return null;
		}

		/**
		 * A workspace path, a project name or a widget row, in that order.
		 * <p>
		 * The row is what reaches an element a viewer shows that is not a resource,
		 * since a tree item carries the model object the viewer put there.
		 */
		private static Object resolve(String spec, IWorkbenchPart part) {
			String value = spec.strip();
			if (value.isEmpty()) {
				return null;
			}
			if (value.startsWith("/")) { //$NON-NLS-1$
				return ResourcesPlugin.getWorkspace().getRoot().findMember(value);
			}
			if (ROW_PATH.matcher(value).matches()) {
				// a row path that names no item is unresolved; getProject below would throw on it
				return null;
			}
			if (value.indexOf('/') >= 0) {
				// a relative path is neither a workspace path nor a project name, and getProject throws on it
				return null;
			}
			var project = ResourcesPlugin.getWorkspace().getRoot().getProject(value);
			return project.exists() ? project : null;
		}

		/** The item a widget path names, whose data is the model object the viewer selects; {@code null} for anything else. */
		private static org.eclipse.swt.widgets.Item itemAt(String spec, IWorkbenchPart part) {
			String value = spec.strip();
			if (!ROW_PATH.matcher(value).matches()) {
				return null;
			}
			org.eclipse.swt.widgets.Control control = ScreenshotTools.Capture.controlOf(part);
			if (control == null) {
				return null;
			}
			return WidgetTools.resolve(control, value) instanceof org.eclipse.swt.widgets.Item item ? item : null;
		}

		/** Whether the selection holds exactly these elements, in any order. */
		private static boolean holds(ISelection selection, List<Object> elements) {
			if (!(selection instanceof IStructuredSelection structured)) {
				return elements.isEmpty() && (selection == null || selection.isEmpty());
			}
			return structured.size() == elements.size() && new java.util.HashSet<>(structured.toList()).equals(new java.util.HashSet<>(elements));
		}

		/** Clears the part's tree and tells its viewer as a click on empty space does; {@code false} without a tree. */
		private static boolean clearRows(IWorkbenchPart part) {
			org.eclipse.swt.widgets.Tree tree = treeOf(part);
			if (tree == null) {
				return false;
			}
			tree.deselectAll();
			tree.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
			return true;
		}

		/**
		 * Selects rows of one Tree or Table and sends the Selection event a click
		 * sends; {@code false} when they do not share an owner.
		 */
		private static boolean selectRows(List<org.eclipse.swt.widgets.Item> rows, boolean reveal) {
			org.eclipse.swt.widgets.Item first = rows.get(0);
			org.eclipse.swt.widgets.Control owner = first instanceof org.eclipse.swt.widgets.TreeItem item ? item.getParent()
					: ((org.eclipse.swt.widgets.TableItem) first).getParent();
			if (owner instanceof org.eclipse.swt.widgets.Tree tree) {
				List<org.eclipse.swt.widgets.TreeItem> items = new ArrayList<>();
				for (org.eclipse.swt.widgets.Item row : rows) {
					if (!(row instanceof org.eclipse.swt.widgets.TreeItem item) || item.getParent() != tree) {
						return false;
					}
					items.add(item);
				}
				if (reveal) {
					tree.showItem(items.get(0));
				}
				tree.setSelection(items.toArray(org.eclipse.swt.widgets.TreeItem[]::new));
			} else {
				org.eclipse.swt.widgets.Table table = (org.eclipse.swt.widgets.Table) owner;
				List<org.eclipse.swt.widgets.TableItem> items = new ArrayList<>();
				for (org.eclipse.swt.widgets.Item row : rows) {
					if (!(row instanceof org.eclipse.swt.widgets.TableItem item) || item.getParent() != table) {
						return false;
					}
					items.add(item);
				}
				if (reveal) {
					table.showItem(items.get(0));
				}
				table.setSelection(items.toArray(org.eclipse.swt.widgets.TableItem[]::new));
			}
			org.eclipse.swt.widgets.Event event = new org.eclipse.swt.widgets.Event();
			event.item = first;
			owner.notifyListeners(org.eclipse.swt.SWT.Selection, event);
			return true;
		}
	}
}
