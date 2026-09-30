package com.vogella.eclipse.mcp.debug.internal;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the live debug sessions and their threads, optionally blocking until the
 * next suspend event.
 */
public final class DebugStatusTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_debug_status"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Lists the debug sessions this IDE knows and their threads: per session whether it was started by MCP, terminated or suspended, and per suspended thread the breakpoint that stopped it and the top stack frame as declaringType.method(File.java:123). Read only. Sessions the user started by hand in the IDE appear here too, with startedByMcp false. With 'waitForSuspendSeconds' it blocks until the next suspend event before answering, which is how a caller learns that a breakpoint was hit without polling; it reports timedOut when the wait ran out. Use eclipse_debug_get_frames to read variables once something is suspended."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "sessionId":            {"type":"string","description":"Narrow to one session, by the id eclipse_debug_launch returned."},
				    "waitForSuspendSeconds":{"type":"integer","default":0,"minimum":0,"maximum":25,"description":"Block until any session suspends next, or this long at most; reports timedOut when it ran out."},
				    "maxResults":           {"type":"integer","default":20,"minimum":1,"maximum":200,"description":"Sessions reported, and threads reported per session."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String sessionId = args.getString("sessionId"); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 20, 1, 200); //$NON-NLS-1$
		int waitSeconds = args.getInt("waitForSuspendSeconds", 0, 0, 25); //$NON-NLS-1$

		DebugSessionRegistry registry = DebugSessionRegistry.getInstance();
		boolean timedOut = false;
		DebugSessionRegistry.Session named = sessionId == null ? null : registry.find(sessionId);
		boolean alreadySuspended = named == null ? registry.anythingSuspended() : named.suspended();
		if ((sessionId == null || named != null) && waitSeconds > 0 && !alreadySuspended) {
			DebugSessionRegistry.SuspendSignal signal = registry.onNextSuspend(named);
			try {
				timedOut = !signal.await(CallBudget.boundedWaitSeconds(waitSeconds));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		List<DebugSessionRegistry.Session> selected;
		if (sessionId != null) {
			DebugSessionRegistry.Session session = named;
			if (session == null) {
				return McpToolResult.error("No debug session '%s'. Known ids, oldest first: %s".formatted( //$NON-NLS-1$
						sessionId, String.join(", ", registry.ids()))); //$NON-NLS-1$
			}
			selected = List.of(session);
		} else {
			selected = registry.all();
			if (selected.isEmpty()) {
				return McpToolResult.of(new JsonObject().put("total", Integer.valueOf(0)) //$NON-NLS-1$
						.put("truncated", Boolean.FALSE).put("sessions", new JsonArray()) //$NON-NLS-1$ //$NON-NLS-2$
						.put("note", "No debug session exists yet; start one through eclipse_debug_launch.") //$NON-NLS-1$ //$NON-NLS-2$
						.toString());
			}
		}
		JsonArray sessions = new JsonArray();
		for (DebugSessionRegistry.Session session : selected) {
			if (sessions.size() >= maxResults) {
				break;
			}
			sessions.add(DebugSupport.sessionJson(session, maxResults));
		}
		JsonObject json = new JsonObject().put("total", Integer.valueOf(selected.size())) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(sessions.size() < selected.size())) //$NON-NLS-1$
				.put("sessions", sessions); //$NON-NLS-1$
		if (timedOut) {
			json.put("timedOut", Boolean.TRUE).put("waitNote", //$NON-NLS-1$ //$NON-NLS-2$
					"Nothing suspended within %d seconds.".formatted(Integer.valueOf(waitSeconds))); //$NON-NLS-1$
		}
		return McpToolResult.of(json.toString());
	}
}
