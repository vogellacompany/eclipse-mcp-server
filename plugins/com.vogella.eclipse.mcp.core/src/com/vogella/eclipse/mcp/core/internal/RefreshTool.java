package com.vogella.eclipse.mcp.core.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;

/**
 * Reads changes made outside the IDE into the workspace, without building.
 */
public final class RefreshTool implements IMcpTool {

	/** Below the default tool call timeout, so that waiting returns an answer rather than being killed. */
	private static final int DEFAULT_TIMEOUT_SECONDS = 25;

	@Override
	public String getName() {
		return "eclipse_refresh"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reads changes made outside the IDE into the workspace, and nothing else. Use it after switching branches, updating submodules or editing files through a shell, when you want the IDE to see the new files without also building or reading markers. Runs as a job and returns a buildId that eclipse_get_build_status reports on, so a slow refresh of a large workspace does not block the call."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "project":        {"type":"string","description":"Single project to refresh. Omit both this and 'projects' to refresh the whole workspace."},
				    "projects":       {"type":"array","items":{"type":"string"},"description":"Projects to refresh."},
				    "wait":           {"type":"boolean","default":true,"description":"Wait for the refresh to finish before answering."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"How long to wait before returning with state 'running'. Keep this below the server's tool call timeout."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		List<String> projects = projectNames(arguments, args);
		for (String name : projects) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
			if (!project.isAccessible()) {
				return McpToolResult.error("No open project named '%s' in this workspace.".formatted(name)); //$NON-NLS-1$
			}
		}
		boolean wait = args.getBoolean("wait", true); //$NON-NLS-1$
		int timeoutSeconds = args.getInt("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS, 1, 3600); //$NON-NLS-1$

		// no problem counting: a refresh does not build, so the marker counts would
		// describe whatever the last build left behind and invite a wrong conclusion
		BuildRegistry.Build build = BuildRegistry.getInstance()
				.start(new BuildRegistry.Request(BuildRegistry.REFRESH, projects, false, true, false));
		if (wait) {
			try {
				build.await(timeoutSeconds, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		return McpToolResult.of(GetBuildStatusTool.toJson(build, false).toString());
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
