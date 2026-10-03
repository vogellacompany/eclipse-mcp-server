package com.vogella.eclipse.mcp.basic.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.osgi.service.prefs.BackingStoreException;

import com.vogella.eclipse.mcp.core.Globs;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports preference values and the scope each of them comes from.
 */
public final class GetPreferencesTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 200;

	private static final Set<String> SCOPES = Set.of("instance", "project", "configuration", "default", "all"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

	@Override
	public String getName() {
		return "eclipse_get_preferences"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reads Eclipse preferences for a qualifier, for example org.eclipse.jdt.core or org.eclipse.core.resources, and reports which scope each value comes from. Use it to find out what has actually been customized in this workspace or project, for instance whether auto-build is off (org.eclipse.core.resources, key description.autobuilding)."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "qualifier":       {"type":"string","description":"Preference qualifier, usually a bundle symbolic name such as org.eclipse.jdt.core."},
				    "key":             {"type":"string","description":"Exact preference key. Omit to list the keys of the qualifier."},
				    "keyPattern":      {"type":"string","description":"Glob over keys for discovery, '*' and '?' allowed, case insensitive."},
				    "scope":           {"type":"string","enum":["instance","project","configuration","default","all"],"default":"instance","description":"Only list keys that are set in this scope. 'all' lists keys set in any scope."},
				    "project":         {"type":"string","description":"Project name, required for the project scope."},
				    "includeDefaults": {"type":"boolean","default":false,"description":"Also list keys that are only set in the default scope. The default value of a listed key is always reported, whatever this is set to."},
				    "maxResults":      {"type":"integer","default":200,"minimum":1,"maximum":2000}
				  },
				  "required": ["qualifier"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String qualifier = args.getString("qualifier"); //$NON-NLS-1$
		if (qualifier == null) {
			return McpToolResult.error("The argument 'qualifier' is required."); //$NON-NLS-1$
		}
		String scope = args.getString("scope", "instance"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!SCOPES.contains(scope)) {
			return McpToolResult
					.error("Unknown scope '%s', expected one of instance, project, configuration, default, all." //$NON-NLS-1$
							.formatted(scope));
		}
		String projectName = args.getString("project"); //$NON-NLS-1$
		if ("project".equals(scope) && projectName == null) { //$NON-NLS-1$
			return McpToolResult.error("The scope 'project' needs a 'project' argument."); //$NON-NLS-1$
		}
		if (projectName != null) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (!project.isAccessible()) {
				return McpToolResult.error("No open project named '%s' in this workspace.".formatted(projectName)); //$NON-NLS-1$
			}
		}
		String key = args.getString("key"); //$NON-NLS-1$
		Pattern keyPattern;
		try {
			keyPattern = Globs.compile(args.getString("keyPattern")); //$NON-NLS-1$
		} catch (PatternSyntaxException e) {
			return McpToolResult.error("Could not read 'keyPattern' as a glob: " + e.getMessage()); //$NON-NLS-1$
		}
		boolean includeDefaults = args.getBoolean("includeDefaults", false); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 2000); //$NON-NLS-1$

		Map<String, IEclipsePreferences> nodes = PreferenceScopes.nodes(qualifier, projectName);
		Map<String, Set<String>> keysByScope;
		try {
			keysByScope = keysByScope(nodes);
		} catch (BackingStoreException e) {
			throw new McpToolException("Could not read the preferences of " + qualifier, e); //$NON-NLS-1$
		}

		List<String> keys = new ArrayList<>(select(keysByScope, scope, includeDefaults));
		keys.removeIf(candidate -> key != null && !key.equals(candidate));
		keys.removeIf(candidate -> keyPattern != null && !keyPattern.matcher(candidate).matches());
		keys.sort(null);

		JsonArray reported = new JsonArray();
		for (String reportedKey : keys.subList(0, Math.min(maxResults, keys.size()))) {
			reported.add(describe(reportedKey, nodes));
		}
		JsonObject result = new JsonObject().put("qualifier", qualifier) //$NON-NLS-1$
				.put("project", projectName) //$NON-NLS-1$
				.put("scope", scope) //$NON-NLS-1$
				.put("total", keys.size()) //$NON-NLS-1$
				.put("truncated", keys.size() > reported.size()) //$NON-NLS-1$
				.put("preferences", reported); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	private static Map<String, Set<String>> keysByScope(Map<String, IEclipsePreferences> nodes)
			throws BackingStoreException {
		Map<String, Set<String>> keys = new LinkedHashMap<>();
		for (Map.Entry<String, IEclipsePreferences> entry : nodes.entrySet()) {
			keys.put(entry.getKey(), Set.of(entry.getValue().keys()));
		}
		return keys;
	}

	private static Set<String> select(Map<String, Set<String>> keysByScope, String scope, boolean includeDefaults) {
		Set<String> selected = new LinkedHashSet<>();
		if ("all".equals(scope)) { //$NON-NLS-1$
			for (Map.Entry<String, Set<String>> entry : keysByScope.entrySet()) {
				if (includeDefaults || !"default".equals(entry.getKey())) { //$NON-NLS-1$
					selected.addAll(entry.getValue());
				}
			}
			return selected;
		}
		selected.addAll(keysByScope.getOrDefault(scope, Set.of()));
		if (includeDefaults) {
			selected.addAll(keysByScope.getOrDefault("default", Set.of())); //$NON-NLS-1$
		}
		return selected;
	}

	/**
	 * Reports the value in every scope that sets the key, plus the effective value
	 * and where it came from. The origin is the point of the tool: an effective
	 * value without it cannot explain why one project behaves unlike its neighbours.
	 */
	private static JsonObject describe(String key, Map<String, IEclipsePreferences> nodes) {
		JsonObject values = new JsonObject();
		String effective = null;
		String effectiveScope = null;
		for (String scope : PreferenceScopes.LOOKUP_ORDER) {
			IEclipsePreferences node = nodes.get(scope);
			String value = node == null ? null : node.get(key, null);
			if (value != null) {
				values.put(scope, value);
				if (effective == null) {
					effective = value;
					effectiveScope = scope;
				}
			}
		}
		return new JsonObject().put("key", key) //$NON-NLS-1$
				.put("effective", effective) //$NON-NLS-1$
				.put("effectiveScope", effectiveScope) //$NON-NLS-1$
				.put("values", values); //$NON-NLS-1$
	}
}
