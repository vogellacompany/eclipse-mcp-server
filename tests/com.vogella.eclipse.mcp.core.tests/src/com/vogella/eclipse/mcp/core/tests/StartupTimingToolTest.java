package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.eclipse.e4.ui.model.application.ui.basic.MBasicFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.service.event.Event;

import com.vogella.eclipse.mcp.basic.internal.GetStartupTimingTool;
import com.vogella.eclipse.mcp.basic.internal.GetStartupTimingTool.OnDemand;
import com.vogella.eclipse.mcp.basic.internal.StartupCaptureComponent;
import com.vogella.eclipse.mcp.core.StartupTimeline;
import com.vogella.eclipse.mcp.core.StartupTimeline.Phase;
import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.ui.internal.StartupUiCapture;

class StartupTimingToolTest {

	private static final String TOOL = "eclipse_get_startup_timing";

	private static final String DESCRIPTOR = "OSGI-INF/com.vogella.eclipse.mcp.basic.internal.StartupCaptureComponent.xml";

	private static final long PROCESS_START = 1_000_000L;

	private static final OnDemand NOTHING_KNOWN = new OnDemand(null, null, null, false, new JsonObject());

	private static final OnDemand KNOWN = new OnDemand(Long.valueOf(PROCESS_START), Long.valueOf(PROCESS_START + 40),
			Long.toString(PROCESS_START + 90), true, new JsonObject());

	@Test
	@SuppressWarnings("unchecked")
	void everyPhaseOfARealCallIsEitherMeasuredOrNullWithAReason() throws Exception {
		Map<String, Object> phases = (Map<String, Object>) TestFixture.callAndParse(TOOL, Map.of()).get("phases");
		assertEquals(12, phases.size(), phases.toString());
		phases.forEach((name, value) -> {
			Map<String, Object> phase = (Map<String, Object>) value;
			if (Boolean.TRUE.equals(phase.get("measured"))) {
				Instant.parse((String) phase.get("instant"));
				assertNotNull(phase.get("obtainedBy"), name);
				assertNull(phase.get("reason"), name);
			} else {
				assertNull(phase.get("instant"), name);
				assertNull(phase.get("millisSinceProcessStart"), name);
				assertFalse(((String) phase.get("reason")).isBlank(), name);
			}
		});
		Map<String, Object> jvm = (Map<String, Object>) phases.get("jvmStart");
		assertEquals(Boolean.TRUE, jvm.get("measured"), jvm.toString());
	}

	/** This suite has no workbench, so the UI phases have to come back null, not refused and not invented. */
	@Test
	@SuppressWarnings("unchecked")
	void aHeadlessCallReportsTheUiPhasesAsNull() throws Exception {
		Map<String, Object> answer = TestFixture.callAndParse(TOOL, Map.of());
		assertEquals(Boolean.FALSE, answer.get("workbenchServiceRegistered"), answer.toString());
		Map<String, Object> phases = (Map<String, Object>) answer.get("phases");
		for (String name : List.of("firstShell", "mainWindowVisible", "mainWindowFirstPaint",
				"workbenchStartupComplete")) {
			Map<String, Object> phase = (Map<String, Object>) phases.get(name);
			assertEquals(Boolean.FALSE, phase.get("measured"), name);
			assertTrue(((String) phase.get("reason")).contains("workbench"), phase.toString());
		}
	}

