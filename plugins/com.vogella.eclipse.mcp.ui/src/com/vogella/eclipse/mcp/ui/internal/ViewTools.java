package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.views.IViewDescriptor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Opens and closes workbench views.
 */
public final class ViewTools {

	private static final long UI_TIMEOUT_SECONDS = 10;

	private static final int MAX_CANDIDATES = 20;

	private ViewTools() {
	}

	/** Opens a view, by id or by the label a person reads. */
	public static final class Show implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_show_view"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Opens a view in the active perspective, so the person at the IDE is looking at the Problems, Console or Git view you are talking about. The view is named by id or by its label, so 'Problems' works as well as org.eclipse.ui.views.ProblemView; an ambiguous name is refused with the candidates rather than guessed. CHANGES THE PERSPECTIVE LAYOUT, which Eclipse remembers across restarts, but writes nothing to the workspace. Use eclipse_list_ui_targets with includeAvailableViews to see what can be opened."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "required": ["view"],
					  "properties": {
					    "view":        {"type":"string","description":"View id, or its label, e.g. org.eclipse.ui.views.ProblemView or Problems."},
					    "secondaryId": {"type":"string","description":"Secondary id, for views that can be open more than once such as the Console."},
					    "activate":    {"type":"boolean","default":true,"description":"Give the view focus. False makes it visible without taking focus away from the editor."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String wanted = args.getString("view"); //$NON-NLS-1$
			if (wanted == null) {
				return McpToolResult.error("The argument 'view' is required."); //$NON-NLS-1$
			}
			String secondaryId = args.getString("secondaryId"); //$NON-NLS-1$
			boolean activate = args.getBoolean("activate", true); //$NON-NLS-1$
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> show(wanted, secondaryId, activate));
		}

