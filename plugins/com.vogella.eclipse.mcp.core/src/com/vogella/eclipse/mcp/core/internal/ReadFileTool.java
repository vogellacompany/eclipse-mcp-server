package com.vogella.eclipse.mcp.core.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;

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
 * Reads a workspace file, through the workspace rather than off the disk.
 */
public final class ReadFileTool implements IMcpTool {

	private static final int DEFAULT_MAX_BYTES = 1_000_000;

	@Override
	public String getName() {
		return "eclipse_read_file"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reads a workspace file by workspace path, the same path form eclipse_open takes. Read-only. This exists because a client is not always on the same machine as the IDE, and once the window is hidden there is no filesystem to fall back on; it is also the only way to look at the plugin.xml or .exsd that eclipse_list_declarations cites as evidence, in the same IDE the verdict came from rather than in your own copy of the tree. The file is read through the workspace, so it uses the encoding Eclipse has for it, which a naive read of the bytes gets wrong silently for properties files and anything not UTF-8. Binary files are reported as binary rather than returned as a mangled string."; //$NON-NLS-1$
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
		if (file != null && args.getBoolean("refresh", true)) { //$NON-NLS-1$
			try {
				FileSupport.refresh(file, monitor);
			} catch (CoreException e) {
				// still readable, and stale content beats no content
			}
		}
		if (file == null || !file.exists()) {
			return McpToolResult.error("No file at the workspace path '%s'.".formatted(path)); //$NON-NLS-1$
		}
		int maxBytes = args.getInt("maxBytes", DEFAULT_MAX_BYTES, 1, 10_000_000); //$NON-NLS-1$
		int offset = args.getInt("offset", 1, 1, Integer.MAX_VALUE); //$NON-NLS-1$
		int limit = args.getInt("limit", -1, -1, 100_000); //$NON-NLS-1$

		byte[] bytes;
		try (InputStream in = file.getContents(true)) {
			bytes = in.readNBytes(maxBytes + 1);
		} catch (CoreException | IOException e) {
			return McpToolResult.error("Could not read '%s': %s".formatted(path, e.getMessage())); //$NON-NLS-1$
		}
		JsonObject result = new JsonObject().put("path", file.getFullPath().toString()) //$NON-NLS-1$
				.put("bytes", Integer.valueOf(Math.min(bytes.length, maxBytes))); //$NON-NLS-1$
		if (bytes.length > maxBytes) {
			return McpToolResult.of(result.put("read", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "The file is larger than maxBytes (%d). Raise it, or read a line range." //$NON-NLS-1$ //$NON-NLS-2$
							.formatted(Integer.valueOf(maxBytes)))
					.toString());
		}
		if (FileSupport.isBinary(bytes)) {
			return McpToolResult.of(result.put("read", Boolean.FALSE) //$NON-NLS-1$
					.put("binary", Boolean.TRUE) //$NON-NLS-1$
					.put("reason", "The file contains NUL bytes, so it is binary and is not returned as text. For a PNG, JPEG, GIF or WebP use eclipse_read_image.") //$NON-NLS-1$ //$NON-NLS-2$
					.toString());
		}

		String charset = FileSupport.charset(file);
		String content = new String(bytes, Charset.forName(charset));
		String[] lines = content.split("\n", -1); //$NON-NLS-1$
		int total = lines.length;
		int from = Math.min(offset - 1, total);
		int to = limit < 0 ? total : Math.min(from + limit, total);
		StringBuilder text = new StringBuilder();
		for (int i = from; i < to; i++) {
			text.append(lines[i]);
			if (i < to - 1) {
				text.append('\n');
			}
		}
		return McpToolResult.of(result.put("read", Boolean.TRUE) //$NON-NLS-1$
				.put("charset", charset) //$NON-NLS-1$
				.put("totalLines", Integer.valueOf(total)) //$NON-NLS-1$
				.put("firstLine", Integer.valueOf(from + 1)) //$NON-NLS-1$
				.put("lastLine", Integer.valueOf(to)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(to < total || from > 0)) //$NON-NLS-1$
				.put("content", text.toString()) //$NON-NLS-1$
				.toString());
	}
}
