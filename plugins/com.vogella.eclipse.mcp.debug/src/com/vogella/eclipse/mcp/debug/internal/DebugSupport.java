package com.vogella.eclipse.mcp.debug.internal;

import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.jdt.debug.core.IJavaBreakpoint;
import org.eclipse.jdt.debug.core.IJavaExceptionBreakpoint;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaValue;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.debug.internal.DebugSessionRegistry.Session;

/**
 * Shared lookups and renderings for the debug tools: session resolution, thread
 * selection and the JSON shape every session tool answers with.
 */
final class DebugSupport {

	private DebugSupport() {
	}

	/** Thrown for an answerable refusal; the tool reports it as an error result. */
	static final class Refusal extends RuntimeException {

		private static final long serialVersionUID = 1L;

		Refusal(String message) {
			super(message);
		}
	}


	/**
	 * Resolves a session by id, or the single live one when no id is given, and
	 * refuses with what exists instead of guessing.
	 */
	static Session requireSession(String sessionId) {
		DebugSessionRegistry registry = DebugSessionRegistry.getInstance();
		if (sessionId != null) {
			Session session = registry.find(sessionId);
			if (session == null) {
				throw new Refusal("No debug session '%s'. Known ids, oldest first: %s".formatted(sessionId, //$NON-NLS-1$
						String.join(", ", registry.ids()))); //$NON-NLS-1$
			}
			return session;
		}
		List<Session> all = registry.all();
		Session single = registry.singleLive();
		if (single != null) {
			return single;
		}
		if (all.isEmpty()) {
			throw new Refusal(
					"No debug session exists. Start one through eclipse_debug_launch, or through eclipse_run_tests with debug true."); //$NON-NLS-1$
		}
		if (registry.liveCount() > 1) {
			throw new Refusal("Several sessions are live: %s. Pass 'sessionId' to name one." //$NON-NLS-1$
					.formatted(describeSessions(all)));
		}
		throw new Refusal("No live debug session; these have ended recently: %s".formatted(describeSessions(all))); //$NON-NLS-1$
	}

	static IDebugTarget target(Session session) {
		ILaunch launchValue = session.launch();
		if (launchValue == null) {
			throw new Refusal("Session %s has not come up yet.".formatted(session.id())); //$NON-NLS-1$
		}
		IDebugTarget live = liveTarget(session);
		if (live != null) {
			return live;
		}
		throw noTarget(session);
	}

	/** The session's live debug target, or {@code null} when it has none. */
	static IDebugTarget liveTarget(Session session) {
		ILaunch launchValue = session.launch();
		if (launchValue == null) {
			return null;
		}
		for (IDebugTarget candidate : launchValue.getDebugTargets()) {
			if (!candidate.isTerminated() && !candidate.isDisconnected()) {
				return candidate;
			}
		}
		return null;
	}

