package com.vogella.eclipse.mcp.debug.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugTarget;
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
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Asks a debugged application to shut itself down, instead of killing it.
 * <p>
 * Terminating a launch calls {@code Process.destroy}, which on Linux is SIGTERM
 * and happens to reach Equinox's shutdown hook, and on Windows is an immediate
 * TerminateProcess. Neither runs the workbench's own shutdown, so the workspace
 * is not saved and the next start of the same instance finds whatever the last
 * one left behind.
 * <p>
 * The debugger can do better without anything being configured in the target:
 * suspend a thread, evaluate a call that queues the close on the target's UI
 * thread, and resume so the UI thread runs it. The queueing is the point.
 * {@code IWorkbench.close} must run on the UI thread, while an evaluation runs
 * on the thread that is suspended, so calling it directly would be wrong; an
 * asyncExec is only put in the queue and is worked off once the program runs
 * again.
 */
final class GracefulClose {

	/**
	 * Where the UI thread is stopped so that a method can be invoked in it.
	 * <p>
	 * The method's own javadoc says that calling {@code IWorkbench.close()} from
	 * here is allowed, so this is not a convenient accident. It is a named public
	 * class in org.eclipse.ui.application, so the expression compiles and JDT
	 * accepts the lambda, and every workbench application passes through it
	 * whenever its event loop goes idle. The line is the {@code display.sleep()}
	 * in the body, and it is the one thing here that a platform version can move.
	 */
	private static final String DEFAULT_BREAKPOINT = "org.eclipse.ui.application.WorkbenchAdvisor:339"; //$NON-NLS-1$

	/** Queued rather than called: the evaluation is not on the target's UI thread. */
	private static final String CLOSE_WORKBENCH = "org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> org.eclipse.ui.PlatformUI.getWorkbench().close())"; //$NON-NLS-1$

	private static final int EVALUATION_SECONDS = 15;

	private GracefulClose() {
	}

