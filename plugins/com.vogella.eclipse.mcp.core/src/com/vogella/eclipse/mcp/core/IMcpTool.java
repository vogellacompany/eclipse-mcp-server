package com.vogella.eclipse.mcp.core;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

/**
 * A single capability that an MCP client can invoke.
 * <p>
 * Implementations are contributed through the {@code com.vogella.eclipse.mcp.core.tools}
 * extension point. They are called on arbitrary worker threads, never on the UI thread,
 * and must not open a dialog. A tool that changes anything has to say so in its own
 * description, because that is the only place the model sees it.
 */
public interface IMcpTool {

	/** Stable, unique tool name, for example {@code eclipse_get_problems}. */
	String getName();

	/** Human readable description handed to the model. */
	String getDescription();

	/** JSON Schema for the tool arguments, as a JSON string. */
	String getInputSchema();

	default String getOutputSchema() {
		return null;
	}

	/**
	 * Executes the tool.
	 *
	 * @param arguments the arguments sent by the client, never {@code null}
	 * @param monitor cancelled when the caller gave up waiting
	 */
	McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException;
}