	/** Whether a process of this launch is still running. */
	static boolean running(Session session) {
		ILaunch launchValue = session.launch();
		if (launchValue == null) {
			return false;
		}
		for (org.eclipse.debug.core.model.IProcess process : launchValue.getProcesses()) {
			if (!process.isTerminated()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Why this session has no debug target. A launch in run mode never had one and
	 * is still running, which is a different answer from a program that has ended,
	 * and reporting the second for the first sent a caller looking for a process
	 * that was very much alive.
	 */
	static Refusal noTarget(Session session) {
		if (running(session)) {
			return new Refusal(
					"Session %s was launched in run mode, so it has no debug target: it can only be terminated, not suspended, stepped or evaluated in. Launch it again with mode 'debug' for those." //$NON-NLS-1$
							.formatted(session.id()));
		}
		return new Refusal("The program of session %s has ended; start a new one to continue." //$NON-NLS-1$
				.formatted(session.id()));
	}

	static List<IThread> threads(IDebugTarget targetValue) {
		try {
			return List.of(targetValue.getThreads());
		} catch (DebugException e) {
			return List.of();
		}
	}

	/**
	 * The named thread, or the only suspended one; an ambiguous or unknown request
	 * is refused with the candidates rather than guessed.
	 */
	static IThread requireThread(IDebugTarget targetValue, String threadArg) {
		List<IThread> list = threads(targetValue);
		if (list.isEmpty()) {
			throw new Refusal("This session exposes no threads."); //$NON-NLS-1$
		}
		if (threadArg != null) {
			for (IThread thread : list) {
				if (name(thread).equals(threadArg)) {
					return thread;
				}
			}
			throw new Refusal("No thread '%s'. Threads: %s".formatted(threadArg, describeThreads(list))); //$NON-NLS-1$
		}
		List<IThread> suspended = list.stream().filter(IThread::isSuspended).toList();
		if (suspended.isEmpty()) {
			throw new Refusal("Nothing is suspended here. Pass 'thread' to name one of: %s" //$NON-NLS-1$
					.formatted(describeThreads(list)));
		}
		if (suspended.size() > 1) {
			throw new Refusal("Several threads are suspended: %s. Pass 'thread' to name one." //$NON-NLS-1$
					.formatted(describeThreads(suspended)));
		}
		return suspended.get(0);
	}

	static String name(IThread thread) {
		try {
			return thread.getName();
		} catch (DebugException e) {
			return "thread-" + System.identityHashCode(thread); //$NON-NLS-1$
		}
	}

	/** {@code declaringType.method(File.java:123)}, or null when nothing is readable. */
	static String location(IThread thread) {
		IStackFrame frame = topFrame(thread);
		return frame instanceof IJavaStackFrame javaFrame ? location(javaFrame) : null;
	}

	static String location(IJavaStackFrame frame) {
		try {
			String type = frame.getDeclaringTypeName();
			String method = frame.getMethodName();
			String source = frame.getSourceName();
			int line = frame.getLineNumber();
			if (source == null) {
				return "%s.%s(Unknown Source)".formatted(type, method); //$NON-NLS-1$
			}
			return line >= 0 ? "%s.%s(%s:%d)".formatted(type, method, source, Integer.valueOf(line)) //$NON-NLS-1$
					: "%s.%s(%s)".formatted(type, method, source); //$NON-NLS-1$
		} catch (DebugException e) {
			return null;
		}
	}

	private static IStackFrame topFrame(IThread thread) {
		try {
			return thread.getTopStackFrame();
		} catch (DebugException e) {
			return null;
		}
	}

	/** Which breakpoint suspended the thread, read live where JDT reports it. */
	static String suspendReason(IThread thread, Session session) {
		try {
			IBreakpoint[] live = thread.getBreakpoints();
			if (live.length > 0) {
				return describe(live[0]);
			}
		} catch (RuntimeException e) {
			// fall through to what the suspend event recorded
		}
		if (session.suspendedThread() == thread && session.suspendBreakpoint() != null) {
			return describe(session.suspendBreakpoint());
		}
		return null;
	}

	static String describe(IBreakpoint breakpoint) {
		try {
			String kind = breakpoint instanceof IJavaLineBreakpoint ? "line" //$NON-NLS-1$
					: breakpoint instanceof IJavaExceptionBreakpoint ? "exception" : "breakpoint"; //$NON-NLS-1$ //$NON-NLS-2$
			StringBuilder text = new StringBuilder(kind);
			if (breakpoint instanceof IJavaBreakpoint java && java.getTypeName() != null) {
				text.append(" at ").append(java.getTypeName()); //$NON-NLS-1$
			}
			if (breakpoint instanceof ILineBreakpoint line) {
				text.append(':').append(line.getLineNumber());
			}
			return text.toString();
		} catch (CoreException e) {
			return "breakpoint"; //$NON-NLS-1$
		}
	}

	/**
	 * The operating system process id of the launched JVM, or {@code null}.
	 * <p>
	 * It is what lets a caller reach that JVM with the tools this server has no
	 * business wrapping, jcmd and jstack among them, since everything here runs
	 * inside the IDE's own process and cannot see another one.
	 */
	private static String pid(ILaunch launchValue) {
		if (launchValue == null) {
			return null;
		}
		for (org.eclipse.debug.core.model.IProcess process : launchValue.getProcesses()) {
			String value = process.getAttribute(org.eclipse.debug.core.model.IProcess.ATTR_PROCESS_ID);
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	/** Longest slice of a launched program's output carried in an answer. */
	private static final int OUTPUT_TAIL = 2000;

	/**
	 * Describes the processes of a launch, and quotes their output when there is
	 * reason to think something went wrong.
	 * <p>
	 * A launch that meets another instance's workspace lock reports no threads, no
	 * pid and terminated false, which reads as "still starting" for as long as
	 * anyone cares to wait. The reason is printed on the process's own console,
	 * where the IDE has it and the caller does not.
	 */
	static void describeProcesses(JsonObject json, Session session) {
		ILaunch launchValue = session.launch();
		if (launchValue == null) {
			return;
		}
		JsonArray processes = new JsonArray();
		boolean anyLive = false;
		for (org.eclipse.debug.core.model.IProcess process : launchValue.getProcesses()) {
			JsonObject entry = new JsonObject().put("label", process.getLabel()) //$NON-NLS-1$
					.put("pid", process.getAttribute(org.eclipse.debug.core.model.IProcess.ATTR_PROCESS_ID)) //$NON-NLS-1$
					.put("terminated", Boolean.valueOf(process.isTerminated())); //$NON-NLS-1$
			if (process.isTerminated()) {
				try {
					entry.put("exitValue", Integer.valueOf(process.getExitValue())); //$NON-NLS-1$
				} catch (org.eclipse.debug.core.DebugException e) {
					// a process that cannot say how it ended still ended
				}
			} else {
				anyLive = true;
			}
			processes.add(entry);
		}
		if (processes.size() == 0) {
			// a launch registers its process a moment after it starts, so an empty list
			// right after launching means "not yet", not "never". Saying the opposite
			// sent a caller looking for a failure that had not happened
			json.put("processes", processes).put("processNote", //$NON-NLS-1$ //$NON-NLS-2$
					"No process is registered for this launch yet. Immediately after eclipse_debug_launch that is normal and it appears within seconds; ask eclipse_debug_status again. If it is still empty after that, nothing was started."); //$NON-NLS-1$
			return;
		}
		json.put("processes", processes); //$NON-NLS-1$
		// output is worth quoting exactly when the picture is otherwise empty: no
		// threads to report, or everything already dead
		if (!anyLive || launchValue.getDebugTargets().length == 0) {
			String output = output(launchValue);
			if (output != null && !output.isBlank()) {
				json.put("processOutputTail", output); //$NON-NLS-1$
			}
		}
	}

	/** The tail of what the processes printed, which is where a refusal to start says why. */
	private static String output(ILaunch launchValue) {
		StringBuilder text = new StringBuilder();
		for (org.eclipse.debug.core.model.IProcess process : launchValue.getProcesses()) {
			org.eclipse.debug.core.model.IStreamsProxy streams = process.getStreamsProxy();
			if (streams == null) {
				continue;
			}
			append(text, streams.getOutputStreamMonitor());
			append(text, streams.getErrorStreamMonitor());
		}
		String all = text.toString();
		return all.length() <= OUTPUT_TAIL ? all : "..." + all.substring(all.length() - OUTPUT_TAIL); //$NON-NLS-1$
	}

	private static void append(StringBuilder text, org.eclipse.debug.core.model.IStreamMonitor monitor) {
		if (monitor != null && monitor.getContents() != null) {
			text.append(monitor.getContents());
		}
	}

	/** One entry per session, the shape status, launch and control answers carry. */
	static JsonObject sessionJson(Session session, int maxThreads) {
		JsonObject json = new JsonObject().put("sessionId", session.id()) //$NON-NLS-1$
				.put("startedByMcp", Boolean.valueOf(session.startedByMcp())) //$NON-NLS-1$
				.put("terminated", Boolean.valueOf(session.terminated())) //$NON-NLS-1$
				.put("suspended", Boolean.valueOf(session.suspended()));
		ILaunch launchValue = session.launch();
		json.put("configuration", launchValue == null ? session.expectedConfigName() : configurationName(launchValue)); //$NON-NLS-1$
		json.put("registered", Boolean.valueOf(session.registered())); //$NON-NLS-1$
		json.put("pid", pid(launchValue)); //$NON-NLS-1$
		if (!session.registered() && session.failure() != null) {
			json.put("failure", session.failure()); //$NON-NLS-1$
		}
		JsonArray reported = new JsonArray();
		int total = 0;
		boolean truncated = false;
		if (launchValue != null && !session.terminated()) {
			for (IDebugTarget targetValue : launchValue.getDebugTargets()) {
				for (IThread thread : threads(targetValue)) {
					total++;
					if (reported.size() >= maxThreads) {
						truncated = true;
						continue;
					}
					reported.add(threadJson(thread, session));
				}
			}
		}
		json.put("threads", reported).put("threadTotal", Integer.valueOf(total)).put("truncated", //$NON-NLS-1$ //$NON-NLS-2$
				Boolean.valueOf(truncated));
		return json;
	}

	private static JsonObject threadJson(IThread thread, Session session) {
		boolean suspended = thread.isSuspended();
		JsonObject json = new JsonObject().put("name", name(thread)).put("suspended", Boolean.valueOf(suspended)); //$NON-NLS-1$ //$NON-NLS-2$
		if (suspended) {
			json.put("location", location(thread)); //$NON-NLS-1$
			json.put("breakpoint", suspendReason(thread, session)); //$NON-NLS-1$
		}
		return json;
	}

	private static String configurationName(ILaunch launchValue) {
		var configuration = launchValue.getLaunchConfiguration();
		return configuration == null ? null : configuration.getName();
	}

	static String describeSessions(List<Session> sessions) {
		StringBuilder text = new StringBuilder();
		for (Session session : sessions) {
			if (text.length() > 0) {
				text.append(", "); //$NON-NLS-1$
			}
			text.append(session.id()).append(session.startedByMcp() ? " (ours)" : ""); //$NON-NLS-1$ //$NON-NLS-2$
			if (session.terminated()) {
				text.append(" (terminated)"); //$NON-NLS-1$
			}
		}
		return text.toString();
	}

	private static String describeThreads(List<IThread> list) {
		StringBuilder text = new StringBuilder();
		for (IThread thread : list) {
			if (text.length() > 0) {
				text.append(", "); //$NON-NLS-1$
			}
			text.append(name(thread));
			if (thread.isSuspended()) {
				text.append(" (suspended)"); //$NON-NLS-1$
			}
		}
		return text.toString();
	}

	/** Truncates a rendered value; callers report valueTruncated beside it. */
	static String truncate(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max);
	}

	static String valueString(IJavaValue value, int max) {
		try {
			return truncate(value.getValueString(), max);
		} catch (DebugException | RuntimeException e) {
			return "<unreadable>"; //$NON-NLS-1$
		}
	}

	/** Whether any debug target anywhere is running, which qualifies the installed note. */
	static boolean anyLiveTarget() {
		for (ILaunch launchValue : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
			if (!launchValue.isTerminated()) {
				for (IDebugTarget targetValue : launchValue.getDebugTargets()) {
					if (!targetValue.isTerminated()) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
