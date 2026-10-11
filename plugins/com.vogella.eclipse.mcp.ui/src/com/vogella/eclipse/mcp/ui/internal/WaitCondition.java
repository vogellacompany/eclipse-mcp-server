package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Item;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * One condition of eclipse_wait_for, parsed from its argument object and evaluated against the live IDE.
 */
public sealed interface WaitCondition {

	Pattern PATH = Pattern.compile("/|[ir]?\\d+(/[ir]?\\d+)*"); //$NON-NLS-1$

	/** What one evaluation found. */
	record Result(boolean met, JsonObject observed) {
	}

	String kind();

	/** Whether this condition reads widgets, and so has to be evaluated on the UI thread. */
	default boolean needsUi() {
		return true;
	}

	Result evaluate(Display display);

	/** Parses one condition, throwing {@link IllegalArgumentException} with a message for the caller. */
	public static WaitCondition parse(Object value) {
		if (!(value instanceof Map<?, ?> map)) {
			throw new IllegalArgumentException("Each condition is an object with a 'kind'."); //$NON-NLS-1$
		}
		@SuppressWarnings("unchecked")
		ToolArguments args = ToolArguments.of((Map<String, Object>) map);
		String kind = args.getString("kind"); //$NON-NLS-1$
		if (kind == null) {
			throw new IllegalArgumentException("A condition has no 'kind'; use shell, widget, selection, editor or jobs."); //$NON-NLS-1$
		}
		return switch (kind) {
		case "shell" -> new ShellCondition(required(args, "shellTitle", kind), //$NON-NLS-1$ //$NON-NLS-2$
				oneOf(args, "state", "open", "open", "closed", "active")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		case "widget" -> WidgetCondition.parse(args); //$NON-NLS-1$
		case "selection" -> { //$NON-NLS-1$
			String contains = args.getString("contains"); //$NON-NLS-1$
			Boolean empty = optionalBoolean(args, "empty"); //$NON-NLS-1$
			if (contains == null && empty == null) {
				throw new IllegalArgumentException("A selection condition needs 'contains' or 'empty'."); //$NON-NLS-1$
			}
			yield new SelectionCondition(args.getString("part"), contains, empty); //$NON-NLS-1$
		}
		case "editor" -> { //$NON-NLS-1$
			String state = oneOf(args, "state", "open", "open", "closed", "active"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			Boolean dirty = optionalBoolean(args, "dirty"); //$NON-NLS-1$
			if (dirty != null && "closed".equals(state)) { //$NON-NLS-1$
				throw new IllegalArgumentException("'dirty' needs an open editor and does not go with state closed."); //$NON-NLS-1$
			}
			yield new EditorCondition(required(args, "input", kind), state, dirty); //$NON-NLS-1$
		}
		case "jobs" -> new JobsCondition(oneOf(args, "scope", "all", "all", "build"), args.getString("jobName")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
		default -> throw new IllegalArgumentException(
				"Unknown condition kind '%s'; use shell, widget, selection, editor or jobs.".formatted(kind)); //$NON-NLS-1$
		};
	}

	private static String required(ToolArguments args, String name, String kind) {
		String value = args.getString(name);
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException("A %s condition needs '%s'.".formatted(kind, name)); //$NON-NLS-1$
		}
		return value;
	}

	private static String oneOf(ToolArguments args, String name, String fallback, String... allowed) {
		String value = args.getString(name, fallback);
		if (!Arrays.asList(allowed).contains(value)) {
			throw new IllegalArgumentException("'%s' is %s, not '%s'.".formatted(name, String.join(" or ", allowed), value)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return value;
	}

	/** Refuses a value that is not a boolean, since a fallback would silently wait for the opposite. */
	private static Boolean optionalBoolean(ToolArguments args, String name) {
		String value = args.getString(name);
		if (value == null) {
			return null;
		}
		if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) { //$NON-NLS-1$ //$NON-NLS-2$
			throw new IllegalArgumentException("'%s' is true or false, not '%s'.".formatted(name, value)); //$NON-NLS-1$
		}
		return Boolean.valueOf(value);
	}

	private static Integer optionalInt(ToolArguments args, String name) {
		String value = args.getString(name);
		if (value == null) {
			return null;
		}
		try {
			int number = Integer.parseInt(value);
			if (number >= 0) {
				return Integer.valueOf(number);
			}
		} catch (NumberFormatException e) {
			// refused below
		}
		throw new IllegalArgumentException("'%s' is a whole number of at least 0, not '%s'.".formatted(name, value)); //$NON-NLS-1$
	}

	private static IWorkbenchPage page() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		return window == null ? null : window.getActivePage();
	}

	private static Result notIde() {
		return new Result(false, new JsonObject().put("reason", Workbenches.noIde())); //$NON-NLS-1$
	}

	/** A shell whose title contains {@code title} is open, closed or active. */
	record ShellCondition(String title, String state) implements WaitCondition {

		@Override
		public String kind() {
			return "shell"; //$NON-NLS-1$
		}

		@Override
		public Result evaluate(Display display) {
			Shell first = null;
			int matching = 0;
			for (Shell shell : display.getShells()) {
				if (!shell.isDisposed() && shell.isVisible() && shell.getText() != null && shell.getText().contains(title)) {
					matching++;
					if (first == null) {
						first = shell;
					}
				}
			}
			Shell activeShell = display.getActiveShell();
			boolean active = activeShell != null && activeShell.getText() != null && activeShell.getText().contains(title);
			boolean met = switch (state) {
			case "closed" -> matching == 0; //$NON-NLS-1$
			case "active" -> active; //$NON-NLS-1$
			default -> matching > 0;
			};
			return new Result(met, new JsonObject().put("open", Boolean.valueOf(matching > 0)) //$NON-NLS-1$
					.put("active", Boolean.valueOf(active)).put("matching", Integer.valueOf(matching)) //$NON-NLS-1$ //$NON-NLS-2$
					.put("title", first == null ? null : first.getText())); //$NON-NLS-1$
		}
	}

	/** A widget, addressed by path or by text under a part or shell, exists and has the expected properties. */
	record WidgetCondition(String part, String shell, String path, String find, Boolean exists, Boolean selected,
			Boolean enabled, Boolean visible, String textEquals, String textContains, Integer rowCount,
			Integer minRowCount) implements WaitCondition {

		static WidgetCondition parse(ToolArguments args) {
			String path = args.getString("path"); //$NON-NLS-1$
			if (path != null && !PATH.matcher(path.strip()).matches()) {
				throw new IllegalArgumentException(
						"'%s' is not a widget path; eclipse_get_widget_tree reports them, such as 1/0, 0/i2 or 0/r25.".formatted(path)); //$NON-NLS-1$
			}
			String find = args.getString("find"); //$NON-NLS-1$
			if (path != null && find != null) {
				throw new IllegalArgumentException("A widget condition takes 'path' or 'find', not both."); //$NON-NLS-1$
			}
			return new WidgetCondition(args.getString("part"), Shells.spec(args), path, find, //$NON-NLS-1$
					optionalBoolean(args, "exists"), optionalBoolean(args, "selected"), //$NON-NLS-1$ //$NON-NLS-2$
					optionalBoolean(args, "enabled"), optionalBoolean(args, "visible"), //$NON-NLS-1$ //$NON-NLS-2$
					args.getString("textEquals"), args.getString("textContains"), //$NON-NLS-1$ //$NON-NLS-2$
					optionalInt(args, "rowCount"), optionalInt(args, "minRowCount")); //$NON-NLS-1$ //$NON-NLS-2$
		}

		@Override
		public String kind() {
			return "widget"; //$NON-NLS-1$
		}

		@Override
		public Result evaluate(Display display) {
			boolean wantExists = exists == null || exists.booleanValue();
			Control root;
			try {
				root = root(display);
			} catch (IllegalStateException e) {
				// a part address that matches nothing is a mistake, not a widget that has gone
				return new Result(false, new JsonObject().put("exists", Boolean.FALSE).put("reason", e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (root == null || root.isDisposed()) {
				return new Result(!wantExists, new JsonObject().put("exists", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", part != null ? "The part is not open or not created yet." : "No such shell is open.")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			Widget target = find != null ? search(root, find.toLowerCase(Locale.ROOT)) : WidgetTools.resolve(root, path);
			if (target == null || target.isDisposed()) {
				return new Result(!wantExists, new JsonObject().put("exists", Boolean.FALSE)); //$NON-NLS-1$
			}
			JsonObject observed = new JsonObject().put("exists", Boolean.TRUE) //$NON-NLS-1$
					.put("widget", target.getClass().getSimpleName()); //$NON-NLS-1$
			if (target instanceof Control control) {
				observed.put("path", WidgetTools.pathOf(root, control)); //$NON-NLS-1$
			} else if (path != null) {
				observed.put("path", path); //$NON-NLS-1$
			}
			String text = text(target);
			Boolean isSelected = selected(target);
			Control owner = owner(target);
			boolean isEnabled = owner != null && owner.isEnabled() && (!(target instanceof ToolItem item) || item.isEnabled());
			boolean isVisible = owner != null && owner.isVisible();
			Integer rows = rowCount(target);
			observed.put("text", text).put("selected", isSelected).put("enabled", Boolean.valueOf(isEnabled)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.put("visible", Boolean.valueOf(isVisible)).put("rowCount", rows); //$NON-NLS-1$ //$NON-NLS-2$
			boolean met = wantExists
					&& (selected == null || isSelected != null && isSelected.booleanValue() == selected.booleanValue())
					&& (enabled == null || isEnabled == enabled.booleanValue())
					&& (visible == null || isVisible == visible.booleanValue())
					&& (textEquals == null || textEquals.equals(text))
					&& (textContains == null || text != null && text.contains(textContains))
					&& (rowCount == null || rows != null && rows.intValue() == rowCount.intValue())
					&& (minRowCount == null || rows != null && rows.intValue() >= minRowCount.intValue());
			if (selected != null && isSelected == null) {
				observed.put("reason", "A %s has no selection state.".formatted(target.getClass().getSimpleName())); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return new Result(met, observed);
		}

		/** Never {@code getPart(true)}: a wait must not create the part it is waiting for. */
		private Control root(Display display) {
			if (part == null || part.isBlank()) {
				return Shells.select(display, shell);
			}
			if (!Workbenches.ide()) {
				return Workbenches.e4Part(part);
			}
			IWorkbenchPage page = page();
			IWorkbenchPartReference reference = page == null ? null : PartAddress.find(page, part);
			IWorkbenchPart workbenchPart = reference == null ? null : reference.getPart(false);
			return workbenchPart == null ? null : ScreenshotTools.Capture.controlOf(workbenchPart);
		}

		/**
		 * The first widget in tree order, rows and items included, whose text contains the needle.
		 * A virtual row that has not been materialized is skipped, since reading it would fire SetData.
		 */
		private static Widget search(Widget widget, String needle) {
			if (unmaterialized(widget)) {
				return null;
			}
			String text = text(widget);
			if (text != null && text.toLowerCase(Locale.ROOT).contains(needle)) {
				return widget;
			}
			for (Widget child : children(widget)) {
				Widget found = search(child, needle);
				if (found != null) {
					return found;
				}
			}
			return null;
		}

		private static boolean unmaterialized(Widget widget) {
			return widget instanceof Item item && item.getData() == null
					&& (item instanceof TreeItem row && (row.getParent().getStyle() & SWT.VIRTUAL) != 0
							|| item instanceof TableItem cell && (cell.getParent().getStyle() & SWT.VIRTUAL) != 0);
		}

		/** What a path can address below a widget: its rows, its items and its child controls. */
		private static List<Widget> children(Widget widget) {
			List<Widget> children = new ArrayList<>(Arrays.asList(WidgetTools.rowsOf(widget)));
			children.addAll(Arrays.asList(WidgetTools.itemsOf(widget)));
			if (widget instanceof Composite composite) {
				children.addAll(Arrays.asList(composite.getChildren()));
			}
			return children;
		}

		/** A widget's text as it holds it; mnemonic markers are stripped only from labels, never from what a user typed. */
		private static String text(Widget widget) {
			String content = switch (widget) {
			case org.eclipse.swt.widgets.Text field -> field.getText();
			case org.eclipse.swt.custom.StyledText field -> field.getText();
			case org.eclipse.swt.widgets.Combo combo -> combo.getText();
			default -> null;
			};
			if (content != null) {
				return content;
			}
			String label = switch (widget) {
			case ToolItem item -> item.getText().isEmpty() ? item.getToolTipText() : item.getText();
			case Item item -> item.getText();
			case Shell shell -> shell.getText();
			case org.eclipse.swt.widgets.Label control -> control.getText();
			case Button button -> button.getText();
			case org.eclipse.swt.widgets.Link link -> link.getText();
			case org.eclipse.swt.widgets.Group group -> group.getText();
			case org.eclipse.swt.custom.CLabel control -> control.getText();
			default -> null;
			};
			return label == null || widget instanceof Shell ? label : label.replaceAll("&(&?)", "$1"); //$NON-NLS-1$ //$NON-NLS-2$
		}

		private static Boolean selected(Widget widget) {
			return switch (widget) {
			case TreeItem row -> Boolean.valueOf(Arrays.asList(row.getParent().getSelection()).contains(row));
			case TableItem row -> Boolean.valueOf(row.getParent().indexOf(row) >= 0
					&& row.getParent().isSelected(row.getParent().indexOf(row)));
			case Button button -> Boolean.valueOf(button.getSelection());
			case ToolItem item -> Boolean.valueOf(item.getSelection());
			case CTabItem item -> Boolean.valueOf(item.getParent().getSelection() == item);
			case TabItem item -> Boolean.valueOf(Arrays.asList(item.getParent().getSelection()).contains(item));
			default -> null;
			};
		}

		private static Control owner(Widget widget) {
			return switch (widget) {
			case Control control -> control;
			case TreeItem row -> row.getParent();
			case TableItem row -> row.getParent();
			case ToolItem item -> item.getParent();
			case CTabItem item -> item.getParent();
			case TabItem item -> item.getParent();
			case org.eclipse.swt.widgets.TableColumn column -> column.getParent();
			case org.eclipse.swt.widgets.TreeColumn column -> column.getParent();
			default -> null;
			};
		}

		private static Integer rowCount(Widget widget) {
			return switch (widget) {
			case Tree tree -> Integer.valueOf(tree.getItemCount());
			case Table table -> Integer.valueOf(table.getItemCount());
			case TreeItem row -> Integer.valueOf(row.getItemCount());
			case org.eclipse.swt.widgets.Combo combo -> Integer.valueOf(combo.getItemCount());
			case org.eclipse.swt.widgets.List list -> Integer.valueOf(list.getItemCount());
			default -> null;
			};
		}
	}

	/** The selection of a part, or the workbench selection, is empty or holds an element with this label or path. */
	record SelectionCondition(String part, String contains, Boolean empty) implements WaitCondition {

		@Override
		public String kind() {
			return "selection"; //$NON-NLS-1$
		}

		@Override
		public Result evaluate(Display display) {
			if (!Workbenches.ide()) {
				return notIde();
			}
			IWorkbenchPage page = page();
			ISelection selection = null;
			if (part == null || part.isBlank()) {
				selection = page == null ? null : page.getSelection();
			} else {
				IWorkbenchPartReference reference;
				try {
					reference = page == null ? null : PartAddress.find(page, part);
				} catch (IllegalStateException e) {
					return new Result(false, new JsonObject().put("reason", e.getMessage())); //$NON-NLS-1$
				}
				IWorkbenchPart workbenchPart = reference == null ? null : reference.getPart(false);
				ISelectionProvider provider = workbenchPart == null ? null : workbenchPart.getSite().getSelectionProvider();
				if (provider == null) {
					return new Result(false, new JsonObject().put("reason", //$NON-NLS-1$
							workbenchPart == null ? "The part is not open or not created yet." : "The part has no selection provider.")); //$NON-NLS-1$ //$NON-NLS-2$
				}
				selection = provider.getSelection();
			}
			boolean isEmpty = selection == null || selection.isEmpty();
			JsonArray labels = new JsonArray();
			boolean found = false;
			int size = 0;
			if (!isEmpty && selection instanceof ITextSelection text) {
				// an editor's selection is text, which is what contains is matched against
				size = 1;
				labels.add(text.getText());
				found = contains != null && text.getText() != null && text.getText().contains(contains);
			} else if (selection instanceof IStructuredSelection structured) {
				size = structured.size();
				for (Object element : structured) {
					IResource resource = Adapters.adapt(element, IResource.class);
					String label = SelectionTools.label(element, resource);
					String path = resource == null ? null : resource.getFullPath().toString();
					if (labels.size() < 20) {
						labels.add(label);
					}
					found |= contains != null && (label != null && label.contains(contains) || path != null && path.contains(contains));
				}
			}
			boolean met = (empty == null || isEmpty == empty.booleanValue()) && (contains == null || found);
			return new Result(met, new JsonObject().put("empty", Boolean.valueOf(isEmpty)) //$NON-NLS-1$
					.put("size", Integer.valueOf(size)).put("labels", labels)); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/** An editor whose input path or title matches is open, closed or active, and optionally dirty or clean. */
	record EditorCondition(String input, String state, Boolean dirty) implements WaitCondition {

		@Override
		public String kind() {
			return "editor"; //$NON-NLS-1$
		}

		@Override
		public Result evaluate(Display display) {
			if (!Workbenches.ide()) {
				return notIde();
			}
			IWorkbenchPage page = page();
			IEditorPart activeEditor = page == null ? null : page.getActiveEditor();
			IEditorReference match = null;
			boolean active = false;
			if (page != null) {
				for (IEditorReference reference : page.getEditorReferences()) {
					if (!matches(reference)) {
						continue;
					}
					boolean isActive = activeEditor != null && activeEditor == reference.getEditor(false);
					// the active match wins, so state and dirty are read off the editor the caller sees
					if (match == null || isActive) {
						match = reference;
						active = isActive;
					}
				}
			}
			boolean open = match != null;
			boolean isDirty = open && match.isDirty();
			boolean inState = switch (state) {
			case "closed" -> !open; //$NON-NLS-1$
			case "active" -> active; //$NON-NLS-1$
			default -> open;
			};
			boolean met = inState && (dirty == null || !open || isDirty == dirty.booleanValue());
			JsonObject observed = new JsonObject().put("open", Boolean.valueOf(open)).put("active", Boolean.valueOf(active)); //$NON-NLS-1$ //$NON-NLS-2$
			if (open) {
				observed.put("dirty", Boolean.valueOf(isDirty)).put("title", match.getTitle()) //$NON-NLS-1$ //$NON-NLS-2$
						.put("path", PartAddress.inputPath(match)); //$NON-NLS-1$
			}
			return new Result(met, observed);
		}

		/** A leading slash is a workspace path and has to match exactly; anything else matches the path or the title as a substring. */
		private boolean matches(IEditorReference reference) {
			String path = PartAddress.inputPath(reference);
			if (input.startsWith("/")) { //$NON-NLS-1$
				return input.equals(path);
			}
			return path != null && path.contains(input) || reference.getTitle() != null && reference.getTitle().contains(input);
		}
	}

	/** No job of the scope, optionally restricted by name, is running or waiting to run. */
	record JobsCondition(String scope, String jobName) implements WaitCondition {

		@Override
		public String kind() {
			return "jobs"; //$NON-NLS-1$
		}

		@Override
		public boolean needsUi() {
			return false;
		}

		@Override
		public Result evaluate(Display display) {
			JsonArray running = new JsonArray();
			for (Job job : Job.getJobManager().find(null)) {
				if (busy(job)) {
					running.add(job.getName());
				}
			}
			return new Result(running.size() == 0, new JsonObject().put("running", running)); //$NON-NLS-1$
		}

		private boolean busy(Job job) {
			int jobState = job.getState();
			if ("build".equals(scope)) { //$NON-NLS-1$
				// a build scheduled after a change sleeps for its delay first, and that is the build a caller waits for
				boolean build = job.belongsTo(ResourcesPlugin.FAMILY_AUTO_BUILD) || job.belongsTo(ResourcesPlugin.FAMILY_MANUAL_BUILD)
						|| job.belongsTo(ResourcesPlugin.FAMILY_AUTO_REFRESH) || job.belongsTo(ResourcesPlugin.FAMILY_MANUAL_REFRESH);
				if (!build || jobState == Job.NONE) {
					return false;
				}
			} else if ((jobState != Job.RUNNING && jobState != Job.WAITING) || (jobName == null && job.isSystem())) {
				return false;
			}
			return jobName == null || job.getName() != null
					&& job.getName().toLowerCase(Locale.ROOT).contains(jobName.toLowerCase(Locale.ROOT));
		}
	}
}
