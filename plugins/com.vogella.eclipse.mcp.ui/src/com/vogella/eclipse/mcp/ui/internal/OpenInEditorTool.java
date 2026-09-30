package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Opens a file in the IDE and puts the cursor on a line.
 */
public final class OpenInEditorTool implements IMcpTool {

	private static final long UI_TIMEOUT_SECONDS = 10;

	@Override
	public String getName() {
		return "eclipse_open"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Opens a file in an Eclipse editor and optionally reveals a line, so that the person at the IDE is looking at what you are talking about instead of copying a path. The path is a workspace path first, and when no workspace file matches, an absolute path on disk, which opens the way File > Open File does, in the default editor for the name with the file outside the workspace; the answer says which of the two it was under external. CHANGES WHAT THE IDE SHOWS but writes nothing."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["path"],
				  "properties": {
				    "path": {"type":"string","description":"Workspace path of the file, e.g. /app/src/com/example/Main.java, or an absolute path on disk for a file outside the workspace."},
				    "line": {"type":"integer","minimum":1,"description":"Line to reveal, 1 based."},
				    "activate": {"type":"boolean","default":true,"description":"Bring the editor to the front."}
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
		if (!PlatformUI.isWorkbenchRunning()) {
			return McpToolResult.error(Workbenches.noIde());
		}
		// getFile throws for a path without a project and a name
		IFile file = new Path(path).segmentCount() < 2 ? null
				: ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(path));
		IFileStore external = null;
		if (file == null || !file.exists()) {
			// a workspace path and an absolute path on disk look the same, so the
			// workspace answers first and the disk only for what it does not have
			IFile inWorkspace = workspaceFileAt(path);
			if (inWorkspace != null) {
				file = inWorkspace;
			} else if (java.nio.file.Files.isRegularFile(java.nio.file.Path.of(path))) {
				external = EFS.getLocalFileSystem().getStore(IPath.fromOSString(path));
			} else {
				return McpToolResult.error(
						"No file at the workspace path '%s', and no file at that path on disk either.".formatted(path)); //$NON-NLS-1$
			}
		}
		int line = args.getInt("line", -1, -1, Integer.MAX_VALUE); //$NON-NLS-1$
		boolean activate = args.getBoolean("activate", true); //$NON-NLS-1$

		IFile workspaceFile = file;
		IFileStore store = external;
		return UiThread.call(UI_TIMEOUT_SECONDS,
				() -> store == null ? open(workspaceFile, line, activate) : openExternal(store, line, activate));
	}

	/** The workspace file at an absolute disk path, when a project contains it. */
	private static IFile workspaceFileAt(String path) {
		java.nio.file.Path disk;
		try {
			disk = java.nio.file.Path.of(path);
		} catch (RuntimeException e) {
			return null;
		}
		if (!disk.isAbsolute()) {
			return null;
		}
		for (IFile candidate : ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(disk.toUri())) {
			if (candidate.exists()) {
				return candidate;
			}
		}
		return null;
	}

	private static JsonObject openExternal(IFileStore store, int line, boolean activate) {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		if (page == null) {
			return new JsonObject().put("opened", false) //$NON-NLS-1$
					.put("reason", "The workbench has no active page."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		try {
			IEditorPart editor = IDE.openEditorOnFileStore(page, store);
			if (activate) {
				page.activate(editor);
			}
			JsonObject result = new JsonObject().put("opened", true) //$NON-NLS-1$
					.put("path", store.toString()) //$NON-NLS-1$
					.put("external", true) //$NON-NLS-1$
					.put("editor", editor.getTitle()); //$NON-NLS-1$
			result.put("revealedLine", line > 0 ? Integer.valueOf(reveal(editor, line)) : null); //$NON-NLS-1$
			return result;
		} catch (org.eclipse.ui.PartInitException e) {
			return new JsonObject().put("opened", false) //$NON-NLS-1$
					.put("reason", e.getMessage()); //$NON-NLS-1$
		}
	}

	private static JsonObject open(IFile file, int line, boolean activate) {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		if (page == null) {
			return new JsonObject().put("opened", false) //$NON-NLS-1$
					.put("reason", "The workbench has no active page."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		try {
			IEditorPart editor = IDE.openEditor(page, file, activate);
			JsonObject result = new JsonObject().put("opened", true) //$NON-NLS-1$
					.put("path", file.getFullPath().toString()) //$NON-NLS-1$
					.put("external", false) //$NON-NLS-1$
					.put("editor", editor.getTitle()); //$NON-NLS-1$
			result.put("revealedLine", line > 0 ? Integer.valueOf(reveal(editor, line)) : null); //$NON-NLS-1$
			return result;
		} catch (org.eclipse.ui.PartInitException e) {
			return new JsonObject().put("opened", false) //$NON-NLS-1$
					.put("reason", e.getMessage()); //$NON-NLS-1$
		}
	}

	/** Returns the line actually revealed, which is clamped to the end of the file. */
	private static int reveal(IEditorPart editor, int line) {
		if (!(editor instanceof ITextEditor textEditor)) {
			return -1;
		}
		IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
		if (document == null) {
			return -1;
		}
		int zeroBased = Math.min(line - 1, document.getNumberOfLines() - 1);
		try {
			textEditor.selectAndReveal(document.getLineOffset(zeroBased), 0);
			return zeroBased + 1;
		} catch (BadLocationException e) {
			return -1;
		}
	}
}
