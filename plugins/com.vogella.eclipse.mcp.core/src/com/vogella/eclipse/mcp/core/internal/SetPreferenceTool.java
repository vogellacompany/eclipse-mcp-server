package com.vogella.eclipse.mcp.core.internal;

import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.osgi.service.prefs.BackingStoreException;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.UiDispatch;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Writes a single preference and reports what was there before.
 * <p>
 * There is no qualifier allowlist, since other tools of this server write any qualifier anyway.
 */
public final class SetPreferenceTool implements IMcpTool {

	private static final String AUTOBUILD_QUALIFIER = "org.eclipse.core.resources"; //$NON-NLS-1$

	private static final String AUTOBUILD_KEY = "description.autobuilding"; //$NON-NLS-1$

	private static final String THEME_QUALIFIER = "org.eclipse.e4.ui.css.swt.theme"; //$NON-NLS-1$

	private static final String THEMEID_KEY = "themeid"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_set_preference"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Writes a single preference in the instance or project scope and returns the previous value, so the change can be undone. MODIFIES THE IDE CONFIGURATION: any qualifier may be written, and a wrong preference is both invisible and long-lived, so keep the previous value out of the answer, since nothing else records it. Auto-build (org.eclipse.core.resources, description.autobuilding) is applied through the workspace description rather than as a raw preference write, because writing the raw key does not take effect properly. The key themeid under org.eclipse.e4.ui.css.swt.theme is refused: the write reaches disk, but at the next startup the engine resolves the persisted id against the registered themes and an id that does not resolve is replaced by a fallback that is persisted over it, so it is not a way to switch themes. Use eclipse_set_theme."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "qualifier": {"type":"string","description":"Preference qualifier, such as org.eclipse.jdt.core. eclipse_get_preferences reads them, which is how to find the key you mean before writing it."},
				    "key":       {"type":"string","description":"Preference key."},
				    "value":     {"type":"string","description":"New value. Omit to remove the key, which lets the value below it in the lookup order take over."},
				    "scope":     {"type":"string","enum":["instance","project"],"default":"instance"},
				    "project":   {"type":"string","description":"Project name, required for the project scope."}
				  },
				  "required": ["qualifier","key"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String qualifier = args.getString("qualifier"); //$NON-NLS-1$
		String key = args.getString("key"); //$NON-NLS-1$
		if (qualifier == null || key == null) {
			return McpToolResult.error("The arguments 'qualifier' and 'key' are both required."); //$NON-NLS-1$
		}
		if (THEME_QUALIFIER.equals(qualifier) && THEMEID_KEY.equals(key)) {
			return McpToolResult.error(
					"Writing %s/%s is refused: the write reaches disk, but at the next startup the theme engine resolves the persisted id against the registered themes, and an id that does not resolve is replaced by a fallback that is persisted over it. Writing it is not a way to switch themes; use eclipse_set_theme instead."
							.formatted(THEME_QUALIFIER, THEMEID_KEY)); //$NON-NLS-1$
		}
		String scope = args.getString("scope", "instance"); //$NON-NLS-1$ //$NON-NLS-2$
		String projectName = args.getString("project"); //$NON-NLS-1$
		IScopeContext context = PreferenceScopes.writableContext(scope, projectName);
		if (context == null) {
			return McpToolResult.error("project".equals(scope) && projectName == null //$NON-NLS-1$
					? "The scope 'project' needs a 'project' argument." //$NON-NLS-1$
					: "Cannot write the scope '%s' for project '%s'.".formatted(scope, projectName)); //$NON-NLS-1$
		}
		String value = args.getString("value"); //$NON-NLS-1$

		IEclipsePreferences node = context.getNode(qualifier);
		String previous = node.get(key, null);

		if (AUTOBUILD_QUALIFIER.equals(qualifier) && AUTOBUILD_KEY.equals(key)) {
			return setAutoBuilding(value, previous);
		}
		// the write fires its listeners synchronously, and editors answer a change
		// by touching widgets, so it has to happen on the UI thread when there is one
		try {
			UiDispatch.call(() -> {
				if (value == null) {
					node.remove(key);
				} else {
					node.put(key, value);
				}
				node.flush();
				return null;
			}, CallBudget.maxWaitSeconds());
		} catch (TimeoutException e) {
			return McpToolResult.error(
					"The Eclipse UI did not process the write of %s/%s within %d seconds. It is still queued and may land later; read it back with eclipse_get_preferences." //$NON-NLS-1$
							.formatted(qualifier, key, Integer.valueOf(CallBudget.maxWaitSeconds())));
		} catch (BackingStoreException e) {
			throw new McpToolException("Could not save the preference %s/%s".formatted(qualifier, key), e); //$NON-NLS-1$
		} catch (Exception e) {
			throw new McpToolException("Could not write the preference %s/%s".formatted(qualifier, key), e); //$NON-NLS-1$
		}
		Map<String, IEclipsePreferences> nodes = PreferenceScopes.nodes(qualifier, projectName);
		String effective = null;
		String effectiveScope = null;
		for (String candidate : PreferenceScopes.LOOKUP_ORDER) {
			IEclipsePreferences lookup = nodes.get(candidate);
			String found = lookup == null ? null : lookup.get(key, null);
			if (found != null) {
				effective = found;
				effectiveScope = candidate;
				break;
			}
		}
		return McpToolResult.of(new JsonObject().put("qualifier", qualifier) //$NON-NLS-1$
				.put("key", key) //$NON-NLS-1$
				.put("scope", scope) //$NON-NLS-1$
				.put("project", projectName) //$NON-NLS-1$
				.put("previousValue", previous) //$NON-NLS-1$
				.put("effective", effective) //$NON-NLS-1$
				.put("effectiveScope", effectiveScope) //$NON-NLS-1$
				.toString());
	}

	/**
	 * Auto-build lives in the workspace description. Writing the raw preference key
	 * is the common way to get this subtly wrong, so it is special cased.
	 */
	private static McpToolResult setAutoBuilding(String value, String previous) throws McpToolException {
		if (value == null) {
			return McpToolResult.error("Auto-build cannot be removed, only set to true or false."); //$NON-NLS-1$
		}
		if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("Auto-build takes 'true' or 'false', not '%s'.".formatted(value)); //$NON-NLS-1$
		}
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		boolean was = workspace.isAutoBuilding();
		IWorkspaceDescription description = workspace.getDescription();
		description.setAutoBuilding(Boolean.parseBoolean(value));
		try {
			workspace.setDescription(description);
		} catch (CoreException e) {
			throw new McpToolException("Could not change auto-build", e); //$NON-NLS-1$
		}
		return McpToolResult.of(new JsonObject().put("qualifier", AUTOBUILD_QUALIFIER) //$NON-NLS-1$
				.put("key", AUTOBUILD_KEY) //$NON-NLS-1$
				.put("scope", "instance") //$NON-NLS-1$ //$NON-NLS-2$
				.put("appliedThrough", "IWorkspaceDescription.setAutoBuilding") //$NON-NLS-1$ //$NON-NLS-2$
				.put("previousValue", previous == null ? String.valueOf(was) : previous) //$NON-NLS-1$
				.put("effective", String.valueOf(workspace.isAutoBuilding())) //$NON-NLS-1$
				.put("effectiveScope", "instance") //$NON-NLS-1$ //$NON-NLS-2$
				.toString());
	}
}
