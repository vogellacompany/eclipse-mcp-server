package com.vogella.eclipse.mcp.p2.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Adds a p2 repository, within the roots the person at the IDE allowed.
 */
public final class AddRepositoryTool implements IMcpTool {

	private static final int SAMPLE = 10;

	@Override
	public String getName() {
		return "eclipse_add_repository"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Adds a p2 repository to this IDE, so that eclipse_install can install from it. CHANGES IDE CONFIGURATION, AND MAKES CODE FROM A NEW SOURCE INSTALLABLE, and runs as a dry run unless dryRun is set to false. The primary case is a file: URL pointing at a freshly built target/repository. Both the dry run and the real add read the repository and report its name and the units it contains, so what is being added is visible before it is committed to. Re-adding the same URL after a rebuild refreshes the metadata, because p2 caches it per URL and a stale cache makes a new build look identical to the old one. Use eclipse_remove_repository for a scratch repository that should not linger."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "url":     {"type":"string","description":"Repository URL, e.g. file:/home/me/git/project/update-site/repo/target/repository or an https site."},
				    "dryRun":  {"type":"boolean","default":true,"description":"Read the repository and report what it contains without adding it. On by default."},
				    "refresh": {"type":"boolean","default":true,"description":"Re-read the metadata of a URL that is already configured. p2 caches per URL, so a rebuilt repository at an unchanged path otherwise looks unchanged."},
				    "maxUnits":{"type":"integer","default":10,"minimum":1,"maximum":200,"description":"How many installable unit ids to list. The count is always reported in full."}
				  },
				  "required": ["url"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public String getOutputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "url":               {"type":"string"},
				    "name":              {"type":["string","null"]},
				    "dryRun":            {"type":"boolean"},
				    "added":             {"type":"boolean"},
				    "alreadyConfigured": {"type":"boolean"},
				    "refreshed":         {"type":"boolean"},
				    "groupCount":        {"type":"integer"},
				    "truncated":         {"type":"boolean"},
				    "groups":            {"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"version":{"type":"string"}}}},
				    "note":              {"type":"string"}
				  },
				  "required": ["url","dryRun","added","alreadyConfigured","refreshed","groupCount","truncated","groups","note"]
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String url = args.getString("url"); //$NON-NLS-1$
		if (url == null || url.isBlank()) {
			return McpToolResult.error("Give the repository 'url' to add."); //$NON-NLS-1$
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		boolean refresh = args.getBoolean("refresh", true); //$NON-NLS-1$
		int maxUnits = args.getInt("maxUnits", SAMPLE, 1, 200); //$NON-NLS-1$
		URI uri;
		try {
			uri = new URI(url.strip());
		} catch (URISyntaxException e) {
			return McpToolResult.error("'%s' is not a valid URL: %s".formatted(url, e.getMessage())); //$NON-NLS-1$
		}
		if (!uri.isAbsolute()) {
			return McpToolResult.error(
					"'%s' has no scheme. Give an absolute URL, such as file:/path/to/repository.".formatted(url)); //$NON-NLS-1$
		}
		IProvisioningAgent agent = Provisioning.agent();
		if (agent == null) {
			return McpToolResult.error("This IDE has no provisioning agent, so it cannot manage repositories."); //$NON-NLS-1$
		}
		IMetadataRepositoryManager metadata = agent.getService(IMetadataRepositoryManager.class);
		IArtifactRepositoryManager artifacts = agent.getService(IArtifactRepositoryManager.class);
		if (metadata == null) {
			return McpToolResult.error("This IDE has no metadata repository manager."); //$NON-NLS-1$
		}
		boolean alreadyKnown = metadata.contains(uri);

		// added before reading, because loading a repository is what registers it with
		// p2 anyway; a dry run removes it again below, so nothing is left behind
		metadata.addRepository(uri);
		if (artifacts != null) {
			artifacts.addRepository(uri);
		}
		if (refresh && alreadyKnown) {
			try {
				metadata.refreshRepository(uri, monitor);
			} catch (ProvisionException e) {
				// reported through the load below, which fails with the same reason
			}
		}
		IMetadataRepository repository;
		try {
			repository = metadata.loadRepository(uri, monitor);
		} catch (ProvisionException e) {
			if (!alreadyKnown) {
				metadata.removeRepository(uri);
				if (artifacts != null) {
					artifacts.removeRepository(uri);
				}
			}
			return McpToolResult.error("Could not read a p2 repository at '%s': %s".formatted(uri, e.getMessage())); //$NON-NLS-1$
		}
		IQueryResult<IInstallableUnit> units = repository.query(QueryUtil.createIUGroupQuery(), monitor);
		JsonArray groups = new JsonArray();
		int total = 0;
		for (IInstallableUnit unit : units) {
			total++;
			if (groups.size() < maxUnits) {
				groups.add(new JsonObject().put("id", unit.getId()) //$NON-NLS-1$
						.put("version", unit.getVersion().toString())); //$NON-NLS-1$
			}
		}
		if (dryRun && !alreadyKnown) {
			metadata.removeRepository(uri);
			if (artifacts != null) {
				artifacts.removeRepository(uri);
			}
		}
		return McpToolResult.of(new JsonObject().put("url", uri.toString()) //$NON-NLS-1$
				.put("name", repository.getName()) //$NON-NLS-1$
				.put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("added", Boolean.valueOf(!dryRun && !alreadyKnown)) //$NON-NLS-1$
				.put("alreadyConfigured", Boolean.valueOf(alreadyKnown)) //$NON-NLS-1$
				.put("refreshed", Boolean.valueOf(refresh && alreadyKnown)) //$NON-NLS-1$
				.put("groupCount", Integer.valueOf(total)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(total > groups.size())) //$NON-NLS-1$
				.put("groups", groups) //$NON-NLS-1$
				.put("note", dryRun //$NON-NLS-1$
						? "Nothing was changed. Call again with dryRun false to add it, then eclipse_install the unit you want." //$NON-NLS-1$
						: "Installable now. The installed code is not active until the IDE restarts.") //$NON-NLS-1$
				.toString());
	}
}
