package com.vogella.eclipse.mcp.git.internal;

import java.io.IOException;

import org.eclipse.jgit.lib.Repository;

import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Opens the repository a request names, or answers with the finished error.
 */
public final class RepositoryLookup {

	/** The answer when no repository was found, naming both ways to point at one. */
	public static final String NO_REPOSITORY = "No git repository found. Give 'project' for a project in the workspace, or 'directory' for a working tree on disk."; //$NON-NLS-1$

	/** The repository a request is about, or the error that answers it. */
	public record Result(Repository repository, McpToolResult error) {

		/** Whether the lookup answered with an error rather than a repository. */
		public boolean failed() {
			return error != null;
		}
	}

	private RepositoryLookup() {
	}

	/** Looks up the repository of a project or working tree directory. */
	public static Result find(String project, String directory) throws McpToolException {
		Repository repository;
		try {
			repository = EGit.lookup(project, directory);
		} catch (IOException e) {
			throw new McpToolException("Could not open the repository", e); //$NON-NLS-1$
		}
		return repository == null ? new Result(null, McpToolResult.error(NO_REPOSITORY))
				: new Result(repository, null);
	}
}
