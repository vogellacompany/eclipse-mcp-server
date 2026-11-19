package com.vogella.eclipse.mcp.jdt.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.manipulation.ChangeCorrectionProposalCore;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the compiler problems of a Java file with the quick fixes JDT offers for each, computed headlessly.
 */
public final class GetQuickFixesTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_get_quick_fixes"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Lists the compiler problems of one Java file and, for each, the quick fixes JDT offers for it (most of what Ctrl+1 shows in the editor), most relevant first. Computed on the file's current content without the UI, so it works in a headless IDE and does not need a build. Apply one with eclipse_apply_quick_fix, passing the problem's offset and problemId and the fix's label exactly as listed. Fixes that exist only as a JDT UI wizard or dialog, such as creating a new class or adding a library to the build path, are not offered, nor are some fixes for rarer problems; where two types share a simple name, the order of the import fixes does not follow your earlier picks as Ctrl+1 does. When JDT UI has not started, a fix that writes a method body or a comment uses JDT's default code templates, not the user's. Fixes JDT offers twice under one label are listed once. A problem whose fixes could not be computed at all carries an 'error' with the reason instead of fixes; when only some of JDT's fix computations fail, the others are listed with a 'partialError', and the failure goes to the Error Log."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["path"],
				  "properties": {
				    "path":            {"type":"string","description":"Workspace path of the Java file, e.g. /project/src/com/example/Main.java."},
				    "line":            {"type":"integer","minimum":1,"description":"Only the problems on this 1-based line."},
				    "includeWarnings": {"type":"boolean","default":true,"description":"Also list warnings and infos, not only errors."},
				    "maxResults":      {"type":"integer","default":50,"minimum":1,"maximum":500,"description":"Most problems to list."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path"); //$NON-NLS-1$
		if (path == null) {
			return McpToolResult.error("The argument 'path' is required."); //$NON-NLS-1$
		}
		int line = args.getInt("line", 0, 0, Integer.MAX_VALUE); //$NON-NLS-1$
		if ((args.has("line") && line < 1) || QuickFixSupport.notAnInt(arguments, "line")) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("'line' is 1-based, so it must be a whole number, 1 or more."); //$NON-NLS-1$
		}
		boolean includeWarnings = args.getBoolean("includeWarnings", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 50, 1, 500); //$NON-NLS-1$

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
			return McpToolResult.error("The call ran out of time before the file was parsed."); //$NON-NLS-1$
		}
		List<IProblem> problems = QuickFixSupport.problems(astRoot, includeWarnings);
		int inFile = problems.size();
		if (line > 0) {
			problems.removeIf(problem -> problem.getSourceLineNumber() != line);
		}
		JsonArray listed = new JsonArray();
		boolean outOfTime = false;
		for (IProblem problem : problems) {
			if (listed.size() >= maxResults) {
				break;
			}
			if (monitor.isCanceled() && listed.size() == 0) {
				return McpToolResult.error("The call ran out of time before the first problem's fixes were computed."); //$NON-NLS-1$
			}
			if (listed.size() > 0 && (monitor.isCanceled() || CallBudget.leftMillis() == 0)) {
				// answer with what is done rather than let the call time out with nothing; the monitor is cancelled
				// only by the server's call timeout, so both mean the call's time is up
				outOfTime = true;
				break;
			}
			JsonObject entry = QuickFixSupport.describe(problem);
			JsonArray fixes = new JsonArray();
			if (problem.getSourceStart() < 0) {
				listed.add(entry.put("fixes", fixes)); //$NON-NLS-1$
				continue;
			}
			try {
				QuickFixSupport.Fixes found = QuickFixSupport.fixes(unit, astRoot, problem);
				for (ChangeCorrectionProposalCore fix : found.proposals()) {
					fixes.add(new JsonObject().put("label", fix.getName()) //$NON-NLS-1$
							.put("relevance", Integer.valueOf(fix.getRelevance()))); //$NON-NLS-1$
				}
				if (found.partialFailure() != null) {
					entry.put("partialError", found.partialFailure()); //$NON-NLS-1$
				}
			} catch (OperationCanceledException e) {
				// JDT stopped on the cancelled monitor, which is the call's time running out, not a failure of this problem
				if (listed.size() == 0) {
					return McpToolResult.error("The call ran out of time before the first problem's fixes were computed."); //$NON-NLS-1$
				}
				outOfTime = true;
				break;
			} catch (IllegalStateException e) {
				if (e.getCause() instanceof LinkageError) {
					// a mismatched installation fails every problem alike, so say it once
					return McpToolResult.error(e.getMessage());
				}
				entry.put("error", Objects.requireNonNullElse(e.getMessage(), e.getClass().getName())); //$NON-NLS-1$
			} catch (CoreException | RuntimeException e) {
				// one subprocessor failing must not hide the other problems' fixes
				entry.put("error", Objects.requireNonNullElse(e.getMessage(), e.getClass().getName())); //$NON-NLS-1$
			}
			listed.add(entry.put("fixes", fixes)); //$NON-NLS-1$
		}
		JsonObject result = new JsonObject().put("file", path) //$NON-NLS-1$
				.put("total", Integer.valueOf(problems.size())) //$NON-NLS-1$
				// any list shorter than total is truncated; outOfTime then says that a larger maxResults would not help
				.put("truncated", Boolean.valueOf(problems.size() > listed.size())) //$NON-NLS-1$
				.put("problems", listed); //$NON-NLS-1$
		if (line > 0) {
			// 'total' counts the problems on the asked line, so also say how many of the same kinds the whole file has
			result.put("totalInFile", Integer.valueOf(inFile)); //$NON-NLS-1$
		}
		if (outOfTime) {
			// a larger maxResults would not help here, so say why the list is short apart from 'truncated'
			result.put("outOfTime", Boolean.TRUE); //$NON-NLS-1$
			IProblem last = problems.get(listed.size() - 1);
			result.put("waitNote", ("Computing the fixes ran out of the call's time" //$NON-NLS-1$
					+ " after %d of %d problems%s. " //$NON-NLS-1$
					+ (line > 0 ? "The rest of this line was not reached, and asking for the line again starts from its first problem." //$NON-NLS-1$
							: "Ask again with 'line' for each later line to list the rest.")).formatted(Integer.valueOf(listed.size()), //$NON-NLS-1$
							Integer.valueOf(problems.size()),
							// a problem without a position has no line to name
							last.getSourceLineNumber() > 0 ? ", the last on line " + last.getSourceLineNumber() : "")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return McpToolResult.of(result.toString());
	}
}
