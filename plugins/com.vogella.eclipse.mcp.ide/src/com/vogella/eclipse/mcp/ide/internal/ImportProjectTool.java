package com.vogella.eclipse.mcp.ide.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.internal.wizards.datatransfer.SmartImportJob;
import org.eclipse.ui.wizards.datatransfer.ProjectConfigurator;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.ui.internal.Workbenches;

/**
 * Imports projects that already exist on disk, through the platform's own smart
 * import.
 * <p>
 * This lives in the ide bundle rather than in core because
 * {@link SmartImportJob} is in {@code org.eclipse.ui.ide}, and it is reached
 * through a discouraged access to an {@code x-internal} package. That was a
 * deliberate decision: the alternative is a hand written walk for {@code
 * .project} files, which is what this replaced, and which could not import a
 * Maven or Gradle module that has no {@code .project} yet. Driving the
 * configurators is the whole point, and there is no public entry point that
 * does it.
 * <p>
 * Three hazards of that class are handled here rather than discovered later.
 * It raises a modal question when it decides the root project is not worth
 * keeping, and that branch is reachable only when no directories to import were
 * set, so this always sets them, from the proposals it just read. It switches
 * auto-build off for the duration and switches it back on at the end of its try
 * block, with no finally, so any exception leaves the workspace with auto-build
 * off for good; this restores what it found. And it creates a project for every
 * directory it is handed whatever {@code configureProjects} says, which writes a
 * {@code .project} file, so {@code configure false} filters the proposals down
 * to what is already a project rather than passing on everything a configurator
 * merely recognised.
 */
