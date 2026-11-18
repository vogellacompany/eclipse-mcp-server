package com.vogella.eclipse.mcp.jdt.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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
				    "line":      {"type":"integer","minimum":1,"description":"The problem's 1-based line, instead of 'offset'; one of the two is required, and both must match when both are given."},
				    "message":   {"type":"string","description":"The problem's message exactly as listed, needed only when several problems share the id and offset, such as one per unhandled exception of a call."},
				    "fix":       {"type":"string","description":"The fix's label exactly as eclipse_get_quick_fixes lists it."},
				    "dryRun":    {"type":"boolean","default":true},
				    "maxResults": {"type":"integer","default":50,"minimum":1,"maximum":500,"description":"Most remaining errors to list after applying."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path"); //$NON-NLS-1$
		String label = args.getString("fix"); //$NON-NLS-1$
		String message = args.getString("message"); //$NON-NLS-1$
		int problemId = args.getInt("problemId", -1, Integer.MIN_VALUE, Integer.MAX_VALUE); //$NON-NLS-1$
		int offset = args.getInt("offset", -1, -1, Integer.MAX_VALUE); //$NON-NLS-1$
		int line = args.getInt("line", -1, -1, Integer.MAX_VALUE); //$NON-NLS-1$
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 50, 1, 500); //$NON-NLS-1$
		if (path == null || label == null || !args.has("problemId")) { //$NON-NLS-1$
			return McpToolResult.error("The arguments 'path', 'problemId' and 'fix' are required."); //$NON-NLS-1$
		}
		if (args.has("offset") && offset < 0 || args.has("line") && line < 1) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("'offset' must be 0 or more, and 'line' 1 or more."); //$NON-NLS-1$
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
		if (astRoot == null) {
			// the parser answers null when the monitor is cancelled
			return McpToolResult.error("The request was cancelled."); //$NON-NLS-1$
		}
		List<IProblem> matching = QuickFixSupport.problems(astRoot, true).stream()
				.filter(problem -> problem.getID() == problemId)
				.filter(problem -> offset < 0 || problem.getSourceStart() == offset)
				.filter(problem -> line < 1 || problem.getSourceLineNumber() == line)
				.filter(problem -> message == null || message.equals(problem.getMessage()))
				.toList();
		if (matching.isEmpty()) {
			String where = offset >= 0 ? "offset " + offset : "line " + line; //$NON-NLS-1$ //$NON-NLS-2$
			if (message != null) {
				List<String> there = QuickFixSupport.problems(astRoot, true).stream()
						.filter(problem -> problem.getID() == problemId)
						.filter(problem -> offset < 0 || problem.getSourceStart() == offset)
						.filter(problem -> line < 1 || problem.getSourceLineNumber() == line)
						.map(IProblem::getMessage).distinct().toList();
				if (!there.isEmpty()) {
					return McpToolResult.error("No problem %d at %s has that message. Its messages are: %s".formatted( //$NON-NLS-1$
							Integer.valueOf(problemId), where, there));
				}
			}
			return McpToolResult.error(("The file has no problem %d at %s. It may already be fixed, or the file changed; " //$NON-NLS-1$
					+ "list the current ones with eclipse_get_quick_fixes.").formatted(Integer.valueOf(problemId), where)); //$NON-NLS-1$
		}
		if (offset < 0 && matching.stream().map(IProblem::getSourceStart).distinct().count() > 1) {
			return McpToolResult.error("Line %d has %d problems with id %d. Name one by its 'offset': %s".formatted( //$NON-NLS-1$
					Integer.valueOf(line), Integer.valueOf(matching.size()), Integer.valueOf(problemId),
					matching.stream().map(problem -> String.valueOf(problem.getSourceStart())).distinct().toList()));
		}
		// JDT reports one problem per exception type at the same node, told apart only by the message
		List<String> messages = matching.stream().map(IProblem::getMessage).distinct().toList();
		if (messages.size() > 1) {
			return McpToolResult.error("%d problems with id %d are at offset %d. Name one by its 'message': %s".formatted( //$NON-NLS-1$
					Integer.valueOf(matching.size()), Integer.valueOf(problemId),
					Integer.valueOf(matching.get(0).getSourceStart()), messages));
		}
		// what is left is one problem, possibly reported twice
		IProblem problem = matching.get(0);

		List<ChangeCorrectionProposalCore> fixes;
		try {
			fixes = QuickFixSupport.fixes(unit, astRoot, problem);
		} catch (CoreException e) {
			throw new McpToolException("Could not compute the quick fixes", e); //$NON-NLS-1$
		} catch (RuntimeException e) {
			return failed("compute the quick fixes", e); //$NON-NLS-1$
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
		Change change;
		try {
			change = fix.getChange();
			if (change == null) {
				return McpToolResult.error("JDT produced no change for '%s'.".formatted(label)); //$NON-NLS-1$
			}
			result.put("changes", QuickFixSupport.describe(change, monitor)); //$NON-NLS-1$
			// validate in the dry run too, so that it does not offer a fix the apply would refuse
			QuickFixSupport.saveFiles(change);
			change.initializeValidationData(monitor);
			RefactoringStatus status = change.isValid(monitor);
			if (status.hasFatalError()) {
				return McpToolResult.error("The fix can no longer be applied: " //$NON-NLS-1$
						+ status.getMessageMatchingSeverity(RefactoringStatus.FATAL));
			}
			if (dryRun) {
				result.put("note", "Nothing was changed. Pass dryRun false to apply it."); //$NON-NLS-1$ //$NON-NLS-2$
				return McpToolResult.of(result.toString());
			}
			if (monitor.isCanceled()) {
				// the caller gave up: change nothing it can no longer see
				return McpToolResult.error("The request was cancelled before the fix was applied."); //$NON-NLS-1$
			}
			PerformChangeOperation operation = new PerformChangeOperation(change);
			try {
				ResourcesPlugin.getWorkspace().run(operation, monitor);
			} catch (CoreException | RuntimeException e) {
				// a cancel or failure while performing does not undo the edits already written
				return McpToolResult.error("JDT could not finish '%s' (%s), and may have applied part of it. Check %s.".formatted( //$NON-NLS-1$
						label, Objects.requireNonNullElse(e.getMessage(), e.getClass().getName()),
						QuickFixSupport.files(change)));
			}
			if (operation.changeExecutionFailed()) {
				// the change may have written some of its edits before it failed
				return McpToolResult.error("JDT could not apply '%s', and may have applied part of it. Check %s.".formatted( //$NON-NLS-1$
						label, QuickFixSupport.files(change)));
			}
		} catch (LinkageError e) {
			return failed("apply the quick fix", new IllegalStateException(QuickFixSupport.mismatch(e), e)); //$NON-NLS-1$
		} catch (CoreException e) {
			throw new McpToolException(dryRun ? "Could not describe the quick fix's change" : "Could not apply the quick fix", e); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (RuntimeException e) {
			return failed("apply the quick fix", e); //$NON-NLS-1$
		}

		// the fix is applied: whatever fails from here on must not read as a failed apply
		try {
			List<String> unsaved = QuickFixSupport.unsavedFiles(change);
			if (!unsaved.isEmpty()) {
				JsonArray files = new JsonArray();
				unsaved.forEach(files::add);
				result.put("unsavedFiles", files).put("note", //$NON-NLS-1$ //$NON-NLS-2$
						"These files had unsaved edits in an open editor, so the fix is in the editor and not yet on disk."); //$NON-NLS-1$
			}
			// what is left tells the caller whether to look for another fix; a move leaves the old unit behind
			CompilationUnit after = unit.exists() ? QuickFixSupport.parse(unit, monitor) : null;
			if (after == null) {
				// no parse, no claim about what is left
				result.put("checkSkipped", unit.exists() ? "The request was cancelled before the file was checked again." //$NON-NLS-1$ //$NON-NLS-2$
						: "The fix moved or renamed the file, so it was not checked again."); //$NON-NLS-1$
			} else {
				List<IProblem> left = QuickFixSupport.problems(after, false);
				JsonArray remaining = new JsonArray();
				left.stream().limit(maxResults).forEach(error -> remaining.add(QuickFixSupport.describe(error)));
				result.put("remainingErrors", remaining) //$NON-NLS-1$
						.put("remainingErrorsTotal", Integer.valueOf(left.size())) //$NON-NLS-1$
						.put("truncated", Boolean.valueOf(left.size() > remaining.size())); //$NON-NLS-1$
			}
		} catch (RuntimeException e) {
			result.put("checkError", "The fix was applied, but the file could not be checked again: " //$NON-NLS-1$ //$NON-NLS-2$
					+ Objects.requireNonNullElse(e.getMessage(), e.getClass().getName()));
		}
		return McpToolResult.of(result.toString());
	}

	/** JDT's correction code throws unchecked exceptions for setups it does not expect; answer them rather than fail. */
	private static McpToolResult failed(String what, RuntimeException e) {
		return McpToolResult.error("JDT failed to %s: %s".formatted(what, //$NON-NLS-1$
				Objects.requireNonNullElse(e.getMessage(), e.getClass().getName())));
	}
}
