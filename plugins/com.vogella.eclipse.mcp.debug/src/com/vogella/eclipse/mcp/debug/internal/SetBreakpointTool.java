package com.vogella.eclipse.mcp.debug.internal;

import java.util.Map;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.debug.core.IJavaBreakpoint;
import org.eclipse.jdt.debug.core.IJavaExceptionBreakpoint;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
import org.eclipse.jdt.debug.core.JDIDebugModel;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Creates, updates or removes a Java breakpoint. The only thing it changes is the
 * IDE's breakpoint list, which Eclipse keeps across restarts; no file is written.
 */
public final class SetBreakpointTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_set_breakpoint"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Creates, updates or removes a Java line or exception breakpoint. CHANGES THE IDE'S BREAKPOINT LIST, which Eclipse keeps across restarts, but writes nothing to the workspace and touches no running program. Give 'type' plus 'line' for a line breakpoint, or 'exception' for one on an exception being thrown, caught or uncaught. Setting an existing breakpoint again updates it instead of duplicating it, and the answer says which happened. The answer reports 'installed': a breakpoint that is not installed will never be hit, which for a line breakpoint usually means the line carries no executable code. Use eclipse_list_breakpoints to see what exists, and eclipse_debug_status to see whether anything stopped at one."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "type":         {"type":"string","description":"Fully qualified type name for a line breakpoint, e.g. com.example.Main. Required unless 'id' is given."},
				    "line":         {"type":"integer","minimum":1,"description":"Line number, 1 based. Present means a line breakpoint."},
				    "exception":    {"type":"string","description":"Fully qualified exception type name. Present means an exception breakpoint; mutually exclusive with 'line'. The value goes in 'type' as well when creating."},
				    "caught":       {"type":"boolean","default":false,"description":"Exception breakpoints: suspend also where the exception is caught."},
				    "uncaught":     {"type":"boolean","default":true,"description":"Exception breakpoints: suspend where it is not caught."},
				    "condition":    {"type":"string","description":"Java expression that has to be true to suspend. Line breakpoints only."},
				    "hitCount":     {"type":"integer","minimum":0,"description":"Suspend only on the nth hit. 0 means every hit."},
				    "suspendPolicy":{"type":"string","enum":["thread","vm"],"default":"thread","description":"Suspend the hitting thread or the whole VM."},
				    "enabled":      {"type":"boolean","default":true,"description":"On create. On update of an existing breakpoint an absent 'enabled' leaves its current state."},
				    "remove":       {"type":"boolean","default":false,"description":"Remove the breakpoint named by 'id', or the matching one, and ignore every other argument."},
				    "id":           {"type":"string","description":"Address an existing breakpoint by the id eclipse_list_breakpoints reported, instead of matching by type and line."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String id = args.getString("id"); //$NON-NLS-1$
		boolean remove = args.getBoolean("remove", false); //$NON-NLS-1$
		String typeName = args.getString("type"); //$NON-NLS-1$
		int line = args.getInt("line", -1, -1, Integer.MAX_VALUE); //$NON-NLS-1$
		String exception = args.getString("exception"); //$NON-NLS-1$

		if (exception != null && line > 0) {
			return McpToolResult
					.error("'line' and 'exception' are mutually exclusive: one call sets one kind of breakpoint."); //$NON-NLS-1$
		}
		if (remove && id == null && exception == null && (typeName == null || line <= 0)) {
			return McpToolResult.error("Removal needs 'id', 'type' with 'line', or 'exception'."); //$NON-NLS-1$
		}
		String policy = args.getString("suspendPolicy"); //$NON-NLS-1$
		if (policy != null && !"thread".equals(policy) && !"vm".equals(policy)) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("'suspendPolicy' is 'thread' or 'vm', not '%s'.".formatted(policy)); //$NON-NLS-1$
		}
		if (!remove) {
			if (id == null && exception == null && (typeName == null || line <= 0)) {
				return McpToolResult.error(
						"A line breakpoint needs 'type' and 'line'; an exception breakpoint needs 'exception'; an existing one can be named with 'id'."); //$NON-NLS-1$
			}
		}

		try {
			IJavaBreakpoint existing = findExisting(id, exception != null ? exception : typeName,
					line > 0 && exception == null ? line : -1, exception != null);
			if (existing == null && id != null) {
				return McpToolResult.error("No breakpoint with id '%s'. Known ids come from eclipse_list_breakpoints." //$NON-NLS-1$
						.formatted(id));
			}
			String targetTypeName = exception != null ? exception : typeName;
			if (remove) {
				if (existing == null) {
					return McpToolResult.error("No breakpoint matches %s. Nothing was removed.".formatted(where(targetTypeName, Math.max(line, 0)))); //$NON-NLS-1$
				}
				existing.delete();
				return McpToolResult.of(new JsonObject().put("removed", Boolean.TRUE) //$NON-NLS-1$
						.put("id", ListBreakpointsTool.idOf(existing)).put("typeName", targetTypeName).toString()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (existing != null) {
				update(existing, args, line);
				return answer(Boolean.FALSE, Boolean.TRUE, existing, targetTypeName);
			}
			IResource resource = resourceForType(targetTypeName);
			IJavaBreakpoint created = exception != null
					? JDIDebugModel.createExceptionBreakpoint(resource, exception, args.getBoolean("caught", false), //$NON-NLS-1$
							args.getBoolean("uncaught", true), true, true, null)
					: JDIDebugModel.createLineBreakpoint(resource, targetTypeName, line, -1, -1,
							args.getInt("hitCount", 0, 0, 1_000_000), true, null); //$NON-NLS-1$
			applyCommon(created, args, true);
			if (created instanceof IJavaLineBreakpoint lineBp && args.getString("condition") != null) { //$NON-NLS-1$
				lineBp.setCondition(args.getString("condition")); //$NON-NLS-1$
			}
			return answer(Boolean.TRUE, Boolean.FALSE, created, targetTypeName);
		} catch (DebugSupport.Refusal e) {
			return McpToolResult.error(e.getMessage());
		} catch (CoreException e) {
			throw new McpToolException("Could not set the breakpoint: %s".formatted(e.getMessage()), e);
		}
	}

	private static String where(String typeName, int line) {
		return line > 0 ? "%s:%d".formatted(typeName, Integer.valueOf(line)) : typeName; //$NON-NLS-1$
	}

	private static IJavaBreakpoint findExisting(String id, String typeName, int line, boolean exceptionKind)
			throws CoreException {
		for (IJavaBreakpoint breakpoint : ListBreakpointsTool.javaBreakpoints()) {
			if (id != null) {
				if (id.equals(ListBreakpointsTool.idOf(breakpoint))) {
					return breakpoint;
				}
				continue;
			}
			if (typeName == null || !typeName.equals(breakpoint.getTypeName())) {
				continue;
			}
			if (exceptionKind != breakpoint instanceof IJavaExceptionBreakpoint) {
				continue;
			}
			if (line > 0 && !(breakpoint instanceof ILineBreakpoint lineBp && lineBp.getLineNumber() == line)) {
				continue;
			}
			return breakpoint;
		}
		return null;
	}

	private void update(IJavaBreakpoint existing, ToolArguments args, int line) throws CoreException {
		// a move to another line is a different breakpoint: refuse it rather than guess,
		// because updating would silently leave the old position armed as well
		if (existing instanceof ILineBreakpoint lineBp && args.has("line") //$NON-NLS-1$
				&& lineBp.getLineNumber() != line) {
			throw new DebugSupport.Refusal(
					"Breakpoint %s sits on line %d, not %d. Remove it and set the new line, so no stale position is left behind." //$NON-NLS-1$
							.formatted(ListBreakpointsTool.idOf(existing), Integer.valueOf(lineBp.getLineNumber()),
									Integer.valueOf(line)));
		}
		applyCommon(existing, args, args.has("enabled")); //$NON-NLS-1$
		if (existing instanceof IJavaLineBreakpoint lineBp && args.getString("condition") != null) { //$NON-NLS-1$
			lineBp.setCondition(args.getString("condition")); //$NON-NLS-1$
		}
		if (existing instanceof IJavaExceptionBreakpoint exception) {
			if (args.has("caught")) { //$NON-NLS-1$
				exception.setCaught(args.getBoolean("caught", false)); //$NON-NLS-1$
			}
			if (args.has("uncaught")) { //$NON-NLS-1$
				exception.setUncaught(args.getBoolean("uncaught", true)); //$NON-NLS-1$
			}
		}
	}

	/** Applies what both create and update share; defaults apply only on create. */
	private void applyCommon(IJavaBreakpoint breakpoint, ToolArguments args, boolean applyDefaults)
			throws CoreException {
		if (applyDefaults) {
			breakpoint.setEnabled(args.getBoolean("enabled", true)); //$NON-NLS-1$
		}
		if (args.has("hitCount")) { //$NON-NLS-1$
			breakpoint.setHitCount(args.getInt("hitCount", 0, 0, 1_000_000)); //$NON-NLS-1$
		}
		String policy = args.getString("suspendPolicy"); //$NON-NLS-1$
		if ("vm".equals(policy)) { //$NON-NLS-1$
			breakpoint.setSuspendPolicy(IJavaBreakpoint.SUSPEND_VM);
		} else if ("thread".equals(policy)) { //$NON-NLS-1$
			breakpoint.setSuspendPolicy(IJavaBreakpoint.SUSPEND_THREAD);
		}
	}

	private McpToolResult answer(Boolean created, Boolean updated, IJavaBreakpoint breakpoint, String askedFor)
			throws CoreException, McpToolException {
		JsonObject json = ListBreakpointsTool.toJson(breakpoint);
		json.put("created", created).put("updated", updated); //$NON-NLS-1$ //$NON-NLS-2$
		if (askedFor != null) {
			json.put("askedFor", askedFor); //$NON-NLS-1$
		}
		if (!breakpoint.isInstalled()) {
			boolean sessionRunning = DebugSupport.anyLiveTarget();
			json.put("note", sessionRunning //$NON-NLS-1$
					? "The breakpoint is NOT installed although a debug session is running. For a line breakpoint this almost always means the chosen line carries no executable code, so it will never be hit. Move it to a statement." //$NON-NLS-1$
					: "The breakpoint is NOT installed yet because no debug session is running; that is expected and it will install when one starts. If it is still not installed then, the chosen line probably carries no executable code, and it will never be hit."); //$NON-NLS-1$
		}
		return McpToolResult.of(json.toString());
	}

	/**
	 * The file a breakpoint is attached to: the source file where the workspace has
	 * the type, the workspace root otherwise, which is what JDIDebugModel expects
	 * for a type that lives in a jar.
	 */
	static IResource resourceForType(String typeName) {
		for (org.eclipse.core.resources.IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (!project.isAccessible()) {
				continue;
			}
			try {
				if (!project.hasNature(JavaCore.NATURE_ID)) {
					continue;
				}
				IJavaProject javaProject = JavaCore.create(project);
				IType type = javaProject.findType(typeName);
				// a binary hit may be build output or a jar; neither gives a file to attach to,
				// so only a source compilation unit counts as found
				if (type != null && type.getCompilationUnit() != null && type.getResource() != null) {
					return type.getResource();
				}
			} catch (CoreException e) {
				// this project cannot answer; the next one might
			}
		}
		return ResourcesPlugin.getWorkspace().getRoot();
	}
}
