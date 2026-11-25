package com.vogella.eclipse.mcp.jdt.internal;

import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Formats a source file with the project's formatter settings. Modifies the file.
 */
public final class FormatTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_format"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Formats a Java source file with the formatter settings of its own project, and saves it. This modifies the file. Use it after writing Java so that the result matches the project's conventions rather than the model's. A file that does not parse produces no edits and is reported as unchanged, so check eclipse_get_problems when nothing happens."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["path"],
				  "properties": {
				    "path": {"type":"string","description":"Workspace path of the Java file, e.g. /app/src/com/example/Main.java"}
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
				    "path":    {"type":"string"},
				    "changed": {"type":"boolean"}
				  },
				  "required": ["path","changed"]
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path"); //$NON-NLS-1$
		if (path == null) {
			return McpToolResult.error("The argument 'path' is required."); //$NON-NLS-1$
		}

		ICompilationUnit unit;
		String source;
		try {
			unit = JavaModelSupport.compilationUnit(path);
			WorkspaceSync.refresh(unit.getResource(), monitor);
			source = unit.getSource();
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		} catch (CoreException e) {
			throw new McpToolException("Could not read " + path, e); //$NON-NLS-1$
		}
		if (source == null) {
			return McpToolResult.error("Could not read the source of '%s'.".formatted(path)); //$NON-NLS-1$
		}

		CodeFormatter formatter = ToolFactory.createCodeFormatter(unit.getJavaProject().getOptions(true),
				ToolFactory.M_FORMAT_EXISTING);
		TextEdit edit = formatter.format(CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS, source, 0,
				source.length(), 0, source.contains("\r\n") ? "\r\n" : "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (edit == null) {
			return McpToolResult.error(
					"The formatter could not process '%s'. The file probably does not parse; check eclipse_get_problems." //$NON-NLS-1$
							.formatted(path));
		}
		if (!edit.hasChildren()) {
			return McpToolResult.of(new JsonObject().put("path", path).put("changed", false).toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		try {
			unit.applyTextEdit(edit, monitor);
			unit.save(monitor, true);
		} catch (JavaModelException | MalformedTreeException e) {
			throw new McpToolException("Could not format " + path, e); //$NON-NLS-1$
		}
		return McpToolResult.of(new JsonObject().put("path", path).put("changed", true).toString()); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
