package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.widgets.Display;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Waits for a fixed time, which is what a recording a person will read needs
 * between steps and what nothing else here offers honestly.
 */
public final class PauseTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_pause"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Waits for the given number of milliseconds and returns. Changes nothing. This is the pause a screencast needs so a person can read a step before the next one, and it is the honest form of what eclipse_wait_until_settled with quietRounds was being used for. The wait is capped at the call timeout less a margin, and the answer says so under clamped when the cap applied, so a longer hold is several calls. Refused inside an atomic eclipse_run_script, where it would freeze the UI thread for the whole time and nothing would repaint."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["millis"],
				  "properties": {
				    "millis": {"type":"integer","minimum":1,"maximum":600000,"description":"How long to wait. Capped at the call timeout less a margin."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		int requested = ToolArguments.of(arguments).getInt("millis", 0, 0, 600_000); //$NON-NLS-1$
		if (requested <= 0) {
			return McpToolResult.error("The argument 'millis' is required and has to be positive."); //$NON-NLS-1$
		}
		if (Display.getCurrent() != null) {
			return McpToolResult.error(
					"eclipse_pause is running on the UI thread, inside an atomic eclipse_run_script; a pause there would only freeze the IDE and nothing would repaint. Run the script without atomic, or pause between scripts."); //$NON-NLS-1$
		}
		int cap = CallBudget.maxWaitSeconds() * 1000;
		int millis = Math.min(requested, cap);
		long start = System.currentTimeMillis();
		long deadline = start + millis;
		while (true) {
			long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0 || monitor.isCanceled()) {
				break;
			}
			try {
				Thread.sleep(Math.min(100, remaining));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		long paused = System.currentTimeMillis() - start;
		JsonObject result = new JsonObject().put("requestedMillis", requested).put("pausedMillis", paused) //$NON-NLS-1$ //$NON-NLS-2$
				.put("clamped", millis < requested); //$NON-NLS-1$
		if (millis < requested) {
			result.put("note", CallBudget.clampNote(requested / 1000 + 1, "eclipse_pause again for the remaining time")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return McpToolResult.of(result.toString());
	}
}
