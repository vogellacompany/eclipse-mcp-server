package com.vogella.eclipse.mcp.core.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Replaces a passage in a workspace file, refusing when what the caller
 * expected to find is not there or is there more than once.
 */
public final class EditFileTool implements IMcpTool {

	/** Lines of the file shown around the change, so the caller can see what happened. */
	private static final int CONTEXT_LINES = 3;

	@Override
	public String getName() {
		return "eclipse_edit_file"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Replaces one passage of a workspace file with another. MODIFIES THE WORKSPACE. This is how a file is changed without resending it: eclipse_write_file takes the complete content, so changing one line of an 800 line file through it means reading and returning all 800. THE EDIT IS CHECKED AGAINST WHAT YOU BELIEVE IS THERE: oldText that matches nothing is refused, and oldText that matches more than once is refused with the count unless replaceAll is set, so an edit made on a stale reading of the file fails instead of landing somewhere unintended. Line endings need no attention: a file written with CRLF is matched against text given with LF, and the answer reports lineDelimiter when that is what happened. Give enough surrounding text to be unique rather than a bare identifier. Writing goes through the workspace, so the file keeps its charset, the resource tree sees the change at once, and the previous content goes into the local history where Compare With > Local History recovers it. The answer shows the changed lines with context. Use eclipse_format afterwards for Java."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["path", "oldText", "newText"],
				  "properties": {
				    "path":       {"type":"string","description":"Workspace path of the file, e.g. /my.project/src/example/Sample.java."},
				    "oldText":    {"type":"string","description":"The exact text to replace, whitespace included. Give enough context to occur once."},
				    "newText":    {"type":"string","description":"What to put in its place. Empty deletes the passage."},
				    "replaceAll": {"type":"boolean","default":false,"description":"Replace every occurrence instead of refusing when there is more than one."},
				    "dryRun":     {"type":"boolean","default":false,"description":"Report what would change, including the match count and the context, and write nothing."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path"); //$NON-NLS-1$
		if (path == null) {
			return McpToolResult.error("The argument 'path' is required."); //$NON-NLS-1$
		}
		// not getString: it trims, and leading or trailing whitespace is exactly what
		// makes an edit unique
		Object oldRaw = arguments == null ? null : arguments.get("oldText"); //$NON-NLS-1$
		Object newRaw = arguments == null ? null : arguments.get("newText"); //$NON-NLS-1$
		if (!(oldRaw instanceof String oldText) || oldText.isEmpty()) {
			return McpToolResult.error("The argument 'oldText' is required and must not be empty."); //$NON-NLS-1$
		}
		if (!(newRaw instanceof String newText)) {
			return McpToolResult.error("The argument 'newText' is required. Pass an empty string to delete the passage."); //$NON-NLS-1$
		}
		boolean replaceAll = args.getBoolean("replaceAll", false); //$NON-NLS-1$
		boolean dryRun = args.getBoolean("dryRun", false); //$NON-NLS-1$

		IPath workspacePath = new Path(path);
		if (workspacePath.segmentCount() < 2) {
			return McpToolResult.error("'%s' is not a workspace file path; it needs a project and a file.".formatted(path)); //$NON-NLS-1$
		}
		IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(workspacePath);
		IProject project = file.getProject();
		if (!project.isAccessible()) {
			return McpToolResult.error("No open project named '%s' in this workspace.".formatted(project.getName())); //$NON-NLS-1$
		}
		try {
			// the file may have been changed through the caller's own shell
			WorkspaceSync.refresh(file, monitor);
		} catch (CoreException e) {
			// a file that cannot be refreshed can still be read
		}
		if (!file.exists()) {
			return McpToolResult.error("No file at the workspace path '%s'.".formatted(path)); //$NON-NLS-1$
		}

		Charset charset;
		String content;
		try {
			charset = Charset.forName(file.getCharset());
			content = new String(file.getContents(true).readAllBytes(), charset);
		} catch (CoreException | IOException | RuntimeException e) {
			return McpToolResult.error("Could not read '%s': %s".formatted(path, e)); //$NON-NLS-1$
		}

		// a file checked out on Windows has CRLF line endings while a client composing
		// an edit sends LF, so a passage spanning a line break matches nothing at all
		// and the refusal reads as a stale reading of the file rather than as what it
		// is. The delimiter belongs to the file, so the caller's text is brought to it
		boolean crlf = count(content, oldText) == 0 && oldText.indexOf('\n') >= 0 && usesCrlf(content);
		String wanted = crlf ? toCrlf(oldText) : oldText;
		String replacement = crlf ? toCrlf(newText) : newText;
		int matches = count(content, wanted);
		JsonObject result = new JsonObject().put("path", file.getFullPath().toString()) //$NON-NLS-1$
				.put("matches", Integer.valueOf(matches)); //$NON-NLS-1$
		if (matches == 0) {
			return McpToolResult.error(
					"'oldText' does not occur in %s, so nothing was changed. The file may have moved on since you read it, or the passage may differ in whitespace or line endings." //$NON-NLS-1$
							.formatted(path));
		}
		if (crlf) {
			result.put("lineDelimiter", "\\r\\n") //$NON-NLS-1$ //$NON-NLS-2$
					.put("matchedAfterConvertingLineEndings", Boolean.TRUE); //$NON-NLS-1$
		}
		if (matches > 1 && !replaceAll) {
			return McpToolResult.error(
					"'oldText' occurs %d times in %s, so which one was meant is not decidable. Give more surrounding text, or pass replaceAll true to change all of them." //$NON-NLS-1$
							.formatted(Integer.valueOf(matches), path));
		}

		String edited = replaceAll ? content.replace(wanted, replacement)
				: replaceFirst(content, wanted, replacement);
		int line = lineOf(content, content.indexOf(wanted));
		result.put("replacements", Integer.valueOf(replaceAll ? matches : 1)) //$NON-NLS-1$
				.put("changedLines", changedLines(content, wanted)) //$NON-NLS-1$
				.put("firstChangedLine", Integer.valueOf(line)) //$NON-NLS-1$
				.put("charset", charset.name()) //$NON-NLS-1$
				.put("context", context(edited, line)); //$NON-NLS-1$
		if (dryRun) {
			return McpToolResult.of(result.put("dryRun", Boolean.TRUE).put("edited", Boolean.FALSE) //$NON-NLS-1$ //$NON-NLS-2$
					.put("note", "Nothing was written. The context shows what the file would look like.") //$NON-NLS-1$ //$NON-NLS-2$
					.toString());
		}
		try {
			file.setContents(new ByteArrayInputStream(edited.getBytes(charset)), IResource.KEEP_HISTORY, monitor);
		} catch (CoreException e) {
			return McpToolResult.error("Could not write '%s': %s".formatted(path, e.getMessage())); //$NON-NLS-1$
		}
		return McpToolResult.of(result.put("edited", Boolean.TRUE) //$NON-NLS-1$
				.put("note", buildNote()) //$NON-NLS-1$
				.toString());
	}

	/** Every line an occurrence starts on, since one number cannot describe a replaceAll. */
	private static JsonArray changedLines(String content, String oldText) {
		var lines = new JsonArray();
		for (int at = content.indexOf(oldText); at >= 0; at = content.indexOf(oldText, at + oldText.length())) {
			lines.add(Integer.valueOf(lineOf(content, at)));
		}
		return lines;
	}

	/** Auto-build decides whether the caller still has to ask for one. */
	private static String buildNote() {
		if (ResourcesPlugin.getWorkspace().isAutoBuilding()) {
			return "The previous content is in the local history. Auto-build is on, so the change is being compiled already; eclipse_get_problems reports what it found, and eclipse_format tidies Java."; //$NON-NLS-1$
		}
		return "The previous content is in the local history. Auto-build is OFF in this workspace, so nothing has compiled the change yet: run eclipse_build before believing any problem report. eclipse_format tidies Java."; //$NON-NLS-1$
	}

	/**
	 * Whether the file is written with CRLF, decided on the first line break: a file
	 * with mixed endings is one an editor has already half converted, and its next
	 * line break is the one this edit has to land beside.
	 */
	private static boolean usesCrlf(String content) {
		int at = content.indexOf('\n');
		return at > 0 && content.charAt(at - 1) == '\r';
	}

	/** The text with every bare LF turned into CRLF, leaving CRLF that is already there. */
	private static String toCrlf(String text) {
		return text.replace("\r\n", "\n").replace("\n", "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	static int count(String content, String text) {
		int found = 0;
		for (int at = content.indexOf(text); at >= 0; at = content.indexOf(text, at + text.length())) {
			found++;
		}
		return found;
	}

	private static String replaceFirst(String content, String oldText, String newText) {
		int at = content.indexOf(oldText);
		return content.substring(0, at) + newText + content.substring(at + oldText.length());
	}

	/** One based line number of an offset, which is what an editor and a stack trace use. */
	static int lineOf(String content, int offset) {
		int line = 1;
		for (int i = 0; i < offset && i < content.length(); i++) {
			if (content.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	/**
	 * The edited file around the change, so the answer shows the result rather than
	 * promising it. Split on LF and stripped of the CR a CRLF file leaves behind,
	 * which would otherwise reach the caller as a stray carriage return per line.
	 */
	static String context(String content, int line) {
		String[] lines = content.split("\r?\n", -1); //$NON-NLS-1$
		int from = Math.max(0, line - 1 - CONTEXT_LINES);
		int to = Math.min(lines.length, line + CONTEXT_LINES);
		StringBuilder text = new StringBuilder();
		for (int i = from; i < to; i++) {
			text.append(i + 1).append(": ").append(lines[i]).append('\n'); //$NON-NLS-1$
		}
		return text.toString();
	}
}
