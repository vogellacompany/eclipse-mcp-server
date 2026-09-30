package com.vogella.eclipse.mcp.git.internal;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Puts files back to the content HEAD has for them.
 */
public final class RevertFilesTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_revert_files"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Replaces files with the content HEAD has for them, which is what 'Replace With > HEAD Revision' does. DESTROYS UNCOMMITTED WORK IN THOSE FILES, both unstaged and staged changes, so it runs as a DRY RUN unless dryRun is set to false, and the dry run reports for every path what would actually be lost. WHAT MAKES THIS DIFFERENT FROM 'git checkout HEAD -- file': a workspace file is written through the workspace with KEEP_HISTORY, so the discarded content goes into Eclipse's Local History and can be recovered from 'Compare With > Local History' afterwards, which a git checkout does not offer; the answer says per file whether that safety net applies, because it does NOT for a file outside the workspace, which is written directly. AN UNTRACKED FILE IS REFUSED BY NAME rather than deleted: it has no HEAD content, and deleting it is what a caller asking to revert would least expect. A file that is already identical to HEAD is reported as unchanged rather than rewritten, so nothing is touched and no local history entry is made for a no-op. The affected files are refreshed, so problem markers and everything else derived from them describe the reverted content rather than the old one. Needs EGit installed."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["paths"],
				  "properties": {
				    "paths":     {"type":"array","items":{"type":"string"},"description":"Files to revert, as workspace paths ('/org.eclipse.team.core/src/Foo.java') or absolute filesystem paths. A directory is refused; name the files.","minItems":1},
				    "project":   {"type":"string","description":"Project whose repository to use, resolved the way the Git Repositories view resolves it. Usually unnecessary: each path finds its own repository."},
				    "directory": {"type":"string","description":"Working tree or .git directory, for a repository outside the workspace."},
				    "dryRun":    {"type":"boolean","default":true,"description":"Report what would be discarded per path and change nothing."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (!EGit.isAvailable()) {
			return McpToolResult.error(EGit.NOT_INSTALLED);
		}
		ToolArguments args = ToolArguments.of(arguments);
		List<String> paths = new ArrayList<>();
		if (arguments != null && arguments.get("paths") instanceof List<?> given) { //$NON-NLS-1$
			given.forEach(value -> paths.add(String.valueOf(value)));
		}
		if (paths.isEmpty()) {
			return McpToolResult.error("Give 'paths' as a non-empty array of files to revert."); //$NON-NLS-1$
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		return revert(paths, args.getString("project"), args.getString("directory"), dryRun); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static McpToolResult revert(List<String> paths, String project, String directory, boolean dryRun) {
		JsonArray files = new JsonArray();
		int wouldChange = 0;
		int reverted = 0;
		int refused = 0;
		for (String requested : paths) {
			JsonObject entry;
			try {
				entry = one(requested, project, directory, dryRun);
			} catch (IOException | CoreException | RuntimeException e) {
				// one failing path must not hide what earlier paths already changed
				entry = new JsonObject().put("path", requested).put("state", "failed") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						.put("reason", String.valueOf(e)); //$NON-NLS-1$
			}
			files.add(entry);
			String state = String.valueOf(entry.remove("state")); //$NON-NLS-1$
			entry.put("state", state); //$NON-NLS-1$
			if ("refused".equals(state) || "failed".equals(state)) { //$NON-NLS-1$ //$NON-NLS-2$
				refused++;
			} else if ("reverted".equals(state)) { //$NON-NLS-1$
				reverted++;
			} else if ("wouldRevert".equals(state)) { //$NON-NLS-1$
				wouldChange++;
			}
		}
		JsonObject result = new JsonObject().put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("requested", Integer.valueOf(paths.size())) //$NON-NLS-1$
				.put("reverted", Integer.valueOf(reverted)) //$NON-NLS-1$
				.put("wouldRevert", Integer.valueOf(wouldChange)) //$NON-NLS-1$
				.put("refused", Integer.valueOf(refused)) //$NON-NLS-1$
				.put("files", files); //$NON-NLS-1$
		if (dryRun && wouldChange > 0) {
			result.put("note", //$NON-NLS-1$
					"Nothing was changed. Pass dryRun false to replace those files with their HEAD content, which discards the uncommitted changes listed above."); //$NON-NLS-1$
		}
		return McpToolResult.of(result.toString());
	}

	private static JsonObject one(String requested, String project, String directory, boolean dryRun)
			throws IOException, CoreException {
		JsonObject entry = new JsonObject().put("path", requested); //$NON-NLS-1$
		IFile workspaceFile = workspaceFile(requested);
		File onDisk = workspaceFile != null && workspaceFile.getLocation() != null
				? workspaceFile.getLocation().toFile()
				: new File(requested);
		if (workspaceFile == null) {
			workspaceFile = fileAt(onDisk);
		}
		if (onDisk.isDirectory()) {
			return entry.put("state", "refused") //$NON-NLS-1$ //$NON-NLS-2$
					.put("reason", "That is a directory. Name the files to revert."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		Repository repository = EGit.lookup(project, directory != null ? directory : onDisk.getParent());
		if (repository == null) {
			return entry.put("state", "refused") //$NON-NLS-1$ //$NON-NLS-2$
					.put("reason", "No git repository holds that path."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		String relative = relativize(repository, onDisk);
		if (relative == null) {
			return entry.put("state", "refused") //$NON-NLS-1$ //$NON-NLS-2$
					.put("reason", "That path is outside the repository's working tree."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		entry.put("repository", repository.getWorkTree().getAbsolutePath()) //$NON-NLS-1$
				.put("repositoryPath", relative); //$NON-NLS-1$
		byte[] head = headContent(repository, relative);
		if (head == null) {
			// deleting it is what a caller asking to revert would least expect, and is
			// unrecoverable for content that was never committed
			return entry.put("state", "refused") //$NON-NLS-1$ //$NON-NLS-2$
					.put("reason", //$NON-NLS-1$
							"HEAD has no such file, so it is untracked or newly added and there is nothing to put back. It was NOT deleted."); //$NON-NLS-1$
		}
		byte[] current = onDisk.isFile() ? Files.readAllBytes(onDisk.toPath()) : null;
		boolean identical = current != null && java.util.Arrays.equals(current, head);
		entry.put("existsOnDisk", Boolean.valueOf(onDisk.isFile())) //$NON-NLS-1$
				.put("headBytes", Integer.valueOf(head.length)) //$NON-NLS-1$
				.put("currentBytes", current == null ? null : Integer.valueOf(current.length)) //$NON-NLS-1$
				.put("staged", Boolean.valueOf(isStaged(repository, relative))) //$NON-NLS-1$
				.put("inWorkspace", Boolean.valueOf(workspaceFile != null)) //$NON-NLS-1$
				.put("localHistory", Boolean.valueOf(workspaceFile != null)); //$NON-NLS-1$
		if (workspaceFile == null) {
			entry.put("localHistoryNote", //$NON-NLS-1$
					"This file is not in the workspace, so it is written directly and Eclipse keeps NO local history of what was discarded."); //$NON-NLS-1$
		}
		if (identical) {
			return entry.put("state", "unchanged") //$NON-NLS-1$ //$NON-NLS-2$
					.put("reason", "Already identical to HEAD, so nothing was written."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (dryRun) {
			return entry.put("state", "wouldRevert"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		write(workspaceFile, onDisk, head);
		return entry.put("state", "reverted"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Writes the HEAD content back.
	 * <p>
	 * Through the workspace when the file is in it, with KEEP_HISTORY, so the
	 * content being discarded lands in Eclipse's local history and the caller has a
	 * way back that {@code git checkout} would not have given them. The refresh
	 * comes with it, so markers describe the reverted file rather than the old one.
	 */
	private static void write(IFile workspaceFile, File onDisk, byte[] head) throws CoreException, IOException {
		if (workspaceFile != null) {
			if (!workspaceFile.exists()) {
				workspaceFile.create(new ByteArrayInputStream(head), IResource.FORCE, null);
				return;
			}
			workspaceFile.setContents(new ByteArrayInputStream(head), IResource.FORCE | IResource.KEEP_HISTORY, null);
			return;
		}
		Path path = onDisk.toPath();
		if (path.getParent() != null) {
			Files.createDirectories(path.getParent());
		}
		Files.write(path, head);
	}

	/** The blob HEAD holds for this path, or {@code null} when it holds none. */
	private static byte[] headContent(Repository repository, String relative) throws IOException {
		ObjectId head = repository.resolve("HEAD"); //$NON-NLS-1$
		if (head == null) {
			return null;
		}
		try (RevWalk walk = new RevWalk(repository)) {
			var tree = walk.parseCommit(head).getTree();
			try (TreeWalk found = TreeWalk.forPath(repository, relative, tree)) {
				if (found == null) {
					return null;
				}
				return repository.open(found.getObjectId(0)).getBytes();
			}
		}
	}

	private static boolean isStaged(Repository repository, String relative) {
		try (Git git = new Git(repository)) {
			Status status = git.status().addPath(relative).call();
			return status.getChanged().contains(relative) || status.getAdded().contains(relative)
					|| status.getRemoved().contains(relative);
		} catch (Exception e) {
			// the staged flag is information, not a precondition
			return false;
		}
	}

	/** The path relative to the working tree, or {@code null} when it is outside it. */
	private static String relativize(Repository repository, File file) {
		Path tree = repository.getWorkTree().toPath().toAbsolutePath().normalize();
		Path target = file.toPath().toAbsolutePath().normalize();
		if (!target.startsWith(tree)) {
			return null;
		}
		return tree.relativize(target).toString().replace(File.separatorChar, '/');
	}

	/** The workspace file that lives at this filesystem location, or {@code null}. */
	private static IFile fileAt(File file) {
		IFile[] found = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(file.toURI());
		for (IFile candidate : found) {
			if (candidate.getProject().isAccessible()) {
				return candidate;
			}
		}
		return null;
	}

	/** The workspace file for a workspace path, or {@code null} when it is not one. */
	private static IFile workspaceFile(String requested) {
		if (!requested.startsWith("/")) { //$NON-NLS-1$
			return null;
		}
		try {
			IResource member = ResourcesPlugin.getWorkspace().getRoot().findMember(requested);
			if (member instanceof IFile file) {
				return file;
			}
			// a path that names nothing yet can still be a workspace path, which is
			// what makes restoring a deleted file land in the workspace rather than
			// only on disk
			IResource candidate = ResourcesPlugin.getWorkspace().getRoot().getFile(new org.eclipse.core.runtime.Path(requested));
			return candidate instanceof IFile file && candidate.getProject().isAccessible() ? file : null;
		} catch (RuntimeException e) {
			return null;
		}
	}
}
