package com.vogella.eclipse.mcp.gradle.internal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the tasks of a Gradle project through the model Buildship exposes.
 */
public final class ListGradleTasksTool implements IMcpTool {

	/** Model requests still in flight, so a call that outlasts the wait finds its answer on the next one. */
	private static final Map<String, CompletableFuture<List<GradleTaskInfo>>> PENDING = new ConcurrentHashMap<>();

	@Override
	public String getName() {
		return "eclipse_list_gradle_tasks"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Lists the Gradle tasks of a project and its subprojects, with each one's Gradle project path, group and description, read from the Gradle model Buildship exposes, so that a client sees which tasks exist before it runs one through eclipse_run_gradle_tasks. Changes nothing in the workspace, but RUNS GRADLE configuration, which executes the project's build scripts and may start a Gradle daemon and download a distribution. A first call can outlast the call timeout; it then answers with state running while the model loads, and asking again returns the list. Only public tasks, the ones with a group, are listed unless includeAll is set. Honours maxResults and reports total and truncated. Needs Buildship and a project with the Gradle nature; without them the tool says so."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "project":     {"type":"string","description":"Name of the Gradle project, as listed by eclipse_list_projects."},
				    "projectPath": {"type":"string","description":"Only the tasks of this Gradle project, for example ':app'. Default: every project of the build."},
				    "includeAll":  {"type":"boolean","default":false,"description":"Also list the tasks Gradle marks as not public, which have no group."},
				    "maxResults":  {"type":"integer","default":200,"minimum":1,"maximum":2000,"description":"How many tasks to list. total always counts them all."}
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
		IProject project = found.project();
		CompletableFuture<List<GradleTaskInfo>> request = PENDING.computeIfAbsent(project.getName(),
				name -> load(project));
		List<GradleTaskInfo> tasks;
		try {
			tasks = request.get(Math.max(1, CallBudget.boundedWaitSeconds(CallBudget.maxWaitSeconds())), TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			return McpToolResult.of(new JsonObject().put("project", project.getName()).put("state", "running") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.put("note", "Gradle is still loading the model, which the call timeout does not wait for. Ask again for the list.") //$NON-NLS-1$ //$NON-NLS-2$
					.toString());
		} catch (ExecutionException e) {
			PENDING.remove(project.getName(), request);
			return McpToolResult.error("Could not read the Gradle tasks of '%s': %s".formatted(project.getName(), //$NON-NLS-1$
					GradleRuns.describe(e.getCause())));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return McpToolResult.error("Interrupted while reading the Gradle tasks."); //$NON-NLS-1$
		}
		PENDING.remove(project.getName(), request);
		return McpToolResult.of(describe(project, tasks, args).toString());
	}

	private static CompletableFuture<List<GradleTaskInfo>> load(IProject project) {
		CompletableFuture<List<GradleTaskInfo>> future = new CompletableFuture<>();
		Job job = Job.create("Listing Gradle tasks of " + project.getName(), (IProgressMonitor jobMonitor) -> { //$NON-NLS-1$
			try {
				future.complete(GradleOperations.listTasks(project, jobMonitor));
			} catch (Exception | LinkageError e) {
				future.completeExceptionally(e);
			}
			return Status.OK_STATUS;
		});
		job.setPriority(Job.LONG);
		job.schedule();
		return future;
	}

	private static JsonObject describe(IProject project, List<GradleTaskInfo> tasks, ToolArguments args) {
		String projectPath = args.getString("projectPath"); //$NON-NLS-1$
		boolean all = args.getBoolean("includeAll", false); //$NON-NLS-1$
		int max = args.getInt("maxResults", 200, 1, 2000); //$NON-NLS-1$
		List<GradleTaskInfo> selected = tasks.stream()
				.filter(task -> projectPath == null || projectPath.equals(task.project()))
				.filter(task -> all || task.isPublic()).toList();
		JsonArray listed = new JsonArray();
		for (GradleTaskInfo task : selected) {
			if (listed.size() >= max) {
				break;
			}
			listed.add(new JsonObject().put("path", task.path()).put("name", task.name()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("project", task.project()).put("group", task.group()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("description", task.description())); //$NON-NLS-1$
		}
		return new JsonObject().put("project", project.getName()) //$NON-NLS-1$
				.put("total", Integer.valueOf(selected.size())) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(selected.size() > listed.size())) //$NON-NLS-1$
				.put("tasks", listed); //$NON-NLS-1$
	}
}
