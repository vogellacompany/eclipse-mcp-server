package com.vogella.eclipse.mcp.basic.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.Globs;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Takes projects out of the workspace and leaves every file where it is.
 */
public final class RemoveProjectTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_remove_project"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Removes projects from the workspace. MODIFIES THE WORKSPACE, and runs as a dry run unless dryRun is set to false. NOTHING ON DISK IS DELETED: only the workspace entry goes, the files stay exactly where they are, which for a workspace pointing at git working trees is the only defensible behaviour and is why deleting the content is not offered here at all. That also makes this reversible only by importing the project again, and a project without a .project file on disk, such as a pomless Tycho module imported through m2e, cannot be imported back by simple means, so removing one of those is close to permanent. Open projects that reference the ones being removed are reported and the removal is refused unless force is set, because they will lose their build path rather than gain anything. To stop a project taking part in the build without giving up the ability to bring it back, close it with eclipse_set_project_state instead; that is reversible in one call."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "projects":    {"type":"array","items":{"type":"string"},"description":"Project names to remove."},
				    "namePattern": {"type":"string","description":"Glob over project names, '*' and '?' allowed, case insensitive. Used instead of or together with 'projects'."},
				    "dryRun":      {"type":"boolean","default":true,"description":"Report what would be removed, including what would lose it, and change nothing."},
				    "force":       {"type":"boolean","default":false,"description":"Remove even when open projects reference the project."},
				    "maxResults":  {"type":"integer","default":200,"minimum":1,"maximum":2000}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		Set<String> named = ProjectSelection.names(arguments);
		String namePatternArg = args.getString("namePattern"); //$NON-NLS-1$
		Pattern namePattern = namePatternArg == null ? null : Globs.compile(namePatternArg);
		if (named.isEmpty() && namePattern == null) {
			return McpToolResult.error("Name what to remove with 'projects' or 'namePattern'."); //$NON-NLS-1$
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		boolean force = args.getBoolean("force", false); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 200, 1, 2000); //$NON-NLS-1$

		List<IProject> selected = new ArrayList<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (named.contains(project.getName())
					|| (namePattern != null && namePattern.matcher(project.getName()).matches())) {
				selected.add(project);
			}
		}
		Set<String> removing = new LinkedHashSet<>();
		for (IProject project : selected) {
			removing.add(project.getName());
		}
		for (String name : named) {
			if (!removing.contains(name)) {
				return McpToolResult.error("No project named '%s' in this workspace.".formatted(name)); //$NON-NLS-1$
			}
		}

		JsonArray results = new JsonArray();
		int removed = 0;
		int refused = 0;
		for (IProject project : selected) {
			JsonObject entry = new JsonObject().put("name", project.getName()) //$NON-NLS-1$
					.put("location", location(project)) //$NON-NLS-1$
					.put("hasProjectFile", Boolean.valueOf(hasProjectFile(project))); //$NON-NLS-1$
			List<String> blocking = ProjectSelection.blockingDependents(project, removing);
			if (!blocking.isEmpty()) {
				entry.put("dependents", of(blocking)); //$NON-NLS-1$
			}
			if (!blocking.isEmpty() && !force) {
				refused++;
				report(results, maxResults, entry.put("removed", Boolean.FALSE) //$NON-NLS-1$
						.put("refusedBecause", //$NON-NLS-1$
								"These open projects reference it and would lose it from their build path. Pass force to remove anyway, or remove them together.")); //$NON-NLS-1$
				continue;
			}
			if (dryRun) {
				report(results, maxResults, entry.put("removed", Boolean.FALSE)); //$NON-NLS-1$
				continue;
			}
			try {
				// deleteContent false is the whole point: the workspace entry goes and
				// the working tree stays
				project.delete(false, force, monitor);
				removed++;
				report(results, maxResults, entry.put("removed", Boolean.TRUE)); //$NON-NLS-1$
			} catch (CoreException e) {
				report(results, maxResults, entry.put("removed", Boolean.FALSE).put("error", e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}

		JsonObject result = new JsonObject().put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("selected", Integer.valueOf(selected.size())) //$NON-NLS-1$
				.put("removed", Integer.valueOf(removed)) //$NON-NLS-1$
				.put("refused", Integer.valueOf(refused)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(selected.size() > results.size())) //$NON-NLS-1$
				.put("projects", results); //$NON-NLS-1$
		if (selected.isEmpty()) {
			return McpToolResult.of(result.put("note", "No project matched, so nothing was removed.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return McpToolResult.of(result.put("note", note(dryRun, results)).toString()); //$NON-NLS-1$
	}

	/** Every selected project is acted on; only the report is capped. */
	private static void report(JsonArray results, int maxResults, JsonObject entry) {
		if (results.size() < maxResults) {
			results.add(entry);
		}
	}

	private static String note(boolean dryRun, JsonArray results) {
		String files = "No file was deleted; every working tree is untouched and only the workspace entries changed."; //$NON-NLS-1$
		if (dryRun) {
			return "Nothing was removed. " + files.replace("was deleted", "would be deleted") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ " Pass dryRun false to carry it out."; //$NON-NLS-1$
		}
		return files
				+ " A project reported with hasProjectFile true can be imported again from its location; one with false, such as a pomless Tycho module, cannot be brought back by simple means."; //$NON-NLS-1$
	}

	/** Whether anything on disk would let this project be imported again. */
	private static boolean hasProjectFile(IProject project) {
		IPath location = project.getLocation();
		return location != null
				&& location.append(IProjectDescription.DESCRIPTION_FILE_NAME).toFile()
						.isFile();
	}

	private static String location(IProject project) {
		IPath location = project.getLocation();
		return location == null ? null : location.toOSString();
	}

	private static JsonArray of(List<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}
}
