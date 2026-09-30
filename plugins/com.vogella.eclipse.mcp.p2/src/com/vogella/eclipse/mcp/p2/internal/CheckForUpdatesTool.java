package com.vogella.eclipse.mcp.p2.internal;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.operations.ProvisioningSession;
import org.eclipse.equinox.p2.operations.Update;
import org.eclipse.equinox.p2.operations.UpdateOperation;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** Reports which installed units have updates, changing nothing. */
public final class CheckForUpdatesTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_check_for_updates"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports which installed units have an update available, from which version to which version and from which repository. Changes nothing. By default it re-reads the repository metadata first, because p2 caches it and a cached miss is reported as 'no updates found', which is indistinguishable from a genuinely current IDE. That costs a network round trip per configured site; pass refresh false for a fast answer from the cache, and read the repository timestamps in the result to judge how stale it is."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "units":   {"type":"array","items":{"type":"string"},"description":"Only check these installed unit ids. Scopes the whole check to the repositories that can supply them, which is one round trip instead of one per configured site. Omit to ask the broad question."},
				    "refresh": {"type":"boolean","default":true,"description":"Re-read the repository metadata before resolving. Off means the answer may be stale; the repository timestamps say how stale."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		IProvisioningAgent agent = Provisioning.agent();
		if (agent == null) {
			return McpToolResult.error(
					"No p2 provisioning agent is available. This IDE was probably not installed through p2, so it cannot update itself."); //$NON-NLS-1$
		}
		boolean refresh = ToolArguments.of(arguments).getBoolean("refresh", true); //$NON-NLS-1$
		List<String> unitIds = Provisioning.stringList(arguments, "units"); //$NON-NLS-1$
		var units = Provisioning.installedUnits(agent, unitIds);
		if (!unitIds.isEmpty() && units.isEmpty()) {
			return McpToolResult.error("None of %s is installed, so there is nothing to check.".formatted(unitIds)); //$NON-NLS-1$
		}
		// scoped only when units were named: narrowing a broad question silently would
		// be the same class of mistake as reporting a stale cache as up to date
		URI[] locations = units.isEmpty() ? null : Provisioning.sourcesFor(agent, units, monitor);
		JsonArray repositories = Provisioning.describeRepositories(agent, refresh, locations, monitor);
		UpdateOperation operation = units.isEmpty() ? new UpdateOperation(new ProvisioningSession(agent))
				: new UpdateOperation(new ProvisioningSession(agent), units);
		operation.setProvisioningContext(Provisioning.scope(agent, locations));
		IStatus status = operation.resolveModal(monitor);
		JsonObject result = new JsonObject().put("resolution", status.isOK() ? "ok" : status.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
		JsonArray updates = new JsonArray();
		Update[] possible = operation.getPossibleUpdates();
		boolean widened = false;
		if (locations != null && (possible == null || possible.length == 0)) {
			// the scope was the repositories holding the installed version, and an
			// update lives somewhere else by definition
			Provisioning.widenToAllRepositories(agent, operation, monitor);
			widened = true;
			possible = operation.getPossibleUpdates();
		}
		if (possible != null) {
			for (Update update : possible) {
				updates.add(new JsonObject().put("unit", update.toUpdate.getId()) //$NON-NLS-1$
						.put("fromVersion", update.toUpdate.getVersion().toString()) //$NON-NLS-1$
						.put("toVersion", update.replacement.getVersion().toString()) //$NON-NLS-1$
						.put("name", update.replacement.getProperty("org.eclipse.equinox.p2.name", null))); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		result.put("metadataRefreshed", refresh) //$NON-NLS-1$
				.put("scopedToUnits", unitIds.isEmpty() ? null : String.join(", ", unitIds)) //$NON-NLS-1$
				.put("widenedToAllRepositories", Boolean.valueOf(widened)); //$NON-NLS-1$
		if (widened) {
			result.put("scopeNote", //$NON-NLS-1$
					"Scoping to the repositories that can supply the named units found nothing, so every enabled repository was searched instead. That scope is where the INSTALLED version lives, and an update is published somewhere else: with a composite whose child location changes per release, the child the current version came from is the one that will never hold a newer one."); //$NON-NLS-1$
		}
		if (updates.size() == 0 && !refresh) {
			result.put("caveat", //$NON-NLS-1$
					"Nothing was found, but the repository metadata was read from p2's cache rather than from the network, so a newly published build would not be visible. Run again with refresh true before concluding that this IDE is current."); //$NON-NLS-1$
		}
		return McpToolResult.of(result.put("total", updates.size()) //$NON-NLS-1$
				.put("updates", updates) //$NON-NLS-1$
				.put("configuredRepositories", repositories) //$NON-NLS-1$
				.toString());
	}
}
