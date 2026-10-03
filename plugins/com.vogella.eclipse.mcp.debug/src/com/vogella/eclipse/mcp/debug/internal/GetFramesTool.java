package com.vogella.eclipse.mcp.debug.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaValue;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The stack of a suspended thread and the variables of one frame, one level of
 * the object graph per call.
 */
public final class GetFramesTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_debug_get_frames"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reads the stack of one suspended thread (frameTotal, framesTruncated) and the variables of one frame (total, truncated): index, declaring type, method with its signature, line, source file, native flag, and for the selected frame every variable with name, declared type, value, hasChildren and the runtime type when it differs from the declared one. Read only. 'variablePath' expands one level deeper into an object or array per call, e.g. this.buffer.count; the tool never walks an object graph on its own, because a live graph is unbounded. Values are cut at maxValueLength and marked valueTruncated. Refuses with the suspended threads when several are stopped and none is named. Use eclipse_debug_evaluate to compute something instead of reading fields."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "sessionId":      {"type":"string","description":"The session to read. Omitted means the only live one."},
				    "thread":         {"type":"string","description":"Thread name, defaulting to the single suspended thread. Refused with the list when several are suspended and none is named."},
				    "frame":          {"type":"integer","default":0,"minimum":0,"maximum":500,"description":"Stack frame index, 0 being the top."},
				    "variablePath":   {"type":"string","description":"Dotted path from the frame's variables into the object graph, e.g. this.buffer.count. Numeric segments address array elements."},
				    "maxResults":     {"type":"integer","default":100,"minimum":1,"maximum":2000,"description":"Frames reported, and variables reported for the selected frame."},
				    "maxValueLength": {"type":"integer","default":500,"minimum":10,"maximum":100000,"description":"Cut every rendered value at this length."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		int maxResults = args.getInt("maxResults", 100, 1, 2000); //$NON-NLS-1$
		int maxValueLength = args.getInt("maxValueLength", 500, 10, 100_000); //$NON-NLS-1$
		try {
			DebugSessionRegistry.Session session = DebugSupport.requireSession(args.getString("sessionId")); //$NON-NLS-1$
			var target = DebugSupport.target(session);
			IThread thread = DebugSupport.requireThread(target, args.getString("thread"));
			int frameIndex = args.getInt("frame", 0, 0, 500); //$NON-NLS-1$
			IJavaStackFrame frame = frame(thread, frameIndex);

			JsonArray frames = new JsonArray();
			List<IStackFrame> stack = stackOf(thread);
			for (int i = 0; i < stack.size() && frames.size() < maxResults; i++) {
				if (stack.get(i) instanceof IJavaStackFrame javaFrame) {
					frames.add(frameJson(i, javaFrame));
				}
			}
			JsonObject json = new JsonObject().put("sessionId", session.id()) //$NON-NLS-1$
					.put("thread", DebugSupport.name(thread)).put("frames", frames) //$NON-NLS-1$ //$NON-NLS-2$
					.put("frameTotal", Integer.valueOf(stack.size())) //$NON-NLS-1$
					.put("framesTruncated", Boolean.valueOf(frames.size() < stack.size())); //$NON-NLS-1$

			String path = args.getString("variablePath"); //$NON-NLS-1$
			IVariable[] shown;
			if (frame.isNative()) {
				// a native frame has no variables and asking throws; the frame list is still needed
				// to pick another (the idle SWT event loop tops out in gtk.OS.Call)
				return McpToolResult.of(json.put("variables", new JsonArray()) //$NON-NLS-1$
						.put("variablesUnavailable", //$NON-NLS-1$
								"Frame %d is a native method, which carries no variable information. The frames above are listed; pass 'frame' with one of them to read variables." //$NON-NLS-1$
										.formatted(Integer.valueOf(frameIndex)))
						.toString());
			}
			if (path == null) {
				shown = frame.getVariables();
			} else {
				IVariable selected = resolve(frame, path);
				if (selected == null) {
					throw new DebugSupport.Refusal(
							"No variable '%s' in this frame. Top level names: %s".formatted(path, //$NON-NLS-1$
									namesOf(frame.getVariables())));
				}
				shown = children(selected);
			}
			JsonArray variables = new JsonArray();
			for (IVariable child : shown) {
				if (variables.size() >= maxResults) {
					break;
				}
				variables.add(variableJson(child, maxValueLength));
			}
			json.put("variablePath", path).put("variables", variables) //$NON-NLS-1$ //$NON-NLS-2$
					.put("total", Integer.valueOf(shown.length)) //$NON-NLS-1$
					.put("truncated", Boolean.valueOf(variables.size() < shown.length)); //$NON-NLS-1$
			return McpToolResult.of(json.toString());
		} catch (DebugException e) {
			throw new McpToolException("Could not read the frames: %s".formatted(e.getMessage()), e);
		} catch (DebugSupport.Refusal e) {
			return McpToolResult.error(e.getMessage());
		}
	}

	private IJavaStackFrame frame(IThread thread, int index) throws DebugException, McpToolException {
		List<IStackFrame> stack = stackOf(thread);
		if (stack.isEmpty()) {
			throw new McpToolException(
					"Thread '%s' has no stack; is it really suspended?".formatted(DebugSupport.name(thread))); //$NON-NLS-1$
		}
		if (index >= stack.size()) {
			throw new McpToolException("Frame %d does not exist; '%s' has %d frames.".formatted( //$NON-NLS-1$
					Integer.valueOf(index), DebugSupport.name(thread), Integer.valueOf(stack.size())));
		}
		return (IJavaStackFrame) stack.get(index);
	}

	private static List<IStackFrame> stackOf(IThread thread) throws DebugException {
		return thread.isSuspended() ? List.of(thread.getStackFrames()) : List.of();
	}

	private static JsonObject frameJson(int index, IJavaStackFrame frame) throws DebugException {
		JsonArray argumentTypes = new JsonArray();
		frame.getArgumentTypeNames().forEach(argumentTypes::add);
		JsonObject json = new JsonObject().put("index", Integer.valueOf(index)) //$NON-NLS-1$
				.put("declaringType", frame.getDeclaringTypeName()).put("method", frame.getMethodName()) //$NON-NLS-1$ //$NON-NLS-2$
				.put("argumentTypes", argumentTypes).put("line", Integer.valueOf(frame.getLineNumber())) //$NON-NLS-1$ //$NON-NLS-2$
				.put("sourceFile", frame.getSourceName()) //$NON-NLS-1$
				.put("native", Boolean.valueOf(frame.isNative())); //$NON-NLS-1$
		return json;
	}

	private static JsonObject variableJson(IVariable variable, int maxValueLength) {
		String declaredType = null;
		String runtimeType = null;
		String value = "<unreadable>"; //$NON-NLS-1$
		boolean hasChildren = false;
		boolean valueTruncated = false;
		try {
			declaredType = variable.getReferenceTypeName();
			hasChildren = variable.getValue().hasVariables();
			if (variable.getValue() instanceof IJavaValue javaValue) {
				String full = javaValue.getValueString();
				valueTruncated = full.length() > maxValueLength;
				value = DebugSupport.truncate(full, maxValueLength);
				runtimeType = javaValue.getReferenceTypeName();
			}
		} catch (DebugException | RuntimeException e) {
			// an unreadable variable still appears, with what could be said about it
		}
		JsonObject json = new JsonObject().put("name", variableName(variable)).put("declaredType", declaredType) //$NON-NLS-1$
				.put("value", value).put("hasChildren", Boolean.valueOf(hasChildren)); //$NON-NLS-1$ //$NON-NLS-2$
		if (runtimeType != null && !runtimeType.equals(declaredType)) {
			json.put("runtimeType", runtimeType); //$NON-NLS-1$
		}
		if (valueTruncated) {
			json.put("valueTruncated", Boolean.TRUE); //$NON-NLS-1$
		}
		return json;
	}

	private static String variableName(IVariable variable) {
		try {
			return variable.getName();
		} catch (DebugException e) {
			return "?"; //$NON-NLS-1$
		}
	}

	private static IVariable[] children(IVariable variable) {
		try {
			return variable.getValue().getVariables();
		} catch (DebugException | RuntimeException e) {
			return new IVariable[0];
		}
	}

	private static String namesOf(IVariable[] variables) {
		List<String> names = new ArrayList<>();
		for (IVariable variable : variables) {
			names.add(variableName(variable));
		}
		return String.join(", ", names); //$NON-NLS-1$
	}

	/**
	 * Walks a dotted path through frame locals and then field by field, array
	 * elements addressed as {@code 3} or {@code [3]}. Only the addressed level is
	 * ever returned; descending further needs another call.
	 */
	private static IVariable resolve(IJavaStackFrame frame, String path) throws DebugException {
		String[] segments = path.split("\\."); //$NON-NLS-1$
		IVariable current = findByName(List.of(frame.getVariables()), segments[0]);
		for (int i = 1; i < segments.length && current != null; i++) {
			current = findByName(List.of(children(current)), segments[i]);
		}
		return current;
	}

	private static IVariable findByName(List<IVariable> candidates, String name) {
		for (IVariable candidate : candidates) {
			if (variableName(candidate).equals(name)) {
				return candidate;
			}
		}
		// array element variables are named "[0]", so a bare number addresses them
		String bracketed = "[" + name + "]"; //$NON-NLS-1$ //$NON-NLS-2$
		for (IVariable candidate : candidates) {
			if (variableName(candidate).equals(bracketed)) {
				return candidate;
			}
		}
		return null;
	}
}
