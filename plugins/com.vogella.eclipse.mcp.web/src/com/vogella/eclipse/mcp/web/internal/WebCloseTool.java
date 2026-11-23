package com.vogella.eclipse.mcp.web.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Closes the web page and the browser this IDE started for it.
 */
public final class WebCloseTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_web_close"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "CLOSES the web page eclipse_web_open opened. A browser this IDE started is shut down and its temporary profile deleted, so the next eclipse_web_open starts clean, without cookies or storage; a tab opened through connectUrl is closed and the user's browser keeps running. Answers closed false when nothing was open. The browser also closes when the IDE exits."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {},
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		return McpToolResult.of(new JsonObject().put("closed", Boolean.valueOf(WebBrowser.shutdown())).toString()); //$NON-NLS-1$
	}
}
