package com.vogella.eclipse.mcp.git.internal;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the branch, the commit and the dirty state of a repository.
 */
public final class GetGitStatusTool implements IMcpTool {

	private static final int SAMPLE = 20;

	@Override
	public String getName() {
		return "eclipse_get_git_status"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports which branch and commit a repository is on, and whether its working tree is clean, listing modified, staged, missing, untracked and conflicting paths. Changes nothing. This is what a problem set or a build result has to be correlated with: comparing errors across branches without recording the commit each set belongs to is how one branch's failures get attributed to another, and doing that by hand across several switches is where the mistake happens. Needs EGit; without it the tools say so rather than guessing."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "project":   {"type":"string","description":"Project whose repository to report, resolved the way the Git Repositories view resolves it."},
				    "directory": {"type":"string","description":"Working tree or .git directory, for a repository that is not in the workspace. Ignored when project is given."},
				    "maxFiles":  {"type":"integer","default":20,"minimum":1,"maximum":500,"description":"How many changed paths to list per category. The counts are always complete."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		if (!EGit.isAvailable()) {
			return McpToolResult.error(EGit.NOT_INSTALLED);
		}
		ToolArguments args = ToolArguments.of(arguments);
		int maxFiles = args.getInt("maxFiles", SAMPLE, 1, 500); //$NON-NLS-1$
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
		try (Git git = new Git(repository)) {
			Status status = git.status().call();
			ObjectId head = repository.resolve("HEAD"); //$NON-NLS-1$
			return McpToolResult.of(new JsonObject()
					.put("directory", repository.getWorkTree() == null ? null //$NON-NLS-1$
							: repository.getWorkTree().getAbsolutePath())
					.put("branch", repository.getBranch()) //$NON-NLS-1$
					.put("head", head == null ? null : head.name()) //$NON-NLS-1$
					.put("state", repository.getRepositoryState().name()) //$NON-NLS-1$
					.put("clean", Boolean.valueOf(status.isClean())) //$NON-NLS-1$
					.put("modified", paths(status.getModified(), maxFiles)) //$NON-NLS-1$
					.put("staged", paths(staged(status), maxFiles)) //$NON-NLS-1$
					.put("missing", paths(status.getMissing(), maxFiles)) //$NON-NLS-1$
					.put("untracked", paths(status.getUntracked(), maxFiles)) //$NON-NLS-1$
					.put("conflicting", paths(status.getConflicting(), maxFiles)) //$NON-NLS-1$
					.toString());
		} catch (Exception e) {
			throw new McpToolException("Could not read the status of the repository", e); //$NON-NLS-1$
		}
	}

	/** Paths with anything in the index that differs from HEAD. */
	private static Set<String> staged(Status status) {
		Set<String> staged = new TreeSet<>(status.getAdded());
		staged.addAll(status.getChanged());
		staged.addAll(status.getRemoved());
		return staged;
	}

	private static JsonObject paths(Set<String> values, int maxFiles) {
		JsonArray listed = new JsonArray();
		for (String value : values) {
			if (listed.size() >= maxFiles) {
				break;
			}
			listed.add(value);
		}
		return new JsonObject().put("total", Integer.valueOf(values.size())) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(values.size() > listed.size())) //$NON-NLS-1$
				.put("paths", listed); //$NON-NLS-1$
	}
}
