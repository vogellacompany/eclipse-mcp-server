package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.ui.ILaunchShortcut;
import org.eclipse.jface.viewers.StructuredSelection;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** Invokes a Run As or Debug As shortcut with resources named by the caller. */
public final class LaunchShortcutTool implements IMcpTool {

	private static final String EXTENSION_POINT = "org.eclipse.debug.ui.launchShortcuts"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_run_launch_shortcut"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Invokes a Run As or Debug As shortcut with a selection built from workspace paths or project names. RUNS PROJECT CODE when dryRun is false. This creates a custom launch configuration when no saved configuration exists, which eclipse_debug_launch cannot do. Identify one shortcut by shortcutId, label, or launchConfigurationTypeId. The launch is asynchronous; inspect eclipse_debug_status and eclipse_list_launch_configurations afterwards. A shortcut can open a modal dialog, so a timeout reports that it may still run and points to eclipse_dismiss_dialog."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "elements": {"type":"array","items":{"type":"string"},"description":"Workspace paths or project names for the shortcut selection."},
				    "mode": {"type":"string","enum":["run","debug"],"default":"run"},
				    "shortcutId": {"type":"string","description":"Exact launch shortcut id."},
				    "label": {"type":"string","description":"Case-insensitive substring of its menu label."},
				    "launchConfigurationTypeId": {"type":"string","description":"A launch configuration type declared by the shortcut."},
				    "timeoutSeconds": {"type":"integer","minimum":1,"maximum":120,"default":20,"description":"Wait for the shortcut call, bounded by the server timeout."},
				    "maxResults": {"type":"integer","minimum":1,"maximum":2000,"default":100,"description":"Maximum ambiguous shortcuts to report."},
				    "dryRun": {"type":"boolean","default":true,"description":"Report the shortcut and selection without invoking it."}
				  },
				  "required": ["elements"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		if (!(arguments != null && arguments.get("elements") instanceof List<?> requested) || requested.isEmpty()) { //$NON-NLS-1$
			return McpToolResult.error("Give 'elements' as a non-empty array of workspace paths or project names."); //$NON-NLS-1$
		}
		String mode = args.getString("mode", "run"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!"run".equals(mode) && !"debug".equals(mode)) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("mode must be 'run' or 'debug'."); //$NON-NLS-1$
		}
		String shortcutId = args.getString("shortcutId"); //$NON-NLS-1$
		String typeId = args.getString("launchConfigurationTypeId"); //$NON-NLS-1$
		String label = args.getString("label"); //$NON-NLS-1$
		if ((shortcutId != null ? 1 : 0) + (typeId != null ? 1 : 0) + (label != null ? 1 : 0) != 1) {
			return McpToolResult.error(
					"Give exactly one of 'shortcutId', 'label' or 'launchConfigurationTypeId' to identify the shortcut."); //$NON-NLS-1$
		}

		List<Object> elements = new ArrayList<>();
		JsonArray unresolved = new JsonArray();
		for (Object requestedElement : requested) {
			String spec = String.valueOf(requestedElement);
			Object element = SelectionTools.resolveResource(spec);
			if (element == null) {
				unresolved.add(spec);
			} else {
				elements.add(element);
			}
		}
		if (elements.isEmpty()) {
			return McpToolResult.error("None of the given elements resolved to a workspace resource."); //$NON-NLS-1$
		}

		List<Candidate> matches = shortcuts(mode, shortcutId, typeId, label);
		if (matches.isEmpty()) {
			return McpToolResult.error("No launch shortcut for mode '%s' matches the requested selector.".formatted(mode)); //$NON-NLS-1$
		}
		if (matches.size() > 1) {
			int maxResults = args.getInt("maxResults", 100, 1, 2000); //$NON-NLS-1$
			JsonArray candidates = new JsonArray();
			matches.stream().limit(maxResults).forEach(candidate -> candidates.add(candidate.describe()));
			return McpToolResult.of(new JsonObject().put("total", Integer.valueOf(matches.size())) //$NON-NLS-1$
					.put("truncated", Boolean.valueOf(matches.size() > maxResults)) //$NON-NLS-1$
					.put("candidates", candidates).toString()); //$NON-NLS-1$
		}

		Candidate shortcut = matches.get(0);
		JsonObject result = result(shortcut, mode, elements.size(), unresolved);
		if (args.getBoolean("dryRun", true)) { //$NON-NLS-1$
			return McpToolResult.of(result.put("dryRun", Boolean.TRUE).toString()); //$NON-NLS-1$
		}
		int requestedTimeout = args.getInt("timeoutSeconds", 20, 1, 120); //$NON-NLS-1$
		int timeout = CallBudget.boundedWaitSeconds(requestedTimeout);
		UiThread.TimedOutcome outcome = UiThread.timed(timeout, () -> launch(shortcut, elements, mode));
		if (outcome.error() != null) {
			return McpToolResult.error(outcome.error());
		}
		if (outcome.timedOut()) {
			return McpToolResult.of(result.put("timedOut", Boolean.TRUE) //$NON-NLS-1$
					.put("waitedSeconds", Integer.valueOf(timeout)) //$NON-NLS-1$
					.put("clamped", Boolean.valueOf(timeout < requestedTimeout)) //$NON-NLS-1$
					.put("note", "The shortcut may still be waiting in a modal dialog. Use eclipse_list_ui_targets and eclipse_dismiss_dialog, then inspect eclipse_debug_status and eclipse_list_launch_configurations.") //$NON-NLS-1$ //$NON-NLS-2$
					.toString());
		}
		return McpToolResult.of(result.put("invoked", Boolean.TRUE) //$NON-NLS-1$
				.put("note", "The shortcut was invoked asynchronously; inspect eclipse_debug_status and eclipse_list_launch_configurations.") //$NON-NLS-1$ //$NON-NLS-2$
				.toString());
	}

	private static JsonObject launch(Candidate shortcut, List<Object> elements, String mode) {
		try {
			ILaunchShortcut implementation = (ILaunchShortcut) shortcut.element().createExecutableExtension("class"); //$NON-NLS-1$
			implementation.launch(new StructuredSelection(elements), mode);
			return new JsonObject();
		} catch (CoreException e) {
			throw new IllegalStateException("Could not instantiate the shortcut: " + e.getMessage(), e); //$NON-NLS-1$
		}
	}

	private static JsonObject result(Candidate shortcut, String mode, int resolved, JsonArray unresolved) {
		return new JsonObject().put("matched", shortcut.describe()) //$NON-NLS-1$
				.put("mode", mode).put("resolved", Integer.valueOf(resolved)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("unresolved", unresolved); //$NON-NLS-1$
	}

	private static List<Candidate> shortcuts(String mode, String shortcutId, String typeId, String label) {
		List<Candidate> matches = new ArrayList<>();
		if (Platform.getExtensionRegistry() == null) {
			return matches;
		}
		for (IConfigurationElement element : Platform.getExtensionRegistry().getConfigurationElementsFor(EXTENSION_POINT)) {
			if (!"shortcut".equals(element.getName()) || !supports(element, mode)) { //$NON-NLS-1$
				continue;
			}
			String id = element.getAttribute("id"); //$NON-NLS-1$
			String shortcutLabel = element.getAttribute("label"); //$NON-NLS-1$
			if ((shortcutId != null && shortcutId.equals(id)) || (typeId != null && declares(element, typeId))
					|| (label != null && shortcutLabel != null
							&& shortcutLabel.toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT)))) {
				matches.add(new Candidate(element, id, shortcutLabel));
			}
		}
		return matches;
	}

	private static boolean supports(IConfigurationElement shortcut, String mode) {
		String modes = shortcut.getAttribute("modes"); //$NON-NLS-1$
		return modes != null && Arrays.stream(modes.split(",")).map(String::strip).anyMatch(mode::equals); //$NON-NLS-1$
	}

	private static boolean declares(IConfigurationElement shortcut, String typeId) {
		return Arrays.stream(shortcut.getChildren("configurationType")) //$NON-NLS-1$
				.anyMatch(type -> typeId.equals(type.getAttribute("id"))); //$NON-NLS-1$
	}

	private record Candidate(IConfigurationElement element, String id, String label) {
		JsonObject describe() {
			return new JsonObject().put("id", id).put("label", label); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}
}
