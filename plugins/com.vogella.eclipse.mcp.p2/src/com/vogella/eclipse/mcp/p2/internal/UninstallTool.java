package com.vogella.eclipse.mcp.p2.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
import org.eclipse.equinox.p2.engine.IProvisioningPlan;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.operations.ProvisioningJob;
import org.eclipse.equinox.p2.operations.ProvisioningSession;
import org.eclipse.equinox.p2.operations.UninstallOperation;
import org.eclipse.equinox.p2.query.IQueryable;
import org.eclipse.equinox.p2.query.QueryUtil;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Removes an installed unit from the running IDE through p2.
 */
public final class UninstallTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 100;

	private static final Comparator<IInstallableUnit> BY_ID_AND_VERSION = Comparator
			.comparing(IInstallableUnit::getId).thenComparing(unit -> unit.getVersion().toString());

	@Override
	public String getName() {
		return "eclipse_uninstall"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Uninstalls an installed unit from this IDE. UNINSTALLS SOFTWARE FROM THE RUNNING INSTALLATION, and runs as a dry run unless dryRun is set to false, because removing one feature can drag out bundles that something else still needs. The dry run reports what the resolved operation would actually remove, taken from the provisioning plan, which is often more than the named feature. THE REMOVAL IS NOT IN EFFECT UNTIL THE IDE RESTARTS. This is the counterpart of eclipse_install and exists because installing from a locally built repository is otherwise a one way door: rebuild a feature under a new qualifier and the stale pin left behind makes every later install or update fail to resolve. Refuses a unit that is not installed; eclipse_get_installation lists what is installed."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["unit"],
				  "properties": {
				    "unit":       {"type":"string","description":"Installable unit id, as eclipse_get_installation reports it. A feature id usually ends in .feature.group."},
				    "version":    {"type":"string","description":"Exact version to remove, when more than one version of the unit is installed."},
				    "dryRun":     {"type":"boolean","default":true,"description":"Resolve and report what would be removed without changing anything. On by default."},
				    "maxResults": {"type":"integer","default":100,"minimum":1,"maximum":2000,"description":"Cap on the reported additions and removals."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String unitId = args.getString("unit"); //$NON-NLS-1$
		if (unitId == null) {
			return McpToolResult.error(
					"Give the 'unit' to uninstall, the installable unit id eclipse_get_installation reports."); //$NON-NLS-1$
		}
		int maxResults = args.getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 2000); //$NON-NLS-1$
		IProvisioningAgent agent = Provisioning.agent();
		if (agent == null) {
			return McpToolResult.error("No p2 provisioning agent is available, so nothing can be uninstalled."); //$NON-NLS-1$
		}
		Version version = null;
		String versionText = args.getString("version"); //$NON-NLS-1$
		if (versionText != null) {
			try {
				version = Version.create(versionText);
			} catch (IllegalArgumentException e) {
				return McpToolResult.error("'%s' is not a valid version.".formatted(versionText)); //$NON-NLS-1$
			}
		}
		List<IInstallableUnit> installed = installed(agent, unitId, version);
		if (installed.isEmpty()) {
			return McpToolResult.error(("Refused: no unit '%s'%s is installed in this IDE, so there is nothing to "
					+ "uninstall and nothing was changed. eclipse_get_installation lists what is installed.") //$NON-NLS-1$
					.formatted(unitId, version == null ? "" : " at version " + version)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (version == null && installed.size() > 1) {
			return McpToolResult.error(
					"Refused: %s versions of '%s' are installed (%s), so nothing was changed. Name the exact 'version' to remove."
							.formatted(installed.size(), unitId, versionsOf(installed))); //$NON-NLS-1$
		}

		UninstallOperation operation = new UninstallOperation(new ProvisioningSession(agent), installed);
		IStatus resolution = operation.resolveModal(monitor);
		if (resolution.getSeverity() == IStatus.ERROR) {
			return McpToolResult.error(ResolutionStatuses.failure("The uninstall could not be resolved", resolution)); //$NON-NLS-1$
		}
		IProvisioningPlan plan = operation.getProvisioningPlan();
		Changes changes = capped(units(plan == null ? null : plan.getRemovals()),
				units(plan == null ? null : plan.getAdditions()), maxResults);

		if (args.getBoolean("dryRun", true)) { //$NON-NLS-1$
			return McpToolResult.of(Provisioning
					.record("uninstall", "dryRun", //$NON-NLS-1$ //$NON-NLS-2$
							"Nothing was changed. This is what would be removed; pass dryRun false to apply it. A removal takes effect on the next IDE restart.", //$NON-NLS-1$
							changes.entries)
					.toJson()
					.put("total", Integer.valueOf(changes.total)) //$NON-NLS-1$
					.put("truncated", Boolean.valueOf(changes.truncated)) //$NON-NLS-1$
					.toString());
		}
		// an uninstall downloads nothing, so trust prompts should never fire here;
		// HeadlessTrust stays installed for the apply anyway and records a refusal
		// if p2 asks anything, so a surprise surfaces instead of blocking
		HeadlessTrust trust = new HeadlessTrust(false);
		HeadlessTrust.install(agent, trust);
		ProvisioningJob job = operation.getProvisioningJob(null);
		if (job == null) {
			HeadlessTrust.restore(agent, trust);
			return McpToolResult.error("p2 produced no provisioning job for the resolved uninstall."); //$NON-NLS-1$
		}
		Provisioning.Operation handle = Provisioning.start("uninstall", tracked -> { //$NON-NLS-1$
			Provisioning.setChanges(tracked, changes.entries, changes.total, changes.truncated);
			return job;
		});
		Provisioning.onFinished(handle, () -> {
			Provisioning.setTrust(handle, trust, false);
			HeadlessTrust.restore(agent, trust);
		});
		return McpToolResult.of(handle.toJson().toString());
	}

	private static JsonObject change(String kind, IInstallableUnit unit) {
		return new JsonObject().put("change", kind).put("unit", unit.getId()) //$NON-NLS-1$ //$NON-NLS-2$
				.put("version", unit.getVersion().toString()); //$NON-NLS-1$
	}

	/** The capped change list, with the count and the truncation flag behind the cap. */
	private record Changes(JsonArray entries, int total, boolean truncated) {
	}

	private static Changes capped(List<IInstallableUnit> removed, List<IInstallableUnit> added, int maxResults) {
		JsonArray entries = new JsonArray();
		for (IInstallableUnit unit : removed) {
			if (entries.size() >= maxResults) {
				return new Changes(entries, removed.size() + added.size(), true);
			}
			entries.add(change("removed", unit)); //$NON-NLS-1$
		}
		for (IInstallableUnit unit : added) {
			if (entries.size() >= maxResults) {
				return new Changes(entries, removed.size() + added.size(), true);
			}
			entries.add(change("added", unit)); //$NON-NLS-1$
		}
		return new Changes(entries, entries.size(), false);
	}

	private static List<IInstallableUnit> installed(IProvisioningAgent agent, String unitId, Version version) {
		IProfileRegistry registry = agent.getService(IProfileRegistry.class);
		IProfile profile = registry == null ? null : registry.getProfile(IProfileRegistry.SELF);
		if (profile == null) {
			return List.of();
		}
		List<IInstallableUnit> found = new ArrayList<>();
		profile.query(QueryUtil.createIUQuery(unitId), null).forEach(found::add);
		if (version != null) {
			found.removeIf(unit -> !unit.getVersion().equals(version));
		}
		found.sort(BY_ID_AND_VERSION);
		return found;
	}

	private static String versionsOf(List<IInstallableUnit> units) {
		List<String> texts = new ArrayList<>();
		for (IInstallableUnit unit : units) {
			texts.add(unit.getVersion().toString());
		}
		return String.join(", ", texts); //$NON-NLS-1$
	}

	/** The units of a plan side, sorted for a stable answer. */
	private static List<IInstallableUnit> units(IQueryable<IInstallableUnit> queryable) {
		List<IInstallableUnit> units = new ArrayList<>();
		if (queryable != null) {
			queryable.query(QueryUtil.ALL_UNITS, null).forEach(units::add);
		}
		units.sort(BY_ID_AND_VERSION);
		return units;
	}
}
