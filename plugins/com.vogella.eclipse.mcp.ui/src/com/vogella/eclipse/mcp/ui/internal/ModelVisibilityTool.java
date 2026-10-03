package com.vogella.eclipse.mcp.ui.internal;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Shows and hides elements of the e4 application model by id.
 * <p>
 * This is the other visibility, and the difference from
 * eclipse_set_ide_visibility matters. That one calls
 * {@code Shell.setVisible} and touches nothing the workbench remembers, so it
 * cannot outlive the session. This one writes the model, which is what reaches
 * a trim bar or one window of several, and which the workbench PERSISTS on
 * exit: an element hidden here comes back hidden at the next start.
 */
public final class ModelVisibilityTool implements IMcpTool {

	private static final long UI_TIMEOUT_SECONDS = 10;

	@Override
	public String getName() {
		return "eclipse_set_model_visibility"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Shows or hides an element of the e4 application model by its id, which is what reaches the things no other tool here can: the status line and other trim bars, the main menu, and one window out of several. CHANGES WHAT THE USER SEES AND IS REMEMBERED: unlike eclipse_set_ide_visibility, which only calls Shell.setVisible for the session, this writes the model, and the workbench saves the model on exit, so an element hidden here is still hidden after a restart and there is no menu entry to undo it with. Runs as a dry run unless dryRun is set to false, and the dry run lists what the id matches. Two flags exist and they are not the same: 'visible' takes the element out of the layout while the renderer keeps it, and 'toBeRendered' throws the widget away entirely, which is harder to reason about and is refused for a window. HIDING THE ONLY WINDOW THIS WAY IS A TRAP: it persists, so the IDE starts invisible next time; use eclipse_set_ide_visibility for that, which forgets."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["elementId"],
				  "properties": {
				    "elementId":    {"type":"string","description":"Id of the model element, as the Model Spy shows it, for instance org.eclipse.ui.trim.status or IDEWindow."},
				    "visible":      {"type":"boolean","description":"The model's visible flag: false takes it out of the layout. Omit to leave it as it is."},
				    "toBeRendered": {"type":"boolean","description":"The model's toBeRendered flag: false discards the widget rather than hiding it. Omit to leave it as it is; refused for a window."},
				    "dryRun":       {"type":"boolean","default":true,"description":"Report what the id matches and what would change, and change nothing."},
				    "maxResults":   {"type":"integer","default":20,"minimum":1,"maximum":200,"description":"Matching elements reported."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return McpToolResult.error(Workbenches.noIde());
		}
		ToolArguments args = ToolArguments.of(arguments);
		String elementId = args.getString("elementId"); //$NON-NLS-1$
		if (elementId == null) {
			return McpToolResult.error("The argument 'elementId' is required. eclipse_get_widget_tree and the Model Spy show the ids."); //$NON-NLS-1$
		}
		Boolean visible = args.has("visible") ? Boolean.valueOf(args.getBoolean("visible", true)) : null; //$NON-NLS-1$ //$NON-NLS-2$
		Boolean rendered = args.has("toBeRendered") ? Boolean.valueOf(args.getBoolean("toBeRendered", true)) : null; //$NON-NLS-1$ //$NON-NLS-2$
		if (visible == null && rendered == null) {
			return McpToolResult.error("Nothing to change: pass 'visible', 'toBeRendered', or both."); //$NON-NLS-1$
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 20, 1, 200); //$NON-NLS-1$

		UiThread.Outcome outcome = UiThread.run(UI_TIMEOUT_SECONDS,
				() -> apply(elementId, visible, rendered, dryRun, maxResults));
		if (outcome.error() != null) {
			return McpToolResult.error("Could not change the model: " + outcome.error()); //$NON-NLS-1$
		}
		JsonObject result = outcome.value();
		return Boolean.FALSE.equals(result.remove("ok")) ? McpToolResult.error(result.toString()) //$NON-NLS-1$
				: McpToolResult.of(result.toString());
	}

	private static JsonObject apply(String elementId, Boolean visible, Boolean rendered, boolean dryRun,
			int maxResults) {
		IEclipseContext context = PlatformUI.getWorkbench().getService(IEclipseContext.class);
		EModelService modelService = context == null ? null : context.get(EModelService.class);
		MApplication application = context == null ? null : context.get(MApplication.class);
		if (modelService == null || application == null) {
			return new JsonObject().put("ok", Boolean.FALSE) //$NON-NLS-1$
					.put("error", "This workbench exposes no e4 model service."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		List<MUIElement> found = modelService.findElements(application, elementId, MUIElement.class, null,
				EModelService.ANYWHERE | EModelService.IN_MAIN_MENU | EModelService.IN_TRIM);
		if (found.isEmpty()) {
			return new JsonObject().put("ok", Boolean.FALSE) //$NON-NLS-1$
					.put("error", //$NON-NLS-1$
							"No model element with id '%s'. eclipse_get_widget_tree and the Model Spy list the ids; a view's id is the part id." //$NON-NLS-1$
									.formatted(elementId));
		}
		JsonArray elements = new JsonArray();
		int changed = 0;
		for (MUIElement element : found) {
			// every match is changed; maxResults only caps what is reported
			boolean report = elements.size() < maxResults;
			JsonObject entry = new JsonObject().put("id", element.getElementId()) //$NON-NLS-1$
					.put("type", element.getClass().getInterfaces().length == 0 ? element.getClass().getSimpleName() //$NON-NLS-1$
							: element.getClass().getInterfaces()[0].getSimpleName())
					.put("wasVisible", Boolean.valueOf(element.isVisible())) //$NON-NLS-1$
					.put("wasToBeRendered", Boolean.valueOf(element.isToBeRendered())); //$NON-NLS-1$
			boolean isWindow = element instanceof org.eclipse.e4.ui.model.application.ui.basic.MWindow;
			if (rendered != null && !rendered.booleanValue() && isWindow) {
				if (report) {
					elements.add(entry.put("changed", Boolean.FALSE) //$NON-NLS-1$
							.put("refusedBecause", //$NON-NLS-1$
									"Discarding a window's widget leaves the workbench without one, and the model remembers it. Hide the IDE with eclipse_set_ide_visibility instead, which does not persist.")); //$NON-NLS-1$
				}
				continue;
			}
			if (!dryRun) {
				if (visible != null) {
					element.setVisible(visible.booleanValue());
				}
				if (rendered != null) {
					element.setToBeRendered(rendered.booleanValue());
				}
				changed++;
			}
			if (report) {
				elements.add(entry.put("changed", Boolean.valueOf(!dryRun)) //$NON-NLS-1$
						.put("visible", visible == null ? Boolean.valueOf(element.isVisible()) : visible) //$NON-NLS-1$
						.put("toBeRendered", rendered == null ? Boolean.valueOf(element.isToBeRendered()) : rendered)); //$NON-NLS-1$
			}
		}
		return new JsonObject().put("elementId", elementId) //$NON-NLS-1$
				.put("matched", Integer.valueOf(found.size())) //$NON-NLS-1$
				.put("changed", Integer.valueOf(changed)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(found.size() > elements.size())) //$NON-NLS-1$
				.put("elements", elements) //$NON-NLS-1$
				.put("note", note(dryRun, visible)); //$NON-NLS-1$
	}

	private static String note(boolean dryRun, Boolean visible) {
		if (dryRun) {
			return "Nothing was changed. Pass dryRun false to apply it, and note that the workbench saves the model on exit, so this outlives the session."; //$NON-NLS-1$
		}
		if (Boolean.FALSE.equals(visible)) {
			return "THIS IS REMEMBERED: the workbench saves the model when it exits, so the element stays hidden after a restart. Call this again with visible true to bring it back; nothing in the IDE's own menus will."; //$NON-NLS-1$
		}
		return "The model was changed, and the workbench saves it on exit, so this survives a restart."; //$NON-NLS-1$
	}
}