	/**
	 * Suspends, asks the application to close, resumes and waits for it to go.
	 *
	 * @param fallback terminate when the application is still there at the end
	 */
	static JsonObject close(DebugSessionRegistry.Session session, int waitSeconds, boolean fallback, String breakpointSpec,
			int breakpointWaitSeconds) throws DebugException, InterruptedException {
		JsonObject json = new JsonObject().put("sessionId", session.id()); //$NON-NLS-1$
		long started = System.nanoTime();
		if (breakpointWaitSeconds > CallBudget.maxWaitSeconds() || waitSeconds > CallBudget.maxWaitSeconds()) {
			json.put("waitNote", //$NON-NLS-1$
					"A wait was cut to what fits in one call. The application may still be starting: call close again when it has come up."); //$NON-NLS-1$
			breakpointWaitSeconds = CallBudget.boundedWaitSeconds(breakpointWaitSeconds);
			waitSeconds = CallBudget.boundedWaitSeconds(waitSeconds);
		}
		ILaunch launchValue = session.launch();
		IDebugTarget target = DebugSupport.target(session);
		if (!(target instanceof IJavaDebugTarget)) {
			return refuse(json, "This is not a Java debug target, so nothing can be evaluated in it."); //$NON-NLS-1$
		}

		IJavaThread thread = breakpointSuspendedThread(target);
		org.eclipse.jdt.debug.core.IJavaLineBreakpoint ours = null;
		try {
			if (thread == null && !"none".equals(breakpointSpec)) { //$NON-NLS-1$
				String spec = breakpointSpec == null ? DEFAULT_BREAKPOINT : breakpointSpec;
				ours = install(spec, json);
				if (ours == null) {
					return json;
				}
				thread = awaitBreakpoint(target, breakpointWaitSeconds);
				// what the breakpoint wait used is gone from the exit wait
				waitSeconds = (int) Math.max(1, Math.min(waitSeconds,
						CallBudget.maxWaitSeconds() - (System.nanoTime() - started) / 1_000_000_000L - EVALUATION_SECONDS));
				if (thread == null) {
					return refuse(json.put("breakpoint", spec) //$NON-NLS-1$
							.put("breakpointHit", Boolean.FALSE), //$NON-NLS-1$
							"The breakpoint was never reached within %d seconds, so nothing could be evaluated and nothing was killed. A workbench passes through it whenever its event loop goes idle, so either the application is still starting, or it is an RCP application whose advisor overrides eventLoopIdle without calling super, or this platform version has moved the line. Name another place with 'breakpoint' as typeName:line." //$NON-NLS-1$
									.formatted(Integer.valueOf(breakpointWaitSeconds)));
				}
				json.put("breakpoint", spec).put("breakpointHit", Boolean.TRUE); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (thread == null) {
				return refuse(json,
						"No thread of this session is suspended at a breakpoint, and none was to be set. A method cannot be invoked in a thread that was merely suspended: JDI allows it only for a thread stopped BY A BREAKPOINT OR A STEP, and eclipse_debug_control action suspend does not qualify."); //$NON-NLS-1$
			}
			return withThread(session, json, launchValue, target, thread, waitSeconds, fallback);
		} finally {
			// a breakpoint this call set is this call's to remove, or the person at the
			// IDE keeps one they never asked for
			remove(ours);
		}
	}

	private static JsonObject withThread(DebugSessionRegistry.Session session, JsonObject json, ILaunch launchValue,
			IDebugTarget target, IJavaThread thread, int waitSeconds, boolean fallback)
			throws DebugException, InterruptedException {
		json.put("suspendedByBreakpoint", Boolean.valueOf(thread.getBreakpoints().length > 0)); //$NON-NLS-1$
		json.put("suspendedThread", DebugSupport.name(thread)); //$NON-NLS-1$

		try {
			IJavaStackFrame frame = workbenchFrame(thread);
			if (frame == null) {
				return refuse(json,
						"No frame of this thread belongs to a named class that can see org.eclipse.ui. The top frame of an idle workbench is org.eclipse.swt.internal.gtk.OS.Call, whose class loader does not see the workbench at all, and an anonymous class such as Workbench$1 cannot compile the lambda. Suspend at a breakpoint in a named org.eclipse.ui class and call close again."); //$NON-NLS-1$
			}
			json.put("frame", DebugSupport.location(frame)); //$NON-NLS-1$
			IJavaProject project = EvaluateTool.projectOf(frame, session);
			if (project == null) {
				return refuse(json,
						"No workspace project supplies the types of the suspended frame, so the expression cannot be compiled against it. That is the same limit eclipse_debug_evaluate has."); //$NON-NLS-1$
			}
			IEvaluationResult result = evaluate(project, (IJavaDebugTarget) target, frame);
			if (result == null) {
				return refuse(json,
						"The close call did not finish evaluating in the target within %d seconds, so it was not queued.".formatted(Integer.valueOf(EVALUATION_SECONDS))); //$NON-NLS-1$
			}
			if (result.hasErrors()) {
				return refuse(json.put("evaluationErrors", String.join("; ", result.getErrorMessages())), //$NON-NLS-1$ //$NON-NLS-2$
						"The close call did not compile in the target. A program without a workbench cannot be closed this way; stop its system bundle instead."); //$NON-NLS-1$
			}
			json.put("closeQueued", Boolean.TRUE); //$NON-NLS-1$
		} finally {
			// resume whatever the state was: the queued close only runs once the UI
			// thread is free again, so leaving it suspended would hang the shutdown
			if (thread.isSuspended()) {
				thread.resume();
			}
		}

		boolean gone = waitForExit(launchValue, waitSeconds);
		json.put("closedGracefully", Boolean.valueOf(gone)) //$NON-NLS-1$
				.put("waitedSeconds", Integer.valueOf(waitSeconds)); //$NON-NLS-1$
		if (gone) {
			return json.put("terminatedInstead", Boolean.FALSE) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"The application ran its own shutdown, so the workspace was saved and the framework stopped in order. A recording written on exit is complete."); //$NON-NLS-1$
		}
		if (!fallback) {
			return json.put("terminatedInstead", Boolean.FALSE) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"It was still running when the wait ran out and nothing was killed, so it may yet close on its own, or it may be waiting on a dialog of its own that nothing here can answer."); //$NON-NLS-1$
		}
		DebugSessionRegistry.terminateAndWait(session, 10);
		return json.put("terminatedInstead", Boolean.TRUE) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"It did not close in time and was terminated, which is Process.destroy: SIGTERM on Linux, where Equinox's shutdown hook still runs, and an immediate kill on Windows. Either way the workbench shutdown did not happen, so treat the state it left as unsaved."); //$NON-NLS-1$
	}

	private static JsonObject refuse(JsonObject json, String why) {
		return json.put("closedGracefully", Boolean.FALSE).put("refusedBecause", why); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static IEvaluationResult evaluate(IJavaProject project, IJavaDebugTarget target, IJavaStackFrame frame)
			throws InterruptedException, DebugException {
		IAstEvaluationEngine engine = EvaluationManager.newAstEvaluationEngine(project, target);
		try {
			CountDownLatch done = new CountDownLatch(1);
			IEvaluationResult[] box = new IEvaluationResult[1];
			IEvaluationListener listener = result -> {
				box[0] = result;
				done.countDown();
			};
			engine.evaluate(CLOSE_WORKBENCH, frame, listener, org.eclipse.debug.core.DebugEvent.EVALUATION, false);
			return done.await(EVALUATION_SECONDS, TimeUnit.SECONDS) ? box[0] : null;
		} finally {
			engine.dispose();
		}
	}

	/**
	 * A thread stopped AT A BREAKPOINT, which is the only kind a method can be
	 * invoked in. A thread suspended by the VM looks the same and is not.
	 */
	private static IJavaThread breakpointSuspendedThread(IDebugTarget target) {
		for (IThread thread : DebugSupport.threads(target)) {
			if (thread instanceof IJavaThread java && java.isSuspended() && java.getBreakpoints().length > 0) {
				return java;
			}
		}
		return null;
	}

	/** Sets the temporary breakpoint, reporting into {@code json} when it cannot. */
	private static org.eclipse.jdt.debug.core.IJavaLineBreakpoint install(String spec, JsonObject json) {
		int colon = spec.lastIndexOf(':');
		if (colon <= 0) {
			refuse(json, "'breakpoint' is typeName:line, such as %s, or 'none'.".formatted(DEFAULT_BREAKPOINT)); //$NON-NLS-1$
			return null;
		}
		String typeName = spec.substring(0, colon);
		int line;
		try {
			line = Integer.parseInt(spec.substring(colon + 1).strip());
		} catch (NumberFormatException e) {
			refuse(json, "'%s' has no line number after the colon.".formatted(spec)); //$NON-NLS-1$
			return null;
		}
		try {
			org.eclipse.jdt.debug.core.IJavaLineBreakpoint breakpoint = org.eclipse.jdt.debug.core.JDIDebugModel
					.createLineBreakpoint(SetBreakpointTool.resourceForType(typeName), typeName, line, -1, -1, 0, true,
							null);
			// the UI thread alone: closing needs the other threads to keep running
			breakpoint.setSuspendPolicy(org.eclipse.jdt.debug.core.IJavaBreakpoint.SUSPEND_THREAD);
			return breakpoint;
		} catch (org.eclipse.core.runtime.CoreException e) {
			refuse(json, "Could not set the breakpoint at %s: %s".formatted(spec, e.getMessage())); //$NON-NLS-1$
			return null;
		}
	}

	private static void remove(org.eclipse.jdt.debug.core.IJavaLineBreakpoint breakpoint) {
		if (breakpoint != null) {
			try {
				breakpoint.delete();
			} catch (org.eclipse.core.runtime.CoreException e) {
				// leaving it is untidy; failing the close over it would be worse
			}
		}
	}

	private static IJavaThread awaitBreakpoint(IDebugTarget target, int seconds) throws InterruptedException {
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < deadline) {
			IJavaThread thread = breakpointSuspendedThread(target);
			if (thread != null) {
				return thread;
			}
			if (target.isTerminated()) {
				return null;
			}
			Thread.sleep(200);
		}
		return breakpointSuspendedThread(target);
	}

	/**
	 * A frame the close expression can actually be compiled and run against.
	 * <p>
	 * Two things rule most frames out, and both were measured rather than guessed.
	 * The type of the frame decides which class loader the expression is compiled
	 * against, so a frame in org.eclipse.swt cannot see org.eclipse.ui at all and
	 * reports it as an unresolved type, which reads like a missing workbench and is
	 * not one. And JDT refuses a lambda inside a local or anonymous class, so
	 * Workbench$1, where the idle event loop lives, is unusable even though it is
	 * the frame a breakpoint most easily reaches.
	 */
	private static IJavaStackFrame workbenchFrame(IJavaThread thread) throws DebugException {
		IJavaStackFrame fallback = null;
		for (var candidate : thread.getStackFrames()) {
			if (!(candidate instanceof IJavaStackFrame frame)) {
				continue;
			}
			String type = frame.getDeclaringTypeName();
			if (type == null || anonymous(type)) {
				continue;
			}
			if (type.startsWith("org.eclipse.ui.")) { //$NON-NLS-1$
				return frame;
			}
			if (fallback == null && (type.startsWith("org.eclipse.e4.ui.") //$NON-NLS-1$
					|| type.startsWith("org.eclipse.equinox.launcher."))) { //$NON-NLS-1$
				fallback = frame;
			}
		}
		return fallback;
	}

	/** {@code Workbench$1} and friends, where a lambda cannot be compiled. */
	private static boolean anonymous(String typeName) {
		int dollar = typeName.lastIndexOf('$');
		return dollar >= 0 && dollar + 1 < typeName.length() && Character.isDigit(typeName.charAt(dollar + 1));
	}

	private static boolean waitForExit(ILaunch launchValue, int seconds) throws InterruptedException {
		if (launchValue == null) {
			return false;
		}
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < deadline) {
			if (launchValue.isTerminated()) {
				return true;
			}
			Thread.sleep(200);
		}
		return launchValue.isTerminated();
	}
}
