package com.vogella.eclipse.mcp.core.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports what a project depends on and what depends on it.
 */
public final class GetProjectDependenciesTool implements IMcpTool {

	private static final Set<String> DIRECTIONS = Set.of("references", "referencedBy", "both"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	@Override
	public String getName() {
		return "eclipse_get_project_dependencies"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports the projects a project references and the open projects that reference it, as Eclipse resolves them. This covers JDT build path project entries and the dynamic references PDE computes from Require-Bundle, so it is the answer .project and .classpath cannot give you by inspection. Use it before closing or deleting a project, and to find the leaves of a dependency graph."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "project":    {"type":"string","description":"Project name. Omit to report every open project."},
				    "direction":  {"type":"string","enum":["references","referencedBy","both"],"default":"both"},
				    "transitive": {"type":"boolean","default":false,"description":"Follow the graph instead of reporting direct neighbours only."},
				    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String direction = args.getString("direction", "both"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!DIRECTIONS.contains(direction)) {
			return McpToolResult.error(
					"Unknown direction '%s', expected one of references, referencedBy, both.".formatted(direction)); //$NON-NLS-1$
		}
		boolean transitive = args.getBoolean("transitive", false); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 200, 1, 2000); //$NON-NLS-1$
		String projectName = args.getString("project"); //$NON-NLS-1$

		List<IProject> roots = new ArrayList<>();
		if (projectName == null) {
			for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
				if (project.isAccessible()) {
					roots.add(project);
				}
			}
		} else {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (!project.isAccessible()) {
				return McpToolResult.error("No open project named '%s' in this workspace.".formatted(projectName)); //$NON-NLS-1$
			}
			roots.add(project);
		}

		JsonArray reported = new JsonArray();
		for (IProject project : roots) {
			if (monitor.isCanceled()) {
				return McpToolResult.error("The request was cancelled."); //$NON-NLS-1$
			}
			if (reported.size() >= maxResults) {
				break;
			}
			JsonObject entry = new JsonObject().put("name", project.getName()); //$NON-NLS-1$
			try {
				if (!"referencedBy".equals(direction)) { //$NON-NLS-1$
					entry.put("references", names(walk(project, true, transitive))); //$NON-NLS-1$
				}
				if (!"references".equals(direction)) { //$NON-NLS-1$
					entry.put("referencedBy", names(walk(project, false, transitive))); //$NON-NLS-1$
				}
			} catch (CoreException e) {
				throw new McpToolException("Could not read the references of " + project.getName(), e); //$NON-NLS-1$
			}
			reported.add(entry);
		}
		JsonObject result = new JsonObject().put("direction", direction) //$NON-NLS-1$
				.put("transitive", transitive) //$NON-NLS-1$
				.put("total", roots.size()) //$NON-NLS-1$
				.put("truncated", roots.size() > reported.size()) //$NON-NLS-1$
				.put("projects", reported); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	/**
	 * Walks one direction of the graph. {@code getReferencingProjects} only reports
	 * open projects, so a closed dependent is invisible here as it is to the builder.
	 */
	private static List<IProject> walk(IProject start, boolean forward, boolean transitive) throws CoreException {
		List<IProject> found = new ArrayList<>();
		Deque<IProject> queue = new ArrayDeque<>(List.of(start));
		Set<String> seen = new LinkedHashSet<>(Set.of(start.getName()));
		while (!queue.isEmpty()) {
			IProject current = queue.removeFirst();
			if (forward && !current.isAccessible()) {
				// a closed or missing project has no readable references
				continue;
			}
			IProject[] neighbours;
			try {
				neighbours = forward ? current.getReferencedProjects() : current.getReferencingProjects();
			} catch (RuntimeException e) {
				// PDE computes dynamic references here and can throw on a stale bundle
				// wiring, which is not a reason to fail the whole graph
				continue;
			}
			for (IProject neighbour : neighbours) {
				if (!seen.add(neighbour.getName())) {
					continue;
				}
				found.add(neighbour);
				if (transitive) {
					queue.add(neighbour);
				}
			}
			if (!transitive) {
				break;
			}
		}
		found.sort((a, b) -> a.getName().compareTo(b.getName()));
		return found;
	}

	private static JsonArray names(List<IProject> projects) {
		JsonArray array = new JsonArray();
		projects.forEach(project -> array.add(project.getName()));
		return array;
	}
}
