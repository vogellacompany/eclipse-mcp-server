package com.vogella.eclipse.mcp.jdt.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.Manifest;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.junit.launcher.TestKindRegistry;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.pde.launching.IPDELauncherConstants;

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
 * Runs JUnit tests through the IDE's own test runner.
 */
public final class RunTestsTool implements IMcpTool {

	private static final String LAUNCH_TYPE = "org.eclipse.jdt.junit.launchconfig"; //$NON-NLS-1$

	/** Declared by org.eclipse.pde.launching, which despite the id has no UI dependency. */
	private static final String PLUGIN_LAUNCH_TYPE = "org.eclipse.pde.ui.JunitLaunchConfig"; //$NON-NLS-1$

	/** Runs the tests in a platform with no workbench. */
	private static final String CORE_TEST_APPLICATION = "org.eclipse.pde.junit.runtime.coretestapplication"; //$NON-NLS-1$

	/** Opens a workbench window, so it is never the default. */
	private static final String UI_TEST_APPLICATION = "org.eclipse.pde.junit.runtime.uitestapplication"; //$NON-NLS-1$

	private static final String PLUGIN_NATURE = "org.eclipse.pde.PluginNature"; //$NON-NLS-1$

	/** Projects named in the pre-flight before it says how many more there are. */
	private static final int MAX_PREFLIGHT_PROJECTS = 10;

	/**
	 * The preference the "Errors in Workspace / Always launch without asking" toggle
	 * writes. Launching with compile errors otherwise raises a modal dialog through
	 * the debug.ui status handler, which blocks a call nobody is watching.
	 */
	/** Launch configuration attributes of the JUnit launcher, which are a stable contract. */
	private static final String ATTR_CONTAINER = "org.eclipse.jdt.junit.CONTAINER"; //$NON-NLS-1$

	private static final String ATTR_TEST_KIND = "org.eclipse.jdt.junit.TEST_KIND"; //$NON-NLS-1$

