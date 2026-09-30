package com.vogella.eclipse.mcp.core.internal;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
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
 * Writes a workspace file, through the workspace rather than onto the disk.
 */
public final class WriteFileTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_write_file"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Writes a text file at a workspace path. MODIFIES THE WORKSPACE. This is the counterpart of eclipse_read_file, and exists for the same reason: a client is not always on the same machine as the IDE, so writing through its own shell is not always possible. Writing through the workspace means the file is encoded with the charset Eclipse has for it, the resource tree sees the change at once rather than at the next refresh, and the previous content goes into the local history, where Compare With > Local History recovers it. Refuses to overwrite an existing file unless overwrite is true. Use eclipse_format afterwards for Java, so the result follows the project's conventions."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["path", "content"],
				  "properties": {
				    "path":          {"type":"string","description":"Workspace path of the file, e.g. /app/src/com/example/Main.java. The first segment is a project that has to exist."},
				    "content":       {"type":"string","description":"The complete text to write, or the text to add when append is true."},
				    "overwrite":     {"type":"boolean","default":false,"description":"Replace the content of a file that already exists. Without it an existing file is refused rather than lost."},
				    "append":        {"type":"boolean","default":false,"description":"Add to the end of the file instead of replacing it. Creates the file when it does not exist."},
				    "createParents": {"type":"boolean","default":true,"description":"Create the folders leading to the file. The project itself is never created."},
				    "charset":       {"type":"string","description":"Encoding to write with, e.g. UTF-8. Defaults to the charset Eclipse has for the file, which is what its own editors use. On a new file an explicit charset is recorded on the resource, so later reads decode it the same way."},
				    "dryRun":        {"type":"boolean","default":false,"description":"Report what would be written, and whether it would create or overwrite, without writing."}
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
		Object content = arguments == null ? null : arguments.get("content"); //$NON-NLS-1$
		if (content == null) {
			return McpToolResult.error("The argument 'content' is required. Pass an empty string for an empty file."); //$NON-NLS-1$
		}
		String text = String.valueOf(content);
		IPath workspacePath = IPath.fromPortableString(path);
		if (workspacePath.segmentCount() < 2) {
			return McpToolResult.error(
					"'%s' names no file. A workspace path is /project/folder/file, and the project has to exist." //$NON-NLS-1$
							.formatted(path));
		}
		IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(workspacePath);
		IProject project = file.getProject();
		if (!project.isAccessible()) {
			return McpToolResult
					.error("No open project named '%s' in this workspace.".formatted(project.getName())); //$NON-NLS-1$
		}
		boolean overwrite = args.getBoolean("overwrite", false); //$NON-NLS-1$
		boolean append = args.getBoolean("append", false); //$NON-NLS-1$
		boolean createParents = args.getBoolean("createParents", true); //$NON-NLS-1$
		boolean dryRun = args.getBoolean("dryRun", false); //$NON-NLS-1$

		try {
			// the state on disk decides between create and overwrite, so read it first
			FileSupport.refresh(file, monitor);
		} catch (CoreException e) {
			// a folder that cannot be refreshed is still writable
		}
		boolean exists = file.exists();
		if (exists && !overwrite && !append) {
			return McpToolResult.error(
					"'%s' already exists. Pass overwrite true to replace it, or append true to add to it." //$NON-NLS-1$
							.formatted(file.getFullPath()));
		}
		if (exists && file.isReadOnly()) {
			return McpToolResult.error("'%s' is read only.".formatted(file.getFullPath())); //$NON-NLS-1$
		}

		String requested = args.getString("charset"); //$NON-NLS-1$
		Charset charset;
		try {
			charset = requested != null ? Charset.forName(requested) : Charset.forName(charset(file, exists));
		} catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
			return McpToolResult.error("Unknown charset '%s'.".formatted(requested)); //$NON-NLS-1$
		}
		byte[] bytes = text.getBytes(charset);

		List<IFolder> missing = FileSupport.missingParents(file);
		if (!missing.isEmpty() && !createParents) {
			return McpToolResult.error("The folder '%s' does not exist. Pass createParents true to create it." //$NON-NLS-1$
					.formatted(missing.get(missing.size() - 1).getFullPath()));
		}

		JsonObject result = new JsonObject().put("path", file.getFullPath().toString()) //$NON-NLS-1$
				.put("created", !exists) //$NON-NLS-1$
				.put("appended", append && exists) //$NON-NLS-1$
				.put("charset", charset.name()) //$NON-NLS-1$
				.put("bytes", bytes.length); //$NON-NLS-1$
		if (dryRun) {
			putCreatedFolders(result, missing);
			return McpToolResult.of(result.put("dryRun", true).put("written", false).toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		try {
			for (IFolder folder : missing) {
				folder.create(false, true, monitor);
			}
			if (!exists) {
				file.create(bytes, IResource.NONE, monitor);
				if (requested != null) {
					// otherwise a file written as anything but the container's default
					// decodes wrongly the next time anything reads it
					file.setCharset(charset.name(), monitor);
				}
			} else if (append) {
				file.appendContents(new ByteArrayInputStream(bytes), IResource.KEEP_HISTORY, monitor);
			} else {
				file.setContents(bytes, IResource.KEEP_HISTORY, monitor);
			}
		} catch (CoreException e) {
			return McpToolResult.error("Could not write '%s': %s".formatted(file.getFullPath(), e.getMessage())); //$NON-NLS-1$
		}
		putCreatedFolders(result, missing);
		return McpToolResult.of(result.put("written", true).toString()); //$NON-NLS-1$
	}

	private static void putCreatedFolders(JsonObject result, List<IFolder> folders) {
		JsonArray created = new JsonArray();
		for (IFolder folder : folders) {
			created.add(folder.getFullPath().toString());
		}
		result.put("createdFolders", created); //$NON-NLS-1$
	}

	/** The charset Eclipse has for the file, or the one its container gives new files. */
	private static String charset(IFile file, boolean exists) {
		try {
			String name = exists ? file.getCharset() : file.getParent().getDefaultCharset();
			return Charset.isSupported(name) ? name : "UTF-8"; //$NON-NLS-1$
		} catch (CoreException | IllegalArgumentException e) {
			return "UTF-8"; //$NON-NLS-1$
		}
	}
}
