package com.vogella.eclipse.mcp.basic.internal;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.osgi.service.debug.DebugOptions;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.StartupTimeline;
import com.vogella.eclipse.mcp.core.StartupTimeline.BundleActivation;
import com.vogella.eclipse.mcp.core.StartupTimeline.Mark;
import com.vogella.eclipse.mcp.core.StartupTimeline.Phase;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports when this process, framework, application and workbench started, as far as anything observed it.
 */
public final class GetStartupTimingTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 50;

	private static final String WORKBENCH_SERVICE = "org.eclipse.e4.ui.workbench.IWorkbench"; //$NON-NLS-1$

	private static final String BUNDLE_START_TIME_OPTION = "org.eclipse.osgi/debug/bundleStartTime"; //$NON-NLS-1$

	private static final String BUNDLE_TIME_OPTION = "org.eclipse.osgi/debug/bundleTime"; //$NON-NLS-1$

	/** Read here because PerformanceStats.ENABLED would load Platform, which this tool must not do during startup. */
	private static final String PERFORMANCE_STATS_OPTION = "org.eclipse.core.runtime/perf"; //$NON-NLS-1$

	/** What can be read at any time, apart from the timeline. Any of it can be null. */
	public record OnDemand(Long processStart, Long jvmStart, String startTimeProperty, boolean workbenchService,
			JsonObject debugOptions) {
	}

	@Override
	public String getName() {
		return "eclipse_get_startup_timing"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports when this IDE started: process, JVM, framework, framework ready, workspace location set, application start, first workbench shell, main window visible, its first paint, application running and workbench startup complete, plus how long each bundle's activation took. Read-only, no UI thread and no workspace lock needed, so it answers while the IDE is still starting, with what has been reached so far. Use it instead of polling native window sizes (the native splash carries the same title as the main window) or parsing log timestamps. Every phase is either measured, with an ISO instant, milliseconds since process start and obtainedBy saying how, or null with a reason; nothing is estimated. The UI phases come from listeners that start with this plug-in's components, so they are null in a headless application, and null with a reason saying so when the plug-in arrived after the workbench was up, for example through a hot install. The native splash and a workspace chooser are never timed, and time spent in the chooser dialog counts against every later phase: workspaceLocationSet shows where it ended. Bundles are sorted slowest first; a duration is wall clock from STARTING to STARTED and includes any bundle started from inside that activator. Bundles active or already in their activator before the listener started have no timing and are listed by name only, bundleActivation.startupEnd says what separates startup activations from later ones, if anything does, and bundleActivation.debugOptions says whether the framework's own trace, which covers them, was switched on from launch."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "maxResults": {"type":"integer","default":50,"minimum":1,"maximum":500,"description":"Limits each bundle list. Each list reports total and truncated; the timed list keeps the slowest."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		int maxResults = ToolArguments.of(arguments).getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 500); //$NON-NLS-1$
		BundleContext context = FrameworkUtil.getBundle(GetStartupTimingTool.class).getBundleContext();
		OnDemand onDemand = new OnDemand(processStart(), Long.valueOf(ManagementFactory.getRuntimeMXBean().getStartTime()),
				startTimeProperty(context), context != null && context.getServiceReference(WORKBENCH_SERVICE) != null,
				debugOptions(context));
		return McpToolResult.of(describe(StartupTimeline.getDefault(), onDemand, maxResults).toString());
	}

	/** Builds the answer from what was observed, so every outcome can be checked without starting a workbench. */
	public static JsonObject describe(StartupTimeline timeline, OnDemand onDemand, int maxResults) {
		Long base = onDemand.processStart();
		JsonObject phases = new JsonObject();
		phases.put("processStart", onDemand(base, base, "ProcessHandle.current().info().startInstant(), from the operating system.", //$NON-NLS-1$ //$NON-NLS-2$
				"The operating system does not report this process's start time, so milliseconds since process start are null everywhere; there is no substitute that means the same.")); //$NON-NLS-1$
		phases.put("jvmStart", onDemand(onDemand.jvmStart(), base, "RuntimeMXBean.getStartTime(), the JVM's own stamp from early in its startup.", //$NON-NLS-1$ //$NON-NLS-2$
				"The JVM did not report its start time.")); //$NON-NLS-1$
		phases.put("frameworkStart", frameworkStart(onDemand.startTimeProperty(), base)); //$NON-NLS-1$
		phases.put("frameworkReady", framework(timeline, Phase.FRAMEWORK_READY, base)); //$NON-NLS-1$
		phases.put("workspaceLocationSet", framework(timeline, Phase.WORKSPACE_LOCATION_SET, base)); //$NON-NLS-1$
		phases.put("applicationStart", framework(timeline, Phase.APPLICATION_START, base)); //$NON-NLS-1$
		phases.put("nativeSplash", unknown( //$NON-NLS-1$
				"The launcher creates the native splash window in its own native code, and nothing records when. Its title is the main window's, so polling windows cannot tell them apart either.")); //$NON-NLS-1$
		boolean workbench = onDemand.workbenchService();
		phases.put("firstShell", ui(timeline, Phase.FIRST_SHELL, workbench, base)); //$NON-NLS-1$
		phases.put("mainWindowVisible", ui(timeline, Phase.MAIN_WINDOW_VISIBLE, workbench, base)); //$NON-NLS-1$
		phases.put("mainWindowFirstPaint", ui(timeline, Phase.MAIN_WINDOW_FIRST_PAINT, workbench, base)); //$NON-NLS-1$
		phases.put("applicationRunning", framework(timeline, Phase.APPLICATION_RUNNING, base)); //$NON-NLS-1$
		phases.put("workbenchStartupComplete", ui(timeline, Phase.WORKBENCH_STARTUP_COMPLETE, workbench, base)); //$NON-NLS-1$
		return new JsonObject().put("phases", phases) //$NON-NLS-1$
				.put("workbenchServiceRegistered", Boolean.valueOf(workbench)) //$NON-NLS-1$
				.put("capture", capture(timeline, base)) //$NON-NLS-1$
				.put("bundleActivation", bundleActivation(timeline, onDemand.debugOptions(), base, maxResults)); //$NON-NLS-1$
	}

	private static JsonObject frameworkStart(String property, Long base) {
		if (property == null) {
			return unknown(
					"eclipse.startTime is not set: it comes from org.eclipse.equinox.launcher.Main or EclipseStarter.main, and this framework was started by something else. The framework keeps no other record of its start; the system bundle's lastModified is its install time."); //$NON-NLS-1$
		}
		try {
			return measured(Long.parseLong(property.trim()), base,
					"The system property eclipse.startTime, which the launcher sets on entry just before it starts the framework, and which the !SESSION line of the log also shows."); //$NON-NLS-1$
		} catch (NumberFormatException e) {
			return unknown("eclipse.startTime is not a number: " + property); //$NON-NLS-1$
		}
	}

	private static JsonObject onDemand(Long value, Long base, String obtainedBy, String reason) {
		return value == null ? unknown(reason) : measured(value.longValue(), base, obtainedBy);
	}

	private static JsonObject framework(StartupTimeline timeline, Phase phase, Long base) {
		Mark mark = timeline.get(phase);
		if (mark != null) {
			return measured(mark.epochMillis(), base, mark.obtainedBy());
		}
		String missed = timeline.missedReason(phase);
		if (missed != null) {
			return unknown(missed);
		}
		return unknown(timeline.frameworkCaptureStarted() == null
				? "The listener never started, so the Service Component Runtime is missing or this plug-in's component failed. The framework keeps no record of this phase." //$NON-NLS-1$
				: "Not reached yet, or never reached by this application."); //$NON-NLS-1$
	}

	private static JsonObject ui(StartupTimeline timeline, Phase phase, boolean workbench, Long base) {
		Mark mark = timeline.get(phase);
		if (mark != null) {
			return measured(mark.epochMillis(), base, mark.obtainedBy());
		}
		if (timeline.uiCaptureFailure() != null) {
			return unknown("The UI listeners could not start, so nothing was listening: " + timeline.uiCaptureFailure()); //$NON-NLS-1$
		}
		if (timeline.uiCaptureStarted() == null) {
			return unknown(
					"com.vogella.eclipse.mcp.ui is not installed or its component has not run, so nothing was listening. This is the normal case in a headless application."); //$NON-NLS-1$
		}
		if (timeline.uiCaptureLate()) {
			return unknown(
					"The listener started after the workbench already existed, for example because this plug-in was installed into a running IDE, and the platform keeps no record of this phase. It is known after a restart."); //$NON-NLS-1$
		}
		if (timeline.get(Phase.WORKBENCH_STARTUP_COMPLETE) != null) {
			if (phase == Phase.MAIN_WINDOW_FIRST_PAINT) {
				return unknown(
						"Not observed when startup finished. A window that is showing has not painted yet and is measured when it does; one that was hidden or minimized at that moment is no longer watched."); //$NON-NLS-1$
			}
			if (phase == Phase.FIRST_SHELL) {
				return unknown(
						"Startup finished without a Shell being observed: the listener was not in place for the widget event of the first MWindow, or no MWindow was rendered."); //$NON-NLS-1$
			}
			return unknown(
					"Startup finished without this phase being observed, for example because the window started hidden or minimized."); //$NON-NLS-1$
		}
		if (!workbench) {
			return unknown("No workbench is running: this is a headless application, or the IDE has not got that far."); //$NON-NLS-1$
		}
		return unknown("Not reached yet: the workbench is coming up."); //$NON-NLS-1$
	}

	private static JsonObject capture(StartupTimeline timeline, Long base) {
		return new JsonObject().put("frameworkListeners", captureStart(timeline.frameworkCaptureStarted(), base, //$NON-NLS-1$
				"com.vogella.eclipse.mcp.basic's immediate component, StartupCaptureComponent, which runs when the bundle reaches its start level.")) //$NON-NLS-1$
				.put("uiListeners", captureStart(timeline.uiCaptureStarted(), base, //$NON-NLS-1$
						"com.vogella.eclipse.mcp.ui's immediate component, McpUiComponent.")); //$NON-NLS-1$
	}

	private static JsonObject captureStart(Long started, Long base, String obtainedBy) {
		return onDemand(started, base, obtainedBy, "The component did not run."); //$NON-NLS-1$
	}

	private static JsonObject bundleActivation(StartupTimeline timeline, JsonObject debugOptions, Long base, int maxResults) {
		Mark complete = timeline.get(Phase.WORKBENCH_STARTUP_COMPLETE);
		Mark running = timeline.get(Phase.APPLICATION_RUNNING);
		Mark end = complete != null ? complete : running;
		List<BundleActivation> during = new ArrayList<>();
		int after = 0;
		for (BundleActivation activation : timeline.activations()) {
			if (end != null && activation.startedMillis() > end.epochMillis()) {
				after++;
			} else {
				during.add(activation);
			}
		}
		during.sort(Comparator.comparingLong(BundleActivation::durationMillis).reversed());
		JsonArray bundles = new JsonArray();
		for (BundleActivation activation : during.subList(0, Math.min(maxResults, during.size()))) {
			bundles.add(new JsonObject().put("symbolicName", activation.symbolicName()) //$NON-NLS-1$
					.put("version", activation.version()) //$NON-NLS-1$
					.put("durationMillis", Long.valueOf(activation.durationMillis())) //$NON-NLS-1$
					.put("startedInstant", Instant.ofEpochMilli(activation.startedMillis()).toString()) //$NON-NLS-1$
					.put("startedMillisSinceProcessStart", since(activation.startedMillis(), base))); //$NON-NLS-1$
		}
		return new JsonObject().put("obtainedBy", //$NON-NLS-1$
				"A SynchronousBundleListener timing STARTING to STARTED on the activating thread. A listener sees no history, so this covers only what started after it did.") //$NON-NLS-1$
				.put("total", during.size()) //$NON-NLS-1$
				.put("truncated", during.size() > bundles.size()) //$NON-NLS-1$
				.put("bundles", bundles) //$NON-NLS-1$
				.put("activatedAfterStartupComplete", end == null ? null : Integer.valueOf(after)) //$NON-NLS-1$
				.put("startupEnd", startupEnd(complete, running)) //$NON-NLS-1$
				.put("activeBeforeCapture", names(timeline.activeBeforeCapture(), maxResults)) //$NON-NLS-1$
				.put("stillStarting", names(timeline.stillStarting(), maxResults)) //$NON-NLS-1$
				.put("debugOptions", debugOptions); //$NON-NLS-1$
	}

	private static JsonObject startupEnd(Mark complete, Mark running) {
		if (complete != null) {
			return new JsonObject().put("phase", "workbenchStartupComplete") //$NON-NLS-1$ //$NON-NLS-2$
					.put("note", "Activations after this phase are counted in activatedAfterStartupComplete rather than listed."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (running != null) {
			return new JsonObject().put("phase", "applicationRunning") //$NON-NLS-1$ //$NON-NLS-2$
					.put("note", "workbenchStartupComplete has not been observed, so applicationRunning stands in for it: activatedAfterStartupComplete counts the activations after that phase."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return new JsonObject().put("phase", null) //$NON-NLS-1$
				.put("note", "Neither workbenchStartupComplete nor applicationRunning has been observed, so nothing separates startup from later activations here: bundles holds every activation seen since the listener started, and activatedAfterStartupComplete is null."); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static JsonObject names(List<String> names, int maxResults) {
		JsonArray reported = new JsonArray();
		for (String name : names.subList(0, Math.min(maxResults, names.size()))) {
			reported.add(name);
		}
		return new JsonObject().put("total", names.size()) //$NON-NLS-1$
				.put("truncated", names.size() > reported.size()) //$NON-NLS-1$
				.put("bundles", reported); //$NON-NLS-1$
	}

	private static JsonObject measured(long epochMillis, Long base, String obtainedBy) {
		return new JsonObject().put("measured", Boolean.TRUE) //$NON-NLS-1$
				.put("instant", Instant.ofEpochMilli(epochMillis).toString()) //$NON-NLS-1$
				.put("millisSinceProcessStart", since(epochMillis, base)) //$NON-NLS-1$
				.put("obtainedBy", obtainedBy); //$NON-NLS-1$
	}

	private static JsonObject unknown(String reason) {
		return new JsonObject().put("measured", Boolean.FALSE) //$NON-NLS-1$
				.put("instant", null) //$NON-NLS-1$
				.put("millisSinceProcessStart", null) //$NON-NLS-1$
				.put("reason", reason); //$NON-NLS-1$
	}

	private static Long since(long epochMillis, Long base) {
		return base == null ? null : Long.valueOf(epochMillis - base.longValue());
	}

	private static Long processStart() {
		return ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(null);
	}

	private static String startTimeProperty(BundleContext context) {
		String property = context == null ? null : context.getProperty("eclipse.startTime"); //$NON-NLS-1$
		return property != null ? property : System.getProperty("eclipse.startTime"); //$NON-NLS-1$
	}

	private static JsonObject debugOptions(BundleContext context) {
		JsonObject json = new JsonObject();
		ServiceReference<DebugOptions> reference = context == null ? null : context.getServiceReference(DebugOptions.class);
		DebugOptions options = reference == null ? null : context.getService(reference);
		boolean bundleStartTime = false;
		boolean bundleTime = false;
		boolean performanceStats = false;
		String traceFile = null;
		if (options != null) {
			try {
				bundleStartTime = options.getBooleanOption(BUNDLE_START_TIME_OPTION, false);
				bundleTime = options.getBooleanOption(BUNDLE_TIME_OPTION, false);
				performanceStats = options.getBooleanOption(PERFORMANCE_STATS_OPTION, false);
				File file = options.getFile();
				traceFile = file == null ? null : file.getPath();
			} finally {
				context.ungetService(reference);
			}
		}
		return json.put(BUNDLE_START_TIME_OPTION, Boolean.valueOf(bundleStartTime))
				.put(BUNDLE_TIME_OPTION, Boolean.valueOf(bundleTime))
				.put("traceFile", traceFile) //$NON-NLS-1$
				.put("performanceStats", Boolean.valueOf(performanceStats)) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"The two OSGi options make the framework trace every bundle's activation, the ones before this listener included, to traceFile (standard output when null). They only help when on from launch, through -debug and a .options file: they keep no history and this tool does not parse the trace. performanceStats is org.eclipse.core.runtime/perf, without which the workbench's own timing events are not recorded either."); //$NON-NLS-1$
	}
}