	/**
	 * The default application's handle exists long before the application runs, so a mark taken from it comes out as
	 * missed: only the launcher's registration, after the start level, stays measurable from the listener.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void applicationStartIsMeasuredAfterTheFrameworkIsReady() throws Exception {
		Map<String, Object> phases = (Map<String, Object>) TestFixture.callAndParse(TOOL, Map.of()).get("phases");
		Map<String, Object> application = (Map<String, Object>) phases.get("applicationStart");
		assertEquals(Boolean.TRUE, application.get("measured"), application.toString());
		Map<String, Object> ready = (Map<String, Object>) phases.get("frameworkReady");
		assertEquals(Boolean.TRUE, ready.get("measured"), ready.toString());
	}

	@Test
	void theCaptureComponentsRunInTheFramework() {
		Bundle basic = FrameworkUtil.getBundle(StartupCaptureComponent.class);
		assertTrue(List.of(basic.getHeaders().get("Service-Component").split(",\\s*")).contains(DESCRIPTOR));
		assertNotNull(basic.getEntry(DESCRIPTOR), "The committed descriptor should be packaged");
		assertNotNull(StartupTimeline.getDefault().frameworkCaptureStarted(), "StartupCaptureComponent never activated");
		assertNotNull(StartupTimeline.getDefault().uiCaptureStarted(), "McpUiComponent never started the UI capture");
		assertFalse(StartupTimeline.getDefault().uiCaptureLate(), "No workbench exists in this suite");
	}

	/** Runs a real bundle through the framework, since only that proves the listener sees STARTING and STARTED. */
	@Test
	@SuppressWarnings("unchecked")
	void aBundleStartedAfterTheListenerIsTimed() throws Exception {
		String name = "com.vogella.eclipse.mcp.startup.timing.probe";
		Bundle tests = FrameworkUtil.getBundle(StartupTimingToolTest.class);
		Bundle probe = tests.getBundleContext().installBundle("startup-timing-probe",
				new ByteArrayInputStream(jarOf(name, null)));
		try {
			probe.start();
			Map<String, Object> answer = TestFixture.callAndParse(TOOL, Map.of("maxResults", Integer.valueOf(500)));
			Map<String, Object> activation = (Map<String, Object>) answer.get("bundleActivation");
			Map<String, Object> reported = ((List<Map<String, Object>>) activation.get("bundles")).stream()
					.filter(bundle -> name.equals(bundle.get("symbolicName"))).findFirst()
					.orElseThrow(() -> new AssertionError("Not reported: " + activation));
			assertEquals("1.0.0", reported.get("version"));
			assertTrue(((Number) reported.get("durationMillis")).longValue() >= 0, reported.toString());
			Instant.parse((String) reported.get("startedInstant"));
		} finally {
			probe.uninstall();
		}
	}

