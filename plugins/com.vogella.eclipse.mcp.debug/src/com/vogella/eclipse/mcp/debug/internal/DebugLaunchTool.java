package com.vogella.eclipse.mcp.debug.internal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.CompileErrorPrompt;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.LaunchAttributes;
import com.vogella.eclipse.mcp.core.LaunchRecording;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Starts a debug session, from a saved launch configuration or from a project and
 * a main type, and answers with the session id.
 */
public final class DebugLaunchTool implements IMcpTool {

	private static final String JAVA_APPLICATION = IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION;

	/**
	 * Keeps a launch nobody is watching from asking a question: without these a
	 * suspend raises the modal perspective switch prompt, which blocks the IDE.
	 * The marker is what tells an adopted session apart from one a person started.
	 */
	static void unattended(org.eclipse.debug.core.ILaunchConfigurationWorkingCopy configuration) {
		configuration.setAttribute(LaunchAttributes.TARGET_DEBUG_PERSPECTIVE,
				LaunchAttributes.PERSPECTIVE_NONE);
		configuration.setAttribute(LaunchAttributes.TARGET_RUN_PERSPECTIVE,
				LaunchAttributes.PERSPECTIVE_NONE);
		configuration.setAttribute(LaunchAttributes.STARTED_BY_MCP, true);
		configuration.setAttribute(LaunchAttributes.PRIVATE, true);
	}

