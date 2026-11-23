package com.vogella.eclipse.mcp.gradle.internal;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;

/**
 * Synchronizes a Gradle project with its build scripts through Buildship.
 */
public final class RefreshGradleProjectTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_refresh_gradle_project"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Synchronizes a Gradle project with its build scripts through Buildship, the 'Refresh Gradle Project' action, so that the Eclipse classpath, source folders and project dependencies follow build.gradle, and reports what the synchronization found. CHANGES THE WORKSPACE and RUNS GRADLE, which executes the project's build scripts and may download a distribution and dependencies. Runs as a job and answers with a runId, to poll through eclipse_get_gradle_run when it outlasts the wait, because a first synchronization takes minutes and the call timeout is 30 seconds by default. The state is failed when Buildship reports an error, with its messages as the output. Needs Buildship and a project with the Gradle nature; without them the tool says so."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "project": {"type":"string","description":"Name of the Gradle project, as listed by eclipse_list_projects. A subproject synchronizes its whole build."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"How long to wait for the synchronization before answering with the handle. Bounded in practice by the server's own call timeout."}
				  },
				  "required": ["project"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (!Buildship.isAvailable()) {
			return McpToolResult.error(Buildship.unavailable());
		}
		ToolArguments args = ToolArguments.of(arguments);
		Buildship.Lookup found = Buildship.find(args.getString("project")); //$NON-NLS-1$
		if (found.failed()) {
			return McpToolResult.error(found.error());
		}
		var project = found.project();
		GradleRuns.Run run = GradleRuns.getInstance().start("synchronize", project.getName(), List.of(), //$NON-NLS-1$
				(handle, jobMonitor) -> GradleOperations.synchronize(project, handle, jobMonitor));
		return RunAnswers.waitAndDescribe(run, args.getInt("timeoutSeconds", 25, 1, 3600), 100); //$NON-NLS-1$
	}
}
