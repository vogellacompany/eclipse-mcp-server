package com.vogella.eclipse.mcp.p2.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Removes a configured p2 repository.
 */
public final class RemoveRepositoryTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_remove_repository"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Removes a p2 repository from this IDE, so that a scratch site added for one install does not linger. CHANGES IDE CONFIGURATION, and runs as a dry run unless dryRun is set to false. It will just as happily remove a site the person at the IDE configured by hand, so read the dry run before committing to it. Nothing that was installed from the repository is uninstalled."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "url":    {"type":"string","description":"Repository URL to remove, exactly as it is configured."},
				    "dryRun": {"type":"boolean","default":true,"description":"Report what would be removed without changing anything. On by default."}
				  },
				  "required": ["url"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String url = args.getString("url"); //$NON-NLS-1$
		if (url == null || url.isBlank()) {
			return McpToolResult.error("Give the repository 'url' to remove."); //$NON-NLS-1$
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		URI uri;
		try {
			uri = new URI(url.strip());
		} catch (URISyntaxException e) {
			return McpToolResult.error("'%s' is not a valid URL: %s".formatted(url, e.getMessage())); //$NON-NLS-1$
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
		boolean known = metadata.contains(uri);
		if (known && !dryRun) {
			metadata.removeRepository(uri);
			if (artifacts != null) {
				artifacts.removeRepository(uri);
			}
		}
		return McpToolResult.of(new JsonObject().put("url", uri.toString()) //$NON-NLS-1$
				.put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("wasConfigured", Boolean.valueOf(known)) //$NON-NLS-1$
				.put("removed", Boolean.valueOf(known && !dryRun)) //$NON-NLS-1$
				.put("note", known ? null //$NON-NLS-1$
						: "This URL was not configured, so there was nothing to remove.") //$NON-NLS-1$
				.toString());
	}
}
