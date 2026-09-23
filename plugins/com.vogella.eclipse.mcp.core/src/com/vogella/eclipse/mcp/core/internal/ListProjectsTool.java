package com.vogella.eclipse.mcp.core.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the projects of the workspace.
 */
public final class ListProjectsTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_list_projects"; //$NON-NLS-1$
	}

	/** .project is machine written, so the element is enough and an XML parse is not. */
	private static final Pattern NATURE = Pattern.compile("<nature>([^<]*)</nature>"); //$NON-NLS-1$

	@Override
	public String getDescription() {
		return "Lists the projects in the Eclipse workspace, with their natures and open/closed state. A closed project still reports its natures, read from its .project file on disk, because the Java model cannot answer for a project it has not opened. natureSource says which of the two answered, so 'has no natures' is never confused with 'could not be asked'. Pass summary true for counts only (total, open, closed and projects per nature), which is the cheap answer to how big a workspace is; a platform workspace lists several hundred projects."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":5000},
				    "summary":    {"type":"boolean","default":false,"description":"Answer with counts only and no per-project entries."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		int maxResults = args.getInt("maxResults", 200, 1, 5000); //$NON-NLS-1$
		boolean summary = args.getBoolean("summary", false); //$NON-NLS-1$
		JsonArray projects = new JsonArray();
		int total = 0;
		int open = 0;
		Map<String, Integer> byNature = new TreeMap<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			total++;
			if (summary) {
				List<String> natures = null;
				if (project.isOpen()) {
					open++;
					try {
						natures = List.of(project.getDescription().getNatureIds());
					} catch (CoreException e) {
						// counted without natures rather than failing a summary over one project
					}
				} else {
					natures = naturesFromProjectFile(project);
				}
				if (natures != null) {
					natures.forEach(nature -> byNature.merge(nature, Integer.valueOf(1), Integer::sum));
				}
				continue;
			}
			if (projects.size() >= maxResults) {
				continue;
			}
			JsonObject entry = new JsonObject();
			entry.put("name", project.getName()); //$NON-NLS-1$
			entry.put("open", project.isOpen()); //$NON-NLS-1$
			if (project.isOpen()) {
				JsonArray natures = new JsonArray();
				try {
					for (String nature : project.getDescription().getNatureIds()) {
						natures.add(nature);
					}
				} catch (CoreException e) {
					throw new McpToolException("Could not read the natures of project " + project.getName(), e); //$NON-NLS-1$
				}
				entry.put("natures", natures).put("natureSource", "model"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			} else {
				List<String> onDisk = naturesFromProjectFile(project);
				JsonArray natures = null;
				if (onDisk != null) {
					natures = new JsonArray();
					onDisk.forEach(natures::add);
				}
				entry.put("natures", natures) //$NON-NLS-1$
						.put("natureSource", onDisk == null ? "unknown" : "projectFile"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			IPath location = project.getLocation();
			entry.put("location", location == null ? null : location.toOSString()); //$NON-NLS-1$
			projects.add(entry);
		}
		if (summary) {
			JsonObject natures = new JsonObject();
			byNature.forEach(natures::put);
			return McpToolResult.of(new JsonObject().put("total", Integer.valueOf(total)) //$NON-NLS-1$
					.put("open", Integer.valueOf(open)) //$NON-NLS-1$
					.put("closed", Integer.valueOf(total - open)) //$NON-NLS-1$
					.put("byNature", natures).toString()); //$NON-NLS-1$
		}
		return McpToolResult.of(new JsonObject().put("total", Integer.valueOf(total)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(total > projects.size())) //$NON-NLS-1$
				.put("projects", projects).toString()); //$NON-NLS-1$
	}

	/**
	 * The natures a closed project declares, or {@code null} when the file cannot be
	 * read.
	 * <p>
	 * {@code IProject.getDescription} fails on a closed project, and reporting that
	 * as "no natures" is worse than saying nothing: a client classifying projects
	 * then gets a different answer for the same workspace depending on which
	 * projects happen to be open. The file is the same information, and the closed
	 * projects are exactly the ones a cleanup client needs to classify.
	 */
	private static List<String> naturesFromProjectFile(IProject project) {
		IPath location = project.getLocation();
		if (location == null) {
			return null;
		}
		String content;
		try {
			content = Files.readString(location.append(".project").toFile().toPath()); //$NON-NLS-1$
		} catch (IOException | RuntimeException e) {
			return null;
		}
		List<String> natures = new ArrayList<>();
		Matcher matcher = NATURE.matcher(content);
		while (matcher.find()) {
			natures.add(matcher.group(1).trim());
		}
		return natures;
	}
}
