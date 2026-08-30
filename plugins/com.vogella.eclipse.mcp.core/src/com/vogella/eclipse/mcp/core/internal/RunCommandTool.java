package com.vogella.eclipse.mcp.core.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Runs a command in a directory the person at the IDE allowed.
 */
public final class RunCommandTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_run_command"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Runs a command and captures its output, so that a build a client cannot otherwise reach, such as a Maven or Tycho run that produces a p2 repository, becomes part of one workflow. RUNS ARBITRARY CODE with the rights of the user running the IDE, which is more than anything else this server does: it is the one tool that is not the IDE acting on itself. The working directory is required and absolute, and the command runs there and nowhere else. Runs as a job and returns a commandId to poll through eclipse_get_command_output, because a build takes minutes and the call timeout is 30 seconds by default. Output is merged from stdout and stderr, so a failure appears next to the step that caused it, and the last 2000 lines are kept, because a build log is long and the useful part is at the end."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "command":   {"type":"string","description":"Command line, run through the shell, e.g. 'mvn clean verify -DskipTests'. Use args instead when a value contains characters the shell would interpret."},
				    "args":      {"type":"array","items":{"type":"string"},"description":"Command and arguments given one by one, executed without a shell. Takes precedence over command, and is the safer form for paths with spaces."},
				    "directory": {"type":"string","description":"Absolute working directory. Required: the command runs here and nowhere else, so name the project or build tree it belongs to rather than a parent of it."},
				    "environment": {"type":"object","additionalProperties":{"type":"string"},"description":"Extra environment variables, merged over the IDE's own."},
				    "wait":      {"type":"boolean","default":false,"description":"Wait for the command instead of returning a handle. Only for something that finishes in seconds; the server aborts any call that outlasts the configured call timeout."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"How long to wait when wait is true. Bounded in practice by the server's own call timeout."}
				  },
				  "required": ["directory"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String directoryName = args.getString("directory"); //$NON-NLS-1$
		if (directoryName == null || directoryName.isBlank()) {
			return McpToolResult.error("Give the absolute 'directory' to run in."); //$NON-NLS-1$
		}
		List<String> command = command(arguments, args);
		if (command.isEmpty()) {
			return McpToolResult.error("Give 'command' as a line, or 'args' as a list."); //$NON-NLS-1$
		}
		Path directory = Path.of(directoryName.strip());
		if (!directory.isAbsolute()) {
			return McpToolResult.error("'%s' is not absolute. Give the full path.".formatted(directoryName)); //$NON-NLS-1$
		}
		if (!Files.isDirectory(directory)) {
			return McpToolResult.error("There is no directory at '%s'.".formatted(directory)); //$NON-NLS-1$
		}
		CommandRegistry.Execution execution = CommandRegistry.getInstance().start(command, directory,
				environment(arguments));
		int requested = args.getInt("timeoutSeconds", 25, 1, 3600); //$NON-NLS-1$
		if (args.getBoolean("wait", false)) { //$NON-NLS-1$
			execution.await(CallBudget.boundedWaitSeconds(requested) * 1000L);
		}
		JsonObject json = CommandOutput.describe(execution, 100);
		if (execution.isRunning()) {
			String clamped = CallBudget.clampNote(requested,
					"eclipse_get_command_output with this commandId"); //$NON-NLS-1$
			json.put("note", clamped == null //$NON-NLS-1$
					? "Still running. Poll eclipse_get_command_output with this commandId, and pass cancel to stop it." //$NON-NLS-1$
					: clamped);
		}
		return McpToolResult.of(json.toString());
	}

	private static List<String> command(Map<String, Object> arguments, ToolArguments args) {
		if (arguments != null && arguments.get("args") instanceof List<?> list && !list.isEmpty()) { //$NON-NLS-1$
			List<String> values = new ArrayList<>();
			list.forEach(value -> values.add(String.valueOf(value)));
			return values;
		}
		String line = args.getString("command"); //$NON-NLS-1$
		if (line == null || line.isBlank()) {
			return List.of();
		}
		// through a shell, because the point of the string form is that a caller can
		// write the command line it would type
		return FileLocations.isWindows() ? List.of("cmd.exe", "/c", line) //$NON-NLS-1$ //$NON-NLS-2$
				: List.of("/bin/sh", "-c", line); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static Map<String, String> environment(Map<String, Object> arguments) {
		if (arguments == null || !(arguments.get("environment") instanceof Map<?, ?> map)) { //$NON-NLS-1$
			return Map.of();
		}
		Map<String, String> values = new LinkedHashMap<>();
		map.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
		return values;
	}

	/** Shared shape of a command handle, so the two tools cannot describe one differently. */
	static final class CommandOutput {

		private CommandOutput() {
		}

		static JsonObject describe(CommandRegistry.Execution execution, int tailLines) {
			JsonArray command = new JsonArray();
			execution.command().forEach(command::add);
			List<String> tail = execution.tail(tailLines);
			return new JsonObject().put("commandId", execution.id()) //$NON-NLS-1$
					.put("command", command) //$NON-NLS-1$
					.put("directory", execution.directory()) //$NON-NLS-1$
					.put("state", execution.state()) //$NON-NLS-1$
					.put("exitCode", execution.isRunning() ? null : Integer.valueOf(execution.exitCode())) //$NON-NLS-1$
					.put("elapsedMillis", Long.valueOf(execution.elapsedMillis())) //$NON-NLS-1$
					.put("droppedLines", Integer.valueOf(execution.droppedLines())) //$NON-NLS-1$
					.put("output", String.join("\n", tail)); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}
}
