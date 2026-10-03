package com.vogella.eclipse.mcp.basic.internal;

import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Records the current problems, so a later call can report only what changed.
 */
public final class MarkProblemsTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_mark_problems"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Records the problems the workspace has right now and returns a marker. Pass it to eclipse_get_problems as 'marker' and the answer is only what appeared since, plus what went away. Changes nothing. This is how to ask 'what did my change break': the alternative is reading every problem before and after and diffing them yourself, which on a large project is a hundred kilobytes a call for an answer that is usually a few lines. Only the last few markers are kept."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{"type":"object","properties":{},"additionalProperties":false}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		// deliberately no build and no refresh: this records the state as it is, and
		// making it current is the caller's decision through eclipse_get_problems or
		// eclipse_build, which is where the cost belongs
		Set<String> problems = GetProblemsTool.allProblemKeys();
		String marker = ProblemBaselines.take(problems);
		return McpToolResult.of(new JsonObject().put("marker", marker) //$NON-NLS-1$
				.put("problems", Integer.valueOf(problems.size())) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"Pass this as 'marker' to eclipse_get_problems to see only what changed since. Nothing was built or refreshed, so the baseline is the state as it stands.") //$NON-NLS-1$
				.toString());
	}
}
