package com.vogella.eclipse.mcp.p2.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.ClientSessions;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** Reports an update or install started through the provisioning tools. */
public final class GetProvisioningStatusTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_get_provisioning_status"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports an update, install or uninstall started through eclipse_update, eclipse_install or eclipse_uninstall: running, done, failed or cancelled, what it changed and the previous configuration timestamp to revert to. Separate from eclipse_get_build_status because a build and an install are not the same kind of thing. Pass cancel to stop one that is still running."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "operationId": {"type":"string","description":"Identifier returned by eclipse_update, eclipse_install or eclipse_uninstall. Omit for the most recent."},
				    "cancel":      {"type":"boolean","default":false,"description":"Stop the operation if it is still running. An install or update modifies the installation, so being able to stop one that was started by mistake matters."}
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
				    "operationId":           {"type":"string"},
				    "kind":                  {"type":"string"},
				    "state":                 {"type":"string"},
				    "elapsedMillis":         {"type":"integer"},
				    "changes":               {"type":"array","items":{"type":"object"}},
				    "message":               {"type":["string","null"]},
				    "previousConfiguration": {"type":["string","null"]},
				    "trustedUnsigned":       {"type":"boolean"},
				    "trustPrompts":          {"type":"array"},
				    "trustPromptsTotal":     {"type":"integer"},
				    "trustPromptsTruncated": {"type":"boolean"},
				    "plan":                  {"type":"object"},
				    "total":                 {"type":"integer"},
				    "truncated":             {"type":"boolean"},
				    "trustNote":             {"type":"string"},
				    "blockedBy":             {"type":"string"},
				    "restartRequired":       {"type":"boolean"},
				    "note":                  {"type":"string"},
				    "cancelRequested":       {"type":"boolean"},
				    "cancelNote":            {"type":"string"}
				  },
				  "required": ["state"]
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		String id = ToolArguments.of(arguments).getString("operationId"); //$NON-NLS-1$
		if (id == null && !ClientSessions.canAssumeASingleClient()) {
			return McpToolResult.error(ClientSessions
					.ambiguousDefault("provisioning operation", "operationId", Provisioning.ids())); //$NON-NLS-1$ //$NON-NLS-2$
		}
		Provisioning.Operation operation = id == null ? Provisioning.findLatest() : Provisioning.find(id);
		if (operation == null) {
			if (id != null) {
				return McpToolResult.error("No provisioning operation with the id '%s'.".formatted(id)); //$NON-NLS-1$
			}
			return McpToolResult.of(new JsonObject().put("state", "none") //$NON-NLS-1$ //$NON-NLS-2$
					.put("message", "No update or install has been started.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (ToolArguments.of(arguments).getBoolean("cancel", false)) { //$NON-NLS-1$
			boolean cancelled = Provisioning.cancel(operation);
			return McpToolResult.of(operation.toJson()
					.put("cancelRequested", cancelled) //$NON-NLS-1$
					.put("cancelNote", cancelled //$NON-NLS-1$
							? "Cancellation was requested. Work already downloaded is discarded, but an operation that had begun committing may still complete." //$NON-NLS-1$
							: "Nothing to cancel; the operation had already finished.") //$NON-NLS-1$
					.toString());
		}
		return McpToolResult.of(operation.toJson().toString());
	}
}
