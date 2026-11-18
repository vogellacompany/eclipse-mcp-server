package com.vogella.eclipse.mcp.jdt.internal;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.manipulation.ChangeCorrectionProposalCore;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Applies one of the quick fixes {@link GetQuickFixesTool} lists, headlessly.
 */
public final class ApplyQuickFixTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_apply_quick_fix"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Applies one JDT quick fix to a compiler problem, as listed by eclipse_get_quick_fixes. MODIFIES SOURCE FILES, and possibly other files or project settings the fix touches, and runs as a dry run unless dryRun is set to false, reporting each file and text edit the fix makes. Name the problem by its problemId and its offset (or its line, when only one problem with that id is on it) and the fix by its label exactly as listed; the fixes are computed again from the file's current content, so an unknown label is refused with the ones now offered. A fix that would ask for a name or a choice in the editor applies JDT's first suggestion."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["path", "problemId", "fix"],
				  "properties": {
				    "path":      {"type":"string","description":"Workspace path of the Java file."},
				    "problemId": {"type":"integer","description":"The problem's problemId from eclipse_get_quick_fixes."},
				    "offset":    {"type":"integer","minimum":0,"description":"The problem's offset from eclipse_get_quick_fixes."},
				    "line":      {"type":"integer","minimum":1,"description":"The problem's 1-based line, instead of 'offset'."},
				    "fix":       {"type":"string","description":"The fix's label exactly as eclipse_get_quick_fixes lists it."},
				    "dryRun":    {"type":"boolean","default":true}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path"); //$NON-NLS-1$
		String label = args.getString("fix"); //$NON-NLS-1$
		int problemId = args.getInt("problemId", -1, Integer.MIN_VALUE, Integer.MAX_VALUE); //$NON-NLS-1$
		int offset = args.getInt("offset", -1, -1, Integer.MAX_VALUE); //$NON-NLS-1$
		int line = args.getInt("line", -1, -1, Integer.MAX_VALUE); //$NON-NLS-1$
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		if (path == null || label == null || !args.has("problemId")) { //$NON-NLS-1$
			return McpToolResult.error("The arguments 'path', 'problemId' and 'fix' are required."); //$NON-NLS-1$
		}
		if (offset < 0 && line < 1) {
			return McpToolResult.error("Give the problem's 'offset', or its 'line'."); //$NON-NLS-1$
		}

		ICompilationUnit unit;
		try {
			unit = JavaModelSupport.compilationUnit(path, monitor);
			WorkspaceSync.refresh(unit.getResource(), monitor);
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		} catch (CoreException e) {
			throw new McpToolException("Could not refresh " + path, e); //$NON-NLS-1$
		}

		CompilationUnit astRoot = QuickFixSupport.parse(unit, monitor);
		List<IProblem> matching = QuickFixSupport.problems(astRoot, true).stream()
				.filter(problem -> problem.getID() == problemId)
				.filter(problem -> offset >= 0 ? problem.getSourceStart() == offset
						: problem.getSourceLineNumber() == line)
				.toList();
		if (matching.isEmpty()) {
			return McpToolResult.error(("The file has no problem %d at %s. It may already be fixed, or the file changed; " //$NON-NLS-1$
					+ "list the current ones with eclipse_get_quick_fixes.").formatted(Integer.valueOf(problemId), //$NON-NLS-1$
							offset >= 0 ? "offset " + offset : "line " + line)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (matching.size() > 1) {
			return McpToolResult.error("Line %d has %d problems with id %d. Name one by its 'offset': %s".formatted( //$NON-NLS-1$
					Integer.valueOf(line), Integer.valueOf(matching.size()), Integer.valueOf(problemId),
					matching.stream().map(problem -> String.valueOf(problem.getSourceStart())).toList()));
		}
		IProblem problem = matching.get(0);

		List<ChangeCorrectionProposalCore> fixes;
		try {
			fixes = QuickFixSupport.fixes(unit, astRoot, problem);
		} catch (CoreException e) {
			throw new McpToolException("Could not compute the quick fixes", e); //$NON-NLS-1$
		}
		ChangeCorrectionProposalCore fix = fixes.stream().filter(candidate -> label.equals(candidate.getName()))
				.findFirst().orElse(null);
		if (fix == null) {
			return McpToolResult.error("No fix labelled '%s' for this problem. Offered: %s".formatted(label, //$NON-NLS-1$
					fixes.isEmpty() ? "none" : fixes.stream().map(ChangeCorrectionProposalCore::getName).toList())); //$NON-NLS-1$
		}

		JsonObject result = new JsonObject().put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("problem", QuickFixSupport.describe(problem)) //$NON-NLS-1$
				.put("fix", fix.getName()); //$NON-NLS-1$
		try {
			Change change = fix.getChange();
			if (change == null) {
				return McpToolResult.error("JDT produced no change for '%s'.".formatted(label)); //$NON-NLS-1$
			}
			result.put("changes", QuickFixSupport.describe(change, monitor)); //$NON-NLS-1$
			if (dryRun) {
				result.put("note", "Nothing was changed. Pass dryRun false to apply it."); //$NON-NLS-1$ //$NON-NLS-2$
				return McpToolResult.of(result.toString());
			}
			QuickFixSupport.saveFiles(change);
			change.initializeValidationData(monitor);
			RefactoringStatus status = change.isValid(monitor);
			if (status.hasFatalError()) {
				return McpToolResult.error("The fix can no longer be applied: " //$NON-NLS-1$
						+ status.getMessageMatchingSeverity(RefactoringStatus.FATAL));
			}
			PerformChangeOperation operation = new PerformChangeOperation(change);
			ResourcesPlugin.getWorkspace().run(operation, monitor);
			if (operation.changeExecutionFailed()) {
				return McpToolResult.error("JDT could not apply '%s'.".formatted(label)); //$NON-NLS-1$
			}
		} catch (CoreException e) {
			throw new McpToolException("Could not apply the quick fix", e); //$NON-NLS-1$
		}

		// what is left tells the caller whether to look for another fix
		JsonArray remaining = new JsonArray();
		QuickFixSupport.problems(QuickFixSupport.parse(unit, monitor), false)
				.forEach(left -> remaining.add(QuickFixSupport.describe(left)));
		return McpToolResult.of(result.put("remainingErrors", remaining).toString()); //$NON-NLS-1$
	}
}
