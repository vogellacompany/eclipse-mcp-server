package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.jdt.core.manipulation.CodeStyleConfiguration;
import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation.IChooseImportQuery;
import org.eclipse.jdt.core.search.TypeNameMatch;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Runs JDT's organize imports on a source file. Modifies the file.
 */
public final class OrganizeImportsTool implements IMcpTool {

	private static final String JDT_UI_NODE = "org.eclipse.jdt.ui"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_organize_imports"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Organizes the imports of a Java source file the way JDT does, using the project's own import order and on-demand thresholds, and saves it. This modifies the file. Ambiguous single-name types are reported instead of guessed, unless resolveAmbiguous is set."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["path"],
				  "properties": {
				    "path":             {"type":"string","description":"Workspace path of the Java file, e.g. /app/src/com/example/Main.java"},
				    "resolveAmbiguous": {"type":"boolean","default":false,"description":"When several types match an unqualified name, take the first candidate instead of aborting."}
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
				    "path":           {"type":"string"},
				    "importsAdded":   {"type":"integer"},
				    "importsRemoved": {"type":"integer"},
				    "changed":        {"type":"boolean"},
				    "ambiguous":      {"type":"array","items":{"type":"string"}},
				    "parseError":     {"type":["string","null"]}
				  },
				  "required": ["path","importsAdded","importsRemoved","changed","ambiguous"]
				}"""; //$NON-NLS-1$
	}

	/**
	 * JDT reads the import order and the on-demand thresholds from the JDT UI preference
	 * node, which only that plug-in registers. Nothing here overrides a value the user or
	 * the project already set, because the lookup falls through to the default scope last.
	 */
	static void ensureCodeStylePreferences() {
		if (JavaManipulation.getPreferenceNodeId() == null) {
			JavaManipulation.setPreferenceNodeId(JDT_UI_NODE);
		}
		IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(JavaManipulation.getPreferenceNodeId());
		putIfAbsent(defaults, CodeStyleConfiguration.ORGIMPORTS_IMPORTORDER, "java;javax;org;com"); //$NON-NLS-1$
		putIfAbsent(defaults, CodeStyleConfiguration.ORGIMPORTS_ONDEMANDTHRESHOLD, "99"); //$NON-NLS-1$
		putIfAbsent(defaults, CodeStyleConfiguration.ORGIMPORTS_STATIC_ONDEMANDTHRESHOLD, "99"); //$NON-NLS-1$
		putIfAbsent(defaults, "org.eclipse.jdt.ui.typefilter.enabled", ""); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static void putIfAbsent(IEclipsePreferences preferences, String key, String value) {
		if (preferences.get(key, null) == null) {
			preferences.put(key, value);
		}
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ensureCodeStylePreferences();
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path"); //$NON-NLS-1$
		if (path == null) {
			return McpToolResult.error("The argument 'path' is required."); //$NON-NLS-1$
		}
		boolean resolveAmbiguous = args.getBoolean("resolveAmbiguous", false); //$NON-NLS-1$

		ICompilationUnit unit;
		try {
			unit = JavaModelSupport.compilationUnit(path);
			WorkspaceSync.refresh(unit.getResource(), monitor);
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		} catch (CoreException e) {
			throw new McpToolException("Could not refresh " + path, e); //$NON-NLS-1$
		}

		Map<String, Set<String>> ambiguous = new LinkedHashMap<>();
		IChooseImportQuery query = (openChoices, ranges) -> {
			List<TypeNameMatch> chosen = new ArrayList<>();
			for (TypeNameMatch[] choice : openChoices) {
				if (choice.length == 0) {
					continue;
				}
				chosen.add(choice[0]);
				// one type reachable through two roots is one import, not a choice
				Set<String> names = new TreeSet<>();
				for (TypeNameMatch match : choice) {
					names.add(match.getFullyQualifiedName());
				}
				if (names.size() > 1) {
					ambiguous.put(choice[0].getSimpleTypeName(), names);
				}
			}
			// returning null aborts the operation without touching the file
			return resolveAmbiguous || ambiguous.isEmpty() ? chosen.toArray(TypeNameMatch[]::new) : null;
		};

		OrganizeImportsOperation operation = new OrganizeImportsOperation(unit, null, true, true, true, query);
		try {
			ResourcesPlugin.getWorkspace().run(operation, monitor);
		} catch (OperationCanceledException e) {
			if (!ambiguous.isEmpty()) {
				List<String> described = new ArrayList<>();
				ambiguous.forEach((name, candidates) -> described
						.add("%s (%s)".formatted(name, String.join(", ", candidates)))); //$NON-NLS-1$ //$NON-NLS-2$
				return McpToolResult.error(
						"Organize imports was not applied because these names are ambiguous: %s. Qualify them, import them by hand, or call again with resolveAmbiguous set to true." //$NON-NLS-1$
								.formatted(String.join(", ", described))); //$NON-NLS-1$
			}
			return McpToolResult.error("Organize imports was cancelled."); //$NON-NLS-1$
		} catch (CoreException e) {
			throw new McpToolException("Could not organize the imports of " + path, e); //$NON-NLS-1$
		}

		JsonArray reported = new JsonArray();
		ambiguous.keySet().forEach(reported::add);
		JsonObject result = new JsonObject().put("path", path) //$NON-NLS-1$
				.put("importsAdded", operation.getNumberOfImportsAdded()) //$NON-NLS-1$
				.put("importsRemoved", operation.getNumberOfImportsRemoved()) //$NON-NLS-1$
				.put("changed", operation.getNumberOfImportsAdded() + operation.getNumberOfImportsRemoved() > 0) //$NON-NLS-1$
				.put("ambiguous", reported); //$NON-NLS-1$
		if (operation.getParseError() != null) {
			result.put("parseError", operation.getParseError().getMessage()); //$NON-NLS-1$
		}
		return McpToolResult.of(result.toString());
	}
}
