package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.manipulation.CleanUpOptionsCore;
import org.eclipse.jdt.internal.corext.fix.CleanUpConstants;
import org.eclipse.jdt.internal.ui.fix.ConvertLoopCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.LambdaExpressionsCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.PatternMatchingForInstanceofCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.StringBufferToStringBuilderCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.UnusedCodeCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.VariableDeclarationCleanUpCore;
import org.eclipse.jdt.ui.cleanup.CleanUpContext;
import org.eclipse.jdt.ui.cleanup.ICleanUp;
import org.eclipse.jdt.ui.cleanup.ICleanUpFix;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.TextChange;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Applies JDT's own clean-ups, the ones behind Source &gt; Clean Up.
 */
public final class CleanUpTool implements IMcpTool {

	/**
	 * One clean-up: the class that performs it and the options that have to be on
	 * for it to do anything.
	 * <p>
	 * JDT's options are a tree: setting only a choice inside a group leaves the group off and the clean-up silently
	 * does nothing, so the companions are declared here.
	 */
	private record CleanUpEntry(Function<Map<String, String>, ICleanUp> factory, List<String> companions) {
	}

	/** The clean-ups offered, by the option key JDT identifies them with. */
	private static final Map<String, CleanUpEntry> CLEAN_UPS = new LinkedHashMap<>();

	static {
		CLEAN_UPS.put(CleanUpConstants.USE_LAMBDA, new CleanUpEntry(LambdaExpressionsCleanUpCore::new,
				List.of(CleanUpConstants.CONVERT_FUNCTIONAL_INTERFACES)));
		CLEAN_UPS.put(CleanUpConstants.USE_PATTERN_MATCHING_FOR_INSTANCEOF,
				new CleanUpEntry(PatternMatchingForInstanceofCleanUpCore::new, List.of()));
		CLEAN_UPS.put(CleanUpConstants.CONTROL_STATEMENTS_CONVERT_FOR_LOOP_TO_ENHANCED,
				new CleanUpEntry(ConvertLoopCleanUpCore::new, List.of()));
		CLEAN_UPS.put(CleanUpConstants.REMOVE_UNUSED_CODE_IMPORTS,
				new CleanUpEntry(UnusedCodeCleanUpCore::new, List.of()));
		CLEAN_UPS.put(CleanUpConstants.VARIABLE_DECLARATIONS_USE_FINAL,
				new CleanUpEntry(VariableDeclarationCleanUpCore::new,
						List.of(CleanUpConstants.VARIABLE_DECLARATIONS_USE_FINAL_LOCAL_VARIABLES,
								CleanUpConstants.VARIABLE_DECLARATIONS_USE_FINAL_PRIVATE_FIELDS)));
		CLEAN_UPS.put(CleanUpConstants.STRINGBUFFER_TO_STRINGBUILDER,
				new CleanUpEntry(StringBufferToStringBuilderCleanUpCore::new, List.of()));
	}

