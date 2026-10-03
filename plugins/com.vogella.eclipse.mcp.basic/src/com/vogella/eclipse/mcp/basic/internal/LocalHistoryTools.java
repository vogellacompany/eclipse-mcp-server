package com.vogella.eclipse.mcp.basic.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reads and restores the versions of a file that Eclipse keeps in its local history.
 */
public final class LocalHistoryTools {

	private static final int DEFAULT_MAX_BYTES = 1_000_000;

	private static final String LIMITS = " Local history covers workspace files only, keeps what the preferences under General > Workspace > Local History allow (by default 7 days and 50 versions per file), and records a version only when a file is written through Eclipse: an editor save, eclipse_write_file, eclipse_edit_file, eclipse_revert_files or a restore. An edit made through your own shell is not recorded, so the version before it is the one Eclipse last wrote."; //$NON-NLS-1$

	private LocalHistoryTools() {
	}

	/** Lists the local history of one file, or returns the content of one version of it. */
	public static final class Get implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_get_local_history"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Lists the older versions of a workspace file that Eclipse keeps in its local history, newest first, or with 'timestamp' returns the content of one of them. Read-only. Works for a file that has since been deleted, as long as its history has not expired. Use it to see what a file looked like before an edit, and eclipse_restore_local_history to bring a version back; eclipse_open_compare with 'historyTimestamp' shows a version side by side with the current file for a person." //$NON-NLS-1$
					+ LIMITS;
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "required": ["path"],
					  "properties": {
					    "path":       {"type":"string","description":"Workspace path of the file, e.g. /app/src/com/example/Main.java"},
					    "timestamp":  {"type":"integer","description":"Modification time of the version to return, in milliseconds, exactly as the listing reports it. Omit to list the versions."},
					    "maxResults": {"type":"integer","minimum":1,"maximum":1000,"default":50,"description":"How many versions to list."},
					    "maxBytes":   {"type":"integer","minimum":1,"maximum":10000000,"default":1000000,"description":"Refuse rather than return a version larger than this."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			IFile file = file(args.getString("path")); //$NON-NLS-1$
			if (file == null) {
				return McpToolResult.error("The argument 'path' is required and has to name a file, /project/folder/file."); //$NON-NLS-1$
			}
			refresh(file, monitor);
			IFileState[] states;
			try {
				states = file.getHistory(monitor);
			} catch (CoreException e) {
				return McpToolResult.error("Could not read the local history of '%s': %s".formatted(file.getFullPath(), //$NON-NLS-1$
						e.getMessage()));
			}
			JsonObject result = new JsonObject().put("path", file.getFullPath().toString()) //$NON-NLS-1$
					.put("exists", Boolean.valueOf(file.exists())); //$NON-NLS-1$
			if (args.has("timestamp")) { //$NON-NLS-1$
				if (!(arguments.get("timestamp") instanceof Number number)) { //$NON-NLS-1$
					return McpToolResult.error("'timestamp' has to be a number of milliseconds."); //$NON-NLS-1$
				}
				long timestamp = number.longValue();
				IFileState state = find(states, timestamp);
				if (state == null) {
					return McpToolResult.error(noSuchVersion(file, timestamp));
				}
				int maxBytes = args.getInt("maxBytes", DEFAULT_MAX_BYTES, 1, 10_000_000); //$NON-NLS-1$
				return content(state, maxBytes, result.put("timestamp", Long.valueOf(timestamp)) //$NON-NLS-1$
						.put("time", Instant.ofEpochMilli(timestamp).toString())); //$NON-NLS-1$
			}
			int maxResults = args.getInt("maxResults", 50, 1, 1000); //$NON-NLS-1$
			JsonArray versions = new JsonArray();
			for (int i = 0; i < Math.min(states.length, maxResults); i++) {
				versions.add(describe(states[i]));
			}
			return McpToolResult.of(result.put("versions", versions) //$NON-NLS-1$
					.put("total", Integer.valueOf(states.length)) //$NON-NLS-1$
					.put("truncated", Boolean.valueOf(states.length > maxResults)) //$NON-NLS-1$
					.toString());
		}

		private static McpToolResult content(IFileState state, int maxBytes, JsonObject result) {
			byte[] bytes;
			try {
				bytes = read(state, maxBytes + 1);
			} catch (CoreException | IOException e) {
				return McpToolResult.error("Could not read the version: %s".formatted(e.getMessage())); //$NON-NLS-1$
			}
			result.put("bytes", Integer.valueOf(Math.min(bytes.length, maxBytes))); //$NON-NLS-1$
			if (bytes.length > maxBytes) {
				return McpToolResult.of(result.put("read", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "The version is larger than maxBytes (%d).".formatted(Integer.valueOf(maxBytes))) //$NON-NLS-1$ //$NON-NLS-2$
						.toString());
			}
			if (FileSupport.isBinary(bytes)) {
				return McpToolResult.of(result.put("read", Boolean.FALSE).put("binary", Boolean.TRUE) //$NON-NLS-1$ //$NON-NLS-2$
						.put("reason", "The version contains NUL bytes, so it is binary and is not returned as text.") //$NON-NLS-1$ //$NON-NLS-2$
						.toString());
			}
			String charset = charset(state);
			return McpToolResult.of(result.put("read", Boolean.TRUE).put("charset", charset) //$NON-NLS-1$ //$NON-NLS-2$
					.put("content", new String(bytes, Charset.forName(charset))) //$NON-NLS-1$
					.toString());
		}
	}

