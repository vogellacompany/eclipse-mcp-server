package com.vogella.eclipse.mcp.debug.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.eclipse.jdt.debug.core.IJavaBreakpoint;
import org.eclipse.jdt.debug.core.IJavaExceptionBreakpoint;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the Java breakpoints of the workspace, with the ids
 * {@code eclipse_set_breakpoint} addresses them by.
 */
public final class ListBreakpointsTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_list_breakpoints"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Lists the Java breakpoints of the workspace: line and exception breakpoints with their id, type, line, enabled and installed state, hit count, condition, suspend policy and the file they are attached to. Read only. The id is what eclipse_set_breakpoint takes to update or remove one again. Use eclipse_debug_status to see whether a session is stopped at one."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "filter":     {"type":"string","description":"Only breakpoints whose type name contains this text, case insensitive."},
				    "maxResults": {"type":"integer","default":100,"minimum":1,"maximum":2000,"description":"Reported breakpoints."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String filter = args.getString("filter"); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 100, 1, 2000); //$NON-NLS-1$
		String needle = filter == null ? null : filter.toLowerCase(Locale.ROOT);
		List<IJavaBreakpoint> matching = new ArrayList<>();
		for (IJavaBreakpoint breakpoint : javaBreakpoints()) {
			String typeName = typeName(breakpoint);
			if (needle == null || (typeName != null && typeName.toLowerCase(Locale.ROOT).contains(needle))) {
				matching.add(breakpoint);
			}
		}
		// by type then line, so truncation keeps a readable slice rather than a random one
		matching.sort(Comparator.comparing((IJavaBreakpoint bp) -> String.valueOf(typeName(bp)))
				.thenComparingInt(ListBreakpointsTool::lineOf));
		JsonArray reported = new JsonArray();
		for (IJavaBreakpoint breakpoint : matching) {
			if (reported.size() >= maxResults) {
				break;
			}
			reported.add(toJson(breakpoint));
		}
		JsonObject result = new JsonObject().put("total", Integer.valueOf(matching.size())) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(reported.size() < matching.size())) //$NON-NLS-1$
				.put("breakpoints", reported); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	static IBreakpointManager breakpointManager() {
		return DebugPlugin.getDefault().getBreakpointManager();
	}

	static List<IJavaBreakpoint> javaBreakpoints() {
		List<IJavaBreakpoint> list = new ArrayList<>();
		for (IBreakpoint breakpoint : breakpointManager().getBreakpoints()) {
			if (breakpoint instanceof IJavaBreakpoint java) {
				list.add(java);
			}
		}
		return list;
	}

	static JsonObject toJson(IJavaBreakpoint breakpoint) throws McpToolException {
		try {
			String kind = kindOf(breakpoint);
			JsonObject json = new JsonObject().put("id", idOf(breakpoint)).put("kind", kind) //$NON-NLS-1$ //$NON-NLS-2$
					.put("typeName", breakpoint.getTypeName()) //$NON-NLS-1$
					.put("enabled", Boolean.valueOf(breakpoint.isEnabled())) //$NON-NLS-1$
					.put("installed", Boolean.valueOf(breakpoint.isInstalled())) //$NON-NLS-1$
					.put("hitCount", Integer.valueOf(breakpoint.getHitCount())) //$NON-NLS-1$
					.put("suspendPolicy", suspendPolicy(breakpoint)); //$NON-NLS-1$
			if (breakpoint instanceof ILineBreakpoint line) {
				json.put("line", Integer.valueOf(line.getLineNumber())); //$NON-NLS-1$
			}
			if (breakpoint instanceof IJavaLineBreakpoint lineWithCondition && lineWithCondition.supportsCondition()) {
				json.put("condition", lineWithCondition.getCondition()); //$NON-NLS-1$
			}
			if (breakpoint instanceof IJavaExceptionBreakpoint exception) {
				json.put("caught", Boolean.valueOf(exception.isCaught())) //$NON-NLS-1$
						.put("uncaught", Boolean.valueOf(exception.isUncaught())); //$NON-NLS-1$
			}
			var marker = breakpoint.getMarker();
			if (marker != null && marker.getResource() != null) {
				json.put("resource", marker.getResource().getFullPath().toString()); //$NON-NLS-1$
			} else {
				json.put("resource", null); //$NON-NLS-1$
			}
			return json;
		} catch (CoreException e) {
			throw new McpToolException("Could not read the breakpoint: %s".formatted(e.getMessage()), e);
		}
	}

	static String kindOf(IBreakpoint breakpoint) {
		if (breakpoint instanceof ILineBreakpoint) {
			return "line"; //$NON-NLS-1$
		}
		if (breakpoint instanceof IJavaExceptionBreakpoint) {
			return "exception"; //$NON-NLS-1$
		}
		return "other"; //$NON-NLS-1$
	}

	private static String suspendPolicy(IJavaBreakpoint breakpoint) throws CoreException {
		return breakpoint.getSuspendPolicy() == IJavaBreakpoint.SUSPEND_VM ? "vm" : "thread"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static int lineOf(IJavaBreakpoint breakpoint) {
		try {
			return breakpoint instanceof ILineBreakpoint line ? line.getLineNumber() : -1;
		} catch (CoreException e) {
			return -1;
		}
	}

	private static String typeName(IJavaBreakpoint breakpoint) {
		try {
			return breakpoint.getTypeName();
		} catch (CoreException e) {
			return null;
		}
	}

	/** Stable within this session: derived from the marker id, never a list index. */
	static String idOf(IBreakpoint breakpoint) {
		var marker = breakpoint.getMarker();
		return marker == null ? null : "bp-" + marker.getId(); //$NON-NLS-1$
	}
}
