package com.vogella.eclipse.mcp.debug.internal;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.eval.EvaluationManager;
import org.eclipse.jdt.debug.eval.IAstEvaluationEngine;
import org.eclipse.jdt.debug.eval.IEvaluationListener;
import org.eclipse.jdt.debug.eval.IEvaluationResult;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Evaluates a Java expression in a stack frame of the debugged program, through
 * the AST evaluation engine JDT's own display view uses.
 */
public final class EvaluateTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_debug_evaluate"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Evaluates a Java expression in a stack frame of the suspended debugged program and reports its value. RUNS CODE INSIDE THE DEBUGGED PROGRAM: every side effect the expression has, field writes, method calls, IO, happens in that program. The thread has to be suspended. Compilation problems come back in 'problems' rather than as a tool error; a mistyped expression is the next question, not a broken server. Waits at most timeoutSeconds and reports timedOut when evaluation did not finish, which leaves it running in the background. Use eclipse_debug_get_frames to read variables instead of evaluating."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["expression"],
				  "properties": {
				    "sessionId":      {"type":"string","description":"The session to evaluate in. Omitted means the only live one."},
				    "expression":     {"type":"string","description":"Java expression or statement, evaluated against the selected frame."},
				    "thread":         {"type":"string","description":"Thread name, defaulting to the single suspended thread."},
				    "frame":          {"type":"integer","default":0,"minimum":0,"maximum":500,"description":"Stack frame index the expression is evaluated against."},
				    "timeoutSeconds": {"type":"integer","default":10,"minimum":1,"maximum":20,"description":"How long to wait for the result before reporting timedOut."},
				    "maxValueLength": {"type":"integer","default":500,"minimum":10,"maximum":100000,"description":"Cut the rendered value at this length."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String expression = args.getString("expression"); //$NON-NLS-1$
		if (expression == null) {
			return McpToolResult.error("The argument 'expression' is required."); //$NON-NLS-1$
		}
		int maxValueLength = args.getInt("maxValueLength", 500, 10, 100_000); //$NON-NLS-1$
		int timeout = CallBudget.boundedWaitSeconds(args.getInt("timeoutSeconds", 10, 1, 20)); //$NON-NLS-1$
		try {
			DebugSessionRegistry.Session session = DebugSupport.requireSession(args.getString("sessionId")); //$NON-NLS-1$
			var target = DebugSupport.target(session);
			IThread thread = DebugSupport.requireThread(target, args.getString("thread"));
			if (!(thread instanceof IJavaThread javaThread) || !javaThread.isSuspended()) {
				throw new DebugSupport.Refusal(
						"Evaluation needs a suspended thread; '%s' is not.".formatted(DebugSupport.name(thread))); //$NON-NLS-1$
			}
			IStackFrame[] stack = javaThread.getStackFrames();
			int frameIndex = args.getInt("frame", 0, 0, 500); //$NON-NLS-1$
			if (frameIndex >= stack.length || !(stack[frameIndex] instanceof IJavaStackFrame frame)) {
				throw new DebugSupport.Refusal("Frame %d does not exist; '%s' has %d frames.".formatted( //$NON-NLS-1$
						Integer.valueOf(frameIndex), DebugSupport.name(thread), Integer.valueOf(stack.length)));
			}
			IJavaProject project = projectOf(frame, session);
			if (project == null) {
				throw new DebugSupport.Refusal(
						"No workspace project can supply the types for this frame's class, so there is nothing to compile the expression against. The launch configuration names no project and no open Java project on the workspace resolves the declaring type; open the project that contains it, or evaluate in a frame whose class is in the workspace."); //$NON-NLS-1$
			}

			IAstEvaluationEngine engine = EvaluationManager.newAstEvaluationEngine(project,
					(IJavaDebugTarget) target);
			try {
				CountDownLatch done = new CountDownLatch(1);
				EvaluationResultBox box = new EvaluationResultBox();
				IEvaluationListener listener = result -> {
					box.result = result;
					done.countDown();
				};
				// explicit user-visible evaluation, not an implicit one like a watch expression
				engine.evaluate(expression, frame, listener, org.eclipse.debug.core.DebugEvent.EVALUATION, false);
				boolean finished = done.await(timeout, TimeUnit.SECONDS);
				if (!finished || box.result == null) {
					return McpToolResult.of(new JsonObject().put("sessionId", session.id()) //$NON-NLS-1$
							.put("expression", expression).put("timedOut", Boolean.TRUE) //$NON-NLS-1$ //$NON-NLS-2$
							.put("note", "No result within %d seconds; the evaluation may still be queued or running." //$NON-NLS-1$ //$NON-NLS-3$
									.formatted(Integer.valueOf(timeout)))
							.toString());
				}
				return McpToolResult.of(answer(session, expression, box.result, maxValueLength).toString());
			} finally {
				engine.dispose();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new McpToolException("Interrupted while waiting for the evaluation.", e);
		} catch (DebugException e) {
			throw new McpToolException("Could not evaluate '%s': %s".formatted(expression, e.getMessage()), e);
		} catch (DebugSupport.Refusal e) {
			return McpToolResult.error(e.getMessage());
		}
	}

	private static final class EvaluationResultBox {

		volatile IEvaluationResult result;
	}

	private JsonObject answer(DebugSessionRegistry.Session session, String expression, IEvaluationResult result,
			int maxValueLength) {
		JsonObject json = new JsonObject().put("sessionId", session.id()).put("expression", expression) //$NON-NLS-1$ //$NON-NLS-2$
				.put("timedOut", Boolean.FALSE); //$NON-NLS-1$
		JsonArray problems = new JsonArray();
		for (String message : result.getErrorMessages()) {
			problems.add(message);
		}
		json.put("problems", problems); //$NON-NLS-1$
		if (result.getException() != null) {
			String message = result.getException().getMessage();
			json.put("exception", message == null || message.isBlank() ? String.valueOf(result.getException()) //$NON-NLS-1$
					: message);
		}
		if (!result.hasErrors() && result.getValue() != null) {
			String full = DebugSupport.valueString(result.getValue(), Integer.MAX_VALUE);
			json.put("declaredType", referenceType(result)).put("valueTruncated", Boolean.valueOf(full.length() > maxValueLength)); //$NON-NLS-1$ //$NON-NLS-2$
			json.put("value", full.length() > maxValueLength ? full.substring(0, maxValueLength) : full);
		}
		return json;
	}

	private static String referenceType(IEvaluationResult result) {
		try {
			return result.getValue().getReferenceTypeName();
		} catch (DebugException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * The project whose classpath can compile an expression against this frame.
	 * <p>
	 * The launch configuration is asked first, because it names the project the
	 * caller launched and answers without touching the workspace. The scan is the
	 * fallback for a session this server did not start.
	 */
	static IJavaProject projectOf(IJavaStackFrame frame, DebugSessionRegistry.Session session) {
		IJavaProject fromLaunch = launchProject(session);
		if (fromLaunch != null) {
			return fromLaunch;
		}
		String typeName;
		try {
			typeName = frame.getDeclaringTypeName();
		} catch (org.eclipse.debug.core.DebugException e) {
			return null;
		}
		for (var project : org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			// one project that cannot answer must not end the scan: JavaCore.create
			// returns a handle for a project without the Java nature too, and findType
			// then throws. With the catch outside this loop, the first such project in
			// a large workspace made every evaluation fail.
			try {
				if (!project.isAccessible() || !project.hasNature(org.eclipse.jdt.core.JavaCore.NATURE_ID)) {
					continue;
				}
				IJavaProject javaProject = org.eclipse.jdt.core.JavaCore.create(project);
				if (javaProject != null && javaProject.findType(typeName) != null) {
					return javaProject;
				}
			} catch (org.eclipse.core.runtime.CoreException e) {
				continue;
			}
		}
		return null;
	}

	/** The project the launch configuration names, when it names one that exists. */
	private static IJavaProject launchProject(DebugSessionRegistry.Session session) {
		try {
			var launch = session.launch();
			var configuration = launch == null ? null : launch.getLaunchConfiguration();
			if (configuration == null) {
				return null;
			}
			String name = configuration.getAttribute(
					org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, (String) null);
			if (name == null || name.isBlank()) {
				return null;
			}
			var project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProject(name);
			if (!project.isAccessible() || !project.hasNature(org.eclipse.jdt.core.JavaCore.NATURE_ID)) {
				return null;
			}
			return org.eclipse.jdt.core.JavaCore.create(project);
		} catch (org.eclipse.core.runtime.CoreException | RuntimeException e) {
			return null;
		}
	}
}