	/** Writes a version from the local history back over the current file. */
	public static final class Restore implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_restore_local_history"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Restores a version of a workspace file from Eclipse's local history. MODIFIES THE WORKSPACE. Without 'timestamp' it restores the newest version, which undoes the last write Eclipse recorded for the file; a file that has since been deleted is recreated. The content being replaced goes into the local history itself, so a restore can be undone the same way, and calling this twice without a timestamp therefore swaps back rather than stepping further into the past: pass the timestamps from eclipse_get_local_history to go further back." //$NON-NLS-1$
					+ LIMITS;
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "required": ["path"],
					  "properties": {
					    "path":      {"type":"string","description":"Workspace path of the file, e.g. /app/src/com/example/Main.java"},
					    "timestamp": {"type":"integer","description":"Modification time of the version to restore, in milliseconds, as eclipse_get_local_history reports it. Omit for the newest version."},
					    "dryRun":    {"type":"boolean","default":false,"description":"Report which version would be restored without writing it."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			IFile file = file(args.getString("path")); //$NON-NLS-1$
			if (file == null) {
				return McpToolResult.error("The argument 'path' is required and has to name a file, /project/folder/file."); //$NON-NLS-1$
			}
			if (!file.getProject().isAccessible()) {
				return McpToolResult
						.error("No open project named '%s' in this workspace.".formatted(file.getProject().getName())); //$NON-NLS-1$
			}
			refresh(file, monitor);
			IFileState[] states;
			try {
				states = file.getHistory(monitor);
			} catch (CoreException e) {
				return McpToolResult.error("Could not read the local history of '%s': %s".formatted(file.getFullPath(), //$NON-NLS-1$
						e.getMessage()));
			}
			IFileState state;
			if (args.has("timestamp")) { //$NON-NLS-1$
				if (!(arguments.get("timestamp") instanceof Number number)) { //$NON-NLS-1$
					return McpToolResult.error("'timestamp' has to be a number of milliseconds."); //$NON-NLS-1$
				}
				long timestamp = number.longValue();
				state = find(states, timestamp);
				if (state == null) {
					return McpToolResult.error(noSuchVersion(file, timestamp));
				}
			} else if (states.length == 0) {
				return McpToolResult.error("'%s' has no local history, so there is nothing to restore.%s" //$NON-NLS-1$
						.formatted(file.getFullPath(), LIMITS));
			} else {
				state = states[0];
			}
			boolean exists = file.exists();
			if (exists && file.isReadOnly()) {
				return McpToolResult.error("'%s' is read only.".formatted(file.getFullPath())); //$NON-NLS-1$
			}
			List<IFolder> missing = FileSupport.missingParents(file);
			JsonObject result = new JsonObject().put("path", file.getFullPath().toString()) //$NON-NLS-1$
					.put("restored", describe(state)) //$NON-NLS-1$
					.put("recreated", Boolean.valueOf(!exists)) //$NON-NLS-1$
					.put("versionsAvailable", Integer.valueOf(states.length)); //$NON-NLS-1$
			if (args.getBoolean("dryRun", false)) { //$NON-NLS-1$
				return McpToolResult.of(result.put("dryRun", Boolean.TRUE).put("written", Boolean.FALSE).toString()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			try {
				for (IFolder folder : missing) {
					folder.create(false, true, monitor);
				}
				if (exists) {
					file.setContents(state, IResource.KEEP_HISTORY, monitor);
				} else {
					try (InputStream in = state.getContents()) {
						file.create(in, IResource.NONE, monitor);
					}
				}
			} catch (CoreException | IOException e) {
				return McpToolResult.error("Could not restore '%s': %s".formatted(file.getFullPath(), e.getMessage())); //$NON-NLS-1$
			}
			return McpToolResult.of(result.put("written", Boolean.TRUE) //$NON-NLS-1$
					.put("previousContentInHistory", Boolean.valueOf(exists)) //$NON-NLS-1$
					.toString());
		}
	}

	private static IFile file(String path) {
		if (path == null) {
			return null;
		}
		IPath workspacePath = IPath.fromPortableString(path);
		if (workspacePath.segmentCount() < 2) {
			return null;
		}
		return ResourcesPlugin.getWorkspace().getRoot().getFile(workspacePath);
	}

	/** An edit through the client's shell is otherwise invisible, and restoring over it would lose it unrecorded. */
	private static void refresh(IFile file, IProgressMonitor monitor) {
		try {
			FileSupport.refresh(file, monitor);
		} catch (CoreException e) {
			// the history is still readable
		}
	}

	private static IFileState find(IFileState[] states, long timestamp) {
		for (IFileState state : states) {
			if (state.getModificationTime() == timestamp) {
				return state;
			}
		}
		return null;
	}

	private static String noSuchVersion(IFile file, long timestamp) {
		return "'%s' has no local history version with timestamp %d. List the versions with eclipse_get_local_history and pass one of their timestamps exactly." //$NON-NLS-1$
				.formatted(file.getFullPath(), Long.valueOf(timestamp));
	}

	private static JsonObject describe(IFileState state) {
		long timestamp = state.getModificationTime();
		JsonObject version = new JsonObject().put("timestamp", Long.valueOf(timestamp)) //$NON-NLS-1$
				.put("time", Instant.ofEpochMilli(timestamp).toString()); //$NON-NLS-1$
		try {
			version.put("bytes", Long.valueOf(size(state))); //$NON-NLS-1$
		} catch (CoreException | IOException e) {
			version.put("bytes", null); //$NON-NLS-1$
		}
		return version;
	}

	private static long size(IFileState state) throws CoreException, IOException {
		try (InputStream in = state.getContents()) {
			return in.transferTo(OutputStream.nullOutputStream());
		}
	}

	private static byte[] read(IFileState state, int limit) throws CoreException, IOException {
		try (InputStream in = state.getContents()) {
			return in.readNBytes(limit);
		}
	}

	private static String charset(IFileState state) {
		try {
			String name = state.getCharset();
			return Charset.isSupported(name) ? name : "UTF-8"; //$NON-NLS-1$
		} catch (CoreException | IllegalArgumentException e) {
			return "UTF-8"; //$NON-NLS-1$
		}
	}
}
