package com.vogella.eclipse.mcp.git.internal;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.egit.core.RepositoryUtil;
import org.eclipse.egit.core.op.ConnectProviderOperation;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Registers a repository that is already on disk with EGit, so that the IDE knows it.
 */
public final class AddGitRepositoryTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_add_git_repository"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Registers a git repository that is ALREADY ON DISK with EGit, which is what 'Add an existing local Git repository' in the Git Repositories view does, and optionally connects the workspace projects inside it so their decorations and Team menu work. CHANGES THE IDE CONFIGURATION, not the workspace and not the repository, and runs as a dry run unless dryRun is set to false. NOTHING IS CLONED AND NOTHING IS FETCHED: this reaches no network and creates no repository. Clone with eclipse_run_command and then register the result here, which keeps the credential handling where the user already configured it. WITHOUT THIS THE OTHER GIT TOOLS ARE HALF BLIND: eclipse_get_git_status resolves a project through EGit's own mapping, so a project whose repository the IDE has never been told about resolves to nothing, and the answer is that no repository was found rather than that it was never registered. action 'list' reports what is registered, 'remove' unregisters one WITHOUT DELETING ANYTHING ON DISK, and 'add' is the default. Registering a repository twice is reported as already registered rather than failing."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "action":          {"type":"string","enum":["add","remove","list"],"default":"add","description":"add registers a repository on disk, remove unregisters one, list reports what is registered."},
				    "directory":       {"type":"string","description":"Working tree or .git directory. Required for add and remove."},
				    "connectProjects": {"type":"boolean","default":false,"description":"Also connect the open workspace projects that live inside the repository, which is what Team > Share Project does. Only for add."},
				    "dryRun":          {"type":"boolean","default":true,"description":"Report what would be registered without registering it."}
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
		String action = args.getString("action", "add"); //$NON-NLS-1$ //$NON-NLS-2$
		if ("list".equals(action)) { //$NON-NLS-1$
			return McpToolResult.of(new JsonObject().put("registered", configured()).toString()); //$NON-NLS-1$
		}
		if (!"add".equals(action) && !"remove".equals(action)) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("'action' has to be add, remove or list, not '%s'.".formatted(action)); //$NON-NLS-1$
		}
		String directory = args.getString("directory"); //$NON-NLS-1$
		if (directory == null) {
			return McpToolResult
					.error("The argument 'directory' is required for '%s'. Give the working tree or the .git directory." //$NON-NLS-1$
							.formatted(action));
		}
		File gitDir = EGit.gitDir(new File(directory));
		if (!EGit.isRepository(gitDir)) {
			return McpToolResult.error(
					"'%s' is not a git repository, so there is nothing to register. Give the working tree of an existing repository, or clone one first with eclipse_run_command." //$NON-NLS-1$
							.formatted(directory));
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		return "remove".equals(action) ? remove(gitDir, dryRun) //$NON-NLS-1$
				: add(gitDir, args.getBoolean("connectProjects", false), dryRun, monitor); //$NON-NLS-1$
	}

	private static McpToolResult add(File gitDir, boolean connectProjects, boolean dryRun, IProgressMonitor monitor)
			throws McpToolException {
		boolean already = RepositoryUtil.INSTANCE.contains(gitDir.getAbsolutePath());
		List<IProject> inside = connectProjects ? projectsInside(gitDir) : List.of();
		JsonObject result = new JsonObject().put("gitDir", gitDir.getAbsolutePath()) //$NON-NLS-1$
				.put("alreadyRegistered", Boolean.valueOf(already)); //$NON-NLS-1$
		JsonArray names = new JsonArray();
		inside.forEach(project -> names.add(project.getName()));
		if (connectProjects) {
			result.put("projectsToConnect", names); //$NON-NLS-1$
		}
		if (dryRun) {
			return McpToolResult.of(result.put("dryRun", Boolean.TRUE).put("registered", Boolean.FALSE) //$NON-NLS-1$ //$NON-NLS-2$
					.put("note", already //$NON-NLS-1$
							? "This repository is already registered, so adding it would change nothing." //$NON-NLS-1$
							: "Nothing was registered. Pass dryRun false to register it.") //$NON-NLS-1$
					.toString());
		}
		// addConfiguredRepository answers whether it actually added, so an
		// already registered repository is reported rather than counted as a change
		boolean added = RepositoryUtil.INSTANCE.addConfiguredRepository(gitDir);
		if (!inside.isEmpty()) {
			connect(inside, gitDir, monitor);
			result.put("projectsConnected", names); //$NON-NLS-1$
		}
		return McpToolResult.of(result.put("registered", Boolean.TRUE).put("changed", Boolean.valueOf(added)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("note", //$NON-NLS-1$
						"The repository is in the Git Repositories view and eclipse_get_git_status now resolves projects inside it by name. Nothing was fetched and nothing on disk changed.") //$NON-NLS-1$
				.toString());
	}

	private static McpToolResult remove(File gitDir, boolean dryRun) {
		boolean known = RepositoryUtil.INSTANCE.contains(gitDir.getAbsolutePath());
		JsonObject result = new JsonObject().put("gitDir", gitDir.getAbsolutePath()) //$NON-NLS-1$
				.put("wasRegistered", Boolean.valueOf(known)); //$NON-NLS-1$
		if (dryRun) {
			return McpToolResult.of(result.put("dryRun", Boolean.TRUE).put("removed", Boolean.FALSE) //$NON-NLS-1$ //$NON-NLS-2$
					.put("note", known ? "Nothing was removed. Pass dryRun false to unregister it." //$NON-NLS-1$ //$NON-NLS-2$
							: "This repository is not registered, so removing it would change nothing.") //$NON-NLS-1$
					.toString());
		}
		return McpToolResult.of(result.put("removed", Boolean.valueOf(RepositoryUtil.INSTANCE.removeDir(gitDir))) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"The repository is out of the Git Repositories view. NOTHING ON DISK WAS DELETED: the working tree and its history are untouched, and it can be registered again.") //$NON-NLS-1$
				.toString());
	}

	/** Open projects whose location lies under the repository's working tree. */
	private static List<IProject> projectsInside(File gitDir) {
		File workTree = ".git".equals(gitDir.getName()) ? gitDir.getParentFile() : gitDir; //$NON-NLS-1$
		if (workTree == null) {
			return List.of();
		}
		// through Path rather than a string prefix: comparing absolute paths as text
		// is case sensitive, and on Windows the same directory reaches this with the
		// casing whoever produced it happened to use, so a working tree naming the
		// profile directory in one casing would not contain a project naming it in
		// another. Path comparison is case insensitive where the filesystem is
		Path root = workTree.toPath().toAbsolutePath().normalize();
		List<IProject> inside = new ArrayList<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (!project.isAccessible() || project.getLocation() == null) {
				continue;
			}
			Path location = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
			if (!location.equals(root) && location.startsWith(root)) {
				inside.add(project);
			}
		}
		return inside;
	}

	private static void connect(List<IProject> projects, File gitDir, IProgressMonitor monitor)
			throws McpToolException {
		Map<IProject, File> mapping = new LinkedHashMap<>();
		projects.forEach(project -> mapping.put(project, gitDir));
		try {
			new ConnectProviderOperation(mapping).execute(monitor);
		} catch (CoreException e) {
			throw new McpToolException("The repository was registered, but connecting the projects failed", e); //$NON-NLS-1$
		}
	}

	private static JsonArray configured() {
		JsonArray registered = new JsonArray();
		RepositoryUtil.INSTANCE.getConfiguredRepositories().forEach(registered::add);
		return registered;
	}
}