		private static JsonObject show(String wanted, String secondaryId, boolean activate) {
			IWorkbenchPage page = activePage();
			if (page == null) {
				return new JsonObject().put("shown", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "The workbench has no active page."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			List<IViewDescriptor> matches = match(wanted);
			if (matches.isEmpty()) {
				return new JsonObject().put("shown", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "No registered view matches '%s'.".formatted(wanted)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (matches.size() > 1) {
				return new JsonObject().put("shown", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "'%s' matches %d views; name one of them exactly." //$NON-NLS-1$
								.formatted(wanted, Integer.valueOf(matches.size())))
						.put("candidates", describe(matches)); //$NON-NLS-1$
			}
			IViewDescriptor descriptor = matches.get(0);
			boolean alreadyOpen = page.findViewReference(descriptor.getId(), secondaryId) != null;
			try {
				IViewPart view = page.showView(descriptor.getId(), secondaryId,
						activate ? IWorkbenchPage.VIEW_ACTIVATE : IWorkbenchPage.VIEW_VISIBLE);
				return new JsonObject().put("shown", Boolean.TRUE) //$NON-NLS-1$
						.put("id", descriptor.getId()) //$NON-NLS-1$
						.put("title", view.getTitle()) //$NON-NLS-1$
						.put("secondaryId", secondaryId) //$NON-NLS-1$
						.put("wasAlreadyOpen", Boolean.valueOf(alreadyOpen)) //$NON-NLS-1$
						.put("activated", Boolean.valueOf(activate)) //$NON-NLS-1$
						.put("perspective", perspective(page)); //$NON-NLS-1$
			} catch (PartInitException e) {
				return new JsonObject().put("shown", Boolean.FALSE) //$NON-NLS-1$
						.put("id", descriptor.getId()) //$NON-NLS-1$
						.put("reason", String.valueOf(e.getMessage())); //$NON-NLS-1$
			}
		}
	}

	/** Closes a view that is open in the active perspective. */
	public static final class Hide implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_hide_view"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Closes an open view in the active perspective, named by id or by its title. Only views that are currently open can be closed, and the answer lists what is open when nothing matches. CHANGES THE PERSPECTIVE LAYOUT, which Eclipse remembers across restarts, but writes nothing to the workspace. This does not close editors, which eclipse_close_editor does."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "required": ["view"],
					  "properties": {
					    "view":        {"type":"string","description":"View id, or its title as shown on the tab."},
					    "secondaryId": {"type":"string","description":"Secondary id, when the view is open more than once."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String wanted = args.getString("view"); //$NON-NLS-1$
			if (wanted == null) {
				return McpToolResult.error("The argument 'view' is required."); //$NON-NLS-1$
			}
			String secondaryId = args.getString("secondaryId"); //$NON-NLS-1$
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> hide(wanted, secondaryId));
		}

		private static JsonObject hide(String wanted, String secondaryId) {
			IWorkbenchPage page = activePage();
			if (page == null) {
				return new JsonObject().put("hidden", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "The workbench has no active page."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			// resolved against open views: a closed view cannot be closed, and the registered label is not the tab text with a secondary id
			List<IViewReference> open = new ArrayList<>(List.of(page.getViewReferences()));
			List<IViewReference> matches = new ArrayList<>();
			for (IViewReference reference : open) {
				if (secondaryId != null && !secondaryId.equals(reference.getSecondaryId())) {
					continue;
				}
				if (reference.getId().equals(wanted) || wanted.equalsIgnoreCase(reference.getTitle())) {
					matches.add(reference);
				}
			}
			if (matches.isEmpty()) {
				JsonArray openViews = new JsonArray();
				for (IViewReference reference : open) {
					openViews.add(new JsonObject().put("id", reference.getId()) //$NON-NLS-1$
							.put("title", reference.getTitle()) //$NON-NLS-1$
							.put("secondaryId", reference.getSecondaryId())); //$NON-NLS-1$
				}
				return new JsonObject().put("hidden", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "No open view matches '%s'.".formatted(wanted)) //$NON-NLS-1$ //$NON-NLS-2$
						.put("openViews", openViews); //$NON-NLS-1$
			}
			if (matches.size() > 1) {
				JsonArray candidates = new JsonArray();
				for (IViewReference reference : matches) {
					candidates.add(new JsonObject().put("id", reference.getId()) //$NON-NLS-1$
							.put("secondaryId", reference.getSecondaryId())); //$NON-NLS-1$
				}
				return new JsonObject().put("hidden", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "'%s' is open %d times; pass secondaryId to say which." //$NON-NLS-1$
								.formatted(wanted, Integer.valueOf(matches.size())))
						.put("candidates", candidates); //$NON-NLS-1$
			}
			IViewReference reference = matches.get(0);
			JsonObject result = new JsonObject().put("hidden", Boolean.TRUE) //$NON-NLS-1$
					.put("id", reference.getId()) //$NON-NLS-1$
					.put("title", reference.getTitle()) //$NON-NLS-1$
					.put("secondaryId", reference.getSecondaryId()) //$NON-NLS-1$
					.put("perspective", perspective(page)); //$NON-NLS-1$
			page.hideView(reference);
			return result;
		}
	}

	static IWorkbenchPage activePage() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		return window == null ? null : window.getActivePage();
	}

	private static String perspective(IWorkbenchPage page) {
		return page.getPerspective() == null ? null : page.getPerspective().getLabel();
	}

	/**
	 * Narrows from exact id, to exact label, to substring, and stops at the first
	 * step that finds anything. Without the ordering, an exact id that is also a
	 * substring of three other ids comes back ambiguous.
	 */
	static List<IViewDescriptor> match(String wanted) {
		IViewDescriptor[] all = PlatformUI.getWorkbench().getViewRegistry().getViews();
		for (IViewDescriptor descriptor : all) {
			if (descriptor.getId().equals(wanted)) {
				return List.of(descriptor);
			}
		}
		List<IViewDescriptor> byLabel = new ArrayList<>();
		for (IViewDescriptor descriptor : all) {
			if (wanted.equalsIgnoreCase(descriptor.getLabel())) {
				byLabel.add(descriptor);
			}
		}
		if (!byLabel.isEmpty()) {
			return byLabel;
		}
		String needle = wanted.toLowerCase(Locale.ROOT);
		List<IViewDescriptor> partial = new ArrayList<>();
		for (IViewDescriptor descriptor : all) {
			if (descriptor.getLabel().toLowerCase(Locale.ROOT).contains(needle)
					|| descriptor.getId().toLowerCase(Locale.ROOT).contains(needle)) {
				partial.add(descriptor);
			}
		}
		return partial;
	}

	static JsonArray describe(List<IViewDescriptor> descriptors) {
		return describe(descriptors, MAX_CANDIDATES);
	}

	static JsonArray describe(List<IViewDescriptor> descriptors, int limit) {
		JsonArray array = new JsonArray();
		for (IViewDescriptor descriptor : descriptors.subList(0, Math.min(limit, descriptors.size()))) {
			array.add(new JsonObject().put("id", descriptor.getId()) //$NON-NLS-1$
					.put("label", descriptor.getLabel()) //$NON-NLS-1$
					.put("category", category(descriptor))); //$NON-NLS-1$
		}
		return array;
	}

	private static String category(IViewDescriptor descriptor) {
		String[] path = descriptor.getCategoryPath();
		return path == null || path.length == 0 ? null : String.join("/", path); //$NON-NLS-1$
	}
}
