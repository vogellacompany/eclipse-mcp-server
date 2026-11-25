package com.vogella.eclipse.mcp.basic.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentRewriteSession;
import org.eclipse.jface.text.DocumentRewriteSessionType;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.text.undo.DocumentUndoManagerRegistry;
import org.eclipse.text.undo.IDocumentUndoManager;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.UiDispatch;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Replaces a passage in the working copy of a workspace file, the text an open editor shows,
 * and leaves it unsaved, so the change lands where the user is looking and can be undone there.
 */
public final class EditWorkingCopyTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_edit_working_copy"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Replaces one passage in the working copy of a workspace file: the shared text buffer that an open editor, a compare view or a refactoring holds. CHANGES THE UNSAVED TEXT THE USER SEES AND DOES NOT SAVE IT: the file on disk stays as it was, the editor shows the change at once as unsaved, and Undo in the editor reverts it. Use it for a file open in an editor, particularly one with unsaved changes, where eclipse_edit_file would write the disk underneath the editor; build oldText from eclipse_read_working_copy, not from eclipse_read_file. Matching works as in eclipse_edit_file: oldText that matches nothing is refused, more than one match is refused unless replaceAll is set, and text given with LF matches a CRLF document. The file is refreshed first, so a working copy without unsaved changes picks up edits made through a shell. The edit is REFUSED when the working copy has unsaved changes and the file also changed on disk, since either side would then be lost, and when the UI thread does not answer, for example behind a modal dialog; nothing falls back to the disk. When nothing holds a working copy the answer says workingCopy false and eclipse_edit_file is the one to use. The answer reports dirty, which is true after any real change because nothing was saved; changes reach the compiler and eclipse_get_problems only once the user or a save writes them."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["path", "oldText", "newText"],
				  "properties": {
				    "path":       {"type":"string","description":"Workspace path of the file, e.g. /my.project/src/example/Sample.java."},
				    "oldText":    {"type":"string","description":"The exact text to replace, whitespace included, as eclipse_read_working_copy returned it. Give enough context to occur once."},
				    "newText":    {"type":"string","description":"What to put in its place. Empty deletes the passage."},
				    "replaceAll": {"type":"boolean","default":false,"description":"Replace every occurrence instead of refusing when there is more than one."},
				    "dryRun":     {"type":"boolean","default":false,"description":"Report what would change, including the match count and the context, and change nothing."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public String getOutputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "path":                              {"type":"string"},
				    "edited":                            {"type":"boolean"},
				    "workingCopy":                       {"type":"boolean"},
				    "reason":                            {"type":"string"},
				    "matches":                           {"type":"integer"},
				    "replacements":                      {"type":"integer"},
				    "changedLines":                      {"type":"array","items":{"type":"integer"}},
				    "changedLinesTruncated":             {"type":"boolean"},
				    "firstChangedLine":                  {"type":"integer"},
				    "lineDelimiter":                     {"type":"string"},
				    "matchedAfterConvertingLineEndings": {"type":"boolean"},
				    "dryRun":                            {"type":"boolean"},
				    "dirty":                             {"type":"boolean"},
				    "context":                           {"type":"string"},
				    "note":                              {"type":"string"}
				  },
				  "required": ["path","edited","workingCopy"]
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path"); //$NON-NLS-1$
		if (path == null) {
			return McpToolResult.error("The argument 'path' is required."); //$NON-NLS-1$
		}
		// not getString: it trims, and surrounding whitespace is what makes an edit unique
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

		IPath workspacePath = IPath.fromPortableString(path);
		if (workspacePath.segmentCount() < 2) {
			return McpToolResult.error("'%s' is not a workspace file path; it needs a project and a file.".formatted(path)); //$NON-NLS-1$
		}
		IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(workspacePath);
		IProject project = file.getProject();
		if (!project.isAccessible()) {
			return McpToolResult.error("No open project named '%s' in this workspace.".formatted(project.getName())); //$NON-NLS-1$
		}
		try {
			// a working copy without unsaved changes reloads what a shell wrote; one with
			// unsaved changes keeps them and stops being synchronized
			WorkspaceSync.refresh(file, monitor);
		} catch (CoreException e) {
			// the working copy can still be checked and edited
		}
		if (WorkingCopies.find(file) == null) {
			if (!file.exists()) {
				return McpToolResult.error("No file at the workspace path '%s'.".formatted(path)); //$NON-NLS-1$
			}
			return noWorkingCopy(file);
		}
		try {
			// checked, matched and changed in one go on the UI thread, which owns the document
			// while an editor shows it, so the user cannot type between the match and the edit
			return UiDispatch.call(() -> {
				// looked up again: an editor that closed meanwhile has disposed its buffer, and
				// an edit to a disconnected buffer would land where nobody sees it
				ITextFileBuffer buffer = WorkingCopies.find(file);
				return buffer == null || buffer.getDocument() == null ? noWorkingCopy(file)
						: edit(buffer, path, oldText, newText, replaceAll, dryRun);
			}, CallBudget.uiWaitSeconds(0));
		} catch (TimeoutException e) {
			return McpToolResult.error(
					"The UI thread did not hand over the working copy of '%s' in time, probably because a modal dialog is open or the IDE is busy. Nothing was changed." //$NON-NLS-1$
							.formatted(path));
		} catch (Exception e) {
			return McpToolResult.error("Could not edit the working copy of '%s': %s".formatted(path, e)); //$NON-NLS-1$
		}
	}

	private static McpToolResult noWorkingCopy(IFile file) {
		return McpToolResult.of(new JsonObject().put("path", file.getFullPath().toString()) //$NON-NLS-1$
				.put("edited", Boolean.FALSE) //$NON-NLS-1$
				.put("workingCopy", Boolean.FALSE) //$NON-NLS-1$
				.put("reason", "Nothing holds a working copy of this file, so there is no unsaved text to change. Edit the saved file with eclipse_edit_file.") //$NON-NLS-1$ //$NON-NLS-2$
				.toString());
	}

	private static McpToolResult edit(ITextFileBuffer buffer, String path, String oldText, String newText,
			boolean replaceAll, boolean dryRun) throws BadLocationException {
		if (!buffer.isSynchronized()) {
			return McpToolResult.error(
					"The working copy of '%s' has unsaved changes and the file also changed on disk, so editing either would lose the other. Nothing was changed; the user has to save or revert the editor first." //$NON-NLS-1$
							.formatted(path));
		}
		IDocument document = buffer.getDocument();
		String content = document.get();
		boolean crlf = EditFileTool.count(content, oldText) == 0 && oldText.indexOf('\n') >= 0
				&& EditFileTool.usesCrlf(content);
		String wanted = crlf ? EditFileTool.toCrlf(oldText) : oldText;
		String replacement = crlf ? EditFileTool.toCrlf(newText) : newText;
		int matches = EditFileTool.count(content, wanted);
		if (matches == 0) {
			return McpToolResult.error(
					"'oldText' does not occur in the working copy of %s, so nothing was changed. It may have moved on since you read it, or the passage may differ in whitespace or line endings." //$NON-NLS-1$
							.formatted(path));
		}
		if (matches > 1 && !replaceAll) {
			return McpToolResult.error(
					"'oldText' occurs %d times in the working copy of %s, so which one was meant is not decidable. Give more surrounding text, or pass replaceAll true to change all of them." //$NON-NLS-1$
							.formatted(Integer.valueOf(matches), path));
		}
		List<Integer> offsets = new ArrayList<>();
		for (int at = content.indexOf(wanted); at >= 0 && (replaceAll || offsets.isEmpty()); at = content
				.indexOf(wanted, at + wanted.length())) {
			offsets.add(Integer.valueOf(at));
		}
		int line = EditFileTool.lineOf(content, offsets.get(0).intValue());
		JsonObject result = new JsonObject().put("path", buffer.getLocation().toString()) //$NON-NLS-1$
				.put("workingCopy", Boolean.TRUE) //$NON-NLS-1$
				.put("matches", Integer.valueOf(matches)) //$NON-NLS-1$
				.put("replacements", Integer.valueOf(offsets.size())) //$NON-NLS-1$
				.put("changedLines", EditFileTool.changedLines(content, wanted)) //$NON-NLS-1$
				.put("changedLinesTruncated", Boolean.valueOf(replaceAll && matches > EditFileTool.MAX_CHANGED_LINES)) //$NON-NLS-1$
				.put("firstChangedLine", Integer.valueOf(line)); //$NON-NLS-1$
		if (crlf) {
			result.put("lineDelimiter", "\\r\\n") //$NON-NLS-1$ //$NON-NLS-2$
					.put("matchedAfterConvertingLineEndings", Boolean.TRUE); //$NON-NLS-1$
		}
		if (dryRun) {
			String edited = replaceAll ? content.replace(wanted, replacement)
					: content.substring(0, offsets.get(0).intValue()) + replacement
							+ content.substring(offsets.get(0).intValue() + wanted.length());
			return McpToolResult.of(result.put("dryRun", Boolean.TRUE).put("edited", Boolean.FALSE) //$NON-NLS-1$ //$NON-NLS-2$
					.put("dirty", Boolean.valueOf(buffer.isDirty())) //$NON-NLS-1$
					.put("context", EditFileTool.context(edited, line)) //$NON-NLS-1$
					.put("note", "Nothing was changed. The context shows what the working copy would look like.") //$NON-NLS-1$ //$NON-NLS-2$
					.toString());
		}
		// one compound change, so a replaceAll is one step in the editor's undo history; the
		// undo manager folds nothing else, rewrite sessions included
		IDocumentUndoManager undo = DocumentUndoManagerRegistry.getDocumentUndoManager(document);
		if (undo != null) {
			undo.beginCompoundChange();
		}
		// and one rewrite session, so a viewer redraws once rather than per replacement
		DocumentRewriteSession session = document instanceof IDocumentExtension4 extension
				? extension.startRewriteSession(DocumentRewriteSessionType.SEQUENTIAL)
				: null;
		try {
			// back to front, so the offsets still ahead stay valid
			for (int i = offsets.size() - 1; i >= 0; i--) {
				document.replace(offsets.get(i).intValue(), wanted.length(), replacement);
			}
		} finally {
			if (session != null) {
				((IDocumentExtension4) document).stopRewriteSession(session);
			}
			if (undo != null) {
				undo.endCompoundChange();
			}
		}
		return McpToolResult.of(result.put("edited", Boolean.TRUE) //$NON-NLS-1$
				.put("dirty", Boolean.valueOf(buffer.isDirty())) //$NON-NLS-1$
				.put("context", EditFileTool.context(document.get(), line)) //$NON-NLS-1$
				.put("note", "The change is in the working copy only and is NOT SAVED: the file on disk, the compiler and eclipse_get_problems do not see it until the editor is saved. Undo in the editor reverts it.") //$NON-NLS-1$ //$NON-NLS-2$
				.toString());
	}
}
