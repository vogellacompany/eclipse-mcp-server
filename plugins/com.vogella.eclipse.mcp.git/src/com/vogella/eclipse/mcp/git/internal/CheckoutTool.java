package com.vogella.eclipse.mcp.git.internal;

import java.io.IOException;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.egit.core.op.BranchOperation;
import org.eclipse.jgit.api.CheckoutResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Switches a repository to another branch or commit through EGit.
 */
public final class CheckoutTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_checkout"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Switches a repository to another branch, tag or commit. CHANGES THE WORKING TREE, and runs as a dry run unless dryRun is set to false. It goes through EGit rather than running git, which is the whole point: a checkout run outside the IDE leaves the workspace believing the old files are still there, so everything derived from them, problem markers above all, is stale until something refreshes, and a build that silently built nothing then reports 'no new errors' as if that meant something. EGit runs the switch as a workspace operation, so the affected projects refresh as part of it and that window never opens. It also refuses a switch that would conflict, instead of leaving a half completed one behind. Needs EGit installed."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "target":    {"type":"string","description":"Branch, tag or commit to switch to, e.g. 'master', 'refs/heads/master' or a SHA."},
				    "project":   {"type":"string","description":"Project whose repository to switch, resolved the way the Git Repositories view resolves it."},
				    "directory": {"type":"string","description":"Working tree or .git directory, for a repository that is not in the workspace. Ignored when project is given."},
				    "dryRun":    {"type":"boolean","default":true,"description":"Report the current and target state without switching. On by default, because a checkout replaces files in the working tree."}
				  },
				  "required": ["target"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		if (!EGit.isAvailable()) {
			return McpToolResult.error(EGit.unavailable());
		}
		ToolArguments args = ToolArguments.of(arguments);
		String target = args.getString("target"); //$NON-NLS-1$
		if (target == null || target.isBlank()) {
			return McpToolResult.error("Give the 'target' branch, tag or commit to switch to."); //$NON-NLS-1$
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		Repository repository;
		try {
			repository = EGit.lookup(args.getString("project"), args.getString("directory")); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (IOException e) {
			throw new McpToolException("Could not open the repository", e); //$NON-NLS-1$
		}
		if (repository == null) {
			return McpToolResult.error(
					"No git repository found. Give 'project' for a project in the workspace, or 'directory' for a working tree on disk."); //$NON-NLS-1$
		}

		String fromBranch;
		String fromHead;
		String resolved;
		boolean clean;
		try (Git git = new Git(repository)) {
			fromBranch = repository.getBranch();
			ObjectId head = repository.resolve("HEAD"); //$NON-NLS-1$
			fromHead = head == null ? null : head.name();
			ObjectId targetId = repository.resolve(target.strip());
			resolved = targetId == null ? null : targetId.name();
			clean = git.status().call().isClean();
		} catch (Exception e) {
			throw new McpToolException("Could not read the repository", e); //$NON-NLS-1$
		}
		if (resolved == null) {
			return McpToolResult
					.error("'%s' does not resolve to a branch, tag or commit in this repository.".formatted(target)); //$NON-NLS-1$
		}

		JsonObject result = new JsonObject()
				.put("directory", repository.getWorkTree() == null ? null //$NON-NLS-1$
						: repository.getWorkTree().getAbsolutePath())
				.put("target", target) //$NON-NLS-1$
				.put("resolved", resolved) //$NON-NLS-1$
				.put("fromBranch", fromBranch) //$NON-NLS-1$
				.put("fromHead", fromHead) //$NON-NLS-1$
				.put("wasClean", Boolean.valueOf(clean)) //$NON-NLS-1$
				.put("dryRun", Boolean.valueOf(dryRun)); //$NON-NLS-1$
		if (dryRun) {
			return McpToolResult.of(result.put("switched", Boolean.FALSE) //$NON-NLS-1$
					.put("note", clean //$NON-NLS-1$
							? "Nothing was changed. Call again with dryRun false to switch." //$NON-NLS-1$
							: "The working tree is not clean. A checkout carries uncommitted changes across or refuses; read eclipse_get_git_status before switching.") //$NON-NLS-1$
					.toString());
		}

		BranchOperation operation = new BranchOperation(repository, target.strip());
		try {
			operation.execute(monitor);
		} catch (CoreException e) {
			return McpToolResult.error("The checkout of '%s' failed: %s".formatted(target, e.getMessage())); //$NON-NLS-1$
		}
		CheckoutResult outcome = operation.getResult(repository);
		JsonArray conflicts = new JsonArray();
		if (outcome != null && outcome.getConflictList() != null) {
			outcome.getConflictList().forEach(conflicts::add);
		}
		String status = outcome == null ? null : outcome.getStatus().name();
		boolean ok = outcome == null || outcome.getStatus() == CheckoutResult.Status.OK;
		try {
			result.put("toBranch", repository.getBranch()) //$NON-NLS-1$
					.put("toHead", String.valueOf(repository.resolve("HEAD"))); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (IOException e) {
			// the switch already happened; not being able to read it back afterwards
			// is worth reporting as unknown rather than as a failure
		}
		return McpToolResult.of(result.put("switched", Boolean.valueOf(ok)) //$NON-NLS-1$
				.put("checkoutStatus", status) //$NON-NLS-1$
				.put("conflicts", conflicts) //$NON-NLS-1$
				.put("note", ok //$NON-NLS-1$
						? "The affected projects were refreshed as part of the switch, so problem markers describe the new tree once a build has run." //$NON-NLS-1$
						: "The switch did not complete. The working tree is as it was, and the conflicting paths are listed.") //$NON-NLS-1$
				.toString());
	}
}
