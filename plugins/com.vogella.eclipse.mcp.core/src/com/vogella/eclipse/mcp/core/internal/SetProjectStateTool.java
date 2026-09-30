package com.vogella.eclipse.mcp.core.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.Globs;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Opens and closes projects, with a dry run by default.
 */
public final class SetProjectStateTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_set_project_state"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Opens or closes projects. MODIFIES THE WORKSPACE, though reversibly: closing a project loses no files and runs no project code. Runs as a dry run unless dryRun is set to false. Closing a project that open projects depend on creates build path errors in those projects rather than removing errors, so dependents are always reported and closing is refused unless force is set. Use platformMismatch to select projects whose bundle cannot run on this platform, read from the Eclipse-PlatformFilter manifest header."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "state":            {"type":"string","enum":["open","closed"],"description":"The state to put the selected projects into."},
				    "projects":         {"type":"array","items":{"type":"string"},"description":"Project names to act on."},
				    "namePattern":      {"type":"string","description":"Glob over project names, '*' and '?' allowed, case insensitive."},
				    "platformMismatch": {"type":"boolean","default":false,"description":"Restrict the selection to projects whose bundle cannot run on this platform, judged by the Eclipse-PlatformFilter manifest header, or by the platform token in the name when there is no header."},
				    "dryRun":           {"type":"boolean","default":true,"description":"Report what would happen without changing anything."},
				    "force":            {"type":"boolean","default":false,"description":"Close a project even when open projects depend on it."},
				    "maxResults":       {"type":"integer","default":200,"minimum":1,"maximum":2000}
				  },
				  "required": ["state"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String state = args.getString("state"); //$NON-NLS-1$
		if (!"open".equals(state) && !"closed".equals(state)) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("The argument 'state' is required and must be 'open' or 'closed'."); //$NON-NLS-1$
		}
		Pattern namePattern;
		try {
			namePattern = Globs.compile(args.getString("namePattern")); //$NON-NLS-1$
		} catch (PatternSyntaxException e) {
			return McpToolResult.error("Could not read 'namePattern' as a glob: " + e.getMessage()); //$NON-NLS-1$
		}
		Set<String> named = ProjectSelection.names(arguments);
		boolean platformMismatch = args.getBoolean("platformMismatch", false); //$NON-NLS-1$
		if (named.isEmpty() && namePattern == null && !platformMismatch) {
			return McpToolResult
					.error("Select projects with 'projects', 'namePattern' or 'platformMismatch'; refusing to act on every project."); //$NON-NLS-1$
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		boolean force = args.getBoolean("force", false); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 200, 1, 2000); //$NON-NLS-1$

		List<IProject> candidates = new ArrayList<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (!named.isEmpty() && !named.contains(project.getName())) {
				continue;
			}
			if (namePattern != null && !namePattern.matcher(project.getName()).matches()) {
				continue;
			}
			candidates.add(project);
		}
		for (String name : named) {
			if (candidates.stream().noneMatch(project -> project.getName().equals(name))) {
				return McpToolResult.error("No project named '%s' in this workspace.".formatted(name)); //$NON-NLS-1$
			}
		}

		List<IProject> selection = candidates;
		if (platformMismatch) {
			selection = candidates.stream().filter(project -> PlatformFilters.evaluate(project).mismatch()).toList();
		}
		Set<String> closingTogether = closingTogether(selection, state, force);
		JsonArray reported = new JsonArray();
		int changed = 0;
		int skipped = 0;
		int considered = 0;
		for (IProject project : candidates) {
			if (monitor.isCanceled()) {
				return McpToolResult.error("The request was cancelled."); //$NON-NLS-1$
			}
			Outcome outcome = act(project, state, platformMismatch, dryRun, force, closingTogether);
			if (outcome == null) {
				continue;
			}
			considered++;
			if (outcome.changed()) {
				changed++;
			} else {
				skipped++;
			}
			if (reported.size() < maxResults) {
				reported.add(outcome.json());
			}
		}
		JsonObject result = new JsonObject().put("state", state) //$NON-NLS-1$
				.put("dryRun", dryRun) //$NON-NLS-1$
				.put("total", considered) //$NON-NLS-1$
				.put("changed", changed) //$NON-NLS-1$
				.put("skipped", skipped) //$NON-NLS-1$
				.put("truncated", considered > reported.size()) //$NON-NLS-1$
				.put("projects", reported); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	/** What happened to one project, and the JSON reported for it. */
	private record Outcome(JsonObject json, boolean changed) {
	}

	private static Outcome done(JsonObject entry, String newState) {
		return new Outcome(entry.put("changed", Boolean.TRUE).put("newState", newState).put("skippedBecause", null), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				true);
	}

	private static Outcome skip(JsonObject entry, String currentState, String because) {
		return new Outcome(
				entry.put("changed", Boolean.FALSE).put("newState", currentState).put("skippedBecause", because), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				false);
	}

	/**
	 * The projects of this batch that will end up closed, as a fixpoint.
	 * <p>
	 * {@code getReferencingProjects} reports the projects that are open right now,
	 * so a batch closing a whole cluster used to refuse every member whose
	 * dependents were themselves in the same batch: the warning described a state
	 * that would not exist once the call returned, and closing the cluster took one
	 * pass per layer. Removing one project can block another, so this iterates
	 * rather than subtracting the selection once, and it runs the same way for a dry
	 * run as for a real one, where nothing has been closed yet either.
	 */
	private static Set<String> closingTogether(List<IProject> candidates, String state, boolean force) {
		if (!"closed".equals(state)) { //$NON-NLS-1$
			return Set.of();
		}
		Set<String> closing = new LinkedHashSet<>();
		for (IProject project : candidates) {
			if (project.isOpen()) {
				closing.add(project.getName());
			}
		}
		if (force) {
			return closing;
		}
		boolean shrank = true;
		while (shrank) {
			shrank = false;
			for (String name : List.copyOf(closing)) {
				IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
				if (!ProjectSelection.blockingDependents(project, closing).isEmpty()) {
					closing.remove(name);
					shrank = true;
				}
			}
		}
		return closing;
	}

	/** Returns {@code null} when the project is not part of the selection at all. */
	private static Outcome act(IProject project, String state, boolean platformMismatch, boolean dryRun,
			boolean force, Set<String> closingTogether) {
		String previous = project.isOpen() ? "open" : "closed"; //$NON-NLS-1$ //$NON-NLS-2$
		PlatformFilters.Verdict verdict = platformMismatch ? PlatformFilters.evaluate(project) : null;
		if (verdict != null && !verdict.mismatch()) {
			return null;
		}
		JsonObject entry = new JsonObject().put("name", project.getName()).put("previousState", previous); //$NON-NLS-1$ //$NON-NLS-2$
		if (verdict != null) {
			entry.put("platformReason", verdict.reason()); //$NON-NLS-1$
		}
		if (previous.equals(state)) {
			return skip(entry, previous, "It is already " + state + "."); //$NON-NLS-1$ //$NON-NLS-2$
		}

		if ("closed".equals(state)) { //$NON-NLS-1$
			JsonArray dependents = new JsonArray();
			JsonArray inBatch = new JsonArray();
			for (IProject referencing : project.getReferencingProjects()) {
				if (closingTogether.contains(referencing.getName())) {
					inBatch.add(referencing.getName());
				} else {
					dependents.add(referencing.getName());
				}
			}
			if (inBatch.size() > 0) {
				entry.put("dependentsClosingTogether", inBatch); //$NON-NLS-1$
			}
			if (dependents.size() > 0) {
				entry.put("openDependents", dependents); //$NON-NLS-1$
				if (!force) {
					return skip(entry, previous,
							"Open projects reference it that this call does not also close, and closing it would give them build path errors rather than removing errors. Pass force to close it anyway."); //$NON-NLS-1$
				}
			}
		}

		if (dryRun) {
			return done(entry, state);
		}
		try {
			if ("closed".equals(state)) { //$NON-NLS-1$
				project.close(null);
			} else {
				project.open(null);
			}
		} catch (CoreException e) {
			return skip(entry, previous, "Eclipse refused: " + e.getMessage()); //$NON-NLS-1$
		}
		return done(entry, project.isOpen() ? "open" : "closed"); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
