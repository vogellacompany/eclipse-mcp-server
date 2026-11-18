package com.vogella.eclipse.mcp.jdt.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
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
		return "Lists the compiler problems of one Java file and, for each, the quick fixes JDT offers for it (what Ctrl+1 shows in the editor), most relevant first. Computed on the file's current content without the UI, so it works in a headless IDE and does not need a build. Apply one with eclipse_apply_quick_fix, passing the problem's offset and problemId and the fix's label exactly as listed. Fixes that exist only as a JDT UI wizard or dialog, such as creating a new class or adding a library to the build path, are not offered."; //$NON-NLS-1$
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
		List<IProblem> problems = QuickFixSupport.problems(astRoot, includeWarnings);
		if (line > 0) {
			problems.removeIf(problem -> problem.getSourceLineNumber() != line);
		}
		JsonArray listed = new JsonArray();
		boolean outOfTime = false;
		for (IProblem problem : problems) {
			if (listed.size() >= maxResults) {
				break;
			}
			if (monitor.isCanceled()) {
				return McpToolResult.error("The request was cancelled."); //$NON-NLS-1$
			}
			if (listed.size() > 0 && CallBudget.leftMillis() == 0) {
				// answer with what is done rather than let the call time out with nothing
				outOfTime = true;
				break;
			}
			JsonObject entry = QuickFixSupport.describe(problem);
			JsonArray fixes = new JsonArray();
			try {
				for (ChangeCorrectionProposalCore fix : QuickFixSupport.fixes(unit, astRoot, problem)) {
					fixes.add(new JsonObject().put("label", fix.getName()) //$NON-NLS-1$
							.put("relevance", Integer.valueOf(fix.getRelevance()))); //$NON-NLS-1$
				}
			} catch (CoreException | RuntimeException e) {
				// one subprocessor failing must not hide the other problems' fixes
				entry.put("error", Objects.requireNonNullElse(e.getMessage(), e.getClass().getName())); //$NON-NLS-1$
			}
			listed.add(entry.put("fixes", fixes)); //$NON-NLS-1$
		}
		JsonObject result = new JsonObject().put("file", path) //$NON-NLS-1$
				.put("total", Integer.valueOf(problems.size())) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(problems.size() > listed.size())) //$NON-NLS-1$
				.put("problems", listed); //$NON-NLS-1$
		if (outOfTime) {
			result.put("waitNote", "Computing the fixes ran out of the call's time, so only the first problems are listed. " //$NON-NLS-1$ //$NON-NLS-2$
					+ "Ask again with 'line', or with includeWarnings false, to list the rest."); //$NON-NLS-1$
		}
		return McpToolResult.of(result.toString());
	}
}
