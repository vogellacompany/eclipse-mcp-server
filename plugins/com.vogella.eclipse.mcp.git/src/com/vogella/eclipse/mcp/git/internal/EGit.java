package com.vogella.eclipse.mcp.git.internal;

import java.io.File;
import java.io.IOException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.egit.core.RepositoryCache;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache.FileKey;
import org.eclipse.jgit.util.FS;

/**
 * Finds the repository a request is about.
 * <p>
 * The EGit and JGit bundles are optional: the tools ask {@link #isAvailable()}
 * first and report that EGit is not installed rather than failing to resolve.
 */
final class EGit {

	static final String NOT_INSTALLED = "EGit is not installed in this IDE, so the git tools cannot work. Install the 'Git integration for Eclipse' feature, or use eclipse_run_command to drive git directly, which leaves the workspace unaware of the change until it is refreshed."; //$NON-NLS-1$

	private EGit() {
	}

	/** Whether the optional EGit bundles resolved. */
	static boolean isAvailable() {
		try {
			Class.forName("org.eclipse.egit.core.RepositoryCache"); //$NON-NLS-1$
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	/**
	 * The repository for a project name or a directory on disk, or {@code null}.
	 * <p>
	 * A project name is resolved through EGit's own mapping, so a project inside a
	 * repository resolves the same way it does in the Git Repositories view. A path
	 * is looked up directly, which is what makes a repository outside the workspace
	 * reachable at all.
	 */
	static Repository lookup(String projectName, String directory) throws IOException {
		if (projectName != null && !projectName.isBlank()) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName.strip());
			return project.isAccessible() ? RepositoryCache.INSTANCE.getRepository(project) : null;
		}
		if (directory == null || directory.isBlank()) {
			return null;
		}
		File file = new File(directory.strip());
		Repository known = RepositoryCache.INSTANCE.getRepository(new org.eclipse.core.runtime.Path(file.getPath()));
		if (known != null) {
			return known;
		}
		File gitDir = gitDir(file);
		// lookupRepository hands back a bare handle for any directory at all, so a
		// path that is no repository has to be rejected here. Otherwise it reaches
		// the status as a repository with no working tree and fails there, which
		// reads as the repository being broken rather than as the wrong path
		return isRepository(gitDir) ? RepositoryCache.INSTANCE.lookupRepository(gitDir) : null;
	}

	/** Whether this really is a git directory, rather than any directory at all. */
	static boolean isRepository(File gitDir) {
		return FileKey.isGitRepository(gitDir, FS.DETECTED);
	}

	/** {@code .git} under the directory, or the directory itself when it already is one. */
	static File gitDir(File directory) {
		File candidate = new File(directory, ".git"); //$NON-NLS-1$
		return candidate.exists() ? candidate : directory;
	}
}
