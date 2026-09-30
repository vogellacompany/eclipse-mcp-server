package com.vogella.eclipse.mcp.p2.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.operations.InstallOperation;
import org.eclipse.equinox.p2.operations.ProvisioningJob;
import org.eclipse.equinox.p2.operations.ProvisioningSession;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Installs a unit from a repository the IDE is already configured with.
 */
public final class InstallTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_install"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Installs an installable unit into this IDE. MODIFIES THE INSTALLATION BY FETCHING AND RUNNING CODE FROM THE NETWORK, which is a larger step than any other tool here takes. Only repositories the IDE is already configured with may be used; an unknown URL is refused rather than added, because adding it is a decision for the person at the IDE. Runs as a job and returns an operationId to poll through eclipse_get_provisioning_status. The installed code is not active until the IDE restarts. A BUNDLE THAT AN INSTALLED FEATURE PINS CANNOT BE REPLACED THIS WAY: a feature requires each of its bundles at one exact version, so p2 refuses a differently versioned build of it with 'Only one of the following can be installed at once', and in an SDK almost every bundle is pinned by a feature that another feature pins in turn. This path works for a unit nothing pins, and for a whole feature built and installed together; for running one changed workspace bundle instead of an installed one, eclipse_substitute_bundle is the tool, at the price of leaving p2's picture of the installation untrue."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["unit"],
				  "properties": {
				    "unit":           {"type":"string","description":"Installable unit id, usually a feature id ending in .feature.group."},
				    "repository":     {"type":"string","description":"Repository URL to install from. Must already be configured in this IDE. Omit to search every configured repository."},
				    "version":        {"type":"string","description":"Exact version. Omit for the newest available."},
				    "trustUnsigned":  {"type":"boolean","default":true,"description":"Accept unsigned content, or content signed by a certificate this IDE does not trust. On by default, because an install performed by this server is unattended and there is nobody to answer the dialog p2 would otherwise raise. This is NOT bounded by which sites are configured, because eclipse_add_repository can configure a new one: whoever can call these tools decides what gets installed. Nothing is added to the IDE's permanent trust store, and whatever was accepted is reported. Set false to refuse unsigned content instead."},
				    "wait":           {"type":"boolean","default":false,"description":"Wait for the install instead of returning an operationId to poll."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"How long to wait when wait is true. Bounded in practice by the server's own call timeout."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String unitId = args.getString("unit"); //$NON-NLS-1$
		if (unitId == null) {
			return McpToolResult.error("The argument 'unit' is required."); //$NON-NLS-1$
		}
		IProvisioningAgent agent = Provisioning.agent();
		if (agent == null) {
			return McpToolResult.error("No p2 provisioning agent is available, so nothing can be installed."); //$NON-NLS-1$
		}
		IMetadataRepositoryManager manager = agent.getService(IMetadataRepositoryManager.class);
		if (manager == null) {
			return McpToolResult.error("p2 has no metadata repository manager."); //$NON-NLS-1$
		}
		List<URI> known = Provisioning.knownRepositories(agent);

		List<URI> search = new ArrayList<>();
		String repository = args.getString("repository"); //$NON-NLS-1$
		if (repository != null) {
			URI uri;
			try {
				uri = new URI(repository.strip());
			} catch (URISyntaxException e) {
				return McpToolResult.error("'%s' is not a URL.".formatted(repository)); //$NON-NLS-1$
			}
			if (known.stream().noneMatch(candidate -> candidate.equals(uri))) {
				JsonArray configured = new JsonArray();
				known.forEach(candidate -> configured.add(candidate.toString()));
				return McpToolResult.error(
						"Refused: '%s' is not among the repositories this IDE is configured with, and adding one fetches and runs code from a new source, which is a decision for the person at the IDE rather than for this server. Add it under Preferences > Install/Update > Available Software Sites first. Configured repositories: %s" //$NON-NLS-1$
								.formatted(repository, configured));
			}
			search.add(uri);
		} else {
			search.addAll(known);
		}
		if (search.isEmpty()) {
			return McpToolResult.error("This IDE has no configured update sites, so there is nothing to install from."); //$NON-NLS-1$
		}

		String version = args.getString("version"); //$NON-NLS-1$
		List<IInstallableUnit> found = new ArrayList<>();
		for (URI uri : search) {
			try {
				IMetadataRepository metadata = manager.loadRepository(uri, monitor);
				IQueryResult<IInstallableUnit> result = metadata
						.query(version == null ? QueryUtil.createLatestQuery(QueryUtil.createIUQuery(unitId))
								: QueryUtil.createIUQuery(unitId,
										org.eclipse.equinox.p2.metadata.Version.create(version)),
								monitor);
				result.forEach(found::add);
			} catch (org.eclipse.equinox.p2.core.ProvisionException e) {
				// an unreachable repository is not a reason to fail the whole search
			}
			if (!found.isEmpty()) {
				break;
			}
		}
		if (found.isEmpty()) {
			return McpToolResult.error("No unit '%s'%s in the configured repositories.".formatted(unitId, //$NON-NLS-1$
					version == null ? "" : " at version " + version)); //$NON-NLS-1$ //$NON-NLS-2$
		}

		InstallOperation operation = new InstallOperation(new ProvisioningSession(agent), found);
		IStatus resolution = operation.resolveModal(monitor);
		if (resolution.getSeverity() == IStatus.ERROR) {
			return McpToolResult.error(ResolutionStatuses.failure("The install could not be resolved", resolution)); //$NON-NLS-1$
		}
		JsonArray changes = new JsonArray();
		for (IInstallableUnit unit : found) {
			changes.add(new JsonObject().put("unit", unit.getId()) //$NON-NLS-1$
					.put("toVersion", unit.getVersion().toString())); //$NON-NLS-1$
		}
		boolean trustUnsigned = args.getBoolean("trustUnsigned", true); //$NON-NLS-1$
		HeadlessTrust trust = new HeadlessTrust(trustUnsigned);
		HeadlessTrust.install(agent, trust);
		ProvisioningJob job = operation.getProvisioningJob(null);
		if (job == null) {
			HeadlessTrust.restore(agent, trust);
			return McpToolResult.error("p2 produced no provisioning job for the resolved install."); //$NON-NLS-1$
		}
		Provisioning.Operation handle = Provisioning.start("install", tracked -> { //$NON-NLS-1$
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
		return McpToolResult.of(handle.toJson().put("waitNote", waitNote).toString()); //$NON-NLS-1$
	}
}
