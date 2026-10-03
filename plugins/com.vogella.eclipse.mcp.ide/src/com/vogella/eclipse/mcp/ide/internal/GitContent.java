package com.vogella.eclipse.mcp.ide.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * Reads a workspace file as it was at a Git revision.
 * <p>
 * JGit is an optional dependency, so every reference to it lives in this class
 * alone and the caller treats a {@link LinkageError} as "not installed". An IDE
 * without EGit stays able to install this feature and loses only this one
 * argument of one tool.
 * <p>
 * The repository is found by walking up from the file, not through EGit's
 * resource mapping, so this works whether or not the project is shared in the
 * IDE.
 */
final class GitContent {

	/** The bytes a revision held, and what the revision turned out to be. */
	record Blob(byte[] content, String commit, String repository, String path) {
	}

	private GitContent() {
	}

	static Blob read(IFile file, String revision, int maxBytes) throws IOException {
		org.eclipse.core.runtime.IPath location = file.getLocation();
		if (location == null) {
			return fail("'%s' has no location on disk, so it cannot be read from Git.", file.getFullPath());
		}
		File onDisk = location.toFile();
		FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(onDisk.getParentFile());
		if (builder.getGitDir() == null) {
			return fail("'%s' is not inside a Git repository.", file.getFullPath());
		}
		try (Repository repository = builder.build()) {
			String path = relativize(repository, onDisk);
			ObjectId blob = repository.resolve(revision + ":" + path); //$NON-NLS-1$
			if (blob == null) {
				ObjectId commit = repository.resolve(revision);
				if (commit == null) {
					return fail("'%s' is not a revision this repository knows.", revision);
				}
				return fail("Revision '%s' does not contain '%s'.", revision, path);
			}
			ObjectLoader loader = repository.open(blob, Constants.OBJ_BLOB);
			if (loader.getSize() > maxBytes) {
				return fail("'%s' at '%s' is %d bytes, more than the %d this tool reads.", path, revision,
						Long.valueOf(loader.getSize()), Integer.valueOf(maxBytes));
			}
			ObjectId commit = repository.resolve(revision);
			return new Blob(loader.getBytes(), commit == null ? null : commit.name(), name(repository), path);
		}
	}

	/**
	 * The repository's name, taken from the work tree.
	 * <p>
	 * Not from the git directory: a submodule's {@code .git} is a file pointing at
	 * {@code <parent>/.git/modules/<name>}, so a name derived from there is
	 * "modules" for every submodule in the tree, which makes twelve repositories
	 * look like one. The work tree is what carries the identity.
	 */
	private static String name(Repository repository) {
		try {
			return repository.getWorkTree().getName();
		} catch (RuntimeException e) {
			// a bare repository has no work tree, and then the git directory is the
			// only name there is
			return repository.getDirectory().getName();
		}
	}

	/**
	 * The path Git knows the file by. Real paths on both sides, because a workspace
	 * reached through a symlink otherwise relativizes to a chain of {@code ..} that
	 * no revision contains.
	 */
	private static String relativize(Repository repository, File onDisk) throws IOException {
		Path workTree = real(repository.getWorkTree().toPath());
		Path target = real(onDisk.toPath());
		if (!target.startsWith(workTree)) {
			throw new IOException("'%s' is outside the work tree of '%s'.".formatted(target, workTree));
		}
		return workTree.relativize(target).toString().replace(File.separatorChar, '/');
	}

	private static Path real(Path path) {
		try {
			return path.toRealPath();
		} catch (IOException e) {
			return path.toAbsolutePath().normalize();
		}
	}

	private static Blob fail(String message, Object... arguments) throws IOException {
		throw new IOException(message.formatted(arguments));
	}
}