	@Override
	public String getName() {
		return "eclipse_debug_launch"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Starts a debug session and returns its sessionId plus the state at the moment of answering. STARTS A PROCESS: this runs project code under the IDE's debugger, so anything main does, the debugged program does. Give either 'configuration', the name of an existing launch configuration, or 'project' plus 'mainType'; the latter builds a transient configuration that never appears in the user's saved launches. With 'stopInMain' the program suspends at the first line of main; otherwise set breakpoints first through eclipse_set_breakpoint and wait for them through eclipse_debug_status with waitForSuspendSeconds. Sessions started here are terminated again when this plug-in stops or after autoTerminateAfterSeconds, whichever comes first. To run tests under the debugger use eclipse_run_tests with debug true instead. Pass flightRecording to profile the launched JVM, which the IDE's own recording tools cannot reach because they record the IDE's own process."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "configuration":           {"type":"string","description":"Name of an existing launch configuration to start in debug mode."},
				    "project":                 {"type":"string","description":"Project holding mainType, when not launching a saved configuration."},
				    "mainType":                {"type":"string","description":"Fully qualified class whose main(String[]) starts the program."},
				    "arguments":               {"type":"string","description":"Program arguments."},
				    "vmArguments":             {"type":"string","description":"VM arguments."},
				    "stopInMain":              {"type":"boolean","default":false,"description":"Suspend at the first executable line of main."},
				    "autoTerminateAfterSeconds":{"type":"integer","default":900,"minimum":0,"maximum":86400,"description":"Terminate the program after this long, whether it finished or not. Only sessions this tool started are ever terminated. For an Eclipse application measured repeatedly, do not cut this too fine: Equinox writes its resolver state to <configuration>/org.eclipse.osgi/framework.info only some 25 to 30 seconds after the JVM starts, and an instance ended before that makes the NEXT start resolve from scratch, so two runs measure different things. Measured by the platform UI session: an instance ended after 18 seconds left the following start resolving, while later runs showed no resolver events at all."},
				    "waitForSuspendSeconds":   {"type":"integer","default":20,"minimum":0,"maximum":25,"description":"Wait for the first suspend (a breakpoint or stopInMain) before answering."},
				    "maxResults":              {"type":"integer","default":50,"minimum":1,"maximum":500,"description":"Threads reported per answer."},
				    "mode":                    {"type":"string","enum":["debug","run"],"default":"debug","description":"'run' starts the program without the debugger, so no JDWP agent is attached and nothing can suspend. Use it when measuring startup or profiling, where the agent distorts the numbers and a suspend ruins them; breakpoints and eclipse_debug_get_frames need 'debug'."},
				    "replaceExisting":         {"type":"boolean","default":true,"description":"Terminate a launch of the same configuration that THIS SERVER started and is still running, and wait for its process to be gone, before starting. Without it the second launch walks into the first one's workspace lock and opens a modal dialog inside the launched process, where no tool here can reach it. Launches a person started are never touched."},
				    "quiet":                   {"type":"boolean","default":true,"description":"Neutralise, for as long as this launch runs, the settings that stop a program nobody is watching: suspend on uncaught exceptions, suspend on compilation errors, and the modal question about switching perspective on suspend. These are not breakpoints, so eclipse_list_breakpoints reports none of them, and OSGi startup trips the first one routinely. The previous values are restored when the launch ends."},
				    "flightRecordingSeconds":  {"type":"integer","default":0,"minimum":0,"maximum":3600,"description":"Write the recording after this many seconds WITHOUT ending the program. This is what makes a startup measurable: the file is complete while the application carries on. IT IS ONE WINDOW: the recording ends at that point and nothing is added later, not even at exit, so measure the start with it and use jcmd JFR.dump for a window further in. 0 relies on the exit dump alone, which needs the program to end."},
				    "flightRecording":         {"type":"string","enum":["off","default","profile"],"default":"off","description":"Record the launched JVM with Java Flight Recorder. 'profile' includes allocation and execution samples at a few percent overhead, 'default' covers GC and threads at about one percent. The file is written when the program EXITS and is read with eclipse_stop_flight_recording by passing its path as 'file'. This is the only way to profile a launched program: the IDE's own recording tools work inside the IDE's JVM and cannot see another process."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		int waitForSuspend = args.getInt("waitForSuspendSeconds", 20, 0, 25); //$NON-NLS-1$
		ILaunchConfigurationWorkingCopy configuration;
		try {
			configuration = configuration(args);
		} catch (CoreException e) {
			throw new McpToolException("Could not build the launch configuration: %s".formatted(e.getMessage()), e);
		}
		int recordingSeconds = args.getInt("flightRecordingSeconds", 0, 0, 3600); //$NON-NLS-1$
		Path recordingFile = record(configuration, args.getString("flightRecording", "off"), //$NON-NLS-1$ //$NON-NLS-2$
				recordingSeconds);
		String mode = "run".equals(args.getString("mode", "debug")) ? ILaunchManager.RUN_MODE //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				: ILaunchManager.DEBUG_MODE;
		try {
			long started = System.nanoTime();
			DebugSessionRegistry registry = DebugSessionRegistry.getInstance();
			JsonObject replaced = args.getBoolean("replaceExisting", true) //$NON-NLS-1$
					? replaceExisting(registry, configuration.getName())
					: null;
			DebugSessionRegistry.Session session = registry.prepare(configuration.getName());
			if (args.getBoolean("quiet", true)) { //$NON-NLS-1$
				session.holdQuiet();
			}
			int idle = args.getInt("autoTerminateAfterSeconds", 900, 0, 86_400); //$NON-NLS-1$
			if (idle > 0) {
				registry.scheduleAutoTerminate(session, idle);
			}
			// launching happens in a job: creating the JVM takes seconds, and doing it on
			// the calling thread would hold the request open for all of it
			org.eclipse.core.runtime.jobs.Job.create("MCP debug launch " + session.id(), progress -> { //$NON-NLS-1$
				// the same prompt eclipse_run_tests answers: launching a project with
				// compile errors otherwise opens a modal dialog and the launch waits for
				// a person who does not know they are being asked
				String promptWas = CompileErrorPrompt.suppress();
				try {
					org.eclipse.debug.core.ILaunch launched = configuration.launch(mode, progress);
					if (!session.registered()) {
						// a run mode launch produces no adoptable launch event
						registry.attachLaunched(session, launched);
					}
				} catch (CoreException | RuntimeException e) {
					session.failed(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
				} finally {
					CompileErrorPrompt.restore(promptWas);
				}
				return org.eclipse.core.runtime.Status.OK_STATUS;
			}).schedule();

			long registrationWait = Math.max(1, Math.min(
					CallBudget.maxWaitSeconds() - (System.nanoTime() - started) / 1_000_000_000L,
					Math.max(waitForSuspend, 10)));
			if (!session.awaitRegistration(registrationWait)) {
				return McpToolResult.error(("The JVM of session %s did not come up within %d seconds. %s")
						.formatted(session.id(), Long.valueOf(registrationWait),
								session.failure() == null ? "It may still be starting." : session.failure())); //$NON-NLS-1$
			}
			if (session.failure() != null && session.launch() == null) {
				return McpToolResult
						.error("The launch failed: %s".formatted(session.failure())); //$NON-NLS-1$
			}
			int maxThreads = args.getInt("maxResults", 50, 1, 500); //$NON-NLS-1$
			JsonObject json;
			if (waitForSuspend > 0 && !session.suspended()) {
				DebugSessionRegistry.SuspendSignal signal = registry.onNextSuspend(session);
				long remaining = CallBudget.maxWaitSeconds() - (System.nanoTime() - started) / 1_000_000_000L;
				long waited = Math.max(0, Math.min(waitForSuspend, remaining));
				boolean arrived = signal.await(waited);
				json = DebugSupport.sessionJson(session, maxThreads);
				if (!arrived && !session.suspended()) {
					String note = waited < waitForSuspend
							? "No suspend within the %d seconds the call timeout left after the launch; the program is probably still running. Poll eclipse_debug_status with waitForSuspendSeconds to keep waiting." //$NON-NLS-1$
							: "No suspend within %d seconds; the program is probably still running. Poll eclipse_debug_status with waitForSuspendSeconds to keep waiting."; //$NON-NLS-1$
					json.put("timedOut", Boolean.TRUE).put("waitNote", note.formatted(Long.valueOf(waited))); //$NON-NLS-1$ //$NON-NLS-2$
				} else {
					json.put("timedOut", Boolean.FALSE); //$NON-NLS-1$
				}
			} else {
				json = DebugSupport.sessionJson(session, maxThreads);
			}
			json.put("note", "The session ends with eclipse_debug_control action terminate; it also terminates by itself after autoTerminateAfterSeconds."); //$NON-NLS-1$ //$NON-NLS-2$
			if (recordingFile != null) {
				json.put("flightRecordingFile", recordingFile.toString()) //$NON-NLS-1$
						.put("flightRecordingNote", //$NON-NLS-1$
								LaunchRecording.note(recordingFile, recordingSeconds));
			}
			json.put("mode", mode); //$NON-NLS-1$
			if (replaced != null) {
				json.put("replaced", replaced); //$NON-NLS-1$
			}
			if (args.getBoolean("quiet", true)) { //$NON-NLS-1$
				json.put("quiet", new JsonObject().put("applied", Boolean.TRUE) //$NON-NLS-1$ //$NON-NLS-2$
						.put("note", //$NON-NLS-1$
								"Suspend on uncaught exceptions, suspend on compilation errors and the perspective switch question are off while this launch runs, and go back to their previous values when it ends. They are not breakpoints, so eclipse_list_breakpoints never showed them.")); //$NON-NLS-1$
			}
			DebugSupport.describeProcesses(json, session);
			return McpToolResult.of(json.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new McpToolException("Interrupted while waiting for the debug session to start.", e);
		}
	}

	/**
	 * Ends a still running launch of the same configuration that this server
	 * started, so the new one does not meet the old one's workspace lock.
	 */
	private static JsonObject replaceExisting(DebugSessionRegistry registry, String configName) {
		JsonArray ended = new JsonArray();
		boolean allGone = true;
		for (DebugSessionRegistry.Session previous : registry.liveStartedByMcp(configName)) {
			boolean gone = DebugSessionRegistry.terminateAndWait(previous, 10);
			allGone &= gone;
			ended.add(new JsonObject().put("sessionId", previous.id()).put("processGone", Boolean.valueOf(gone))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (ended.size() == 0) {
			return null;
		}
		JsonObject json = new JsonObject().put("sessions", ended); //$NON-NLS-1$
		if (!allGone) {
			json.put("warning", //$NON-NLS-1$
					"One of them had not actually ended within ten seconds. It may still hold the workspace lock, in which case this launch stops inside its own process with a dialog no tool here can reach."); //$NON-NLS-1$
		}
		return json;
	}

	/**
	 * Adds the flight recorder flag to whatever VM arguments the configuration
	 * already carries, saved ones included, and answers where it will write.
	 *
	 * @return {@code null} when no recording was asked for
	 */
	private static Path record(ILaunchConfigurationWorkingCopy configuration, String settings,
			int seconds) {
		if (!LaunchRecording.wanted(settings)) {
			return null;
		}
		Path file = LaunchRecording.fileFor(configuration.getName());
		String existing = null;
		try {
			existing = configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, (String) null);
		} catch (CoreException e) {
			// unreadable VM arguments are not a reason to lose the recording
		}
		configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS,
				LaunchRecording.appendTo(existing,
						LaunchRecording.vmArgument(settings, file, seconds)));
		return file;
	}

	private ILaunchConfigurationWorkingCopy configuration(ToolArguments args)
			throws McpToolException, CoreException {
		String name = args.getString("configuration"); //$NON-NLS-1$
		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
		if (name != null) {
			List<String> names = new ArrayList<>();
			for (ILaunchConfiguration candidate : manager.getLaunchConfigurations()) {
				names.add(candidate.getName());
				if (candidate.getName().equals(name)) {
					return candidate.getWorkingCopy();
				}
			}
			throw new McpToolException(
					"No launch configuration named '%s'. Existing ones: %s".formatted(name, String.join(", ", names))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		String projectName = args.getString("project"); //$NON-NLS-1$
		String mainType = args.getString("mainType"); //$NON-NLS-1$
		if (projectName == null || mainType == null) {
			throw new McpToolException(
					"Give 'configuration', or both 'project' and 'mainType'."); //$NON-NLS-1$
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.isAccessible()) {
			throw new McpToolException("No open project named '%s' in this workspace.".formatted(projectName)); //$NON-NLS-1$
		}
		IJavaProject javaProject = JavaCore.create(project);
		if (javaProject == null || !javaProject.exists()) {
			throw new McpToolException("'%s' is not a Java project.".formatted(projectName)); //$NON-NLS-1$
		}
		IType type = javaProject.findType(mainType);
		if (type == null) {
			throw new McpToolException("No type '%s' resolvable from project '%s'.".formatted(mainType, projectName)); //$NON-NLS-1$
		}
		ILaunchConfigurationType launchType = manager.getLaunchConfigurationType(JAVA_APPLICATION);
		if (launchType == null) {
			throw new McpToolException("This IDE has no Java Application launch configuration type."); //$NON-NLS-1$
		}
		ILaunchConfigurationWorkingCopy configuration = launchType.newInstance(null, "MCP debug " + mainType + " " + System.nanoTime()); //$NON-NLS-1$ //$NON-NLS-2$
		configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName);
		configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, mainType);
		unattended(configuration);
		if (args.getBoolean("stopInMain", false)) { //$NON-NLS-1$
			configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_STOP_IN_MAIN, true);
		}
		if (args.getString("arguments") != null) { //$NON-NLS-1$
			configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS,
					args.getString("arguments")); //$NON-NLS-1$
		}
		if (args.getString("vmArguments") != null) { //$NON-NLS-1$
			configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS,
					args.getString("vmArguments")); //$NON-NLS-1$
		}
		return configuration;
	}
}
