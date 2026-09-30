package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Walks the callers of a method, so that reachability can be judged rather than
 * only whether something is referenced at all.
 */
public final class GetCallHierarchyTool implements IMcpTool {

	private static final Set<String> DIRECTIONS = Set.of("callers", "callees", "both"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	@Override
	public String getName() {
		return "eclipse_get_call_hierarchy"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Returns the callers of a Java method (callees are not implemented), to the requested depth, using the JDT search engine. For dead code the useful question is not whether something is referenced but whether it is reachable from anything that is itself reachable, and a one level reference list cannot answer that. A method with callers that are themselves uncalled is still dead."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["typeName"],
				  "properties": {
				    "typeName":   {"type":"string","description":"Fully qualified type declaring the method."},
				    "memberName": {"type":"string","description":"Method name. All overloads are followed. Same argument name as eclipse_find_references, which asks the same question the other way round."},
				    "methodName": {"type":"string","description":"Deprecated alias for memberName."},
				    "project":    {"type":"string","description":"Project used to resolve the type and to scope the search. Defaults to the whole workspace."},
				    "direction":  {"type":"string","enum":["callers","callees","both"],"default":"callers"},
				    "depth":      {"type":"integer","default":2,"minimum":1,"maximum":5,"description":"How many levels to follow. Each level costs another search, so keep it small."},
				    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000,"description":"Maximum nodes in the whole tree."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String typeName = args.getString("typeName"); //$NON-NLS-1$
		// memberName matches eclipse_find_references; methodName stays accepted
		String methodName = args.getString("memberName", args.getString("methodName")); //$NON-NLS-1$ //$NON-NLS-2$
		if (typeName == null || methodName == null) {
			return McpToolResult.error("The arguments 'typeName' and 'memberName' are both required."); //$NON-NLS-1$
		}
		String direction = args.getString("direction", "callers"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!DIRECTIONS.contains(direction)) {
			return McpToolResult
					.error("Unknown direction '%s', expected one of callers, callees, both.".formatted(direction)); //$NON-NLS-1$
		}
		if (!"callers".equals(direction)) { //$NON-NLS-1$
			return McpToolResult.error(
					"Only 'callers' is implemented. Callees need the AST of every method body, which is a different and much more expensive traversal than the search engine provides; ask for it and it will be built rather than faked."); //$NON-NLS-1$
		}
		int depth = args.getInt("depth", 2, 1, 5); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 200, 1, 2000); //$NON-NLS-1$

		List<IJavaProject> projects;
		IType type;
		List<IMember> methods;
		try {
			projects = JavaModelSupport.javaProjects(args.getString("project")); //$NON-NLS-1$
			if (projects.isEmpty()) {
				return McpToolResult.error("The workspace contains no open Java project."); //$NON-NLS-1$
			}
			JavaModelSupport.refresh(args.getString("project"), monitor); //$NON-NLS-1$
			type = JavaModelSupport.findType(typeName, projects, monitor);
			methods = JavaModelSupport.findMembers(type, methodName).stream().filter(IMethod.class::isInstance)
					.toList();
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}
		if (methods.isEmpty()) {
			return McpToolResult.error("'%s' has no method named '%s'.".formatted(typeName, methodName)); //$NON-NLS-1$
		}

		IJavaSearchScope scope = args.getString("project") == null ? SearchEngine.createWorkspaceScope() //$NON-NLS-1$
				: SearchEngine.createJavaSearchScope(new IJavaElement[] { projects.get(0) }, true);
		Counter counter = new Counter(maxResults);
		Set<String> seen = new LinkedHashSet<>();
		JsonArray callers = callersOf(methods, scope, depth, seen, counter, monitor);

		JsonObject result = new JsonObject().put("resolved", type.getFullyQualifiedName() + '#' + methodName) //$NON-NLS-1$
				.put("direction", "callers") //$NON-NLS-1$ //$NON-NLS-2$
				.put("depth", depth) //$NON-NLS-1$
				.put("total", counter.used()) //$NON-NLS-1$
				.put("truncated", counter.exhausted()) //$NON-NLS-1$
				.put("callers", callers); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	/** Bounds the whole tree rather than each level, so a wide first level cannot explode. */
	private static final class Counter {
		private final int max;
		private int used;
		private boolean truncated;

		Counter(int max) {
			this.max = max;
		}

		boolean take() {
			if (used >= max) {
				truncated = true;
				return false;
			}
			used++;
			return true;
		}

		int used() {
			return used;
		}

		boolean exhausted() {
			return truncated;
		}
	}

	private JsonArray callersOf(List<IMember> targets, IJavaSearchScope scope, int depth, Set<String> seen,
			Counter counter, IProgressMonitor monitor) throws McpToolException {
		JsonArray nodes = new JsonArray();
		if (depth <= 0) {
			return nodes;
		}
		for (SearchMatch match : search(targets, scope, monitor)) {
			if (monitor.isCanceled()) {
				return nodes;
			}
			if (!(match.getElement() instanceof IJavaElement element)) {
				continue;
			}
			IMethod enclosing = (IMethod) element.getAncestor(IJavaElement.METHOD);
			String key = enclosing == null ? JavaModelSupport.describe(element) : enclosing.getHandleIdentifier();
			if (!seen.add(key)) {
				// a caller already in the tree; recursing again would loop on mutual recursion
				continue;
			}
			if (!counter.take()) {
				return nodes;
			}
			JsonObject node = new JsonObject()
					.put("caller", JavaModelSupport.describe(enclosing == null ? element : enclosing)); //$NON-NLS-1$
			JavaModelSupport.describeLocation(match, node);
			node.put("offset", match.getOffset()); //$NON-NLS-1$
			if (enclosing != null && depth > 1) {
				JsonArray deeper = callersOf(List.of(enclosing), scope, depth - 1, seen, counter, monitor);
				if (deeper.size() > 0) {
					node.put("callers", deeper); //$NON-NLS-1$
				}
			}
			nodes.add(node);
		}
		return nodes;
	}

	private List<SearchMatch> search(List<IMember> targets, IJavaSearchScope scope, IProgressMonitor monitor)
			throws McpToolException {
		SearchPattern combined = null;
		for (IMember target : targets) {
			SearchPattern pattern = SearchPattern.createPattern(target, IJavaSearchConstants.REFERENCES);
			if (pattern != null) {
				combined = combined == null ? pattern : SearchPattern.createOrPattern(combined, pattern);
			}
		}
		if (combined == null) {
			return List.of();
		}
		List<SearchMatch> matches = new ArrayList<>();
		SearchRequestor requestor = new SearchRequestor() {
			@Override
			public void acceptSearchMatch(SearchMatch match) {
				matches.add(match);
			}
		};
		try {
			new SearchEngine().search(combined, new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
					scope, requestor, monitor);
		} catch (CoreException e) {
			throw new McpToolException("The JDT search for callers failed", e); //$NON-NLS-1$
		}
		return matches;
	}
}