@SuppressWarnings("restriction")
public final class ImportProjectTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_import_project"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Imports projects that already exist on disk into the workspace, through the same smart import the IDE's own File > Import > Projects from Folder or Archive uses. MODIFIES THE WORKSPACE, and runs as a dry run unless dryRun is set to false. It finds projects by asking every registered project configurator what it recognises below the directory, so unlike a plain search for .project files it also finds a Maven or Gradle module that has never been imported here, and it detects nested projects at any depth. WITH configure TRUE IT ALSO WRITES TO DISK: a configurator adds natures and builders, which for m2e means writing .project and .classpath into a module that had none, and that is the only way such a module can become a project at all. With configure false nothing on disk is written and only directories that already have a .project are imported, which is the cheaper answer when the tree is already a workspace. The dry run reports which configurator claimed each directory, so what would be written is visible before it is. Projects are always left open, because the platform's importer does not expose closing them. Imports of a large tree outlast the call timeout; the answer then says the import is still running, and eclipse_list_projects is what shows it finishing."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "location":   {"type":"string","description":"Absolute path of the directory to import from. Its subdirectories are searched at any depth."},
				    "configure":  {"type":"boolean","default":false,"description":"Run the project configurators, which is what imports a module that has no .project yet. WRITES .project and .classpath for such a module. False imports only what is already an Eclipse project."},
				    "dryRun":     {"type":"boolean","default":true,"description":"Report what would be imported and which configurator claimed each directory, and change nothing."},
				    "maxResults": {"type":"integer","default":100,"minimum":1,"maximum":1000,"description":"How many projects to LIST. Every project found is still imported; 'found' is the true count and 'truncated' says the list was cut."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"How long to wait for the import. Bounded by the server's own call timeout; the import continues past it either way."}
				  },
				  "required": ["location"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (!PlatformUI.isWorkbenchRunning()) {
			// SmartImportJob reaches the working set manager through PlatformUI on every
			// project it creates, without checking whether any working set was asked for
			return McpToolResult.error(Workbenches.noIde());
		}
		ToolArguments args = ToolArguments.of(arguments);
		String location = args.getString("location"); //$NON-NLS-1$
		if (location == null) {
			return McpToolResult.error("The argument 'location' is required: the absolute path of the directory to import from."); //$NON-NLS-1$
		}
		File directory = new File(location);
		if (!directory.isDirectory()) {
			return McpToolResult.error("'%s' is not a directory on this machine.".formatted(location)); //$NON-NLS-1$
		}
		boolean configure = args.getBoolean("configure", false); //$NON-NLS-1$
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 100, 1, 1000); //$NON-NLS-1$
		int requested = args.getInt("timeoutSeconds", 25, 1, 3600); //$NON-NLS-1$
		int waitSeconds = CallBudget.boundedWaitSeconds(requested);

		SmartImportJob job = new SmartImportJob(directory, Collections.emptySet(), configure, true);
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		boolean wasAutoBuilding = workspace.isAutoBuilding();
		Map<File, List<ProjectConfigurator>>[] scanned = newProposalHolder();

		// The scan is part of the work, not something to do before it: asking every
		// configurator what it recognises walks the whole tree at unlimited depth, and
		// on a repository of repositories that alone outlasts the call timeout. Doing
		// it inside the job is what lets the answer say the import is still running
		// instead of the call dying with the handle.
		Job work = Job.create("Smart import of " + directory.getAbsolutePath(), (IProgressMonitor jobMonitor) -> { //$NON-NLS-1$
			Map<File, List<ProjectConfigurator>> proposals = job.getImportProposals(jobMonitor);
			if (!configure) {
				proposals = onlyExistingProjects(proposals);
			}
			scanned[0] = proposals;
			if (!dryRun && !proposals.isEmpty()) {
				job.setDirectoriesToImport(proposals.keySet());
				job.run(jobMonitor);
			}
		});

		// while the import runs auto-build stays off on purpose, so a wait that ran out restores it only when the job ends
		work.addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(IJobChangeEvent event) {
				restoreAutoBuilding(workspace, wasAutoBuilding);
			}
		});
		boolean finished;
		try {
			work.schedule();
			finished = work.join(TimeUnit.SECONDS.toMillis(waitSeconds), monitor);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return McpToolResult.error("The import was interrupted."); //$NON-NLS-1$
		}

		Map<File, List<ProjectConfigurator>> proposals = scanned[0];
		if (proposals == null) {
			return McpToolResult.of(new JsonObject().put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
					.put("stillRunning", Boolean.TRUE) //$NON-NLS-1$
					.put("waitNote", //$NON-NLS-1$
							"Still working out what is there after %d seconds, so nothing can be reported yet; nothing was cancelled. Asking every configurator walks the whole tree, which is the slow part on a large repository." //$NON-NLS-1$
									.formatted(Integer.valueOf(waitSeconds)))
					.toString());
		}
		if (proposals.isEmpty()) {
			return McpToolResult.error(
					"No project was recognised below '%s'. Nothing there has a .project file; pass configure true if it holds a Maven or Gradle module that has never been imported." //$NON-NLS-1$
							.formatted(location));
		}

		JsonObject answer = describe(proposals, maxResults, dryRun, dryRun ? null : job);
		answer.put("stillRunning", Boolean.valueOf(!finished)); //$NON-NLS-1$
		answer.put("note", dryRun //$NON-NLS-1$
				? "Nothing was imported. Pass dryRun false to carry it out." //$NON-NLS-1$
				: "The projects stay where they are on disk; the workspace only gained entries pointing at them."); //$NON-NLS-1$
		if (!finished) {
			answer.put("waitNote", //$NON-NLS-1$
					"The import did not finish within %d seconds and is still running; nothing was cancelled. eclipse_list_projects is what shows it finishing, and 'imported' here counts only what had landed when the wait ran out." //$NON-NLS-1$
							.formatted(Integer.valueOf(waitSeconds)));
		}
		return McpToolResult.of(answer.toString());
	}

	@SuppressWarnings("unchecked")
	private static Map<File, List<ProjectConfigurator>>[] newProposalHolder() {
		return new Map[1];
	}

	/**
	 * Keeps only directories that are already Eclipse projects.
	 * <p>
	 * SmartImportJob creates a project for every directory it is given whatever
	 * configureProjects says, and IProject.create writes a .project file, so
	 * without this a call with configure false would write one into every
	 * directory that merely holds a pom.xml. On the platform aggregator that is
	 * the repository root and dozens more. The promise that configure false
	 * writes nothing is only true because of this filter.
	 */
	private static Map<File, List<ProjectConfigurator>> onlyExistingProjects(
			Map<File, List<ProjectConfigurator>> proposals) {
		Map<File, List<ProjectConfigurator>> kept = new LinkedHashMap<>();
		for (Map.Entry<File, List<ProjectConfigurator>> entry : proposals.entrySet()) {
			if (new File(entry.getKey(), IProjectDescription.DESCRIPTION_FILE_NAME).isFile()) {
				kept.put(entry.getKey(), entry.getValue());
			}
		}
		return kept;
	}

	/**
	 * Puts auto-build back the way it was found.
	 * <p>
	 * SmartImportJob switches it off at the start of its run and back on at the
	 * end of the same try block. The catch below that returns an error status
	 * without restoring it, and there is no finally, so a failed import silently
	 * leaves the workspace with auto-build off. Setting it to what it already is
	 * costs nothing, so this does not try to work out whether that happened.
	 */
	private static void restoreAutoBuilding(IWorkspace workspace, boolean wasAutoBuilding) {
		if (workspace.isAutoBuilding() == wasAutoBuilding) {
			return;
		}
		try {
			IWorkspaceDescription description = workspace.getDescription();
			description.setAutoBuilding(wasAutoBuilding);
			workspace.setDescription(description);
		} catch (CoreException e) {
			// the import itself succeeded or failed on its own terms; losing the
			// restore must not turn either of those into a failed call
		}
	}

	private static JsonObject describe(Map<File, List<ProjectConfigurator>> proposals, int maxResults, boolean dryRun,
			SmartImportJob job) {
		Map<IProject, List<ProjectConfigurator>> configured = job == null ? Map.of() : job.getConfiguredProjects();
		Set<String> importedNames = new TreeSet<>();
		configured.keySet().forEach(project -> importedNames.add(project.getName()));

		JsonArray projects = new JsonArray();
		List<File> directories = new ArrayList<>(proposals.keySet());
		directories.sort((left, right) -> left.getAbsolutePath().compareTo(right.getAbsolutePath()));
		for (File candidate : directories) {
			if (projects.size() >= maxResults) {
				break;
			}
			projects.add(new JsonObject().put("location", candidate.getAbsolutePath()) //$NON-NLS-1$
					.put("configurators", names(proposals.get(candidate)))); //$NON-NLS-1$
		}

		JsonObject answer = new JsonObject().put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("found", Integer.valueOf(proposals.size())) //$NON-NLS-1$
				.put("imported", Integer.valueOf(importedNames.size())) //$NON-NLS-1$
				.put("listed", Integer.valueOf(projects.size())) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(projects.size() < proposals.size())) //$NON-NLS-1$
				.put("projects", projects); //$NON-NLS-1$
		if (job != null) {
			answer.put("errors", errors(job)); //$NON-NLS-1$
		}
		return answer;
	}

	private static JsonArray names(List<ProjectConfigurator> configurators) {
		JsonArray names = new JsonArray();
		if (configurators != null) {
			configurators.forEach(configurator -> names.add(configurator.getClass().getSimpleName()));
		}
		return names;
	}

	private static JsonArray errors(SmartImportJob job) {
		JsonArray errors = new JsonArray();
		for (Map.Entry<IPath, Exception> entry : job.getErrors().entrySet()) {
			errors.add(new JsonObject().put("path", entry.getKey().toOSString()) //$NON-NLS-1$
					.put("error", entry.getValue().getMessage() == null //$NON-NLS-1$
							? entry.getValue().getClass().getSimpleName()
							: entry.getValue().getMessage()));
		}
		return errors;
	}
}
