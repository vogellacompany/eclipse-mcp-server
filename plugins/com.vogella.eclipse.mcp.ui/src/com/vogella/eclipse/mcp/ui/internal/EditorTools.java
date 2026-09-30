package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the open editors and closes them.
 */
public final class EditorTools {

	private static final long UI_TIMEOUT_SECONDS = 15;

	private EditorTools() {
	}

	/** What is open, in tab order, and which of it is unsaved. */
	public static final class ListEditors implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_list_editors"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Lists the open editors in tab order, with the file each one shows, which is active, and which have unsaved changes. Read-only. This is the tool that answers 'is there unsaved work', which eclipse_restart refuses on and which nothing else here reports on its own. It matters more once the IDE is hidden or on another machine, since a person cannot look at a window they cannot see, and a long session leaves editors open that nobody is watching."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{"type":"object","properties":{},"additionalProperties":false}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			return UiThread.call(UI_TIMEOUT_SECONDS, ListEditors::collect);
		}

		private static JsonObject collect() {
			IWorkbenchPage page = ViewTools.activePage();
			if (page == null) {
				return new JsonObject().put("editors", new JsonArray()) //$NON-NLS-1$
						.put("total", Integer.valueOf(0)) //$NON-NLS-1$
						.put("dirty", Integer.valueOf(0)) //$NON-NLS-1$
						.put("note", "The workbench has no active page."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			IEditorPart active = page.getActiveEditor();
			JsonArray editors = new JsonArray();
			int dirty = 0;
			for (IEditorReference reference : page.getEditorReferences()) {
				IEditorPart editor = reference.getEditor(false);
				boolean isDirty = reference.isDirty();
				if (isDirty) {
					dirty++;
				}
				editors.add(new JsonObject().put("title", reference.getTitle()) //$NON-NLS-1$
						.put("id", reference.getId()) //$NON-NLS-1$
						.put("path", path(reference)) //$NON-NLS-1$
						.put("dirty", Boolean.valueOf(isDirty)) //$NON-NLS-1$
						.put("active", Boolean.valueOf(editor != null && editor == active)) //$NON-NLS-1$
						.put("pinned", Boolean.valueOf(reference.isPinned()))); //$NON-NLS-1$
			}
			return new JsonObject().put("total", Integer.valueOf(editors.size())) //$NON-NLS-1$
					.put("dirty", Integer.valueOf(dirty)) //$NON-NLS-1$
					.put("editors", editors); //$NON-NLS-1$
		}
	}

	/** Closes editors, and refuses to lose unsaved work by accident. */
	public static final class CloseEditor implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_close_editor"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Closes open editors, by workspace path, by title, or all of them. CHANGES WHAT THE IDE SHOWS. Closing a clean editor loses nothing. A DIRTY EDITOR IS REFUSED unless you pass save, which saves it first, or discardUnsaved, which throws the changes away and is never a default. Nothing here opens a save prompt: the save happens through the editor rather than by asking, so an unattended call cannot leave a dialog waiting for somebody. Use eclipse_list_editors first to see what is open and what is unsaved."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "path":           {"type":"string","description":"Workspace path of the file whose editor to close."},
					    "title":          {"type":"string","description":"Editor tab title, or a substring of it."},
					    "all":            {"type":"boolean","default":false,"description":"Close every open editor."},
					    "save":           {"type":"boolean","default":false,"description":"Save a dirty editor before closing it."},
					    "discardUnsaved": {"type":"boolean","default":false,"description":"Close a dirty editor and lose its changes."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String path = args.getString("path"); //$NON-NLS-1$
			String title = args.getString("title"); //$NON-NLS-1$
			boolean all = args.getBoolean("all", false); //$NON-NLS-1$
			if (path == null && title == null && !all) {
				return McpToolResult
						.error("Select editors with 'path', 'title' or 'all'; refusing to close every editor by omission."); //$NON-NLS-1$
			}
			boolean save = args.getBoolean("save", false); //$NON-NLS-1$
			boolean discard = args.getBoolean("discardUnsaved", false); //$NON-NLS-1$
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> close(path, title, all, save, discard));
		}

		private static JsonObject close(String path, String title, boolean all, boolean save, boolean discard) {
			IWorkbenchPage page = ViewTools.activePage();
			if (page == null) {
				return new JsonObject().put("closed", Integer.valueOf(0)) //$NON-NLS-1$
						.put("reason", "The workbench has no active page."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			List<IEditorReference> selected = new ArrayList<>();
			for (IEditorReference reference : page.getEditorReferences()) {
				if (all || (path != null && path.equals(path(reference)))
						|| (title != null && reference.getTitle() != null
								&& reference.getTitle().toLowerCase().contains(title.toLowerCase()))) {
					selected.add(reference);
				}
			}
			if (selected.isEmpty()) {
				return new JsonObject().put("closed", Integer.valueOf(0)) //$NON-NLS-1$
						.put("reason", "No open editor matches.") //$NON-NLS-1$ //$NON-NLS-2$
						.put("openEditors", ListEditors.collect().remove("editors")); //$NON-NLS-1$ //$NON-NLS-2$
			}

			JsonArray closed = new JsonArray();
			JsonArray refused = new JsonArray();
			JsonArray saved = new JsonArray();
			List<IEditorReference> closing = new ArrayList<>();
			for (IEditorReference reference : selected) {
				JsonObject entry = new JsonObject().put("title", reference.getTitle()).put("path", path(reference)); //$NON-NLS-1$ //$NON-NLS-2$
				if (!reference.isDirty()) {
					closing.add(reference);
					closed.add(entry);
					continue;
				}
				if (save) {
					IEditorPart editor = reference.getEditor(true);
					if (editor != null) {
						// through the editor, not through closeEditors(refs, true), which is
						// the path that can raise a save prompt nobody is there to answer
						editor.doSave(new NullProgressMonitor());
					}
					if (editor == null || editor.isDirty()) {
						refused.add(entry.put("reason", "The save did not clear the dirty state.")); //$NON-NLS-1$ //$NON-NLS-2$
						continue;
					}
					saved.add(entry);
					closing.add(reference);
					closed.add(entry);
				} else if (discard) {
					closing.add(reference);
					closed.add(entry.put("discardedUnsavedChanges", Boolean.TRUE)); //$NON-NLS-1$
				} else {
					refused.add(entry.put("reason", //$NON-NLS-1$
							"It has unsaved changes. Pass save to save it first, or discardUnsaved to lose them.")); //$NON-NLS-1$
				}
			}
			if (!closing.isEmpty()) {
				page.closeEditors(closing.toArray(IEditorReference[]::new), false);
			}
			return new JsonObject().put("closed", Integer.valueOf(closed.size())) //$NON-NLS-1$
					.put("refused", Integer.valueOf(refused.size())) //$NON-NLS-1$
					.put("saved", saved) //$NON-NLS-1$
					.put("closedEditors", closed) //$NON-NLS-1$
					.put("refusedEditors", refused); //$NON-NLS-1$
		}
	}

	/** The workspace path the editor shows, or {@code null} for anything not file backed. */
	static String path(IEditorReference reference) {
		try {
			if (reference.getEditorInput() instanceof org.eclipse.ui.IFileEditorInput input) {
				return input.getFile().getFullPath().toString();
			}
		} catch (PartInitException e) {
			// an editor whose input cannot be restored still has a title
		}
		return null;
	}
}
