package com.vogella.eclipse.mcp.pde.internal;

import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetHandle;
import org.eclipse.pde.core.target.ITargetPlatformService;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Resolves a target definition and makes it the active target platform.
 */
public final class SetTargetPlatformTool implements IMcpTool {

	/** Below the default tool call timeout, so that waiting returns an answer rather than being killed. */
	private static final int DEFAULT_TIMEOUT_SECONDS = 25;

	@Override
	public String getName() {
		return "eclipse_set_target_platform"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Resolves a target definition and sets it as the active target platform, which is what the 'Set as Active Target Platform' link in the target editor does. CHANGES THE IDE: it replaces the bundles every plug-in project compiles against, PDE recomputes the plug-in classpaths, and problem markers across the workspace change with it. Resolving a target that is not cached downloads from its p2 repositories and can take many minutes, so the work runs as a job and a call that does not finish in time comes back with state 'running', to be polled through eclipse_get_target_platform. Use resolveOnly to find out whether a definition resolves without activating it, which is also how to see the jre field before committing to it. IT ALSO BINDS A JRE, AND THAT BINDING IS STICKY: a target definition may name one, and activating overwrites the workspace default VM with it, which every project whose JRE container names no execution environment then follows. Activating a different target afterwards does NOT put the old VM back. The answer resolves that container to the install it actually means and warns when the install cannot compile, for instance a JDK whose lib/ct.sym is missing, which fails every plug-in project with a message about ct.sym that mentions no target at all."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "file":             {"type":"string","description":"The .target file: a workspace path such as /target-platform/example.target, or an absolute file system path."},
				    "memento":          {"type":"string","description":"Instead of a file, a target handle memento as reported by eclipse_get_target_platform with includeKnown."},
				    "resolveOnly":      {"type":"boolean","default":false,"description":"Resolve and report, without making the definition the active target platform."},
				    "wait":             {"type":"boolean","default":true,"description":"Wait for the job to finish before answering."},
				    "timeoutSeconds":   {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"How long to wait before returning with state 'running'. Keep this below the server's tool call timeout, which is set in Preferences > General > MCP Server. The job keeps running either way."},
				    "includeLocations": {"type":"boolean","default":true,"description":"Report each location of the definition with its resolution status."},
				    "maxProblems":      {"type":"integer","default":50,"minimum":1,"maximum":1000,"description":"Cap on the reported bundles that failed to resolve."},
			    "maxResults":       {"type":"integer","default":100,"minimum":1,"maximum":1000,"description":"Cap on the reported locations."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String file = args.getString("file"); //$NON-NLS-1$
		String memento = args.getString("memento"); //$NON-NLS-1$
		if (file == null && memento == null) {
			return McpToolResult.error("Name the target definition with 'file' or 'memento'."); //$NON-NLS-1$
		}
		boolean resolveOnly = args.getBoolean("resolveOnly", false); //$NON-NLS-1$
		boolean wait = args.getBoolean("wait", true); //$NON-NLS-1$
		int timeoutSeconds = args.getInt("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS, 1, 3600); //$NON-NLS-1$
		boolean includeLocations = args.getBoolean("includeLocations", true); //$NON-NLS-1$
		int maxProblems = args.getInt("maxProblems", 50, 1, 1000); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 100, 1, 1000); //$NON-NLS-1$

		return TargetPlatforms.with(service -> {
			String named = file == null ? memento : file;
			ITargetHandle handle;
			try {
				handle = TargetPlatforms.handle(service, file, memento);
			} catch (CoreException e) {
				return McpToolResult.error("Could not read that target definition: " + e.getMessage()); //$NON-NLS-1$
			}
			if (handle == null || !handle.exists()) {
				return McpToolResult.error(
						"No target definition at '%s'. Give a workspace path such as /target-platform/example.target, an absolute file system path, or a memento from eclipse_get_target_platform with includeKnown." //$NON-NLS-1$
								.formatted(named));
			}
			ITargetDefinition definition;
			try {
				definition = handle.getTargetDefinition();
			} catch (CoreException e) {
				return McpToolResult.error("That target definition could not be read: " + e.getMessage()); //$NON-NLS-1$
			}

			TargetLoad load = TargetLoad.start(named, definition, resolveOnly, previous(service));
			String waitNote = null;
			if (wait) {
				try {
					load.await(CallBudget.boundedWaitSeconds(timeoutSeconds));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				waitNote = CallBudget.clampNote(timeoutSeconds, "eclipse_get_target_platform"); //$NON-NLS-1$
			}
			JsonObject answer = load.toJson(includeLocations, maxProblems, maxResults);
			if (waitNote != null) {
				answer.put("waitNote", waitNote); //$NON-NLS-1$
			}
			return McpToolResult.of(answer.toString());
		});
	}

	private static JsonObject previous(ITargetPlatformService service) {
		try {
			ITargetDefinition active = service.getWorkspaceTargetDefinition();
			return service.getWorkspaceTargetHandle() == null || active == null ? null
					: new JsonObject().put("name", active.getName()) //$NON-NLS-1$
							.put("memento", TargetPlatforms.memento(active.getHandle())); //$NON-NLS-1$
		} catch (CoreException e) {
			return null;
		}
	}
}
