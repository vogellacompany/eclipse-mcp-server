package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.ClientSessions;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ProductFamily;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Renders a sampling session that has already been taken as a flame graph page.
 */
public final class ShowTraceTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_show_trace"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Renders a sampling session as a flame graph on a page this IDE serves, and returns its URL. READ ONLY, and it neither starts nor stops anything: it draws a session eclipse_start_sampling already took, so a profile can be looked at again, or re-read through a different frameFilter, without sampling twice. The page is dark themed and entirely self contained, it is held in memory rather than written to disk, only the last few are kept, and all of them are gone when Eclipse restarts. It is served on the loopback interface behind an unguessable URL, because a browser cannot send the bearer token on a plain navigation; treat that URL as the secret it is. Pass open to have the IDE open it in the machine's browser, which is VISIBLE TO WHOEVER IS AT THE IDE. For a flight recording use eclipse_stop_flight_recording with show, which takes the same flags and can also render a .jfr file from disk through its file argument; allocation stacks are weighted by bytes there rather than by samples."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "sessionId":          {"type":"string","description":"Session from eclipse_start_sampling. Omit for the most recent."},
				    "frameFilter":        {"type":"string","description":"Draw only the stacks containing this text in a frame, e.g. a package prefix. Applied when reading, so one session can be drawn from several angles."},
				    "includeIdleThreads": {"type":"boolean","default":false,"description":"Include threads parked or waiting, including an event loop sleeping in Display.sleep and accept or poll loops. Off by default, because the pooled threads of an idle IDE otherwise dominate the picture. Turn it ON to look at a FREEZE, whose threads are usually parked."},
				    "includeMcpFrames":   {"type":"boolean","default":false,"description":"Keep what this MCP server itself contributed. Off by default: stacks on the server's own threads, stacks whose running code is the server's, and stacks carrying its request machinery are left out and counted in serverSamplesExcluded, and the server frames that only dispatched the application's work, such as a tool running a menu entry on the UI thread, are removed from otherwise kept stacks and counted in serverFramesElidedFrom. Turn it ON to profile the server itself."},
				    "productPrefixes":    {"type":"array","items":{"type":"string"},"description":"Package prefixes of the application under test, such as org.jkiss and com.dbeaver, drawn as a family of their own that the page can filter to. Added to what the running product names, reported as productFamily; an Eclipse product names none."},
				    "open":               {"type":"boolean","default":false,"description":"Open the page in the machine's browser. VISIBLE TO WHOEVER IS AT THE IDE, since a browser window appears."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String sessionId = args.getString("sessionId"); //$NON-NLS-1$
		if (sessionId == null && !ClientSessions.canAssumeASingleClient()) {
			return McpToolResult.error(
					ClientSessions.ambiguousDefault("sampling session", "sessionId", //$NON-NLS-1$ //$NON-NLS-2$
							SamplingRegistry.getInstance().ids()));
		}
		SamplingRegistry.Session session = sessionId == null ? SamplingRegistry.getInstance().findLatest()
				: SamplingRegistry.getInstance().find(sessionId);
		if (session == null) {
			return McpToolResult.error(sessionId == null
					? "No sampling session has been taken in this IDE. Use eclipse_start_sampling first." //$NON-NLS-1$
					: "No sampling session with the id '%s'.".formatted(sessionId)); //$NON-NLS-1$
		}
		boolean includeIdle = args.getBoolean("includeIdleThreads", false); //$NON-NLS-1$
		String frameFilter = args.getString("frameFilter"); //$NON-NLS-1$
		JsonObject answer = new JsonObject().put("sessionId", session.id()) //$NON-NLS-1$
				.put("running", Boolean.valueOf(session.running())) //$NON-NLS-1$
				.put("ticks", Integer.valueOf(session.ticks())); //$NON-NLS-1$
		boolean includeServer = args.getBoolean("includeMcpFrames", false); //$NON-NLS-1$
		// the same counts eclipse_stop_sampling reports, so the two answers can be compared
		SamplingRegistry.counts(session, includeIdle, frameFilter, includeServer, answer);
		ProductFamily product = ProductFamily.of(arguments, true);
		answer.put("productFamily", product == null ? null : product.toJson()); //$NON-NLS-1$
		TracePage.publishSampling(session, includeIdle, frameFilter, includeServer, product, answer,
				args.getBoolean("open", false)); //$NON-NLS-1$
		return McpToolResult.of(answer.toString());
	}
}
