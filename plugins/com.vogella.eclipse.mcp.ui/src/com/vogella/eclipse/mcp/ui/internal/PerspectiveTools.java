package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists, switches and resets perspectives.
 */
public final class PerspectiveTools {

	private static final long UI_TIMEOUT_SECONDS = 15;

	private PerspectiveTools() {
	}

	/** Every registered perspective, and which of them is showing. */
	public static final class ListPerspectives implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_list_perspectives"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Lists the perspectives this IDE registers, which of them are open in the window and which one is active, so a caller does not have to guess an id for eclipse_switch_perspective. Reads only. A perspective is what decides which views exist and where they sit, so this is also the answer to 'why is the view I opened not where I expected': the layout belongs to the active perspective and nowhere else."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "filter":     {"type":"string","description":"Substring of the id or the label, case insensitive."},
					    "maxResults": {"type":"integer","default":100,"minimum":1,"maximum":500}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public String getOutputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "active":       {"type":["object","null"]},
					    "open":         {"type":"array","items":{"type":"string"}},
					    "perspectives": {"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"label":{"type":"string"},"open":{"type":"boolean"},"active":{"type":"boolean"}}}},
					    "total":        {"type":"integer"},
					    "truncated":    {"type":"boolean"}
					  },
					  "required": ["active", "open", "perspectives", "total", "truncated"]
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String filter = args.getString("filter"); //$NON-NLS-1$
			int maxResults = args.getInt("maxResults", 100, 1, 500); //$NON-NLS-1$
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> collect(filter, maxResults));
		}

		private static JsonObject collect(String filter, int maxResults) {
			IWorkbenchPage page = ViewTools.activePage();
			IPerspectiveDescriptor active = page == null ? null : page.getPerspective();
			List<IPerspectiveDescriptor> open = page == null ? List.of()
					: List.of(page.getOpenPerspectives());

			String needle = filter == null ? null : filter.toLowerCase(Locale.ROOT);
			List<IPerspectiveDescriptor> matching = new ArrayList<>();
			for (IPerspectiveDescriptor descriptor : PlatformUI.getWorkbench().getPerspectiveRegistry()
					.getPerspectives()) {
				if (needle == null || descriptor.getId().toLowerCase(Locale.ROOT).contains(needle)
						|| descriptor.getLabel().toLowerCase(Locale.ROOT).contains(needle)) {
					matching.add(descriptor);
				}
			}
			matching.sort((a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));

			JsonArray perspectives = new JsonArray();
			for (IPerspectiveDescriptor descriptor : matching.subList(0, Math.min(maxResults, matching.size()))) {
				perspectives.add(describe(descriptor).put("open", Boolean.valueOf(open.contains(descriptor))) //$NON-NLS-1$
						.put("active", Boolean.valueOf(descriptor.equals(active)))); //$NON-NLS-1$
			}
			JsonArray openIds = new JsonArray();
			for (IPerspectiveDescriptor descriptor : open) {
				openIds.add(descriptor.getId());
			}
			return new JsonObject().put("active", active == null ? null : describe(active)) //$NON-NLS-1$
					.put("open", openIds) //$NON-NLS-1$
					.put("perspectives", perspectives) //$NON-NLS-1$
					.put("total", Integer.valueOf(matching.size())) //$NON-NLS-1$
					.put("truncated", Boolean.valueOf(matching.size() > maxResults)); //$NON-NLS-1$
		}
	}

	/** Shows another perspective in the active window. */
	public static final class Switch implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_switch_perspective"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Switches the active window to another perspective, named by id or by the label a person reads, so 'Debug' works as well as org.eclipse.debug.ui.DebugPerspective. CHANGES WHAT THE USER SEES and replaces the whole view layout, though it writes nothing to the workspace. Use it to reach the views a perspective brings with it rather than opening them one by one, and to reach the layouts that only exist per perspective. The answer reports previousPerspective, so a caller can put the IDE back. An ambiguous name is refused with the candidates rather than guessed."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "required": ["perspective"],
					  "properties": {
					    "perspective": {"type":"string","description":"Perspective id, or its label, e.g. org.eclipse.jdt.ui.JavaPerspective or Java. Use eclipse_list_perspectives."},
					    "reset":       {"type":"boolean","default":false,"description":"Reset it to its registered layout after switching, discarding views the user moved, opened or closed in it."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public String getOutputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "switched":            {"type":"boolean"},
					    "reason":              {"type":"string"},
					    "note":                {"type":"string"},
					    "candidates":          {"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"label":{"type":"string"}}}},
					    "id":                  {"type":"string"},
					    "label":               {"type":"string"},
					    "wasAlreadyActive":    {"type":"boolean"},
					    "reset":               {"type":"boolean"},
					    "previousPerspective": {"type":["object","null"]}
					  },
					  "required": ["switched"]
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String wanted = args.getString("perspective"); //$NON-NLS-1$
			if (wanted == null) {
				return McpToolResult.error("The argument 'perspective' is required."); //$NON-NLS-1$
			}
			boolean reset = args.getBoolean("reset", false); //$NON-NLS-1$
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> apply(wanted, reset));
		}

		private static JsonObject apply(String wanted, boolean reset) {
			IWorkbenchPage page = ViewTools.activePage();
			if (page == null) {
				return new JsonObject().put("switched", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "The workbench has no active page."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			List<IPerspectiveDescriptor> matches = match(wanted);
			if (matches.isEmpty()) {
				return new JsonObject().put("switched", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "No registered perspective matches '%s'.".formatted(wanted)) //$NON-NLS-1$ //$NON-NLS-2$
						.put("note", "Use eclipse_list_perspectives to see the ids."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (matches.size() > 1) {
				JsonArray candidates = new JsonArray();
				for (IPerspectiveDescriptor descriptor : matches) {
					candidates.add(describe(descriptor));
				}
				return new JsonObject().put("switched", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "'%s' matches %d perspectives; name one of them exactly." //$NON-NLS-1$
								.formatted(wanted, Integer.valueOf(matches.size())))
						.put("candidates", candidates); //$NON-NLS-1$
			}
			IPerspectiveDescriptor target = matches.get(0);
			IPerspectiveDescriptor previous = page.getPerspective();
			page.setPerspective(target);
			if (reset) {
				page.resetPerspective();
			}
			return new JsonObject().put("switched", Boolean.TRUE) //$NON-NLS-1$
					.put("id", target.getId()) //$NON-NLS-1$
					.put("label", target.getLabel()) //$NON-NLS-1$
					.put("wasAlreadyActive", Boolean.valueOf(target.equals(previous))) //$NON-NLS-1$
					.put("reset", Boolean.valueOf(reset)) //$NON-NLS-1$
					.put("previousPerspective", previous == null ? null : describe(previous)) //$NON-NLS-1$
					.put("note", "Pass previousPerspective.id back to return to it."); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/** Puts the active perspective back to the layout it was registered with. */
	public static final class Reset implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_reset_perspective"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Resets the active perspective to the layout it was registered with, without the confirmation dialog the menu entry shows. CHANGES WHAT THE USER SEES and DISCARDS the layout that perspective currently has: views opened, closed, resized, detached or moved in it go back to where the perspective factory put them. That is what makes it the undo for eclipse_show_view, eclipse_hide_view, eclipse_move_part and eclipse_set_part_state, none of which can be undone individually, and Eclipse remembers those changes across restarts. It cannot bring back a layout, so on somebody's running IDE it is worth asking first."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "confirm": {"type":"boolean","default":false,"description":"Required to be true. Resetting cannot be undone, so it does not happen by accident."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public String getOutputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "reset":       {"type":"boolean"},
					    "reason":      {"type":"string"},
					    "perspective": {"type":"object","properties":{"id":{"type":"string"},"label":{"type":"string"}}}
					  },
					  "required": ["reset"]
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			if (!args.getBoolean("confirm", false)) { //$NON-NLS-1$
				return McpToolResult
						.error("Resetting discards the current layout of the active perspective and cannot be undone. Pass confirm true."); //$NON-NLS-1$
			}
			return UiThread.call(UI_TIMEOUT_SECONDS, PerspectiveTools::reset);
		}
	}

	private static JsonObject reset() {
		IWorkbenchPage page = ViewTools.activePage();
		if (page == null) {
			return new JsonObject().put("reset", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "The workbench has no active page."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		IPerspectiveDescriptor active = page.getPerspective();
		if (active == null) {
			return new JsonObject().put("reset", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "The active page has no perspective."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		page.resetPerspective();
		return new JsonObject().put("reset", Boolean.TRUE).put("perspective", describe(active)); //$NON-NLS-1$ //$NON-NLS-2$
	}

	static JsonObject describe(IPerspectiveDescriptor descriptor) {
		return new JsonObject().put("id", descriptor.getId()).put("label", descriptor.getLabel()); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Exact id, then exact label, then substring, stopping at the first step that
	 * matches anything, for the same reason {@link ViewTools#match} does it.
	 */
	static List<IPerspectiveDescriptor> match(String wanted) {
		IPerspectiveDescriptor[] all = PlatformUI.getWorkbench().getPerspectiveRegistry().getPerspectives();
		for (IPerspectiveDescriptor descriptor : all) {
			if (descriptor.getId().equals(wanted)) {
				return List.of(descriptor);
			}
		}
		List<IPerspectiveDescriptor> byLabel = new ArrayList<>();
		for (IPerspectiveDescriptor descriptor : all) {
			if (wanted.equalsIgnoreCase(descriptor.getLabel())) {
				byLabel.add(descriptor);
			}
		}
		if (!byLabel.isEmpty()) {
			return byLabel;
		}
		String needle = wanted.toLowerCase(Locale.ROOT);
		List<IPerspectiveDescriptor> partial = new ArrayList<>();
		for (IPerspectiveDescriptor descriptor : all) {
			if (descriptor.getLabel().toLowerCase(Locale.ROOT).contains(needle)
					|| descriptor.getId().toLowerCase(Locale.ROOT).contains(needle)) {
				partial.add(descriptor);
			}
		}
		return partial;
	}
}
