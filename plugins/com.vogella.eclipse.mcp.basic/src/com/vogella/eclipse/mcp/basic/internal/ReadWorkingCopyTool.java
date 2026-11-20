package com.vogella.eclipse.mcp.basic.internal;

import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reads the working copy of a workspace file, unsaved changes included, which is the text the
 * user sees in an open editor rather than the one on disk.
 */
public final class ReadWorkingCopyTool implements IMcpTool {

	private static final int DEFAULT_MAX_BYTES = 1_000_000;

	@Override
	public String getName() {
		return "eclipse_read_working_copy"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reads the working copy of a workspace file: the shared text buffer that an open editor, a compare view or a refactoring holds, UNSAVED CHANGES INCLUDED. Read-only. eclipse_read_file reads the file as saved and says unsavedInEditor when the two differ; this is the tool that then returns what the user actually sees. eclipse_edit_working_copy changes this text in place; eclipse_edit_file and eclipse_write_file work on the saved file, so do not build their oldText from unsaved text. When nothing holds a working copy of the file the answer says workingCopy false and eclipse_read_file is the one to use. The file is refreshed first, so a working copy without unsaved changes picks up edits made outside Eclipse, for example through a shell. dirty says the text differs from the saved file; synchronized false says the file changed on disk while the working copy had unsaved changes, which the refresh does not overwrite, so the text and the disk have diverged. The buffer is read on the UI thread, so a modal dialog or a busy IDE can make this time out while eclipse_read_file still works."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["path"],
				  "properties": {
				    "path":     {"type":"string","description":"Workspace path of the file, e.g. /app/src/com/example/Main.java"},
				    "offset":   {"type":"integer","minimum":1,"description":"First line to return, 1 based. Omit for the start of the file."},
				    "limit":    {"type":"integer","minimum":1,"maximum":100000,"description":"How many lines to return. Omit for the rest of the file."},
				    "maxBytes": {"type":"integer","minimum":1,"maximum":10000000,"default":1000000,"description":"Refuse rather than return more than this."},
				    "refresh":  {"type":"boolean","default":true,"description":"Read outside changes into the workspace first."}
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
		IPath workspacePath = IPath.fromPortableString(path);
		IFile file = workspacePath.segmentCount() < 2 ? null
				: ResourcesPlugin.getWorkspace().getRoot().getFile(workspacePath);
		if (file == null) {
			return McpToolResult.error("'%s' is not a workspace file path; it needs a project and a file.".formatted(path)); //$NON-NLS-1$
		}
		if (args.getBoolean("refresh", true)) { //$NON-NLS-1$
			try {
				FileSupport.refresh(file, monitor);
			} catch (CoreException e) {
				// the working copy is still readable, and stale content beats no content
			}
		}
		int maxBytes = args.getInt("maxBytes", DEFAULT_MAX_BYTES, 1, 10_000_000); //$NON-NLS-1$
		int offset = args.getInt("offset", 1, 1, Integer.MAX_VALUE); //$NON-NLS-1$
		int limit = args.getInt("limit", -1, -1, 100_000); //$NON-NLS-1$

		WorkingCopies.Snapshot workingCopy;
		try {
			workingCopy = WorkingCopies.read(file);
		} catch (TimeoutException e) {
			return McpToolResult.error(
					"The UI thread did not hand over the working copy of '%s' in time, probably because a modal dialog is open or the IDE is busy. eclipse_read_file still reads the saved file." //$NON-NLS-1$
							.formatted(path));
		} catch (Exception e) {
			return McpToolResult.error("Could not read the working copy of '%s': %s".formatted(path, e)); //$NON-NLS-1$
		}
		JsonObject result = new JsonObject().put("path", file.getFullPath().toString()); //$NON-NLS-1$
		if (workingCopy == null) {
			if (!file.exists()) {
				return McpToolResult.error("No file at the workspace path '%s'.".formatted(path)); //$NON-NLS-1$
			}
			return McpToolResult.of(result.put("read", Boolean.FALSE) //$NON-NLS-1$
					.put("workingCopy", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "Nothing holds a working copy of this file, so the saved file is all there is. Read it with eclipse_read_file.") //$NON-NLS-1$ //$NON-NLS-2$
					.toString());
		}
		String charset = FileSupport.charset(file);
		String content = workingCopy.text();
		int bytes = content.getBytes(Charset.forName(charset)).length;
		result.put("workingCopy", Boolean.TRUE) //$NON-NLS-1$
				.put("dirty", Boolean.valueOf(workingCopy.dirty())) //$NON-NLS-1$
				.put("synchronized", Boolean.valueOf(workingCopy.inSync())) //$NON-NLS-1$
				.put("bytes", Integer.valueOf(bytes)) //$NON-NLS-1$
				.put("charset", charset); //$NON-NLS-1$
		if (bytes > maxBytes) {
			return McpToolResult.of(result.put("read", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "The working copy is larger than maxBytes (%d). Raise it, or read a line range." //$NON-NLS-1$ //$NON-NLS-2$
							.formatted(Integer.valueOf(maxBytes)))
					.toString());
		}
		return McpToolResult.of(ReadFileTool.putLines(result, content, offset, limit).toString());
	}
}
