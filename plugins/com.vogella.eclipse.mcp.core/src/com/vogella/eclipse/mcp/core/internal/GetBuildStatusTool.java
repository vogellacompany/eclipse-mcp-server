package com.vogella.eclipse.mcp.core.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.ClientSessions;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the state of a build started through {@code eclipse_build}.
 */
public final class GetBuildStatusTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_get_build_status"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports the state of work started through eclipse_build or eclipse_refresh: running, done, failed or cancelled, how long the refresh and the build each took, the builder failures it hit and the error and warning counts that followed it. Without a buildId it reports the most recent build. EVERY ANSWER ALSO CARRIES 'jobs', which is the live job manager rather than this server's own record: the auto-build is started by the workspace and by nobody's tool, so after a restart the IDE builds for a minute or two while no build here has a state at all. Ask that before timing anything. eclipse_wait_until_quiet waits for it instead of asking."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "buildId": {"type":"string","description":"Identifier returned by eclipse_build. Omit for the most recent build."},
				    "includeBuiltProjects": {"type":"boolean","default":false,"description":"List the names of the projects that were built, not only builtProjectCount. A workspace build of a platform workspace names several hundred."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String buildId = args.getString("buildId"); //$NON-NLS-1$
		if (buildId == null && !ClientSessions.canAssumeASingleClient()) {
			// the refusal stands even though the job snapshot below would be
			// unambiguous: eclipse_wait_until_quiet answers that question and belongs
			// to no client, so nothing is lost by keeping this one strict
			return McpToolResult.error(ClientSessions.ambiguousDefault("build", //$NON-NLS-1$
					"buildId", BuildRegistry.getInstance().ids()) //$NON-NLS-1$
					+ " Use eclipse_wait_until_quiet to ask what the workspace is doing; that answer is not per client."); //$NON-NLS-1$
		}
		JsonObject jobs = WorkspaceJobs.snapshot();
		BuildRegistry.Build build = buildId == null ? BuildRegistry.getInstance().findLatest()
				: BuildRegistry.getInstance().find(buildId);
		if (build == null) {
			if (buildId != null) {
				return McpToolResult
						.error("No build with the id '%s'. Only the last few builds are kept.".formatted(buildId)); //$NON-NLS-1$
			}
			// nothing built yet is an answer, not a failure, and the workspace may
			// well be building anyway
			return McpToolResult.of(new JsonObject().put("state", "none") //$NON-NLS-1$ //$NON-NLS-2$
					.put("jobs", jobs) //$NON-NLS-1$
					.put("message", //$NON-NLS-1$
							"No build has been started through eclipse_build yet. 'jobs' says what the workspace is doing on its own.") //$NON-NLS-1$
					.toString());
		}
		return McpToolResult.of(
				toJson(build, args.getBoolean("includeBuiltProjects", false)).put("jobs", jobs).toString()); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** {added, changed, removed} as an object, or null when that phase did not run. */
	private static JsonObject counts(int[] counts) {
		if (counts == null) {
			return null;
		}
		return new JsonObject().put("total", Integer.valueOf(counts[0] + counts[1] + counts[2])) //$NON-NLS-1$
				.put("added", Integer.valueOf(counts[0])) //$NON-NLS-1$
				.put("changed", Integer.valueOf(counts[1])) //$NON-NLS-1$
				.put("removed", Integer.valueOf(counts[2])); //$NON-NLS-1$
	}

	static JsonObject toJson(BuildRegistry.Build build, boolean includeBuiltProjects) {
		JsonArray projects = new JsonArray();
		build.projects().forEach(projects::add);
		JsonArray failures = new JsonArray();
		build.builderFailures().forEach(failures::add);
		JsonObject json = new JsonObject().put("buildId", build.id()) //$NON-NLS-1$
				.put("kind", build.kind()) //$NON-NLS-1$
				.put("state", build.state()) //$NON-NLS-1$
				.put("scope", build.projects().isEmpty() ? "workspace" : "projects") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("projects", projects) //$NON-NLS-1$
				.put("elapsedMillis", build.elapsedMillis()) //$NON-NLS-1$
				.put("refreshMillis", build.refreshMillis() < 0 ? null : Long.valueOf(build.refreshMillis())) //$NON-NLS-1$
				.put("buildMillis", build.buildMillis() < 0 ? null : Long.valueOf(build.buildMillis())) //$NON-NLS-1$
				// what a duration alone cannot say: a refresh that picked up a whole
				// branch switch and one that found nothing both finish quickly
				.put("refreshedFiles", counts(build.refreshedFiles())) //$NON-NLS-1$
				.put("builtFiles", counts(build.builtFiles())) //$NON-NLS-1$
				.put("builtProjectCount", Integer.valueOf(build.builtProjects().size())) //$NON-NLS-1$
				.put("note", build.note()) //$NON-NLS-1$
				.put("errors", build.errors() < 0 ? null : Integer.valueOf(build.errors())) //$NON-NLS-1$
				.put("warnings", build.warnings() < 0 ? null : Integer.valueOf(build.warnings())) //$NON-NLS-1$
				.put("builderFailures", failures); //$NON-NLS-1$
		if (includeBuiltProjects) {
			JsonArray builtProjects = new JsonArray();
			build.builtProjects().forEach(builtProjects::add);
			json.put("builtProjects", builtProjects); //$NON-NLS-1$
		}
		return json;
	}
}