	/** Packs the activator into the probe, since only a real failed start shows which events the framework fires. */
	@Test
	@SuppressWarnings("unchecked")
	void aBundleWhoseActivatorThrowsDoesNotStayInStillStarting() throws Exception {
		String name = "com.vogella.eclipse.mcp.startup.timing.throwing.probe";
		Bundle tests = FrameworkUtil.getBundle(StartupTimingToolTest.class);
		Bundle probe = tests.getBundleContext().installBundle("startup-timing-throwing-probe",
				new ByteArrayInputStream(jarOf(name, ThrowingActivator.class)));
		List<Integer> events = new CopyOnWriteArrayList<>();
		SynchronousBundleListener recorder = event -> {
			if (event.getBundle().equals(probe) && event.getType() != BundleEvent.RESOLVED) {
				events.add(Integer.valueOf(event.getType()));
			}
		};
		tests.getBundleContext().addBundleListener(recorder);
		try {
			BundleException thrown = assertThrows(BundleException.class, probe::start);
			assertEquals(ThrowingActivator.MESSAGE, thrown.getCause().getMessage(), thrown.toString());
			assertEquals(List.of(Integer.valueOf(BundleEvent.STARTING), Integer.valueOf(BundleEvent.STOPPING),
					Integer.valueOf(BundleEvent.STOPPED)), events);
			Map<String, Object> activation = (Map<String, Object>) TestFixture
					.callAndParse(TOOL, Map.of("maxResults", Integer.valueOf(500))).get("bundleActivation");
			List<String> stillStarting = (List<String>) ((Map<String, Object>) activation.get("stillStarting"))
					.get("bundles");
			assertFalse(stillStarting.contains(name), stillStarting.toString());
			assertFalse(((List<Map<String, Object>>) activation.get("bundles")).stream()
					.anyMatch(bundle -> name.equals(bundle.get("symbolicName"))), activation.toString());
		} finally {
			tests.getBundleContext().removeBundleListener(recorder);
			probe.uninstall();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void aMeasuredPhaseSaysWhenAndHow() throws Exception {
		StartupTimeline timeline = new StartupTimeline();
		timeline.mark(Phase.APPLICATION_START, PROCESS_START + 1500, "the handle");

		Map<String, Object> phases = (Map<String, Object>) describe(timeline, KNOWN, 50).get("phases");

		Map<String, Object> application = (Map<String, Object>) phases.get("applicationStart");
		assertEquals(Instant.ofEpochMilli(PROCESS_START + 1500).toString(), application.get("instant"));
		assertEquals(1500, ((Number) application.get("millisSinceProcessStart")).intValue());
		assertEquals("the handle", application.get("obtainedBy"));
		assertEquals(0, ((Number) ((Map<String, Object>) phases.get("processStart")).get("millisSinceProcessStart")).intValue());
		assertEquals(40, ((Number) ((Map<String, Object>) phases.get("jvmStart")).get("millisSinceProcessStart")).intValue());
		assertEquals(90, ((Number) ((Map<String, Object>) phases.get("frameworkStart")).get("millisSinceProcessStart")).intValue());
	}

	@Test
	@SuppressWarnings("unchecked")
	void withoutAProcessStartNothingPretendsToBeRelativeToIt() throws Exception {
		StartupTimeline timeline = new StartupTimeline();
		timeline.mark(Phase.APPLICATION_START, PROCESS_START, "the handle");

		Map<String, Object> phases = (Map<String, Object>) describe(timeline, NOTHING_KNOWN, 50).get("phases");

		Map<String, Object> application = (Map<String, Object>) phases.get("applicationStart");
		assertEquals(Boolean.TRUE, application.get("measured"));
		assertNull(application.get("millisSinceProcessStart"));
		assertEquals(Boolean.FALSE, ((Map<String, Object>) phases.get("processStart")).get("measured"));
		assertTrue(((String) ((Map<String, Object>) phases.get("frameworkStart")).get("reason"))
				.contains("eclipse.startTime"));
	}

	@Test
	void theUiPhaseReasonTellsTheCasesApart() throws Exception {
		StartupTimeline noUi = new StartupTimeline();
		assertTrue(firstShellReason(noUi, KNOWN).contains("com.vogella.eclipse.mcp.ui"));

		StartupTimeline failed = new StartupTimeline();
		failed.uiCaptureFailed("no event admin");
		assertTrue(firstShellReason(failed, KNOWN).contains("no event admin"));

		StartupTimeline headless = new StartupTimeline();
		headless.uiCaptureStarted(false);
		assertTrue(firstShellReason(headless, NOTHING_KNOWN).contains("headless"));

		StartupTimeline coming = new StartupTimeline();
		coming.uiCaptureStarted(false);
		assertTrue(firstShellReason(coming, KNOWN).contains("Not reached yet"));

		StartupTimeline late = new StartupTimeline();
		late.uiCaptureStarted(true);
		assertTrue(firstShellReason(late, KNOWN).contains("after the workbench already existed"));

		StartupTimeline finished = new StartupTimeline();
		finished.uiCaptureStarted(false);
		finished.mark(Phase.WORKBENCH_STARTUP_COMPLETE, "the event");
		String firstShell = firstShellReason(finished, KNOWN);
		assertTrue(firstShell.contains("no MWindow was rendered"), firstShell);
		assertFalse(firstShell.contains("hidden"), firstShell);
		Map<String, Object> phases = (Map<String, Object>) describe(finished, KNOWN, 50).get("phases");
		assertTrue(((Map<String, Object>) phases.get("mainWindowVisible")).get("reason").toString().contains("hidden or minimized"));
		assertTrue(((Map<String, Object>) phases.get("mainWindowFirstPaint")).get("reason").toString().contains("is measured when it does"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void aMissedPhaseStaysMissedWhenItsEventFiresAgain() throws Exception {
		StartupTimeline timeline = new StartupTimeline();
		timeline.missed(Phase.APPLICATION_START, "had been launched");
		assertFalse(timeline.mark(Phase.APPLICATION_START, "a second application"));

		Map<String, Object> phases = (Map<String, Object>) describe(timeline, KNOWN, 50).get("phases");
		assertEquals("had been launched", ((Map<String, Object>) phases.get("applicationStart")).get("reason"));
	}

	@Test
	void theFirstObservationOfAPhaseWins() {
		StartupTimeline timeline = new StartupTimeline();
		assertTrue(timeline.mark(Phase.FIRST_SHELL, 1, "first"));
		assertFalse(timeline.mark(Phase.FIRST_SHELL, 2, "second"));
		assertEquals("first", timeline.get(Phase.FIRST_SHELL).obtainedBy());
	}

	@Test
	void aPhaseIsEitherMeasuredOrMissedWhicheverCameFirst() {
		StartupTimeline timeline = new StartupTimeline();
		assertTrue(timeline.mark(Phase.FIRST_SHELL, 1, "seen"));
		timeline.missed(Phase.FIRST_SHELL, "too late");
		assertNull(timeline.missedReason(Phase.FIRST_SHELL));
		assertEquals("seen", timeline.get(Phase.FIRST_SHELL).obtainedBy());

		timeline.missed(Phase.MAIN_WINDOW_VISIBLE, "too late");
		assertFalse(timeline.mark(Phase.MAIN_WINDOW_VISIBLE, 1, "seen"));
		assertEquals("too late", timeline.missedReason(Phase.MAIN_WINDOW_VISIBLE));
		assertNull(timeline.get(Phase.MAIN_WINDOW_VISIBLE));
	}

	@Test
	void theListenerSeesOnlyAnInstanceLocationWithAUrlAndTheApplicationServices() throws Exception {
		var filter = FrameworkUtil.createFilter(StartupCaptureComponent.SERVICES_FILTER);
		String location = "org.eclipse.osgi.service.datalocation.Location";
		assertTrue(filter.matches(Map.of("objectClass", new String[] { location }, "type", "osgi.instance.area", "url", "file:/w")));
		assertFalse(filter.matches(Map.of("objectClass", new String[] { location }, "type", "osgi.instance.area")));
		assertFalse(filter.matches(Map.of("objectClass", new String[] { location }, "type", "osgi.configuration.area", "url", "file:/c")));
		assertTrue(filter.matches(Map.of("objectClass", new String[] { "org.osgi.service.application.ApplicationHandle" })));
		assertTrue(filter.matches(Map.of("objectClass", new String[] { "org.eclipse.osgi.service.runnable.ApplicationLauncher" })));
		assertFalse(filter.matches(Map.of("objectClass", new String[] { "java.lang.Runnable" })));
	}

	@Test
	void onlyTheLocationServiceSetsTheWorkspaceLocation() {
		StartupTimeline timeline = new StartupTimeline();
		StartupCaptureComponent component = new StartupCaptureComponent(timeline);

		component.serviceChanged(serviceEvent("org.osgi.service.application.ApplicationHandle",
				Map.of("type", "not a location", "application.state", "RUNNING")));
		assertNull(timeline.get(Phase.WORKSPACE_LOCATION_SET));
		assertNotNull(timeline.get(Phase.APPLICATION_RUNNING));

		component.serviceChanged(serviceEvent("org.eclipse.osgi.service.datalocation.Location", Map.of()));
		assertNotNull(timeline.get(Phase.WORKSPACE_LOCATION_SET));
	}

	@Test
	@SuppressWarnings("unchecked")
	void theBundleListHonoursMaxResultsAndKeepsTheSlowest() throws Exception {
		StartupTimeline timeline = new StartupTimeline();
		timeline.frameworkCaptureStarted(List.of("early.one", "early.two"));
		timeline.bundleStarting(1, "fast", "1.0.0");
		timeline.bundleStarted(1, "fast");
		timeline.bundleStarting(2, "slow", "1.0.0");
		Thread.sleep(30);
		timeline.bundleStarted(2, "slow");
		timeline.bundleStarting(3, "running", "1.0.0");

		Map<String, Object> activation = (Map<String, Object>) describe(timeline, KNOWN, 1).get("bundleActivation");

		assertEquals(2, activation.get("total"));
		assertEquals(Boolean.TRUE, activation.get("truncated"));
		List<Map<String, Object>> bundles = (List<Map<String, Object>>) activation.get("bundles");
		assertEquals(1, bundles.size());
		assertEquals("slow", bundles.get(0).get("symbolicName"));
		Map<String, Object> early = (Map<String, Object>) activation.get("activeBeforeCapture");
		assertEquals(2, early.get("total"));
		assertEquals(Boolean.TRUE, early.get("truncated"));
		assertEquals(List.of("running"), ((Map<String, Object>) activation.get("stillStarting")).get("bundles"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void bundlesActivatedAfterStartupCompleteAreCountedApart() throws Exception {
		StartupTimeline timeline = new StartupTimeline();
		timeline.bundleStarting(1, "before", "1.0.0");
		timeline.bundleStarted(1, "before");
		Thread.sleep(10);
		timeline.mark(Phase.WORKBENCH_STARTUP_COMPLETE, "the event");
		Thread.sleep(10);
		timeline.bundleStarting(2, "after", "1.0.0");
		timeline.bundleStarted(2, "after");

		Map<String, Object> activation = (Map<String, Object>) describe(timeline, KNOWN, 50).get("bundleActivation");

		assertEquals(1, activation.get("activatedAfterStartupComplete"));
		assertEquals(1, activation.get("total"));
		assertEquals("workbenchStartupComplete", ((Map<String, Object>) activation.get("startupEnd")).get("phase"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void withoutStartupCompleteApplicationRunningSeparatesTheActivations() throws Exception {
		StartupTimeline timeline = new StartupTimeline();
		timeline.bundleStarting(1, "before", "1.0.0");
		timeline.bundleStarted(1, "before");
		Thread.sleep(10);
		timeline.mark(Phase.APPLICATION_RUNNING, "the handle");
		Thread.sleep(10);
		timeline.bundleStarting(2, "after", "1.0.0");
		timeline.bundleStarted(2, "after");

		Map<String, Object> activation = (Map<String, Object>) describe(timeline, KNOWN, 50).get("bundleActivation");

		assertEquals(1, activation.get("activatedAfterStartupComplete"));
		assertEquals(1, activation.get("total"));
		assertEquals("applicationRunning", ((Map<String, Object>) activation.get("startupEnd")).get("phase"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void withNeitherPhaseTheAnswerSaysNothingSeparatesTheActivations() throws Exception {
		StartupTimeline timeline = new StartupTimeline();
		timeline.bundleStarting(1, "one", "1.0.0");
		timeline.bundleStarted(1, "one");
		timeline.bundleStarting(2, "two", "1.0.0");
		timeline.bundleStarted(2, "two");

		Map<String, Object> activation = (Map<String, Object>) describe(timeline, KNOWN, 50).get("bundleActivation");

		assertEquals(2, activation.get("total"));
		assertNull(activation.get("activatedAfterStartupComplete"), activation.toString());
		Map<String, Object> end = (Map<String, Object>) activation.get("startupEnd");
		assertNull(end.get("phase"));
		assertTrue(end.get("note").toString().contains("nothing separates"), end.toString());
	}

	@Test
	void aBundleSeenOnlyAtItsStartedEventHasNoTimingAndIsListedOnce() {
		StartupTimeline timeline = new StartupTimeline();
		timeline.bundleStarted(1, "midway");
		timeline.bundleStarting(2, "timed", "1.0.0");
		timeline.bundleStarted(2, "timed");
		timeline.bundleStarting(3, "finishing", "1.0.0");
		timeline.frameworkCaptureStarted(List.of("early", "midway", "timed", "finishing"));
		timeline.bundleStarted(3, "finishing");

		assertEquals(List.of("midway", "early"), timeline.activeBeforeCapture());
		assertEquals(List.of("timed", "finishing"),
				timeline.activations().stream().map(StartupTimeline.BundleActivation::symbolicName).toList());
	}

	/** Only the real framework shows that a bundle waiting for lazy activation is STARTING without having activated. */
	@Test
	void aLazyBundleWaitingForItsTriggerIsNotReportedAsActivated() throws Exception {
		String name = "com.vogella.eclipse.mcp.startup.timing.lazy.probe";
		BundleContext context = FrameworkUtil.getBundle(StartupTimingToolTest.class).getBundleContext();
		Bundle probe = context.installBundle("startup-timing-lazy-probe", new ByteArrayInputStream(lazyJarOf(name)));
		StartupTimeline timeline = new StartupTimeline();
		StartupCaptureComponent component = new StartupCaptureComponent(timeline);
		try {
			probe.start(Bundle.START_ACTIVATION_POLICY);
			assertEquals(Bundle.STARTING, probe.getState());
			lifecycle(component, "activate", context);
			assertFalse(timeline.activeBeforeCapture().contains(name), timeline.activeBeforeCapture().toString());

			probe.start();
			assertFalse(timeline.activeBeforeCapture().contains(name), timeline.activeBeforeCapture().toString());
			assertTrue(timeline.activations().stream().anyMatch(activation -> name.equals(activation.symbolicName())));
		} finally {
			lifecycle(component, "deactivate");
			probe.uninstall();
		}
	}

	/** Listeners added while an event is delivered miss it, which is the capture starting inside an activation. */
	@Test
	void aBundleInItsActivatorWhenTheListenerStartsEndsUpWithoutTiming() throws Exception {
		String name = "com.vogella.eclipse.mcp.startup.timing.midway.probe";
		BundleContext context = FrameworkUtil.getBundle(StartupTimingToolTest.class).getBundleContext();
		Bundle probe = context.installBundle("startup-timing-midway-probe", new ByteArrayInputStream(jarOf(name, null)));
		StartupTimeline timeline = new StartupTimeline();
		StartupCaptureComponent component = new StartupCaptureComponent(timeline);
		SynchronousBundleListener starter = event -> {
			if (event.getBundle().equals(probe) && event.getType() == BundleEvent.STARTING) {
				lifecycle(component, "activate", context);
			}
		};
		context.addBundleListener(starter);
		try {
			probe.start();
			assertTrue(timeline.activeBeforeCapture().contains(name), timeline.activeBeforeCapture().toString());
			assertFalse(timeline.activations().stream().anyMatch(activation -> name.equals(activation.symbolicName())));
		} finally {
			context.removeBundleListener(starter);
			lifecycle(component, "deactivate");
			probe.uninstall();
		}
	}

	@Test
	void aFailedRegistrationDoesNotReportTheCaptureAsStarted() {
		StartupTimeline timeline = new StartupTimeline();
		StartupUiCapture capture = new StartupUiCapture(timeline);
		capture.start(contextWith(false, false));
		assertNull(timeline.uiCaptureStarted());
		assertTrue(timeline.uiCaptureFailure().contains("IllegalStateException"), timeline.uiCaptureFailure());
	}

	@Test
	void aLateStartIsStillSeenWhenItIsRecordedAfterRegistering() {
		StartupTimeline timeline = new StartupTimeline();
		new StartupUiCapture(timeline).start(contextWith(true, true));
		assertNotNull(timeline.uiCaptureStarted());
		assertTrue(timeline.uiCaptureLate());
	}

	@Test
	void theUiCaptureRecordsStartupCompleteAndIgnoresOtherElements() {
		StartupTimeline timeline = new StartupTimeline();
		StartupUiCapture capture = new StartupUiCapture(timeline);

		capture.handleEvent(new Event("org/eclipse/e4/ui/model/ui/UIElement/widget/SET",
				Map.of("ChangedElement", "not a window", "NewValue", "not a shell")));
		assertNull(timeline.get(Phase.FIRST_SHELL));

		capture.handleEvent(new Event("org/eclipse/e4/ui/LifeCycle/appStartupComplete", Map.of()));
		assertNotNull(timeline.get(Phase.WORKBENCH_STARTUP_COMPLETE));
	}

	@Test
	void aPaintBeforeStartupCompleteIsTheFirstPaint() {
		onAShell((shell, timeline, capture) -> {
			shell.notifyListeners(SWT.Show, null);
			shell.notifyListeners(SWT.Paint, null);
			assertNotNull(timeline.get(Phase.MAIN_WINDOW_VISIBLE));
			assertNotNull(timeline.get(Phase.MAIN_WINDOW_FIRST_PAINT));
		});
	}

	/** The event admin thread delivers this event, so it has to reach the watch through the Display. */
	@Test
	void startupCompleteEndsAWatchWhoseShellIsNotShowing() {
		onAShell((shell, timeline, capture) -> {
			shell.notifyListeners(SWT.Show, null);
			completeOnAnotherThread(capture);
			dispatch(shell.getDisplay());
			shell.notifyListeners(SWT.Paint, null);
			assertNull(timeline.get(Phase.MAIN_WINDOW_FIRST_PAINT));
		});
	}

	/** The event loop starts after this event is posted, so a showing window may not have painted yet. */
	@Test
	void aShowingShellKeepsItsWatchPastStartupComplete() {
		onAShell((shell, timeline, capture) -> {
			// no real paint, or it would end the watch by itself and the fix could not be told apart
			shell.setRedraw(false);
			shell.setVisible(true);
			completeOnAnotherThread(capture);
			dispatch(shell.getDisplay());
			shell.notifyListeners(SWT.Paint, null);
			assertNotNull(timeline.get(Phase.MAIN_WINDOW_FIRST_PAINT));
		});
	}

	@Test
	void startupCompleteEndsAWatchWhoseShellStartedHidden() {
		onAShell((shell, timeline, capture) -> {
			completeOnAnotherThread(capture);
			dispatch(shell.getDisplay());
			shell.notifyListeners(SWT.Show, null);
			assertNull(timeline.get(Phase.MAIN_WINDOW_VISIBLE));
		});
	}

	@Test
	void startupCompleteLeavesADisposedShellAlone() {
		onAShell((shell, timeline, capture) -> {
			Display display = shell.getDisplay();
			shell.notifyListeners(SWT.Show, null);
			completeOnAnotherThread(capture);
			shell.dispose();
			dispatch(display);
			assertNotNull(timeline.get(Phase.WORKBENCH_STARTUP_COMPLETE));
		});
	}

	/** Unlike startup complete, the plug-in going away ends the watch of a window that is showing too. */
	@Test
	void deactivationEndsAWatchWhoseShellIsShowing() {
		onAShell((shell, timeline, capture) -> {
			shell.setRedraw(false);
			shell.setVisible(true);
			completeOnAnotherThread(capture);
			dispatch(shell.getDisplay());
			Thread thread = new Thread(capture::deactivate);
			thread.start();
			thread.join();
			dispatch(shell.getDisplay());
			// a real paint may already have ended it, so this looks at what is left and not at the mark
			assertEquals(0, shell.getListeners(SWT.Dispose).length);
		});
	}

	@Test
	void deactivationEndsAWatchBeforeTheWindowShows() {
		onAShell((shell, timeline, capture) -> {
			capture.deactivate();
			dispatch(shell.getDisplay());
			shell.notifyListeners(SWT.Show, null);
			assertNull(timeline.get(Phase.MAIN_WINDOW_VISIBLE));
		});
	}

	/** Display.dispose runs what is queued after its shells are gone, so the queued end meets a disposed shell. */
	@Test
	void aDeactivationStillQueuedWhenTheDisplayGoesAwayDoesNotThrow() {
		onAShell((shell, timeline, capture) -> {
			Display display = shell.getDisplay();
			shell.notifyListeners(SWT.Show, null);
			capture.deactivate();
			display.dispose();
			assertTrue(display.isDisposed());
			capture.deactivate();
		});
	}

	private interface ShellCheck {
		void run(Shell shell, StartupTimeline timeline, StartupUiCapture capture) throws Exception;
	}

	/** A private Display, disposed again, so no other test sees this thread as the UI thread. */
	private static void onAShell(ShellCheck check) {
		Display display;
		try {
			display = new Display();
		} catch (SWTError | SWTException | LinkageError e) {
			assumeTrue(false, "no display: " + e.getMessage());
			return;
		}
		try {
			Shell shell = new Shell(display);
			StartupTimeline timeline = new StartupTimeline();
			StartupUiCapture capture = new StartupUiCapture(timeline);
			capture.handleEvent(new Event("org/eclipse/e4/ui/model/ui/UIElement/widget/SET", Map.of("ChangedElement",
					MBasicFactory.INSTANCE.createTrimmedWindow(), "NewValue", shell)));
			assertNotNull(timeline.get(Phase.FIRST_SHELL));
			check.run(shell, timeline, capture);
		} catch (Exception e) {
			throw new AssertionError(e);
		} finally {
			display.dispose();
		}
	}

	private static void completeOnAnotherThread(StartupUiCapture capture) throws InterruptedException {
		Thread thread = new Thread(
				() -> capture.handleEvent(new Event("org/eclipse/e4/ui/LifeCycle/appStartupComplete", Map.of())));
		thread.start();
		thread.join();
	}

	private static void dispatch(Display display) {
		while (display.readAndDispatch()) {
			// drains the queue
		}
	}

	private static void lifecycle(StartupCaptureComponent component, String method, Object... arguments) {
		try {
			Method declared = arguments.length == 0 ? StartupCaptureComponent.class.getDeclaredMethod(method)
					: StartupCaptureComponent.class.getDeclaredMethod(method, BundleContext.class);
			declared.setAccessible(true);
			declared.invoke(component, arguments);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static BundleContext contextWith(boolean workbench, boolean registers) {
		return (BundleContext) Proxy.newProxyInstance(StartupTimingToolTest.class.getClassLoader(),
				new Class<?>[] { BundleContext.class }, (proxy, method, arguments) -> {
					if ("getServiceReference".equals(method.getName())) {
						return workbench ? proxyOf(ServiceReference.class) : null;
					}
					if ("registerService".equals(method.getName())) {
						if (!registers) {
							throw new IllegalStateException("not registered");
						}
						return proxyOf(ServiceRegistration.class);
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}

	private static Object proxyOf(Class<?> type) {
		return Proxy.newProxyInstance(StartupTimingToolTest.class.getClassLoader(), new Class<?>[] { type },
				(proxy, method, arguments) -> null);
	}

	private static ServiceEvent serviceEvent(String objectClass, Map<String, Object> properties) {
		ServiceReference<?> reference = (ServiceReference<?>) Proxy.newProxyInstance(
				StartupTimingToolTest.class.getClassLoader(), new Class<?>[] { ServiceReference.class },
				(proxy, method, arguments) -> {
					if (!"getProperty".equals(method.getName())) {
						return null;
					}
					return Constants.OBJECTCLASS.equals(arguments[0]) ? new String[] { objectClass }
							: properties.get(arguments[0]);
				});
		return new ServiceEvent(ServiceEvent.REGISTERED, reference);
	}

	@SuppressWarnings("unchecked")
	private static String firstShellReason(StartupTimeline timeline, OnDemand onDemand) throws Exception {
		Map<String, Object> phases = (Map<String, Object>) describe(timeline, onDemand, 50).get("phases");
		return (String) ((Map<String, Object>) phases.get("firstShell")).get("reason");
	}

	private static Map<String, Object> describe(StartupTimeline timeline, OnDemand onDemand, int maxResults)
			throws Exception {
		return TestFixture.parse(GetStartupTimingTool.describe(timeline, onDemand, maxResults).toString());
	}

	/** Loaded from the probe bundle's own copy of its class file. */
	public static final class ThrowingActivator implements BundleActivator {

		static final String MESSAGE = "this activator always fails";

		@Override
		public void start(BundleContext context) {
			throw new IllegalStateException(MESSAGE);
		}

		@Override
		public void stop(BundleContext context) {
			// never started
		}
	}

	private static byte[] jarOf(String symbolicName, Class<?> activator) throws Exception {
		return jarOf(symbolicName, activator, false);
	}

	private static byte[] lazyJarOf(String symbolicName) throws Exception {
		return jarOf(symbolicName, null, true);
	}

	private static byte[] jarOf(String symbolicName, Class<?> activator, boolean lazy) throws Exception {
		Manifest manifest = new Manifest();
		Attributes main = manifest.getMainAttributes();
		if (lazy) {
			main.putValue("Bundle-ActivationPolicy", "lazy");
		}
		main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
		main.putValue("Bundle-ManifestVersion", "2");
		main.putValue("Bundle-SymbolicName", symbolicName);
		main.putValue("Bundle-Version", "1.0.0");
		if (activator != null) {
			main.putValue("Bundle-Activator", activator.getName());
			main.putValue("Import-Package", "org.osgi.framework");
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
			if (activator != null) {
				String path = activator.getName().replace('.', '/') + ".class";
				jar.putNextEntry(new JarEntry(path));
				try (InputStream classFile = activator.getClassLoader().getResourceAsStream(path)) {
					classFile.transferTo(jar);
				}
				jar.closeEntry();
			}
			jar.flush();
		}
		return bytes.toByteArray();
	}
}
