package com.vogella.eclipse.mcp.core.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;

/**
 * Starts a build. Long builds are polled through {@code eclipse_get_build_status}
 * rather than held open on the HTTP request.
 */
public final class BuildTool implements IMcpTool {

	/** Below the default tool call timeout, so that waiting returns an answer rather than being killed. */
	private static final int DEFAULT_TIMEOUT_SECONDS = 25;

	private static final Set<String> KINDS = Set.of("incremental", "full", "clean"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	@Override
	public String getName() {
		return "eclipse_build"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Builds the workspace or named projects and reports the outcome. MODIFIES THE WORKSPACE: builders run project code and write build output. The build runs as a job, refresh included, so wait false always returns straight away with a buildId to poll through eclipse_get_build_status. A clean only deletes build state; pass buildAfterClean to rebuild, otherwise the error count afterwards only says that nothing is built. Builder exceptions that never become problem markers are reported in builderFailures, so a build that looks clean but threw is not mistaken for a success."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "kind":           {"type":"string","enum":["incremental","full","clean"],"default":"incremental"},
				    "project":        {"type":"string","description":"Single project to build. Omit both this and 'projects' to build the whole workspace."},
				    "projects":       {"type":"array","items":{"type":"string"},"description":"Projects to build."},
				    "wait":           {"type":"boolean","default":true,"description":"Wait for the build to finish before answering."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"How long to wait before returning with state 'running'. Capped at the server's tool call timeout less a margin, so asking for more than that returns early with the buildId and a waitNote rather than being killed as a timed out call."},
				    "returnProblems": {"type":"boolean","default":true,"description":"Count errors and warnings once the build ended."},
				    "refresh":        {"type":"boolean","default":true,"description":"Refresh from disk first, so that edits made outside the IDE are built. Scoped to the named projects. The refresh runs inside the job, so it never blocks a call with wait false, and its cost is reported as refreshMillis."},
				    "includeBuiltProjects":{"type":"boolean","default":false,"description":"List the names of the projects that were built, not only builtProjectCount. A workspace build of a platform workspace names several hundred."},
				    "buildAfterClean":{"type":"boolean","default":false,"description":"After a clean, build again, the way the 'Build immediately' checkbox of Project > Clean does. Without it a clean only deletes build state, so the error count afterwards means nothing."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String kind = args.getString("kind", "incremental"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!KINDS.contains(kind)) {
			return McpToolResult.error("Unknown kind '%s', expected one of incremental, full, clean.".formatted(kind)); //$NON-NLS-1$
		}
		List<String> projects = projectNames(arguments, args);
		for (String name : projects) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
			if (!project.isAccessible()) {
				return McpToolResult.error("No open project named '%s' in this workspace.".formatted(name)); //$NON-NLS-1$
			}
		}
		boolean wait = args.getBoolean("wait", true); //$NON-NLS-1$
		int timeoutSeconds = args.getInt("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS, 1, 3600); //$NON-NLS-1$
		boolean returnProblems = args.getBoolean("returnProblems", true); //$NON-NLS-1$

		BuildRegistry.Build build = BuildRegistry.getInstance()
				.start(new BuildRegistry.Request(kind, projects, returnProblems, args.getBoolean("refresh", true), //$NON-NLS-1$
						args.getBoolean("buildAfterClean", false))); //$NON-NLS-1$
		String clamped = null;
		if (wait) {
			try {
				build.await(CallBudget.boundedWaitSeconds(timeoutSeconds), TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			clamped = CallBudget.clampNote(timeoutSeconds, "eclipse_get_build_status with this buildId"); //$NON-NLS-1$
		}
		var json = GetBuildStatusTool.toJson(build, args.getBoolean("includeBuiltProjects", false)); //$NON-NLS-1$
		if (clamped != null && "running".equals(build.state())) { //$NON-NLS-1$
			json.put("waitNote", clamped); //$NON-NLS-1$
		}
		return McpToolResult.of(json.toString());
	}

	private static List<String> projectNames(Map<String, Object> arguments, ToolArguments args) {
		List<String> names = new ArrayList<>();
		String single = args.getString("project"); //$NON-NLS-1$
		if (single != null) {
			names.add(single);
		}
		if (arguments != null && arguments.get("projects") instanceof List<?> list) { //$NON-NLS-1$
			for (Object entry : list) {
				String name = String.valueOf(entry).trim();
				if (!name.isEmpty() && !names.contains(name)) {
					names.add(name);
				}
			}
		}
		return names;
	}
}
