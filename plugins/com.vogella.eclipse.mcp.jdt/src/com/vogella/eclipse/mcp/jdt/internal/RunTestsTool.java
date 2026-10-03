package com.vogella.eclipse.mcp.jdt.internal;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
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
			String kind = testKind(type == null ? javaProject : type);
			String pluginTest = args.getString("pluginTest", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
			boolean asPlugin = "true".equals(pluginTest) //$NON-NLS-1$
					|| ("auto".equals(pluginTest) && project.hasNature(PLUGIN_NATURE)); //$NON-NLS-1$
			boolean ui = args.getBoolean("ui", false); //$NON-NLS-1$
			String launchedAs = launchedAs(project, args);
			// this run answers the prompt itself and puts the setting back
			String compileErrorPromptWas = CompileErrorPrompt.effectiveValue();
			TestRunRegistry.Run run = TestRunRegistry.getInstance()
					.create(testClass == null ? projectName : testClass + (testMethod == null ? "" : "#" + testMethod)); //$NON-NLS-1$ //$NON-NLS-2$

			// a run stuck in "running" would block every later run until abandoned
			boolean scheduled = false;
			try {
				ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
				ILaunchConfigurationType launchType = manager
						.getLaunchConfigurationType(asPlugin ? PLUGIN_LAUNCH_TYPE : LAUNCH_TYPE);
				if (launchType == null) {
					TestRunRegistry.failed(run, "no launch configuration type"); //$NON-NLS-1$
					return McpToolResult.error(asPlugin
							? "This IDE has no plug-in JUnit launch configuration type, so PDE is probably not installed. Pass pluginTest false to run as plain JUnit." //$NON-NLS-1$
							: "This IDE has no JUnit launch configuration type."); //$NON-NLS-1$
				}
				ILaunchConfigurationWorkingCopy configuration = launchType.newInstance(null, run.launchName());
				configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName);
				configuration.setAttribute(ATTR_TEST_KIND, kind);
				// a suspended debugged test would otherwise raise the modal perspective switch prompt
				configuration.setAttribute(LaunchAttributes.TARGET_DEBUG_PERSPECTIVE,
						LaunchAttributes.PERSPECTIVE_NONE);
				configuration.setAttribute(LaunchAttributes.TARGET_RUN_PERSPECTIVE,
						LaunchAttributes.PERSPECTIVE_NONE);
				configuration.setAttribute(LaunchAttributes.STARTED_BY_MCP, true);
				// launching saves the configuration; private keeps it out of the user's Run Configurations
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
				String testBundle = FileText.symbolicName(project.getFile("META-INF/MANIFEST.MF"));
				if (asPlugin) {
					configurePlatform(configuration, args.getString("runtimeWorkspace"), ui, allWorkspacePlugins, //$NON-NLS-1$
							testBundle);
				}
				// unbuilt workspace plug-ins shadow installed bundles and keep the UI workbench from starting
				JsonObject preflight = ui && asPlugin ? unbuiltWorkspacePlugins() : null;
				// launched in a job: preLaunchCheck alone can outlast wait:false
				run.launchedAs(launchedAs);
				boolean debug = args.getBoolean("debug", false); //$NON-NLS-1$
				Job.create("MCP test launch " + run.id(), progress -> { //$NON-NLS-1$
					String previous = CompileErrorPrompt.suppress();
					try {
						ILaunch launch = configuration.launch(
								debug ? ILaunchManager.DEBUG_MODE : ILaunchManager.RUN_MODE, null);
						TestRunRegistry.watch(run, launch, asPlugin ? 300 : 120);
					} catch (CoreException | RuntimeException e) {
						// the runner bundles ship with the SDK, and JDT reports a missing one
						// as an assertion rather than a CoreException
						TestRunRegistry.failed(run, describe(e));
					} finally {
						CompileErrorPrompt.restore(previous);
					}
					return Status.OK_STATUS;
				}).schedule();
				scheduled = true;

				if (args.getBoolean("wait", !asPlugin)) { //$NON-NLS-1$
					try {
						run.await(args.getInt("timeoutSeconds", 25, 1, 3600)); //$NON-NLS-1$
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				JsonObject result = TestRunRegistry.toJson(run, args.getInt("maxResults", 50, 1, 2000), false) //$NON-NLS-1$
						.put("testKind", kind); //$NON-NLS-1$
				if (asPlugin && run.running()) {
					result.put("note", //$NON-NLS-1$
							"A second Eclipse is starting, which takes tens of seconds before the first test runs. Poll eclipse_get_test_results with this runId."); //$NON-NLS-1$
				}
				// read back from the configuration, not echoed
				if (asPlugin) {
					result.put("launchAttributes", new JsonObject() //$NON-NLS-1$
							.put(IPDELauncherConstants.APPLICATION,
									configuration.getAttribute(IPDELauncherConstants.APPLICATION, (String) null))
							.put(IPDELauncherConstants.APP_TO_TEST,
									configuration.getAttribute(IPDELauncherConstants.APP_TO_TEST, (String) null))
							.put("workspacePlugins", allWorkspacePlugins ? "all" : "required") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							.put("workspaceBundle", testBundle) //$NON-NLS-1$
							.put(IPDELauncherConstants.USE_DEFAULT,
									configuration.getAttribute(IPDELauncherConstants.USE_DEFAULT, true))
							.put("configurationArea", //$NON-NLS-1$
									"%s/.metadata/.plugins/org.eclipse.pde.core/%s (config.ini holds osgi.bundles; bundles.info is written next to it only for the wide set)" //$NON-NLS-1$
											.formatted(ResourcesPlugin.getWorkspace().getRoot()
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
			} catch (CoreException | RuntimeException e) {
				if (!scheduled) {
					TestRunRegistry.failed(run, describe(e));
				}
				throw e;
			}
		} catch (CoreException e) {
			throw new McpToolException(
					"Could not run the tests of %s: %s".formatted(projectName, describe(e)), e); //$NON-NLS-1$
		}
	}

	/**
	 * The projects the launch depends on that do not compile, transitively.
	 * The launch delegate checks the whole required closure, not just direct references.
	 */
	private static JsonArray projectsWithErrors(IProject project) {
		Set<String> seen = new LinkedHashSet<>();
		ArrayDeque<IProject> queue = new ArrayDeque<>(List.of(project));
		JsonArray broken = new JsonArray();
		while (!queue.isEmpty() && seen.size() < 500) {
			IProject current = queue.removeFirst();
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
			for (IMarker marker : project.findMarkers(
					IMarker.PROBLEM, true,
					IResource.DEPTH_INFINITE)) {
				if (marker.getAttribute(IMarker.SEVERITY,
						-1) == IMarker.SEVERITY_ERROR) {
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
	 * Configures a plug-in test launch with its own workspace and a headless application unless {@code ui} is set.
	 */
	private static void configurePlatform(ILaunchConfigurationWorkingCopy configuration, String runtimeWorkspace,
			boolean ui, boolean allWorkspacePlugins, String testBundle) {
		// APPLICATION set means a headless app, unset means the UI one; APP_TO_TEST is the UI product only
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
		// USE_DEFAULT true makes PDE ignore the selection (BundleLauncherHelper.getMergedBundleMap), so it must follow
		// allWorkspacePlugins; AUTOMATIC_ADD true takes every workspace plug-in, which breaks UI launches with unbuilt copies
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
	 * Markers only, no build, so with auto-build off an empty answer proves nothing.
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
	 * Builds the projects going into the launch, when anything else would not.
	 * The OSGI-INF descriptors are written by the DS builder, so current class files can still come with stale descriptors.
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
				// descriptors come from a compilation participant, so an incremental build may write none
				each.build(full ? IncrementalProjectBuilder.FULL_BUILD
						: IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
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
	 * Off by platform default and set per project, so it is always reported.
	 */
	private static JsonObject descriptorGeneration(IProject project) {
		String qualifier = "org.eclipse.pde.ds.annotations"; //$NON-NLS-1$
		var lookup = Platform.getPreferencesService();
		var scopes = new IScopeContext[] {
				new ProjectScope(project),
				InstanceScope.INSTANCE };
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
	 * Sends a UI run to another X display.
	 * GDK_BACKEND goes with it because GTK otherwise takes the Wayland compositor and ignores the display.
	 */
	private static JsonObject applyDisplay(ILaunchConfigurationWorkingCopy configuration, String display) {
		if (display == null) {
			return null;
		}
		String windowSystem = Platform.getWS();
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
	private static String launchedAs(IProject project, ToolArguments args)
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
				.put("testKind", testKind(type == null ? javaProject : type)) //$NON-NLS-1$
				.put("launchedAs", launchedAs); //$NON-NLS-1$
		try {
			for (IType candidate : JUnitCore.findTestTypes(type == null ? javaProject : type, monitor)) {
				names.add(candidate.getFullyQualifiedName());
			}
		} catch (JavaModelException | RuntimeException e) {
			// JDT descends into an anonymous type inside a lambda and fails for the whole project
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
	 * Asks JDT per top level type so one unresolvable type costs only itself, and returns how many were skipped.
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
	 * The test kind JDT's launch delegate expects for the element, which tells JUnit 5 from JUnit 6 and,
	 * given the test class, a {@code @RunWith(JUnitPlatform)} class from a Jupiter one.
	 */
	private static String testKind(IJavaElement element) {
		return TestKindRegistry.getContainerTestKindId(element);
	}
}
