package com.vogella.eclipse.mcp.gradle.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;

/**
 * Runs Gradle tasks of a project through Buildship's connection.
 */
public final class RunGradleTasksTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_run_gradle_tasks"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Runs Gradle tasks of a project through Buildship, using the Gradle distribution, Java home and arguments the project is configured with, so a build, a test run or a bootRun needs no wrapper script and no shell. RUNS PROJECT CODE with the rights of the user running the IDE: build scripts, plugins, tests and applications execute, and a build may change files and download dependencies. Runs as a job and answers with a runId to poll through eclipse_get_gradle_run, which also cancels it, because a build takes minutes, a bootRun never ends and the call timeout is 30 seconds by default. The last 2000 lines of output are kept, because the failing task is at the end. The project is refreshed from disk when the run ends. Needs Buildship and a project with the Gradle nature; without them the tool says so."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "project": {"type":"string","description":"Name of the Gradle project, as listed by eclipse_list_projects. Selects the Buildship build; task names resolve against the build's root project, so name a subproject's task by its path, for example ':app:build'."},
				    "tasks":   {"type":"array","minItems":1,"items":{"type":"string"},"description":"Task names with their task options, as after ./gradlew, for example [\\"build\\"], [\\"bootRun\\"] or [\\"test\\",\\"--tests\\",\\"com.acme.FooTest\\"]. eclipse_list_gradle_tasks names what exists."},
				    "arguments": {"type":"array","items":{"type":"string"},"description":"Gradle options and properties that apply to the whole build, for example [\\"-x\\",\\"test\\"], [\\"--info\\"] or [\\"-Pprofile=ci\\"]."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":0,"maximum":3600,"description":"How long to wait for the run before answering with the handle; 0 answers at once. Bounded in practice by the server's own call timeout."}
				  },
				  "required": ["project", "tasks"],
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
		List<String> tasks = strings(arguments, "tasks"); //$NON-NLS-1$
		if (tasks.isEmpty()) {
			return McpToolResult.error("Give 'tasks' as a list of task names, for example [\"build\"]."); //$NON-NLS-1$
		}
		List<String> gradleArguments = strings(arguments, "arguments"); //$NON-NLS-1$
		var project = found.project();
		GradleRuns.Run run = GradleRuns.getInstance().start("run", project.getName(), tasks, //$NON-NLS-1$
				(handle, jobMonitor) -> GradleOperations.runTasks(project, tasks, gradleArguments, handle,
						jobMonitor));
		return RunAnswers.waitAndDescribe(run, args.getInt("timeoutSeconds", 25, 0, 3600), 100); //$NON-NLS-1$
	}

	private static List<String> strings(Map<String, Object> arguments, String name) {
		List<String> values = new ArrayList<>();
		if (arguments != null && arguments.get(name) instanceof List<?> list) {
			list.forEach(value -> values.add(String.valueOf(value)));
		}
		return values;
	}
}
