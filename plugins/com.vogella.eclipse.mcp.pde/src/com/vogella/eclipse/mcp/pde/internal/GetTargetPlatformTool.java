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
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the active target platform and the target definitions the IDE knows.
 */
public final class GetTargetPlatformTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_get_target_platform"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports the active target platform: which target definition is loaded, whether it resolved, its locations with the status of each, how many bundles and features it contributes, and the bundles that failed to resolve. Reports a load started by eclipse_set_target_platform as lastLoad, which is how a resolve that outlived its call is followed. WAITS FOR A RESOLVE IN FLIGHT when wait is true, which is the default, the way eclipse_get_problems waits for a running build: a cold resolve takes tens of seconds and reading the target while it runs otherwise reports the state before it. It NEVER STARTS one. The wait is bounded by the server's call timeout and answers before the call would be abandoned, with lastLoad.state still 'running' and waitedForResolve true, so a resolve longer than the budget costs another call rather than losing the answer. eclipse_wait_until_quiet also covers this job and can be used instead when one wait should cover a build and a resolve together. Can also list the target definitions the IDE knows, so one of them can be handed to eclipse_set_target_platform. Read-only. This is the answer to 'why does every plug-in in this workspace have unresolved dependencies', which no manifest shows."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "wait":             {"type":"boolean","default":true,"description":"Wait for a resolve already in flight before answering. Never starts one. A warm resolve finishes in milliseconds, so this costs nothing when nothing is running."},
				    "timeoutSeconds":   {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"How long to wait for a resolve in flight. Bounded in practice by the server's own call timeout, which is what the answer says when it had to cut the wait short."},
				    "includeLocations": {"type":"boolean","default":true,"description":"List the locations of the active definition with the resolution status of each."},
				    "includeKnown":     {"type":"boolean","default":false,"description":"Also list the target definitions the IDE knows, workspace .target files included, with the memento that names each one."},
				    "maxProblems":      {"type":"integer","default":50,"minimum":1,"maximum":1000,"description":"Cap on the reported bundles that failed to resolve."},
			    "maxResults":       {"type":"integer","default":100,"minimum":1,"maximum":1000,"description":"Cap on the reported locations and known definitions."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		boolean includeLocations = args.getBoolean("includeLocations", true); //$NON-NLS-1$
		boolean includeKnown = args.getBoolean("includeKnown", false); //$NON-NLS-1$
		int maxProblems = args.getInt("maxProblems", 50, 1, 1000); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 100, 1, 1000); //$NON-NLS-1$
		int requested = args.getInt("timeoutSeconds", 25, 1, 3600); //$NON-NLS-1$
		// before the service call, so the answer describes the state after the wait
		// rather than the one that was current when the request arrived
		Waited waited = args.getBoolean("wait", true) ? awaitResolve(requested) : Waited.notAsked(); //$NON-NLS-1$

		return TargetPlatforms.with(service -> {
			JsonObject result = new JsonObject();
			ITargetHandle activeHandle;
			ITargetDefinition active;
			try {
				// the handle, not the definition: PDE answers getWorkspaceTargetDefinition
				// with an empty default when nothing is set, and only the handle is null then
				activeHandle = service.getWorkspaceTargetHandle();
				active = service.getWorkspaceTargetDefinition();
			} catch (CoreException e) {
				return McpToolResult.error("Could not read the active target platform: " + e.getMessage()); //$NON-NLS-1$
			}
			result.put("targetSet", activeHandle != null); //$NON-NLS-1$
			if (activeHandle == null) {
				result.put("note", //$NON-NLS-1$
						"No target definition is set, so PDE compiles against the IDE's own installation."); //$NON-NLS-1$
			}
			JsonObject activeJson = active == null ? null
					: TargetPlatforms.describe(active, includeLocations, maxProblems, maxResults);
			result.put("active", activeJson); //$NON-NLS-1$
			if (active != null && !active.isResolved()) {
				result.put("resolveNote", //$NON-NLS-1$
						"This definition is not resolved in this session, so bundle counts and per bundle problems are missing. eclipse_set_target_platform resolves and loads it."); //$NON-NLS-1$
			}
			TargetLoad load = TargetLoad.current();
			if (load != null) {
				result.put("lastLoad", load.toJson(includeLocations, maxProblems, maxResults, active, activeJson)); //$NON-NLS-1$
			}
			waited.reportInto(result, requested);
			if (includeKnown) {
				JsonArray known = known(service, monitor, maxResults, result);
				result.put("known", known); //$NON-NLS-1$
			}
			return McpToolResult.of(result.toString());
		});
	}

	/** What the wait came to, so the answer can say why it is reporting this state. */
	private record Waited(boolean asked, boolean waited, boolean stillResolving, boolean interrupted) {

		static Waited notAsked() {
			return new Waited(false, false, false, false);
		}

		void reportInto(JsonObject result, int requested) {
			if (!asked) {
				return;
			}
			result.put("waitedForResolve", Boolean.valueOf(waited)); //$NON-NLS-1$
			if (interrupted) {
				result.put("waitNote", "The wait was interrupted, so this is the state at that moment."); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			if (!stillResolving) {
				return;
			}
			// the resolve outlived what one call can wait for, which is the ordinary
			// case for a cold target rather than a failure
			String clamped = CallBudget.clampNote(requested, "eclipse_get_target_platform again"); //$NON-NLS-1$
			result.put("stillResolving", Boolean.TRUE) //$NON-NLS-1$
					.put("waitNote", clamped == null //$NON-NLS-1$
							? "The resolve did not finish within the %d seconds asked for and is still running; call again." //$NON-NLS-1$
									.formatted(Integer.valueOf(requested))
							: clamped);
		}
	}

	/**
	 * Waits for a resolve already running, bounded by what fits inside one call.
	 * <p>
	 * Bounded rather than blocking: a cold resolve runs for tens of seconds and can
	 * outlast the server's call timeout, and a call abandoned mid-wait tells the
	 * caller nothing at all, while a short answer saying the resolve is still going
	 * costs one more call and loses nothing.
	 */
	private static Waited awaitResolve(int requested) {
		TargetLoad load = TargetLoad.current();
		if (load == null || !load.isRunning()) {
			return new Waited(true, false, false, false);
		}
		try {
			boolean finished = load.await(CallBudget.boundedWaitSeconds(requested));
			return new Waited(true, true, !finished, false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Waited(true, true, load.isRunning(), true);
		}
	}

	private static JsonArray known(ITargetPlatformService service, IProgressMonitor monitor, int maxResults,
			JsonObject result) {
		JsonArray targets = new JsonArray();
		String activeMemento = null;
		try {
			activeMemento = TargetPlatforms.memento(service.getWorkspaceTargetHandle());
		} catch (CoreException e) {
			// leave the active flag off rather than failing the whole listing
		}
		ITargetHandle[] handles = service.getTargets(monitor);
		ITargetHandle[] all = handles == null ? new ITargetHandle[0] : handles;
		result.put("knownTotal", all.length).put("knownTruncated", all.length > maxResults); //$NON-NLS-1$ //$NON-NLS-2$
		for (ITargetHandle handle : all) {
			if (targets.size() >= maxResults) {
				break;
			}
			String memento = TargetPlatforms.memento(handle);
			JsonObject entry = new JsonObject().put("memento", memento) //$NON-NLS-1$
					.put("active", memento != null && memento.equals(activeMemento)); //$NON-NLS-1$
			try {
				entry.put("name", handle.getTargetDefinition().getName()); //$NON-NLS-1$
			} catch (CoreException e) {
				// a .target file that does not parse still deserves its memento in the list
				entry.put("name", null).put("unreadable", e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			targets.add(entry);
		}
		return targets;
	}
}
