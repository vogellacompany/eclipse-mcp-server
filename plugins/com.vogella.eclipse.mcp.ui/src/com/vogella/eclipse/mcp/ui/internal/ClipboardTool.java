package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Display;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** Reads the text on the system clipboard. */
public final class ClipboardTool implements IMcpTool {

	private static final int DEFAULT_MAX_CHARS = 100_000;

	@Override
	public String getName() {
		return "eclipse_read_clipboard"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reads the text on the system clipboard, which is shared with everything else on the machine. Changes nothing. This reads a custom-painted control that reports no content of its own: run org.eclipse.ui.edit.copy through eclipse_run_workbench_command with the control focused, then read the result here. THAT COPY OVERWRITES WHAT THE PERSON AT THE MACHINE HAD ON THE CLIPBOARD, and what is read here can be anything they copied, a password included. availableTypes names the other formats on offer, which this does not read."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "maxChars": {"type":"integer","default":100000,"minimum":1,"maximum":10000000,"description":"How much of the text to return; length always reports all of it."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (!Workbenches.running()) {
			return McpToolResult.error("There is no running workbench."); //$NON-NLS-1$
		}
		int maxChars = ToolArguments.of(arguments).getInt("maxChars", DEFAULT_MAX_CHARS, 1, 10_000_000); //$NON-NLS-1$
		return UiThread.call(10, () -> read(Workbenches.display(), maxChars));
	}

	/** The clipboard's text, cut at {@code maxChars}, on the UI thread. */
	public static JsonObject read(Display display, int maxChars) {
		Clipboard clipboard = new Clipboard(display);
		try {
			JsonArray types = new JsonArray();
			for (String type : clipboard.getAvailableTypeNames()) {
				types.add(type);
			}
			String text = (String) clipboard.getContents(TextTransfer.getInstance());
			if (text == null) {
				return new JsonObject().put("hasText", Boolean.FALSE).put("availableTypes", types); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return new JsonObject().put("hasText", Boolean.TRUE) //$NON-NLS-1$
					.put("text", text.length() > maxChars ? text.substring(0, maxChars) : text) //$NON-NLS-1$
					.put("length", Integer.valueOf(text.length())) //$NON-NLS-1$
					.put("truncated", Boolean.valueOf(text.length() > maxChars)) //$NON-NLS-1$
					.put("availableTypes", types); //$NON-NLS-1$
		} finally {
			clipboard.dispose();
		}
	}
}
