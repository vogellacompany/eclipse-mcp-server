package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Finds types by name across the workspace and the classpath.
 */
public final class SearchTypesTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 200;

	/** Without {@code R_CASE_SENSITIVE} the match is case insensitive. */
	private static final int MATCH_RULE = SearchPattern.R_PATTERN_MATCH;

	@Override
	public String getName() {
		return "eclipse_search_types"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Finds Java types by name across the workspace and everything on the project classpaths, including types inside jars. Use it to turn a simple name into a fully qualified one, or to find out whether a type exists at all."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["pattern"],
				  "properties": {
				    "pattern":    {"type":"string","description":"Simple or qualified name, case insensitive, '*' and '?' allowed. Examples: TreeViewer, Tree*, org.eclipse.jface.viewers.Tree*"},
				    "project":    {"type":"string","description":"Optional project whose classpath is searched. Defaults to the whole workspace."},
				    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000}
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
				    "total":     {"type":"integer"},
				    "truncated": {"type":"boolean"},
				    "types":     {"type":"array","items":{"type":"object","properties":{"fullyQualifiedName":{"type":"string"},"simpleName":{"type":"string"},"packageName":{"type":"string"},"path":{"type":["string","null"]},"binary":{"type":"boolean"}}}}
				  },
				  "required": ["total","truncated","types"]
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String pattern = args.getString("pattern"); //$NON-NLS-1$
		if (pattern == null) {
			return McpToolResult.error("The argument 'pattern' is required."); //$NON-NLS-1$
		}
		String projectName = args.getString("project"); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 2000); //$NON-NLS-1$

		IJavaSearchScope scope;
		try {
			if (projectName == null) {
				scope = SearchEngine.createWorkspaceScope();
			} else {
				List<IJavaProject> projects = JavaModelSupport.javaProjects(projectName);
				scope = SearchEngine.createJavaSearchScope(new IJavaElement[] { projects.get(0) }, true);
			}
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}

		int separator = pattern.lastIndexOf('.');
		char[] packageName = separator < 0 ? null : pattern.substring(0, separator).toCharArray();
		char[] typeName = pattern.substring(separator + 1).toCharArray();

		List<TypeNameMatch> matches = new ArrayList<>();
		TypeNameMatchRequestor requestor = new TypeNameMatchRequestor() {
			@Override
			public void acceptTypeNameMatch(TypeNameMatch match) {
				matches.add(match);
			}
		};
		try {
			new SearchEngine().searchAllTypeNames(packageName, MATCH_RULE, typeName, MATCH_RULE,
					IJavaSearchConstants.TYPE, scope, requestor, IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
					monitor);
		} catch (JavaModelException e) {
			throw new McpToolException("The JDT type search for '%s' failed".formatted(pattern), e); //$NON-NLS-1$
		}
		matches.sort(Comparator.comparing(TypeNameMatch::getFullyQualifiedName));

		JsonArray types = new JsonArray();
		for (TypeNameMatch match : matches.subList(0, Math.min(maxResults, matches.size()))) {
			types.add(new JsonObject().put("fullyQualifiedName", match.getFullyQualifiedName()) //$NON-NLS-1$
					.put("simpleName", match.getSimpleTypeName()) //$NON-NLS-1$
					.put("packageName", match.getPackageName()) //$NON-NLS-1$
					.put("path", pathOf(match)) //$NON-NLS-1$
					.put("binary", isBinary(match))); //$NON-NLS-1$
		}

		JsonObject result = new JsonObject().put("total", matches.size()) //$NON-NLS-1$
				.put("truncated", matches.size() > types.size()) //$NON-NLS-1$
				.put("types", types); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	private static String pathOf(TypeNameMatch match) {
		return match.getType().getPath() == null ? null : match.getType().getPath().toString();
	}

	private static boolean isBinary(TypeNameMatch match) {
		try {
			return match.getPackageFragmentRoot().isArchive();
		} catch (RuntimeException e) {
			return false;
		}
	}
}
