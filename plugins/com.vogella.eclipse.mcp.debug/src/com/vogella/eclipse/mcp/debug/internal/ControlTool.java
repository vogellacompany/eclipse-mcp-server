package com.vogella.eclipse.mcp.debug.internal;

import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Resumes, steps, suspends, terminates or disconnects a debug session, and
 * reports where the program ended up.
 */
public final class ControlTool implements IMcpTool {

	private static final Set<String> ACTIONS = Set.of("resume", "stepOver", "stepInto", "stepReturn", "suspend", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"terminate", "disconnect", "close", "resumeAll"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	@Override
	public String getName() {
		return "eclipse_debug_control"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Drives a suspended or running debug session: resume, stepOver, stepInto, stepReturn, suspend, resumeAll, terminate, disconnect or close. SUSPEND STOPS EVERY THREAD, and resume then refuses because several are suspended; resumeAll is the way back. CHANGES THE STATE OF THE DEBUGGED PROGRAM, and terminate KILLS THE PROCESS: that is Process.destroy, SIGTERM on Linux, where Equinox's shutdown hook still runs, and an immediate kill on Windows, and in neither case does the workbench save its workspace. 'close' is the orderly alternative for a debugged application that HAS a workbench: it stops the UI thread at a breakpoint, which JDI requires before any method can be invoked in it, evaluates an asyncExec of IWorkbench.close and resumes so that thread runs it, which is a real shutdown with a workspace save, and it falls back to terminate only if that does not finish in time. The breakpoint it sets is its own and is removed again. The answer says which of the two actually happened, because a measurement taken afterwards depends on it. After a step or a resume it waits for the next suspend (waitForSuspendSeconds) and reports the new location in the same answer, so stepping costs one call; timedOut on resume normally just means the program kept running. Stepping needs a suspended thread: name one with 'thread' or leave it to the only suspended thread, which is refused when several are stopped. A session launched in run mode has no debug target, so terminate is the only action that works on it and the others say so. Use eclipse_debug_launch to start something first."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["action"],
				  "properties": {
				    "sessionId":            {"type":"string","description":"The session to drive. Omitted means the only live one."},
				    "action":               {"type":"string","enum":["resume","resumeAll","stepOver","stepInto","stepReturn","suspend","terminate","disconnect","close"],"description":"What to do. 'resume' continues one thread and is refused when several are suspended; 'resumeAll' continues the whole VM, which is the way back from 'suspend' since that stops every thread. 'terminate' kills the process; 'close' asks the application to shut itself down first and only kills it if that does not work."},
				    "closeWaitSeconds":     {"type":"integer","default":20,"minimum":1,"maximum":120,"description":"For 'close': how long to wait for the application to be gone before falling back."},
				    "fallbackToTerminate":  {"type":"boolean","default":true,"description":"For 'close': kill the process when it has not closed within closeWaitSeconds. False leaves it running and says so."},
				    "breakpoint":           {"type":"string","description":"For 'close': typeName:line where the UI thread is stopped so that a method can be invoked in it, or 'none' to use a thread that is already stopped at one. The default is org.eclipse.ui.application.WorkbenchAdvisor:339, the display.sleep() of eventLoopIdle, whose own javadoc says calling IWorkbench.close() from there is allowed and which every workbench application reaches whenever its event loop goes idle. Name another one for an RCP application whose advisor does not call super, or when a platform version has moved the line. The breakpoint is removed again afterwards."},
				    "breakpointWaitSeconds":{"type":"integer","default":60,"minimum":1,"maximum":300,"description":"For 'close': how long to wait for that breakpoint to be reached. A runtime workbench takes about fifty seconds to start, so the default allows for a launch that is still coming up."},
				    "thread":               {"type":"string","description":"Thread to act on. Defaults to the single suspended thread for the stepping actions."},
				    "waitForSuspendSeconds":{"type":"integer","default":20,"minimum":0,"maximum":25,"description":"How long to wait for the next suspend after resume and the steps. 0 answers immediately. The maximum is 25 seconds while a runtime workbench takes about fifty to start, so waiting for the first breakpoint of one regularly needs a second call; that is normal and not a failure."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String action = args.getString("action"); //$NON-NLS-1$
		if (action == null || !ACTIONS.contains(action)) {
			return McpToolResult.error("'action' is one of %s%s.".formatted(String.join(", ", ACTIONS.stream().sorted().toList()), //$NON-NLS-1$ //$NON-NLS-2$
					action == null ? "" : ", not '" + action + "'")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		boolean waitAfter = action.equals("resume") || action.startsWith("step"); //$NON-NLS-1$ //$NON-NLS-2$
		int waitSeconds = args.getInt("waitForSuspendSeconds", waitAfter ? 20 : 0, 0, 25); //$NON-NLS-1$

		try {
			DebugSessionRegistry.Session session = DebugSupport.requireSession(args.getString("sessionId")); //$NON-NLS-1$
			// terminate acts on the launch, which a run mode session has while it has
			// no debug target at all. Demanding one up front refused to end a process
			// that was still running, and the refusal said it had ended
			IDebugTarget target = DebugSupport.liveTarget(session);
			if (target == null && !action.equals("terminate")) { //$NON-NLS-1$
				throw DebugSupport.noTarget(session);
			}
			IThread thread = null;
			if (action.equals("close")) { //$NON-NLS-1$
				return McpToolResult.of(GracefulClose
						.close(session, args.getInt("closeWaitSeconds", 20, 1, 120), //$NON-NLS-1$
								args.getBoolean("fallbackToTerminate", true), //$NON-NLS-1$
								args.getString("breakpoint"), //$NON-NLS-1$
								args.getInt("breakpointWaitSeconds", 60, 1, 300)) //$NON-NLS-1$
						.toString());
			}
			if (!action.equals("terminate") && !action.equals("disconnect") && !action.equals("resumeAll")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				thread = DebugSupport.requireThread(target, args.getString("thread")); //$NON-NLS-1$
			}
			DebugSessionRegistry.SuspendSignal signal = null;
			if (waitSeconds > 0) {
				signal = DebugSessionRegistry.getInstance().onNextSuspend(session);
			}
			perform(session, target, thread, action);

			boolean arrived = signal != null && signal.await(CallBudget.boundedWaitSeconds(waitSeconds));
			JsonObject json = DebugSupport.sessionJson(session, 50);
			json.put("action", action); //$NON-NLS-1$
			if (signal != null) {
				boolean nowSuspended = session.suspended();
				if (!arrived && !nowSuspended) {
					json.put("timedOut", Boolean.TRUE).put("waitNote", //$NON-NLS-1$ //$NON-NLS-2$
							action.equals("resume") //$NON-NLS-1$
									? "No suspend within %d seconds; the program most likely kept running.".formatted(Integer.valueOf(waitSeconds)) //$NON-NLS-2$
									: "No suspend within %d seconds.".formatted(Integer.valueOf(waitSeconds))); //$NON-NLS-1$
				} else {
					json.put("timedOut", Boolean.FALSE); //$NON-NLS-1$
					json.put("location", locationOf(session)); //$NON-NLS-1$
				}
			}
			return McpToolResult.of(json.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new McpToolException("Interrupted while waiting for the suspend.", e);
		} catch (DebugException e) {
			throw new McpToolException("Could not %s: %s".formatted(action, e.getMessage()), e);
		} catch (DebugSupport.Refusal e) {
			return McpToolResult.error(e.getMessage());
		}
	}

	private static String locationOf(DebugSessionRegistry.Session session) {
		var target = DebugSupport.liveTarget(session);
		if (target == null) {
			return null;
		}
		for (IThread candidate : DebugSupport.threads(target)) {
			if (candidate.isSuspended()) {
				String location = DebugSupport.location(candidate);
				if (location != null) {
					return location;
				}
			}
		}
		return null;
	}

	private static void perform(DebugSessionRegistry.Session session, IDebugTarget target, IThread thread, String action)
			throws DebugException {
		switch (action) {
		case "resumeAll" -> target.resume(); //$NON-NLS-1$
		case "resume" -> { //$NON-NLS-1$
			if (thread != null && thread.isSuspended()) {
				thread.resume();
			} else if (!target.isSuspended()) {
				throw new DebugSupport.Refusal("Nothing is suspended here; there is nothing to resume."); //$NON-NLS-1$
			} else {
				target.resume();
			}
		}
		case "suspend" -> { //$NON-NLS-1$
			if (target.isSuspended()) {
				throw new DebugSupport.Refusal("The program is already suspended."); //$NON-NLS-1$
			}
			target.suspend();
		}
		case "stepOver" -> { //$NON-NLS-1$
			if (!thread.canStepOver()) {
				throw cannot("step over"); //$NON-NLS-1$
			}
			thread.stepOver();
		}
		case "stepInto" -> { //$NON-NLS-1$
			if (!thread.canStepInto()) {
				throw cannot("step into"); //$NON-NLS-1$
			}
			thread.stepInto();
		}
		case "stepReturn" -> { //$NON-NLS-1$
			if (!thread.canStepReturn()) {
				throw cannot("step return"); //$NON-NLS-1$
			}
			thread.stepReturn();
		}
		case "terminate" -> terminate(session); //$NON-NLS-1$
		case "disconnect" -> target.disconnect(); //$NON-NLS-1$
		default -> throw new IllegalStateException(action);
		}
	}

	private static DebugSupport.Refusal cannot(String what) {
		return new DebugSupport.Refusal(
				"Cannot %s from here; the thread has to be suspended at a steppable frame.".formatted(what)); //$NON-NLS-1$
	}

	private static void terminate(DebugSessionRegistry.Session session) throws DebugException {
		var launch = session.launch();
		if (launch == null) {
			throw new DebugSupport.Refusal("This session has nothing running to terminate."); //$NON-NLS-1$
		}
		if (!launch.canTerminate()) {
			throw new DebugSupport.Refusal("This session cannot be terminated; it has probably ended already."); //$NON-NLS-1$
		}
		launch.terminate();
	}
}
