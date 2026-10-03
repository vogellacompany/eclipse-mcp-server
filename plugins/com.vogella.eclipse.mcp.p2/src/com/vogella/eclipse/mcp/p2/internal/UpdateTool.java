package com.vogella.eclipse.mcp.p2.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.operations.ProvisioningJob;
import org.eclipse.equinox.p2.operations.ProvisioningSession;
import org.eclipse.equinox.p2.operations.Update;
import org.eclipse.equinox.p2.operations.UpdateOperation;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** Applies available updates to the running IDE. */
public final class UpdateTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_update"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Applies the available updates to this IDE. MODIFIES THE INSTALLATION, and runs as a dry run unless dryRun is set to false. Only units that are already installed are updated, from the repositories already configured; it installs nothing new. Runs as a job and returns an operationId to poll through eclipse_get_provisioning_status, because resolution can take minutes. THIS IS SELF UPDATING MACHINERY: if a bad build lands, the tools that would fix it are the tools that just broke. The result names the previous configuration timestamp so the installation can be reverted from Help > About > Installation Details even when this path is dead, and eclipse_restart works independently of this tool."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "acknowledgeSelfUpdate": {"type":"boolean","default":false,"description":"Required to update the MCP server itself, which can leave the IDE with no server until somebody restarts Eclipse by hand."},
				    "units":          {"type":"array","items":{"type":"string"},"description":"Installed unit ids to update. Scopes the resolution to the repositories that can supply them, so a targeted update makes one network round trip instead of one per configured site. Omit to update everything that has an update."},
				    "dryRun":         {"type":"boolean","default":true,"description":"Report what would be updated without changing anything. On by default: an update modifies the installation and there is no undo short of reverting the configuration, so the change list should be seen before it is committed to."},
				    "refresh":        {"type":"boolean","default":true,"description":"Re-read the repository metadata first. Without it the update resolves against p2's cache and may find nothing to apply."},
				    "trustUnsigned":  {"type":"boolean","default":true,"description":"Accept unsigned content, or content signed by a certificate this IDE does not trust. On by default, because an install performed by this server is unattended and there is nobody to answer the dialog p2 would otherwise raise. This is NOT bounded by which sites are configured, because eclipse_add_repository can configure a new one: whoever can call these tools decides what gets installed. Nothing is added to the IDE's permanent trust store, and whatever was accepted is reported. Set false to refuse unsigned content instead."},
				    "wait":           {"type":"boolean","default":false,"description":"Wait for the job. Updates are slow, so this is off by default."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"How long to wait when wait is true. Bounded in practice by the server's own call timeout."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		IProvisioningAgent agent = Provisioning.agent();
		if (agent == null) {
			return McpToolResult.error("No p2 provisioning agent is available, so this IDE cannot update itself."); //$NON-NLS-1$
		}
		// refresh first for the same reason as the check tool: without it the update
		// resolves against cached metadata and finds nothing to apply
		List<String> unitIds = Provisioning.stringList(arguments, "units"); //$NON-NLS-1$
		var units = Provisioning.installedUnits(agent, unitIds);
		if (!unitIds.isEmpty() && units.isEmpty()) {
			return McpToolResult.error("None of %s is installed, so there is nothing to update.".formatted(unitIds)); //$NON-NLS-1$
		}
		URI[] locations = units.isEmpty() ? null : Provisioning.sourcesFor(agent, units, monitor);
		Provisioning.describeRepositories(agent, args.getBoolean("refresh", true), locations, monitor); //$NON-NLS-1$
		UpdateOperation operation = units.isEmpty() ? new UpdateOperation(new ProvisioningSession(agent))
				: new UpdateOperation(new ProvisioningSession(agent), units);
		operation.setProvisioningContext(Provisioning.scope(agent, locations));
		IStatus resolution = operation.resolveModal(monitor);
		Update[] possible = operation.getPossibleUpdates();
		if (locations != null && (possible == null || possible.length == 0)) {
			// the scope was the repositories holding the installed version, and an
			// update lives somewhere else by definition
			Provisioning.widenToAllRepositories(agent, operation, monitor);
			possible = operation.getPossibleUpdates();
		}
		if (possible == null || possible.length == 0) {
			return McpToolResult.of(Provisioning
					.record("update", "done", //$NON-NLS-1$ //$NON-NLS-2$
							"Nothing to update; everything installed is already current. Every enabled repository was searched, not only the one the installed version came from.", //$NON-NLS-1$
							new JsonArray())
					.toJson().toString());
		}
		if (!unitIds.isEmpty()) {
			// Constructing the operation with the units restricts what is examined but
			// not what is selected: without setSelectedUpdates p2 applies every update
			// it found, which once updated a whole SDK when one feature was named.
			List<Update> wanted = new ArrayList<>();
			for (Update update : possible) {
				if (unitIds.contains(update.toUpdate.getId())) {
					wanted.add(update);
				}
			}
			if (wanted.isEmpty()) {
				return McpToolResult.of(Provisioning.record("update", "done", //$NON-NLS-1$ //$NON-NLS-2$
						"No update is available for %s.".formatted(unitIds), new JsonArray()).toJson().toString()); //$NON-NLS-1$
			}
			operation.setSelectedUpdates(wanted.toArray(Update[]::new));
			resolution = operation.resolveModal(monitor);
			possible = operation.getSelectedUpdates();
		}
		if (!resolution.isOK() && resolution.getSeverity() == IStatus.ERROR) {
			return McpToolResult.error(ResolutionStatuses.failure("The update could not be resolved", resolution)); //$NON-NLS-1$
		}
		JsonArray changes = new JsonArray();
		List<String> unexpected = new ArrayList<>();
		for (Update update : possible) {
			changes.add(new JsonObject().put("unit", update.toUpdate.getId()) //$NON-NLS-1$
					.put("fromVersion", update.toUpdate.getVersion().toString()) //$NON-NLS-1$
					.put("toVersion", update.replacement.getVersion().toString())); //$NON-NLS-1$
			if (!unitIds.isEmpty() && !unitIds.contains(update.toUpdate.getId())) {
				unexpected.add(update.toUpdate.getId());
			}
		}
		// last line of defence: never install something the caller did not name
		if (!unexpected.isEmpty()) {
			return McpToolResult.error(
					"Refused: the resolution wants to update %s, which you did not ask for. Only %s was named. Nothing was changed." //$NON-NLS-1$
							.formatted(unexpected, unitIds));
		}
		List<String> self = selfUpdates(possible);
		if (!self.isEmpty() && !args.getBoolean("acknowledgeSelfUpdate", false)) { //$NON-NLS-1$
			return McpToolResult.error(
					"Refused: %s is the MCP server itself, so applying this stops the bundle answering you while it does it. Nothing was changed. That is not merely a dropped connection: the provisioning job runs inside the bundles being replaced, so if anything goes wrong there is nothing left running to finish the update or to report why, and the IDE is then left with no server and no way to reach it except a restart by hand at the machine. Pass acknowledgeSelfUpdate true to accept that, and only when somebody can restart Eclipse if it does not come back." //$NON-NLS-1$
							.formatted(self));
		}
		if (args.getBoolean("dryRun", true)) { //$NON-NLS-1$
			return McpToolResult.of(Provisioning
					.record("update", "dryRun", //$NON-NLS-1$ //$NON-NLS-2$
							"Nothing was changed. This is what would be updated; pass dryRun false to apply it.", //$NON-NLS-1$
							changes)
					.toJson().toString());
		}
		// installed after the dry run returns, so a dry run never swaps the IDE's dialogs
		boolean trustUnsigned = args.getBoolean("trustUnsigned", true); //$NON-NLS-1$
		HeadlessTrust trust = new HeadlessTrust(trustUnsigned);
		HeadlessTrust.install(agent, trust);
		ProvisioningJob job = operation.getProvisioningJob(null);
		if (job == null) {
			HeadlessTrust.restore(agent, trust);
			return McpToolResult.error("p2 produced no provisioning job for the resolved update."); //$NON-NLS-1$
		}
		Provisioning.Operation handle = Provisioning.start("update", tracked -> { //$NON-NLS-1$
			Provisioning.setChanges(tracked, changes);
			return job;
		});
		Provisioning.onFinished(handle, () -> {
			Provisioning.setTrust(handle, trust, trustUnsigned);
			HeadlessTrust.restore(agent, trust);
		});
		String waitNote = null;
		if (args.getBoolean("wait", false)) { //$NON-NLS-1$
			int requested = args.getInt("timeoutSeconds", 25, 1, 3600); //$NON-NLS-1$
			try {
				handle.await(CallBudget.boundedWaitSeconds(requested));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			waitNote = CallBudget.clampNote(requested, "eclipse_get_provisioning_status"); //$NON-NLS-1$
		}
		JsonObject answer = handle.toJson();
		if (waitNote != null) {
			answer.put("waitNote", waitNote); //$NON-NLS-1$
		}
		return McpToolResult.of(answer.toString());
	}

	/**
	 * The units in this update that are the server itself.
	 * <p>
	 * A self update stops the bundle serving the request and can lose its own driver half way,
	 * leaving an IDE with no server and no way in.
	 */
	private static List<String> selfUpdates(Update[] updates) {
		List<String> self = new ArrayList<>();
		for (Update update : updates) {
			String id = update.toUpdate.getId();
			if (id.startsWith("com.vogella.eclipse.mcp")) { //$NON-NLS-1$
				self.add(id);
			}
		}
		return self;
	}
}
