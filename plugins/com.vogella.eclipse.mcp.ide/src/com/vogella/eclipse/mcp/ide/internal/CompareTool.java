package com.vogella.eclipse.mcp.ide.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.ISharedDocumentAdapter;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.ResourceNode;
import org.eclipse.compare.SharedDocumentAdapter;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.ui.internal.UiThread;

/**
 * Opens Eclipse's compare editor on a workspace file, or the file's own editor with
 * the difference drawn in when the unified diff preference is on.
 */
public final class CompareTool implements IMcpTool {

	private static final long UI_TIMEOUT_SECONDS = 15;

	private static final int MAX_BYTES = 10 * 1024 * 1024;

	private static final String COMPARE_QUALIFIER = "org.eclipse.compare"; //$NON-NLS-1$

	private static final String UNIFIED_DIFF_KEY = "org.eclipse.compare.UnifiedDiff"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_open_compare"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Opens the Eclipse compare editor on a workspace file against another file, against content you supply, against a Git revision, or against a version from its local history, so a person can review a change side by side with syntax colouring and the structural Java compare instead of reading a patch in chat. Comparing against 'content' is the way to show a proposed edit before anything is written. Both sides are READ ONLY: this opens a view of a difference and never modifies a file. CHANGES WHAT THE IDE SHOWS. With the preference org.eclipse.compare.UnifiedDiff set, the file opens in its own editor instead and the difference is drawn into it; 'unifiedDiff' in the answer says which. Comparing against a revision needs the org.eclipse.jgit bundle, which every Eclipse with EGit has and a bare Platform SDK does not."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["left"],
				  "properties": {
				    "left":       {"type":"string","description":"Workspace path of the file to compare, e.g. /app/src/com/example/Main.java"},
				    "right":      {"type":"string","description":"Workspace path of the file to compare it against."},
				    "content":    {"type":"string","description":"Text to compare it against, for reviewing a proposed edit."},
				    "revision":   {"type":"string","description":"Git revision to compare it against, e.g. HEAD, HEAD~1, a branch, a tag or a commit id."},
				    "historyTimestamp": {"type":"integer","description":"Local history version to compare it against, by the timestamp eclipse_get_local_history reports."},
				    "leftLabel":  {"type":"string","description":"Label over the left side. Defaults to the workspace path."},
				    "rightLabel": {"type":"string","description":"Label over the right side. Defaults to what the right side is."},
				    "activate":   {"type":"boolean","default":true,"description":"Bring the compare editor to the front."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String leftPath = args.getString("left"); //$NON-NLS-1$
		if (leftPath == null) {
			return McpToolResult.error("The argument 'left' is required."); //$NON-NLS-1$
		}
		String rightPath = args.getString("right"); //$NON-NLS-1$
		String content = args.getText("content"); //$NON-NLS-1$
		String revision = args.getString("revision"); //$NON-NLS-1$
		Object historyTimestamp = arguments.get("historyTimestamp"); //$NON-NLS-1$
		int given = (rightPath == null ? 0 : 1) + (content == null ? 0 : 1) + (revision == null ? 0 : 1)
				+ (historyTimestamp == null ? 0 : 1);
		if (given != 1) {
			return McpToolResult.error(
					"Give exactly one of 'right', 'content', 'revision' or 'historyTimestamp' to compare '%s' against." //$NON-NLS-1$
							.formatted(leftPath));
		}

		IFile left = file(leftPath);
		if (left == null) {
			return McpToolResult.error("No file at the workspace path '%s'.".formatted(leftPath)); //$NON-NLS-1$
		}
		refresh(left, monitor);
		byte[] leftBytes;
		try {
			leftBytes = read(left);
		} catch (CoreException | IOException e) {
			return McpToolResult.error("Could not read '%s': %s".formatted(leftPath, e.getMessage())); //$NON-NLS-1$
		}

		JsonObject report = new JsonObject().put("left", left.getFullPath().toString()); //$NON-NLS-1$
		ITypedElement rightElement;
		byte[] rightBytes;
		String rightDescription;
		if (rightPath != null) {
			IFile right = file(rightPath);
			if (right == null) {
				return McpToolResult.error("No file at the workspace path '%s'.".formatted(rightPath)); //$NON-NLS-1$
			}
			refresh(right, monitor);
			try {
				rightBytes = read(right);
			} catch (CoreException | IOException e) {
				return McpToolResult.error("Could not read '%s': %s".formatted(rightPath, e.getMessage())); //$NON-NLS-1$
			}
			rightElement = new FileNode(right);
			rightDescription = right.getFullPath().toString();
		} else if (content != null) {
			rightBytes = content.getBytes(StandardCharsets.UTF_8);
			rightElement = new TextElement(left.getName(), left.getFileExtension(), rightBytes);
			rightDescription = "supplied content"; //$NON-NLS-1$
		} else if (historyTimestamp instanceof Number timestamp) {
			IFileState state = historyState(left, timestamp.longValue(), monitor);
			if (state == null) {
				return McpToolResult.error(
						"'%s' has no local history version with timestamp %d. List the versions with eclipse_get_local_history." //$NON-NLS-1$
								.formatted(leftPath, Long.valueOf(timestamp.longValue())));
			}
			try (InputStream stream = state.getContents()) {
				rightBytes = stream.readAllBytes();
			} catch (CoreException | IOException e) {
				return McpToolResult.error("Could not read the local history of '%s': %s".formatted(leftPath, //$NON-NLS-1$
						e.getMessage()));
			}
			String when = Instant.ofEpochMilli(state.getModificationTime()).toString();
			rightElement = new TextElement(left.getName() + " " + when, left.getFileExtension(), rightBytes); //$NON-NLS-1$
			rightDescription = "local history %s".formatted(when); //$NON-NLS-1$
		} else if (historyTimestamp != null) {
			return McpToolResult.error("'historyTimestamp' has to be a number."); //$NON-NLS-1$
		} else {
			GitContent.Blob blob;
			try {
				blob = GitContent.read(left, revision, MAX_BYTES);
			} catch (LinkageError e) {
				return McpToolResult.error(
						"Comparing against a Git revision needs the org.eclipse.jgit bundle, which is not installed in this IDE. Install EGit, or pass 'content' with the text to compare against."); //$NON-NLS-1$
			} catch (IOException | RuntimeException e) {
				return McpToolResult.error("Could not read '%s' at '%s': %s".formatted(leftPath, revision, //$NON-NLS-1$
						e.getMessage() == null ? e.toString() : e.getMessage()));
			}
			rightBytes = blob.content();
			rightElement = new TextElement(left.getName() + " " + revision, left.getFileExtension(), rightBytes); //$NON-NLS-1$
			rightDescription = "%s:%s".formatted(revision, blob.path()); //$NON-NLS-1$
			report.put("repository", blob.repository()).put("commit", blob.commit()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		report.put("right", rightDescription); //$NON-NLS-1$

		String leftLabel = args.getString("leftLabel", left.getFullPath().toString()); //$NON-NLS-1$
		String rightLabel = args.getString("rightLabel", rightDescription); //$NON-NLS-1$
		boolean activate = args.getBoolean("activate", true); //$NON-NLS-1$
		boolean identical = Arrays.equals(leftBytes, rightBytes);
		report.put("identical", Boolean.valueOf(identical)) //$NON-NLS-1$
				.put("leftBytes", Integer.valueOf(leftBytes.length)) //$NON-NLS-1$
				.put("rightBytes", Integer.valueOf(rightBytes.length)); //$NON-NLS-1$
		if (identical) {
			// the editor opens anyway and shows nothing; saying so is cheaper than
			// having the caller wonder why the person reports an empty compare
			report.put("note", "The two sides are byte for byte identical, so the compare editor shows no differences."); //$NON-NLS-1$ //$NON-NLS-2$
		}

		ITypedElement rightSide = rightElement;
		return UiThread.call(UI_TIMEOUT_SECONDS,
				() -> open(new FileNode(left), rightSide, leftLabel, rightLabel, activate, report));
	}

	private static JsonObject open(ITypedElement left, ITypedElement right, String leftLabel, String rightLabel,
			boolean activate, JsonObject report) {
		CompareConfiguration configuration = new CompareConfiguration();
		configuration.setLeftLabel(leftLabel);
		configuration.setRightLabel(rightLabel);
		configuration.setLeftEditable(false);
		configuration.setRightEditable(false);
		Input input = new Input(configuration, left, right);
		input.setTitle("Compare %s and %s".formatted(left.getName(), rightLabel)); //$NON-NLS-1$
		// the compare framework decides between the two presentations from this
		// preference, and the answer says which one the caller is going to find
		boolean unifiedDiff = Platform.getPreferencesService().getBoolean(COMPARE_QUALIFIER, UNIFIED_DIFF_KEY, false,
				null);
		CompareUI.openCompareEditor(input, activate);
		return report.put("opened", Boolean.TRUE).put("editor", input.getTitle()) //$NON-NLS-1$ //$NON-NLS-2$
				.put("unifiedDiff", Boolean.valueOf(unifiedDiff)); //$NON-NLS-1$
	}

	private static IFileState historyState(IFile file, long timestamp, IProgressMonitor monitor) {
		try {
			for (IFileState state : file.getHistory(monitor)) {
				if (state.getModificationTime() == timestamp) {
					return state;
				}
			}
		} catch (CoreException e) {
			// reported as a missing version
		}
		return null;
	}

	private static IFile file(String path) {
		IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(path));
		return file.exists() ? file : null;
	}

	/** A client edits through its own shell, so the resource tree may not have the edit yet. */
	private static void refresh(IFile file, IProgressMonitor monitor) {
		try {
			WorkspaceSync.refresh(file, monitor);
		} catch (CoreException e) {
			// a file that cannot be refreshed is still readable, and comparing stale
			// content is better than refusing to show anything
		}
	}

	private static byte[] read(IFile file) throws CoreException, IOException {
		try (InputStream stream = file.getContents(true)) {
			byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
			if (bytes.length > MAX_BYTES) {
				throw new IOException("the file is larger than the " + MAX_BYTES + " bytes this tool reads"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return bytes;
		}
	}

	/**
	 * The compare framework's own input. Its result is always a {@link DiffNode},
	 * including when the sides are equal: a null result makes CompareUI raise a
	 * modal "no differences" dialog, which on this path nobody is there to close.
	 */
	private static final class Input extends CompareEditorInput {

		private final ITypedElement left;
		private final ITypedElement right;

		Input(CompareConfiguration configuration, ITypedElement left, ITypedElement right) {
			super(configuration);
			this.left = left;
			this.right = right;
		}

		@Override
		protected Object prepareInput(IProgressMonitor monitor) {
			return new DiffNode(Differencer.CHANGE, null, left, right);
		}
	}

	/**
	 * A workspace file that shares its editor's document. Without the adapter the
	 * compare framework has no document key for the file, and the unified diff,
	 * which is drawn into the file's own editor, silently falls back to the compare
	 * editor.
	 */
	public static final class FileNode extends ResourceNode implements IAdaptable {

		public FileNode(IFile file) {
			super(file);
		}

		@Override
		public <T> T getAdapter(Class<T> adapter) {
			if (adapter == ISharedDocumentAdapter.class) {
				return adapter.cast(new SharedDocumentAdapter() {
					@Override
					public void flushDocument(IDocumentProvider provider, IEditorInput documentKey, IDocument document,
							boolean overwrite) {
						// both sides are read only, so there is never anything to write back
					}
				});
			}
			return Platform.getAdapterManager().getAdapter(this, adapter);
		}
	}

	/** Content that is not a file: supplied text, or a blob out of a Git revision. */
	private static final class TextElement implements ITypedElement, IStreamContentAccessor {

		private final String name;
		private final String type;
		private final byte[] content;

		TextElement(String name, String type, byte[] content) {
			this.name = name;
			this.type = type == null ? ITypedElement.TEXT_TYPE : type;
			this.content = content;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public String getType() {
			// the extension, so that compare picks the Java or XML viewer rather than plain text
			return type;
		}

		@Override
		public Image getImage() {
			return null;
		}

		@Override
		public InputStream getContents() {
			return new ByteArrayInputStream(content);
		}
	}
}