	@Override
	public String getName() {
		return "eclipse_clean_up"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Applies JDT's own clean-ups, the transformations behind Source > Clean Up, so the result is what Eclipse itself would produce rather than a rewrite of its own. MODIFIES SOURCE FILES, and runs as a dry run unless dryRun is set to false, reporting the files and the number of edits per clean-up. Name the clean-ups by their JDT option key, for example cleanup.use_lambda; an unknown key is refused with the list of the ones offered. Each clean-up is a semantic transformation with conditions, so it changes only what it can prove safe and silently leaves the rest: a file reported with no edits is a file where the pattern did not apply, not a failure. A clean-up that threw is counted in failed, once per file and clean-up, and listed under failures with the file and the error."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["cleanUps"],
				  "properties": {
				    "cleanUps": {"type":"array","items":{"type":"string"},"description":"JDT clean-up option keys, e.g. cleanup.use_lambda, cleanup.instanceof, cleanup.remove_unused_imports."},
				    "path":     {"type":"string","description":"Workspace path of one Java file."},
				    "project":  {"type":"string","description":"Every source file of this project. Use instead of 'path'."},
				    "dryRun":   {"type":"boolean","default":true},
				    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		// headless, the jdt.ui preference node is missing and JDT dies on the lookup
		OrganizeImportsTool.ensureCodeStylePreferences();
		ToolArguments args = ToolArguments.of(arguments);
		List<String> requested = new ArrayList<>();
		if (arguments.get("cleanUps") instanceof List<?> list) { //$NON-NLS-1$
			list.forEach(value -> requested.add(String.valueOf(value).trim()));
		}
		if (requested.isEmpty()) {
			return McpToolResult
					.error("Name at least one clean-up in 'cleanUps'. Offered: " + String.join(", ", CLEAN_UPS.keySet())); //$NON-NLS-1$
		}
		for (String id : requested) {
			if (!CLEAN_UPS.containsKey(id)) {
				return McpToolResult.error("Unknown clean-up '%s'. Offered: %s".formatted(id, //$NON-NLS-1$
						String.join(", ", CLEAN_UPS.keySet()))); //$NON-NLS-1$
			}
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 200, 1, 2000); //$NON-NLS-1$

		List<ICompilationUnit> units = new ArrayList<>();
		IProgressMonitor progress = monitor;
		String path = args.getString("path"); //$NON-NLS-1$
		String projectName = args.getString("project"); //$NON-NLS-1$
		try {
			if (path != null) {
				IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(IPath.fromPortableString(path));
				if (!file.exists() || !(JavaCore.create(file) instanceof ICompilationUnit unit)) {
					return McpToolResult.error("No Java compilation unit at '%s'.".formatted(path)); //$NON-NLS-1$
				}
				WorkspaceSync.refresh(file, progress);
				units.add(unit);
			} else if (projectName != null) {
				IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
				if (!project.isAccessible()) {
					return McpToolResult.error("No open project named '%s'.".formatted(projectName)); //$NON-NLS-1$
				}
				WorkspaceSync.refresh(project, progress);
				collect(JavaCore.create(project), units);
			} else {
				return McpToolResult.error("Give 'path' for one file or 'project' for all of them."); //$NON-NLS-1$
			}
		} catch (CoreException e) {
			throw new McpToolException("Could not collect the files to clean up", e); //$NON-NLS-1$
		}

		Map<String, String> options = new LinkedHashMap<>();
		for (String id : requested) {
			options.put(id, CleanUpOptionsCore.TRUE);
			CLEAN_UPS.get(id).companions().forEach(companion -> options.put(companion, CleanUpOptionsCore.TRUE));
		}

		JsonArray files = new JsonArray();
		// reported apart from files, which only lists files with edits, so a failure is never read as "did not apply"
		JsonArray failures = new JsonArray();
		int failed = 0;
		int changedFiles = 0;
		int totalEdits = 0;
		for (ICompilationUnit unit : units) {
			if (progress.isCanceled()) {
				return McpToolResult.error("The request was cancelled."); //$NON-NLS-1$
			}
			int edits = 0;
			JsonArray applied = new JsonArray();
			for (String id : requested) {
				try {
					int count = run(CLEAN_UPS.get(id).factory().apply(options), unit, dryRun, progress);
					if (count > 0) {
						applied.add(new JsonObject().put("cleanUp", id).put("edits", Integer.valueOf(count))); //$NON-NLS-1$ //$NON-NLS-2$
						edits += count;
					}
				} catch (CoreException e) {
					applied.add(new JsonObject().put("cleanUp", id).put("error", errorOf(e))); //$NON-NLS-1$ //$NON-NLS-2$
					failed++;
					if (failures.size() < maxResults) {
						failures.add(new JsonObject()
								.put("file", fileOf(unit)) //$NON-NLS-1$
								.put("cleanUp", id).put("error", errorOf(e))); //$NON-NLS-1$ //$NON-NLS-2$
					}
				}
			}
			if (edits == 0) {
				continue;
			}
			changedFiles++;
			totalEdits += edits;
			if (files.size() < maxResults) {
				files.add(new JsonObject()
						.put("file", fileOf(unit)) //$NON-NLS-1$
						.put("edits", Integer.valueOf(edits)).put("cleanUps", applied)); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}

		JsonObject result = new JsonObject().put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("cleanUps", array(requested)) //$NON-NLS-1$
				.put("filesConsidered", Integer.valueOf(units.size())) //$NON-NLS-1$
				.put("filesChanged", Integer.valueOf(changedFiles)) //$NON-NLS-1$
				.put("edits", Integer.valueOf(totalEdits)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(changedFiles > files.size() || failed > failures.size())) //$NON-NLS-1$
				.put("files", files); //$NON-NLS-1$
		if (failed > 0) {
			result.put("failed", Integer.valueOf(failed)).put("failures", failures); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (dryRun) {
			result.put("note", "Nothing was changed. Pass dryRun false to apply it."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return McpToolResult.of(result.toString());
	}

	/** The status message, or the first child's when a multi status leaves its own blank, or the exception itself. */
	private static String errorOf(CoreException e) {
		IStatus status = e.getStatus();
		if (status.getMessage() != null && !status.getMessage().isBlank()) {
			return status.getMessage();
		}
		for (IStatus child : status.getChildren()) {
			if (child.getMessage() != null && !child.getMessage().isBlank()) {
				return child.getMessage();
			}
		}
		return e.toString();
	}

	/** The label of a unit in files and failures, which a client joins the two lists on. */
	private static String fileOf(ICompilationUnit unit) {
		return unit.getResource() == null ? unit.getElementName() : unit.getResource().getFullPath().toString();
	}

	/** Runs one clean-up over one unit, returning how many edits it produced. */
	private static int run(ICleanUp cleanUp, ICompilationUnit unit, boolean dryRun, IProgressMonitor monitor)
			throws CoreException {
		ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
		parser.setSource(unit);
		parser.setResolveBindings(true);
		parser.setStatementsRecovery(true);
		parser.setBindingsRecovery(true);
		CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);
		ICleanUpFix fix = cleanUp.createFix(new CleanUpContext(unit, ast));
		if (fix == null) {
			return 0;
		}
		Change change = fix.createChange(monitor);
		if (!(change instanceof TextChange text) || text.getEdit() == null) {
			return 0;
		}
		int edits = text.getEdit().getChildrenSize();
		if (edits == 0) {
			return 0;
		}
		if (!dryRun) {
			change.initializeValidationData(monitor);
			ResourcesPlugin.getWorkspace().run(new PerformChangeOperation(change), monitor);
		}
		return edits;
	}

	private static void collect(IJavaProject project, List<ICompilationUnit> units) throws CoreException {
		if (project == null || !project.exists()) {
			return;
		}
		for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
			if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
				continue;
			}
			for (var child : root.getChildren()) {
				if (child instanceof IPackageFragment fragment) {
					units.addAll(List.of(fragment.getCompilationUnits()));
				}
			}
		}
	}

	private static JsonArray array(List<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}
}