	private static final String ATTR_TEST_NAME = "org.eclipse.jdt.junit.TESTNAME"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_run_tests"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Runs JUnit tests through the IDE's own test runner and reports the failures with their stack traces, expected and actual values. RUNS PROJECT CODE. The JUnit version is detected from the project's own build path and the runtime classpath is the one Run As > JUnit Test would use, so nothing has to be configured. Runs as a launched JVM and returns a runId to poll through eclipse_get_test_results. A plug-in project is run as a JUnit Plug-in Test by default, which launches a second Eclipse with a running platform in its own cleared workspace, because tests needing OSGi produce meaningless errors under a plain JUnit launch. That is slower. The UI test application, which opens a workbench window, is opt-in. launchedAs in the answer says which was used. With 'debug' the tests launch under the debugger instead, which is how a failing run is inspected at the moment of failure; the debug tools address that session."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["project"],
				  "properties": {
				    "project":        {"type":"string","description":"Project holding the tests."},
				    "testClass":      {"type":"string","description":"Fully qualified test class. Omit to run every test in the project."},
				    "testMethod":     {"type":"string","description":"Single method of testClass."},
 				    "pluginTest":     {"type":"string","enum":["auto","true","false"],"default":"auto","description":"Run as a JUnit Plug-in Test, which launches a second Eclipse with a running platform. 'auto' uses it when the project is a plug-in project. Tests that need OSGi fail as plain JUnit with errors that look like broken tests rather than real results."},
				    "buildFirst":     {"type":"string","enum":["auto","full","never"],"default":"auto","description":"Build the launch's projects before launching. auto builds incrementally when auto-build is off, which is when nothing else would. full rebuilds them completely, which is the only thing that regenerates the OSGI-INF declarative services descriptors for sources that have not changed: those are written by a compilation participant, so they appear only for units that are actually recompiled. Use full once after turning descriptor generation on, not on every run: in a large workspace it costs minutes."},
				    "display":        {"type":"string","description":"X display for a UI run, e.g. :1 for a VNC server or :99 for Xvfb, so the workbench opens there instead of on the user's screen. GDK_BACKEND=x11 is set with it, without which GTK takes the Wayland compositor and ignores the display. X11 only, so GTK platforms; on Windows and macOS SWT does not draw through X11 and the answer says the display was not applied rather than pretending it was. Start the server yourself: a display this tool started would be a process nobody owns."},
				    "workspacePlugins": {"type":"string","enum":["required","all"],"default":"required","description":"Which workspace plug-ins the launched platform gets. required is the test bundle and what it needs; all adds every plug-in in the workspace, which is the PDE launch tab's own default and which breaks a UI test launch in a workspace holding unbuilt copies of platform bundles."},
				    "ui":             {"type":"boolean","default":false,"description":"Use the UI test application, which opens a workbench window on the user's screen. Off by default: a launched IDE should never be a surprise. A UI launch depends on generated artefacts being current in a way the headless one does not: the OSGI-INF declarative services descriptors carry the wiring between components, they are build output rather than committed source, and no compilation error flags a mismatch. A run that comes back with zero tests is usually that, so read descriptorGeneration and buildBeforeLaunch in the answer before suspecting the test bundle."},
				    "debug":          {"type":"boolean","default":false,"description":"Launch in debug mode instead of plain run. The session appears in eclipse_debug_status and its state at a failure is readable through eclipse_debug_get_frames and eclipse_debug_evaluate."},
				    "runtimeWorkspace": {"type":"string","description":"Workspace directory for the launched platform. Defaults to a sibling junit-workspace, and it is cleared on every run."},
				    "maxResults":     {"type":"integer","default":50,"minimum":1,"maximum":2000,"description":"Reported cases. A suite of several hundred truncates; omitted says how many were dropped and eclipse_get_test_results returns the rest."},
				    "flightRecording":{"type":"string","enum":["off","default","profile"],"default":"off","description":"Record the test JVM with Java Flight Recorder. 'profile' includes allocation and execution samples at a few percent overhead, 'default' covers GC and threads at about one percent. The file is written when the test JVM EXITS and is read with eclipse_stop_flight_recording by passing its path as 'file'. This is what answers why a suite is slow or where its memory goes: the IDE's own recording tools record the IDE's process, not the one the tests run in."},
				    "dryRun":         {"type":"boolean","default":false,"description":"List the test types that would run, without running anything."},
				    "wait":           {"type":"boolean","default":true,"description":"Defaults to false for a plug-in test: launching a second Eclipse takes tens of seconds, well past the server's call timeout, so waiting would abandon the call rather than answer it."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"Keep below the server's tool call timeout; poll with eclipse_get_test_results for longer runs."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String projectName = args.getString("project"); //$NON-NLS-1$
		if (projectName == null) {
			return McpToolResult.error("The argument 'project' is required."); //$NON-NLS-1$
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.isAccessible()) {
			return McpToolResult.error("No open project named '%s' in this workspace.".formatted(projectName)); //$NON-NLS-1$
		}
		IJavaProject javaProject = JavaCore.create(project);
		if (javaProject == null || !javaProject.exists()) {
			return McpToolResult.error("'%s' is not a Java project.".formatted(projectName)); //$NON-NLS-1$
		}
		String testClass = args.getString("testClass"); //$NON-NLS-1$
		String testMethod = args.getString("testMethod"); //$NON-NLS-1$
		if (testMethod != null && testClass == null) {
			return McpToolResult.error("'testMethod' needs a 'testClass' to belong to."); //$NON-NLS-1$
		}

		try {
			IType type = null;
			if (testClass != null) {
				type = javaProject.findType(testClass);
				if (type == null || !type.exists()) {
					return McpToolResult.error("No type '%s' in project '%s'.".formatted(testClass, projectName)); //$NON-NLS-1$
				}
			}
			if (args.getBoolean("dryRun", false)) { //$NON-NLS-1$
				return McpToolResult.of(dryRun(javaProject, type, monitor, args.getInt("maxResults", 50, 1, 2000), //$NON-NLS-1$
						launchedAs(project, args)).toString());
			}

			TestRunRegistry.Run active = TestRunRegistry.getInstance().findRunning();
			if (active != null) {
				return McpToolResult.error(
						"A test run is already in progress (%s). Two overlapping runs share JDT's AST parser and can fail with an IllegalStateException, so this one is refused; poll eclipse_get_test_results for the active run first." //$NON-NLS-1$
								.formatted(active.id()));
			}
			String kind = testKind(javaProject);
			String pluginTest = args.getString("pluginTest", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
			boolean asPlugin = "true".equals(pluginTest) //$NON-NLS-1$
					|| ("auto".equals(pluginTest) && project.hasNature(PLUGIN_NATURE)); //$NON-NLS-1$
			boolean ui = args.getBoolean("ui", false); //$NON-NLS-1$
			String launchedAs = launchedAs(project, args);
			// what the person's own launches would have done, since this run answers the
			// prompt for itself and puts the setting back
			String compileErrorPromptWas = CompileErrorPrompt.effectiveValue();
			TestRunRegistry.Run run = TestRunRegistry.getInstance()
					.create(testClass == null ? projectName : testClass + (testMethod == null ? "" : "#" + testMethod)); //$NON-NLS-1$ //$NON-NLS-2$

			ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
			ILaunchConfigurationType launchType = manager
					.getLaunchConfigurationType(asPlugin ? PLUGIN_LAUNCH_TYPE : LAUNCH_TYPE);
			if (launchType == null) {
				return McpToolResult.error(asPlugin
						? "This IDE has no plug-in JUnit launch configuration type, so PDE is probably not installed. Pass pluginTest false to run as plain JUnit." //$NON-NLS-1$
						: "This IDE has no JUnit launch configuration type."); //$NON-NLS-1$
			}
			ILaunchConfigurationWorkingCopy configuration = launchType.newInstance(null, run.launchName());
			configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName);
			configuration.setAttribute(ATTR_TEST_KIND, kind);
			// a run nobody is watching must not ask anything: a debugged test that
			// suspends otherwise raises the modal perspective switch prompt
			configuration.setAttribute(LaunchAttributes.TARGET_DEBUG_PERSPECTIVE,
					LaunchAttributes.PERSPECTIVE_NONE);
			configuration.setAttribute(LaunchAttributes.TARGET_RUN_PERSPECTIVE,
					LaunchAttributes.PERSPECTIVE_NONE);
			configuration.setAttribute(LaunchAttributes.STARTED_BY_MCP, true);
			// launching a working copy saves it, and a saved configuration shows up in
			// the user's Run Configurations dialog. Private keeps this server's launches
			// out of a list that belongs to the person at the IDE.
			configuration.setAttribute(LaunchAttributes.PRIVATE, true);
			if (type == null) {
				// a container runs everything under it, which is how Run As on a project works
				configuration.setAttribute(ATTR_CONTAINER, javaProject.getHandleIdentifier());
			} else {
				configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, testClass);
				if (testMethod != null) {
					configuration.setAttribute(ATTR_TEST_NAME, testMethod);
				}
			}
			Path recordingFile = null;
			String recording = args.getString("flightRecording", "off"); //$NON-NLS-1$ //$NON-NLS-2$
			if (LaunchRecording.wanted(recording)) {
				recordingFile = LaunchRecording.fileFor(run.launchName());
				configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS,
						LaunchRecording.appendTo(
								configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS,
										(String) null),
								LaunchRecording.vmArgument(recording, recordingFile, 0)));
			}
			String buildFirst = args.getString("buildFirst", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
			boolean autoBuilding = ResourcesPlugin.getWorkspace().isAutoBuilding();
			JsonObject built = asPlugin ? buildForLaunch(project, buildFirst, autoBuilding, monitor) : null;
			JsonObject displayed = ui && asPlugin ? applyDisplay(configuration, args.getString("display")) : null; //$NON-NLS-1$
			boolean allWorkspacePlugins = "all".equals(args.getString("workspacePlugins", "required")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			String testBundle = symbolicName(project);
			if (asPlugin) {
				configurePlatform(configuration, args.getString("runtimeWorkspace"), ui, allWorkspacePlugins, //$NON-NLS-1$
						testBundle);
			}
			// only for the UI application: it is the one that needs a workbench, and a
			// workspace plug-in with unbuilt classes shadows the installed bundle and
			// stops that workbench from starting, which reports as a run with no tests
			JsonObject preflight = ui && asPlugin ? unbuiltWorkspacePlugins() : null;
			// launching happens in a job: preLaunchCheck alone can take a while, and
			// doing it here would defeat wait:false exactly as the p2 refresh once did
			run.launchedAs(launchedAs);
			boolean debug = args.getBoolean("debug", false); //$NON-NLS-1$
			org.eclipse.core.runtime.jobs.Job.create("MCP test launch " + run.id(), progress -> { //$NON-NLS-1$
				String previous = CompileErrorPrompt.suppress();
				try {
					org.eclipse.debug.core.ILaunch launch = configuration.launch(
							debug ? ILaunchManager.DEBUG_MODE : ILaunchManager.RUN_MODE, null);
					TestRunRegistry.watch(run, launch, asPlugin ? 300 : 120);
				} catch (CoreException | RuntimeException e) {
					// the runner bundles ship with the SDK, and JDT reports a missing one
					// as an assertion rather than a CoreException
					TestRunRegistry.failed(run, describe(e));
				} finally {
					CompileErrorPrompt.restore(previous);
				}
				return org.eclipse.core.runtime.Status.OK_STATUS;
			}).schedule();

			// a launched platform starts far too slowly to hold a call open for
			if (args.getBoolean("wait", !asPlugin)) { //$NON-NLS-1$
				try {
					run.await(args.getInt("timeoutSeconds", 25, 1, 3600)); //$NON-NLS-1$
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			JsonObject result = TestRunRegistry.toJson(run, args.getInt("maxResults", 50, 1, 2000), false) //$NON-NLS-1$
					.put("testKind", kind) //$NON-NLS-1$
					;
			if (asPlugin && run.running()) {
				result.put("note", //$NON-NLS-1$
						"A second Eclipse is starting, which takes tens of seconds before the first test runs. Poll eclipse_get_test_results with this runId."); //$NON-NLS-1$
			}
			// report what was actually set rather than what was intended: two rounds of
			// this were spent inferring the launch configuration from a runtime log
			if (asPlugin) {
				result.put("launchAttributes", new JsonObject() //$NON-NLS-1$
						.put(IPDELauncherConstants.APPLICATION,
								configuration.getAttribute(IPDELauncherConstants.APPLICATION, (String) null))
						.put(IPDELauncherConstants.APP_TO_TEST,
								configuration.getAttribute(IPDELauncherConstants.APP_TO_TEST, (String) null))
						.put("workspacePlugins", allWorkspacePlugins ? "all" : "required") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						.put("workspaceBundle", testBundle) //$NON-NLS-1$
						// read back rather than echoed: the selection is only honoured
						// when useDefault is false, and reporting the intention hid that
						.put(IPDELauncherConstants.USE_DEFAULT,
								configuration.getAttribute(IPDELauncherConstants.USE_DEFAULT, true))
						// where to read the bundle list the launch actually got: config.ini
						// always holds it as osgi.bundles, while bundles.info is written
						// only when simpleconfigurator is in use, which the narrow set is
						// not, so naming that file alone would name a missing one
						.put("configurationArea", //$NON-NLS-1$
								"%s/.metadata/.plugins/org.eclipse.pde.core/%s (config.ini holds osgi.bundles; bundles.info is written next to it only for the wide set)" //$NON-NLS-1$
										.formatted(org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
												.getLocation(), run.launchName()))
						.put(IPDELauncherConstants.SELECTED_WORKSPACE_BUNDLES,
								String.join(", ", configuration.getAttribute( //$NON-NLS-1$
										IPDELauncherConstants.SELECTED_WORKSPACE_BUNDLES, Set.<String>of())))
						.put(IPDELauncherConstants.RUN_IN_UI_THREAD,
								configuration.getAttribute(IPDELauncherConstants.RUN_IN_UI_THREAD, true))
						.put(IPDELauncherConstants.LOCATION,
								configuration.getAttribute(IPDELauncherConstants.LOCATION, (String) null)));
			}
			if (preflight != null) {
				result.put("workspacePluginErrors", preflight); //$NON-NLS-1$
			}
			if (built != null) {
				result.put("buildBeforeLaunch", built); //$NON-NLS-1$
			}
			if (displayed != null) {
				result.put("display", displayed); //$NON-NLS-1$
			}
			if (recordingFile != null) {
				result.put("flightRecordingFile", recordingFile.toString()) //$NON-NLS-1$
						.put("flightRecordingNote", //$NON-NLS-1$
								LaunchRecording.note(recordingFile, 0));
			}
			if (asPlugin) {
				result.put("descriptorGeneration", descriptorGeneration(project)); //$NON-NLS-1$
			}
			JsonArray broken = projectsWithErrors(project);
			if (broken.size() > 0) {
				result.put("launchedWithCompileErrors", broken) //$NON-NLS-1$
						.put("compileErrorPromptWas", compileErrorPromptWas) //$NON-NLS-1$
						.put("compileErrorNote", //$NON-NLS-1$
								"These projects do not compile. Eclipse would normally ask whether to launch anyway; this server answered yes, because a dialog would block a call nobody is watching. Failures may be stale classes rather than real results."); //$NON-NLS-1$
			}
			if (asPlugin && !ui) {
				result.put("headless", //$NON-NLS-1$
						"Running the core test application, which has no workbench. Tests that need a Display fail here; pass ui true to run them in a real workbench window."); //$NON-NLS-1$
			}
			if (debug) {
				result.put("debug", Boolean.TRUE).put("debugNote", //$NON-NLS-1$ //$NON-NLS-2$
						"The tests are being debugged: the launch is a debug session, visible through eclipse_debug_status and addressable by its sessionId. Set a breakpoint first and the run suspends there; eclipse_debug_get_frames and eclipse_debug_evaluate read the state at it.");
			}
			if (!asPlugin && project.hasNature(PLUGIN_NATURE)) {
				result.put("caveat", //$NON-NLS-1$
						"'%s' is a plug-in project but was run as plain JUnit, so tests needing OSGi fail with errors such as 'The application has not been initialized', a null IExtensionRegistry or NoClassDefFoundError. Those are not test failures. Omit pluginTest to launch a platform." //$NON-NLS-1$
								.formatted(projectName));
			}
			return McpToolResult.of(result.toString());
		} catch (CoreException e) {
			// the cause's own text in the message: a bare "could not run the tests"
			// restates the request and says nothing about what went wrong
			throw new McpToolException(
					"Could not run the tests of %s: %s".formatted(projectName, describe(e)), e); //$NON-NLS-1$
		}
	}

	/**
	 * The projects the launch depends on that do not compile, transitively.
	 * <p>
	 * Direct references are not enough: the launch delegate checks the whole
	 * required closure, which is why the prompt named a project this field did not.
	 */
	private static JsonArray projectsWithErrors(IProject project) {
		Set<String> seen = new LinkedHashSet<>();
		List<IProject> queue = new ArrayList<>(List.of(project));
		JsonArray broken = new JsonArray();
		while (!queue.isEmpty() && seen.size() < 500) {
			IProject current = queue.remove(0);
			if (!current.isAccessible() || !seen.add(current.getName())) {
				continue;
			}
			if (hasErrors(current)) {
				broken.add(current.getName());
			}
			try {
				queue.addAll(List.of(current.getReferencedProjects()));
			} catch (CoreException | RuntimeException e) {
				// PDE can throw computing dynamic references on a stale bundle wiring
			}
		}
		return broken;
	}

	private static boolean hasErrors(IProject project) {
		try {
			for (org.eclipse.core.resources.IMarker marker : project.findMarkers(
					org.eclipse.core.resources.IMarker.PROBLEM, true,
					org.eclipse.core.resources.IResource.DEPTH_INFINITE)) {
				if (marker.getAttribute(org.eclipse.core.resources.IMarker.SEVERITY,
						-1) == org.eclipse.core.resources.IMarker.SEVERITY_ERROR) {
					return true;
				}
			}
		} catch (CoreException e) {
			// a project whose markers cannot be read is not evidence either way
		}
		return false;
	}

	/** An exception with no message is useless to a caller; name the type at least. */
	private static String describe(Throwable e) {
		if (e.getMessage() != null && !e.getMessage().isBlank()) {
			return e.getMessage();
		}
		StackTraceElement[] frames = e.getStackTrace();
		return e.getClass().getName() + (frames.length == 0 ? "" : " at " + frames[0]); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * A plug-in test launches a second Eclipse, so it needs its own workspace and an
	 * application to run. The workbench one is opt-in: it opens a window on the
	 * user's screen, which should never happen by surprise.
	 */
	private static void configurePlatform(ILaunchConfigurationWorkingCopy configuration, String runtimeWorkspace,
			boolean ui, boolean allWorkspacePlugins, String testBundle) {
		// APPLICATION is the switch, per PDE's own comment in getApplication: "if
		// application is set, it must be a headless app". Leaving it unset yields the
		// UI test application. APP_TO_TEST is a different thing, the product the UI
		// test application runs inside, so it belongs only on the ui path.
		if (ui) {
			configuration.removeAttribute(IPDELauncherConstants.APPLICATION);
			configuration.setAttribute(IPDELauncherConstants.APP_TO_TEST, "org.eclipse.ui.ide.workbench"); //$NON-NLS-1$
		} else {
			configuration.setAttribute(IPDELauncherConstants.APPLICATION, CORE_TEST_APPLICATION);
			configuration.removeAttribute(IPDELauncherConstants.APP_TO_TEST);
		}
		configuration.setAttribute(IPDELauncherConstants.USE_PRODUCT, false);
		configuration.setAttribute(IPDELauncherConstants.LOCATION,
				runtimeWorkspace == null ? "${workspace_loc}/../mcp-junit-workspace" : runtimeWorkspace); //$NON-NLS-1$
		// cleared and never asked about: a prompt would block a call nobody is watching
		configuration.setAttribute(IPDELauncherConstants.DOCLEAR, true);
		configuration.setAttribute(IPDELauncherConstants.ASKCLEAR, false);
		configuration.setAttribute(IPDELauncherConstants.CONFIG_CLEAR_AREA, true);
		// AUTOMATIC_ADD decides only how the workspace list is read: true means
		// everything except the deselected, false means only the selected. Whether
		// dependencies come along is a separate switch and stays on, so the narrow set
		// is still the test bundle plus its closure plus the target platform. Taking
		// everything is the launch tab's default and it kills a UI test launch in a
		// workspace that holds unbuilt copies of the bundles the workbench is made of.
		// USE_DEFAULT is the switch that decides whether any of the rest is read at
		// all. BundleLauncherHelper.getMergedBundleMap returns every active model when
		// it is true, which it is by default, so a selection written next to it is
		// simply ignored. That is why setting AUTOMATIC_ADD alone changed nothing.
		configuration.setAttribute(IPDELauncherConstants.USE_DEFAULT, allWorkspacePlugins);
		configuration.setAttribute(IPDELauncherConstants.AUTOMATIC_ADD, allWorkspacePlugins);
		configuration.setAttribute(IPDELauncherConstants.AUTOMATIC_INCLUDE_REQUIREMENTS, true);
		if (!allWorkspacePlugins && testBundle != null) {
			configuration.setAttribute(IPDELauncherConstants.SELECTED_WORKSPACE_BUNDLES, Set.of(testBundle));
		}
	}

	/**
	 * Workspace plug-in projects PDE reports errors on, which a UI test launch
	 * takes with it because every workspace plug-in is on its bundle list.
	 * <p>
	 * Markers only, no build: this must not cost seconds before a launch. With
	 * auto-build off the markers can be stale, so an empty answer proves nothing
	 * and the note says as much.
	 */
	private static JsonObject unbuiltWorkspacePlugins() {
		JsonArray projects = new JsonArray();
		int total = 0;
		try {
			IMarker[] markers = ResourcesPlugin.getWorkspace().getRoot()
					.findMarkers("org.eclipse.pde.core.problem", true, IResource.DEPTH_INFINITE); //$NON-NLS-1$
			Set<String> named = new LinkedHashSet<>();
			for (IMarker marker : markers) {
				if (marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO) != IMarker.SEVERITY_ERROR) {
					continue;
				}
				total++;
				if (named.size() < MAX_PREFLIGHT_PROJECTS && marker.getResource().getProject() != null) {
					named.add(marker.getResource().getProject().getName());
				}
			}
			named.forEach(projects::add);
		} catch (CoreException e) {
			return null;
		}
		boolean autoBuilding = ResourcesPlugin.getWorkspace().isAutoBuilding();
		if (total == 0 && autoBuilding) {
			return null;
		}
		return new JsonObject().put("projects", projects) //$NON-NLS-1$
				.put("total", Integer.valueOf(total)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(projects.size() < total)) //$NON-NLS-1$
				.put("autoBuilding", Boolean.valueOf(autoBuilding)) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"The UI test application starts a workbench. With workspacePlugins all, every workspace plug-in is on this launch's bundle list, so a workspace copy without compiled classes shadows the installed bundle and the workbench fails to start. That reports as a run with no tests rather than as an error. These are PDE's markers only, so with auto-build off they can be stale and an empty list proves nothing; build the workspace if the run comes back with total 0."); //$NON-NLS-1$
	}

	/**
	 * The bundle symbolic name of a plug-in project, read from its manifest. The
	 * project name is not it: a project may be named anything, and the launch
	 * selection is by symbolic name.
	 */
	private static String symbolicName(IProject project) {
		try (InputStream in = project.getFile("META-INF/MANIFEST.MF").getContents()) { //$NON-NLS-1$
			String header = new Manifest(in).getMainAttributes().getValue("Bundle-SymbolicName"); //$NON-NLS-1$
			if (header == null) {
				return null;
			}
			int semicolon = header.indexOf(';');
			return (semicolon < 0 ? header : header.substring(0, semicolon)).trim();
		} catch (CoreException | IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Builds the projects going into the launch, when anything else would not.
	 * <p>
	 * A plug-in launch runs workspace bundles in dev mode and reads generated
	 * artefacts off disk. The declarative services descriptors under OSGI-INF are
	 * written by org.eclipse.pde.ds.core.builder rather than by the Java builder,
	 * so a workspace whose class files are current can still hand the launch stale
	 * descriptors, and a component then registers with the wrong services.
	 */
	private static JsonObject buildForLaunch(IProject project, String buildFirst, boolean autoBuilding,
			IProgressMonitor monitor) {
		boolean full = "full".equals(buildFirst); //$NON-NLS-1$
		boolean wanted = full || ("auto".equals(buildFirst) && !autoBuilding); //$NON-NLS-1$
		JsonObject result = new JsonObject().put("requested", buildFirst) //$NON-NLS-1$
				.put("autoBuilding", Boolean.valueOf(autoBuilding)); //$NON-NLS-1$
		if (!wanted) {
			return result.put("built", Boolean.FALSE) //$NON-NLS-1$
					.put("note", autoBuilding //$NON-NLS-1$
							? "Auto-build is on, so the workspace is already current." //$NON-NLS-1$
							: "Auto-build is OFF and no build was run, so this launch may be using stale generated artefacts. Pass buildFirst auto or full to build first."); //$NON-NLS-1$
		}
		JsonArray builtProjects = new JsonArray();
		long started = System.nanoTime();
		try {
			for (IProject each : launchProjects(project)) {
				// full, not incremental, when asked: the descriptors come from a
				// compilation participant, so an incremental build that finds nothing to
				// recompile writes none of them
				each.build(full ? org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD
						: org.eclipse.core.resources.IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
				builtProjects.add(each.getName());
			}
		} catch (CoreException | RuntimeException e) {
			return result.put("built", Boolean.FALSE).put("projects", builtProjects) //$NON-NLS-1$ //$NON-NLS-2$
					.put("reason", String.valueOf(e.getMessage())); //$NON-NLS-1$
		}
		return result.put("built", Boolean.TRUE).put("kind", full ? "full" : "incremental") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("projects", builtProjects) //$NON-NLS-1$
				.put("elapsedMillis", Long.valueOf((System.nanoTime() - started) / 1_000_000L)); //$NON-NLS-1$
	}

	/** The test project and what it references, which is what the launch runs. */
	private static List<IProject> launchProjects(IProject project) throws CoreException {
		LinkedHashMap<String, IProject> found = new LinkedHashMap<>();
		ArrayDeque<IProject> queue = new ArrayDeque<>(List.of(project));
		while (!queue.isEmpty() && found.size() < 200) {
			IProject current = queue.removeFirst();
			if (!current.isAccessible() || found.putIfAbsent(current.getName(), current) != null) {
				continue;
			}
			queue.addAll(List.of(current.getReferencedProjects()));
		}
		return List.copyOf(found.values());
	}

	/**
	 * Whether the test project generates its declarative services descriptors.
	 * <p>
	 * Off by platform default, and a project that turns it on does so in its own
	 * .settings, so two projects side by side can differ. Reported always rather
	 * than only on failure: nothing else tells a caller, and it stays invisible
	 * until it is catastrophic.
	 */
	private static JsonObject descriptorGeneration(IProject project) {
		String qualifier = "org.eclipse.pde.ds.annotations"; //$NON-NLS-1$
		var lookup = org.eclipse.core.runtime.Platform.getPreferencesService();
		var scopes = new org.eclipse.core.runtime.preferences.IScopeContext[] {
				new org.eclipse.core.resources.ProjectScope(project),
				org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE };
		boolean enabled = lookup.getBoolean(qualifier, "enabled", false, scopes); //$NON-NLS-1$
		String path = lookup.getString(qualifier, "path", "OSGI-INF", scopes); //$NON-NLS-1$ //$NON-NLS-2$
		int descriptors = 0;
		try {
			var folder = project.getFolder(path);
			if (folder.exists()) {
				for (var member : folder.members()) {
					if (member.getName().endsWith(".xml")) { //$NON-NLS-1$
						descriptors++;
					}
				}
			}
		} catch (CoreException e) {
			descriptors = -1;
		}
		JsonObject result = new JsonObject().put("enabled", Boolean.valueOf(enabled)) //$NON-NLS-1$
				.put("project", project.getName()).put("path", path) //$NON-NLS-1$ //$NON-NLS-2$
				.put("descriptorsOnDisk", Integer.valueOf(descriptors)); //$NON-NLS-1$
		if (!enabled) {
			return result.put("note", //$NON-NLS-1$
					"This project does NOT generate its OSGi declarative services descriptors: org.eclipse.pde.ds.annotations/enabled is false for it, which is the platform default, and a project that wants them turns it on in its own .settings. A plug-in launch reads those files off disk, so a component whose descriptor is missing registers with the wrong services and the launched platform misbehaves in ways that look nothing like a descriptor problem. Turning the preference on changes nothing by itself: the descriptors are written by a compilation participant, so only sources that are actually recompiled produce them. Set the preference, then run once with buildFirst full."); //$NON-NLS-1$
		}
		if (descriptors == 0) {
			return result.put("note", //$NON-NLS-1$
					"Descriptor generation is on for this project but nothing is under %s, which is what a project looks like when the preference was turned on and nothing has been recompiled since. The descriptors come from a compilation participant, so run once with buildFirst full." //$NON-NLS-1$
							.formatted(path));
		}
		return result.put("note", //$NON-NLS-1$
				"Descriptor generation is on and descriptors are present, so an incremental build keeps them current. Whether they MATCH the current source cannot be checked from here: a descriptor generated against older source is a file like any other, and that failure is invisible until the launched platform misbehaves."); //$NON-NLS-1$
	}

	/**
	 * Sends a UI run to another X display, so the workbench does not open over
	 * whatever the person at the machine is doing.
	 * <p>
	 * The environment is appended rather than replaced: a launch needs the rest of
	 * it. GDK_BACKEND goes with the display because GTK otherwise takes the Wayland
	 * compositor and the display is silently ignored, which is the failure this
	 * whole feature exists to avoid.
	 */
	private static JsonObject applyDisplay(ILaunchConfigurationWorkingCopy configuration, String display) {
		if (display == null) {
			return null;
		}
		String windowSystem = org.eclipse.core.runtime.Platform.getWS();
		JsonObject result = new JsonObject().put("requested", display) //$NON-NLS-1$
				.put("windowSystem", windowSystem); //$NON-NLS-1$
		if (!"gtk".equals(windowSystem)) { //$NON-NLS-1$
			return result.put("applied", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", //$NON-NLS-1$
							"DISPLAY only redirects an X11 toolkit, and this IDE runs on the %s window system, where SWT does not draw through X11. The run will open on the usual screen." //$NON-NLS-1$
									.formatted(windowSystem));
		}
		configuration.setAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES,
				Map.of("DISPLAY", display, "GDK_BACKEND", "x11")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		configuration.setAttribute(ILaunchManager.ATTR_APPEND_ENVIRONMENT_VARIABLES, true);
		return result.put("applied", Boolean.TRUE) //$NON-NLS-1$
				.put("variables", "DISPLAY=%s, GDK_BACKEND=x11".formatted(display)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("note", //$NON-NLS-1$
						"The workbench opens on that display rather than on this screen. Nothing here starts or checks it: a launch against a display that is not running fails in the launched process, not in this answer."); //$NON-NLS-1$
	}

	/** How the run would be launched, which a dry run has to report as well. */
	private static String launchedAs(org.eclipse.core.resources.IProject project, ToolArguments args)
			throws CoreException {
		String pluginTest = args.getString("pluginTest", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
		boolean asPlugin = "true".equals(pluginTest) //$NON-NLS-1$
				|| ("auto".equals(pluginTest) && project.hasNature(PLUGIN_NATURE)); //$NON-NLS-1$
		boolean ui = args.getBoolean("ui", false); //$NON-NLS-1$
		return asPlugin ? (ui ? "pluginTest-ui" : "pluginTest") : "junit"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private static JsonObject dryRun(IJavaProject javaProject, IType type, IProgressMonitor monitor, int maxResults,
			String launchedAs) throws CoreException {
		List<String> names = new ArrayList<>();
		JsonObject result = new JsonObject().put("dryRun", Boolean.TRUE) //$NON-NLS-1$
				.put("testKind", testKind(javaProject)) //$NON-NLS-1$
				.put("launchedAs", launchedAs); //$NON-NLS-1$
		try {
			for (IType candidate : JUnitCore.findTestTypes(type == null ? javaProject : type, monitor)) {
				names.add(candidate.getFullyQualifiedName());
			}
		} catch (JavaModelException | RuntimeException e) {
			// JDT's own scan descends into an anonymous type declared inside a lambda
			// and builds a handle that does not resolve, and the failure takes the whole
			// project with it. Scanning type by type costs one type instead.
			int skipped = perTypeScan(javaProject, monitor, names);
			result.put("scan", "perType") //$NON-NLS-1$ //$NON-NLS-2$
					.put("skippedTypes", Integer.valueOf(skipped)) //$NON-NLS-1$
					.put("scanNote", //$NON-NLS-1$
							"The project-wide scan of JDT failed with '%s', so the types were collected one at a time and %d of them were skipped. The list may therefore be incomplete." //$NON-NLS-1$
									.formatted(String.valueOf(e.getMessage()), Integer.valueOf(skipped)));
		}
		names.sort(String::compareTo);
		JsonArray types = new JsonArray();
		names.stream().limit(maxResults).forEach(types::add);
		return result.put("total", Integer.valueOf(names.size())) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(names.size() > maxResults)) //$NON-NLS-1$
				.put("testTypes", types); //$NON-NLS-1$
	}

	/**
	 * Asks JDT per top level type instead of per project, so that one type it
	 * cannot resolve costs that type rather than the answer. Returns how many were
	 * skipped, because a scan that quietly returns fewer tests is worse than one
	 * that says it was incomplete.
	 */
	private static int perTypeScan(IJavaProject javaProject, IProgressMonitor monitor, List<String> into)
			throws JavaModelException {
		int skipped = 0;
		for (IPackageFragment fragment : javaProject.getPackageFragments()) {
			if (fragment.getKind() != IPackageFragmentRoot.K_SOURCE) {
				continue;
			}
			for (ICompilationUnit unit : fragment.getCompilationUnits()) {
				for (IType candidate : unit.getTypes()) {
					try {
						for (IType test : JUnitCore.findTestTypes(candidate, monitor)) {
							into.add(test.getFullyQualifiedName());
						}
					} catch (CoreException | RuntimeException e) {
						skipped++;
					}
				}
			}
		}
		return skipped;
	}

	/**
	 * Which JUnit the project is on, asked of JDT rather than worked out here.
	 * <p>
	 * The kind has to be the one {@code JUnitLaunchConfigurationDelegate} would
	 * pick, because that delegate rechecks it in {@code preLaunchCheck} and aborts
	 * the launch when the two disagree. Since jdt.junit.core 3.14 the Jupiter kind
	 * is split into junit5 and junit6, and a project on JUnit Platform 6 launched
	 * as junit5 is refused with "Cannot find
	 * 'org.junit.platform.commons.annotation.Testable' on project build path",
	 * naming the one thing that is on the path. Reading the platform version here
	 * would be a copy of that decision that can drift from it; calling the
	 * registry cannot.
	 * <p>
	 * {@code TestKindRegistry} is x-friends to four JDT bundles, so this is a
	 * discouraged access, taken deliberately: there is no public API for the
	 * question, and a JDT rename fails this build rather than mispicking a runner
	 * at launch time.
	 */
	private static String testKind(IJavaProject javaProject) {
		return TestKindRegistry.getContainerTestKindId(javaProject);
	}
}
