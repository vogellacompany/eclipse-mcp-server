package com.vogella.eclipse.mcp.jdt.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestRunSession;

import org.eclipse.pde.launching.IPDELauncherConstants;

import com.vogella.eclipse.mcp.core.LaunchAttributes;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Collects JUnit results from the IDE's own test runner.
 * <p>
 * The JUnit listener is global, so runs are matched by their generated launch configuration name and a run
 * started from the UI is never reported as ours.
 */
public final class TestRunRegistry {

	private static final TestRunRegistry INSTANCE = new TestRunRegistry();

	private static final String NAME_PREFIX = "MCP tests "; //$NON-NLS-1$

	private final AtomicLong ids = new AtomicLong();

	private final Map<String, Run> runs = new LinkedHashMap<>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Run> eldest) {
			return size() > 20 && !eldest.getValue().running;
		}
	};

	private String lastId;

	private boolean listening;

	public static TestRunRegistry getInstance() {
		return INSTANCE;
	}

	private TestRunRegistry() {
	}

	/** One test run. */
	public static final class Run {

		private final String id;
		private final String launchName;
		private final String scope;
		private final long startedAt = System.currentTimeMillis();
		private final CountDownLatch finished = new CountDownLatch(1);
		private final List<Case> cases = new ArrayList<>();

		private volatile boolean running = true;
		private volatile long endedAt;
		private volatile String state = "running"; //$NON-NLS-1$
		private volatile String message;
		private volatile String launchedAs;
		private volatile ILaunch launch;

		Run(String id, String launchName, String scope) {
			this.id = id;
			this.launchName = launchName;
			this.scope = scope;
		}

		public String id() {
			return id;
		}

		public boolean running() {
			return running;
		}

		/** Which launcher was used, kept so a polled result can say what it is waiting on. */
		public void launchedAs(String value) {
			launchedAs = value;
		}

		String launchName() {
			return launchName;
		}

		public boolean await(long seconds) throws InterruptedException {
			return finished.await(seconds, TimeUnit.SECONDS);
		}

		/** Moves to a terminal state, once, so a later sessionFinished cannot overwrite "abandoned" with "done". */
		synchronized void finish(String terminalState, String reason) {
			if (!running) {
				return;
			}
			state = terminalState;
			message = reason;
			endedAt = System.currentTimeMillis();
			running = false;
			finished.countDown();
		}
	}

	private record Case(String className, String methodName, String result, double seconds, String trace,
			String expected, String actual) {
	}

	/** Registers the listener once, lazily, so a workspace that never runs tests pays nothing. */
	private synchronized void listen() {
		if (listening) {
			return;
		}
		deleteLeftoverConfigurations();
		JUnitCore.addTestRunListener(new TestRunListener() {
			@Override
			public void sessionStarted(ITestRunSession session) {
				Run run = match(session);
				if (run != null) {
					run.state = "running"; //$NON-NLS-1$
				}
			}

			@Override
			public void testCaseFinished(ITestCaseElement element) {
				Run run = match(element.getTestRunSession());
				if (run == null) {
					return;
				}
				ITestElement.FailureTrace trace = element.getFailureTrace();
				synchronized (run.cases) {
					run.cases.add(new Case(element.getTestClassName(), element.getTestMethodName(),
							// Result.toString() is "Failure", so normalise or the counters match only OK
							String.valueOf(element.getTestResult(false)).toUpperCase(Locale.ROOT),
							element.getElapsedTimeInSeconds(),
							trace == null ? null : trace.getTrace(), trace == null ? null : trace.getExpected(),
							trace == null ? null : trace.getActual()));
				}
			}

			@Override
			public void sessionFinished(ITestRunSession session) {
				Run run = match(session);
				if (run != null) {
					run.finish("done", null); //$NON-NLS-1$
				}
			}
		});
		listening = true;
	}

	/** Matches by launch configuration name, which is unique per run. */
	private synchronized Run match(ITestRunSession session) {
		if (session == null || session.getTestRunName() == null) {
			return null;
		}
		for (Run run : runs.values()) {
			if (session.getTestRunName().startsWith(run.launchName())) {
				return run;
			}
		}
		return null;
	}

	/**
	 * Removes launch configurations this server left behind in earlier sessions, identified by its own marker.
	 */
	private static void deleteLeftoverConfigurations() {
		try {
			var manager = DebugPlugin.getDefault().getLaunchManager();
			for (var configuration : manager.getLaunchConfigurations()) {
				if (!configuration.getAttribute(LaunchAttributes.STARTED_BY_MCP,
						false)) {
					continue;
				}
				boolean running = false;
				for (var launch : manager.getLaunches()) {
					running |= configuration.equals(launch.getLaunchConfiguration()) && !launch.isTerminated();
				}
				if (!running) {
					configuration.delete();
				}
			}
		} catch (CoreException | RuntimeException e) {
			// tidying is a courtesy; failing at it must not stop a test run
		}
	}

	/** Distinguishes launch names across server generations, since the ids restart. */
	private static final long GENERATION = System.currentTimeMillis();

	public synchronized Run create(String scope) {
		listen();
		String id = "testrun-" + ids.incrementAndGet(); //$NON-NLS-1$
		// ids restart with the server, so the generation keeps launch names (and .launch files) apart
		Run run = new Run(id, NAME_PREFIX + id + " " + GENERATION, scope); //$NON-NLS-1$
		runs.put(id, run);
		lastId = id;
		return run;
	}

	public synchronized Run find(String id) {
		return runs.get(id);
	}

	/** The run still in progress, if any. JDT's AST parser is not safe to share. */
	public synchronized Run findRunning() {
		for (Run run : runs.values()) {
			if (run.running) {
				return run;
			}
		}
		return null;
	}

	public synchronized Run findLatest() {
		return lastId == null ? null : runs.get(lastId);
	}

	/** The ids still held, oldest first, for a caller that has to name one. */
	public synchronized List<String> ids() {
		return List.copyOf(runs.keySet());
	}

	public static void failed(Run run, String reason) {
		run.finish("failed", reason); //$NON-NLS-1$
	}

	/** Errors reported from a launched platform's log. Enough to diagnose, not a dump. */
	private static final int MAX_LAUNCH_ERRORS = 8;

	/**
	 * The lines of a platform log, decoded leniently because it carries the launched platform's default
	 * encoding, which is not always UTF-8.
	 */
	private static List<String> logLines(Path log) throws IOException {
		var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE);
		String text = decoder.decode(ByteBuffer.wrap(Files.readAllBytes(log))).toString();
		return List.of(text.split("\r\n|\n|\r", -1)); //$NON-NLS-1$
	}

	/** Error entries from the log of the platform that was launched, newest last. */
	private static JsonArray launchedPlatformErrors(Run run) {
		JsonArray errors = new JsonArray();
		try {
			ILaunch launch = run.launch;
			var configuration = launch == null ? null : launch.getLaunchConfiguration();
			if (configuration == null) {
				return errors;
			}
			String location = configuration
					.getAttribute(IPDELauncherConstants.LOCATION, (String) null);
			if (location == null) {
				return errors;
			}
			location = VariablesPlugin.getDefault().getStringVariableManager()
					.performStringSubstitution(location);
			Path log = Path.of(location, ".metadata", ".log"); //$NON-NLS-1$ //$NON-NLS-2$
			if (!Files.isReadable(log)) {
				return errors;
			}
			List<String> collected = new ArrayList<>();
			String entry = null;
			for (String line : logLines(log)) {
				if (line.startsWith("!ENTRY") && line.contains(" 4 ")) { //$NON-NLS-1$ //$NON-NLS-2$
					entry = line.substring("!ENTRY ".length()); //$NON-NLS-1$
				} else if (entry != null && line.startsWith("!MESSAGE")) { //$NON-NLS-1$
					collected.add(entry + ": " + line.substring("!MESSAGE ".length())); //$NON-NLS-1$ //$NON-NLS-2$
					entry = null;
				}
			}
			// the last ones: a failing start says so after pages of resolution noise
			collected.subList(Math.max(0, collected.size() - MAX_LAUNCH_ERRORS), collected.size()).forEach(errors::add);
		} catch (CoreException | IOException | RuntimeException e) {
			// the diagnosis is a bonus; failing to read it must not cost the answer
		}
		return errors;
	}

	/**
	 * Ends a run whose launch died or never reported, which would otherwise stay running and block every later run.
	 */
	static void watch(Run run, ILaunch launch, int staleAfterSeconds) {
		run.launch = launch;
		Thread watchdog = new Thread(() -> {
			long deadline = System.currentTimeMillis() + staleAfterSeconds * 1000L;
			while (run.running) {
				try {
					if (run.await(2)) {
						return;
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				boolean anyResult;
				synchronized (run.cases) {
					anyResult = !run.cases.isEmpty();
				}
				if (launch != null && launch.isTerminated() && !anyResult) {
					run.finish("cancelled", //$NON-NLS-1$
							"The launch ended without producing any test event. It was most likely cancelled, at the 'Errors in Workspace' prompt or otherwise."); //$NON-NLS-1$
					return;
				}
				if (System.currentTimeMillis() > deadline && !anyResult) {
					// kill it: an abandoned plug-in launch is a second Eclipse holding memory and a port
					boolean killed = terminate(run);
					run.finish("abandoned", //$NON-NLS-1$
							"No test event arrived within %d seconds, so the run was abandoned. %s" //$NON-NLS-1$
									.formatted(staleAfterSeconds, killed ? "Its launch was terminated." //$NON-NLS-1$
											: "Its launch could NOT be terminated and may still be running; check for an orphaned process.")); //$NON-NLS-1$
					return;
				}
			}
		}, "MCP test watchdog " + run.id()); //$NON-NLS-1$
		watchdog.setDaemon(true);
		watchdog.start();
	}

	/** Abandons a run and kills what it launched. */
	public static boolean abandon(Run run) {
		if (!run.running) {
			return false;
		}
		boolean killed = terminate(run);
		run.finish("abandoned", killed ? "Abandoned on request; its launch was terminated." //$NON-NLS-1$ //$NON-NLS-2$
				: "Abandoned on request, but its launch could NOT be terminated and may still be running."); //$NON-NLS-1$
		return true;
	}

	/** Terminates the launch and its processes. Reports whether anything is still alive. */
	private static boolean terminate(Run run) {
		ILaunch launch = run.launch;
		if (launch == null) {
			return false;
		}
		try {
			if (launch.canTerminate()) {
				launch.terminate();
			}
			for (IProcess process : launch.getProcesses()) {
				if (process.canTerminate()) {
					process.terminate();
				}
			}
		} catch (CoreException e) {
			return false;
		}
		return launch.isTerminated();
	}

	/** Failures first, since that is what a caller asked the question for. */
	public static JsonObject toJson(Run run, int maxResults, boolean includePassed) {
		List<Case> cases;
		synchronized (run.cases) {
			cases = List.copyOf(run.cases);
		}
		int passed = 0;
		int failed = 0;
		int errors = 0;
		int ignored = 0;
		int unclassified = 0;
		List<Case> interesting = new ArrayList<>();
		for (Case testCase : cases) {
			switch (testCase.result()) {
			case "OK" -> passed++; //$NON-NLS-1$
			case "FAILURE" -> failed++; //$NON-NLS-1$
			case "ERROR" -> errors++; //$NON-NLS-1$
			case "IGNORED" -> ignored++; //$NON-NLS-1$
			// an unknown result is still counted, or errors vanish from the summary
			default -> unclassified++;
			}
			if (includePassed || !"OK".equals(testCase.result())) { //$NON-NLS-1$
				interesting.add(testCase);
			}
		}
		JsonArray reported = new JsonArray();
		for (Case testCase : interesting.subList(0, Math.min(maxResults, interesting.size()))) {
			JsonObject json = new JsonObject().put("class", testCase.className()) //$NON-NLS-1$
					.put("method", testCase.methodName()) //$NON-NLS-1$
					.put("result", testCase.result()) //$NON-NLS-1$
					.put("seconds", testCase.seconds()); //$NON-NLS-1$
			if (testCase.trace() != null) {
				json.put("trace", testCase.trace()) //$NON-NLS-1$
						.put("expected", testCase.expected()) //$NON-NLS-1$
						.put("actual", testCase.actual()); //$NON-NLS-1$
			}
			reported.add(json);
		}
		JsonObject counted = new JsonObject();
		if (unclassified > 0) {
			counted.put("unclassified", unclassified); //$NON-NLS-1$
		}
		// state is the field read programmatically, so a run with no tests must not claim success
		if ("done".equals(run.state) && cases.isEmpty()) { //$NON-NLS-1$
			counted.put("stateInconsistent", //$NON-NLS-1$
					"State is done but no test was reported, which cannot both be true. Treat this as a run that did not happen."); //$NON-NLS-1$
			// the launched platform knows why a workbench that failed to start reported no tests
			JsonArray launchErrors = launchedPlatformErrors(run);
			if (launchErrors.size() > 0) {
				counted.put("launchedPlatformErrors", launchErrors) //$NON-NLS-1$
						// the shadowing explanation is only offered when its symptom is present
						.put("launchedPlatformNote", launchErrors.toString().contains("ClassNotFoundException") //$NON-NLS-1$ //$NON-NLS-2$
								? "These come from the log of the platform that was launched, not from this IDE. A ClassNotFoundException in a bundle whose version ends in .qualifier is a workspace copy shadowing the installed bundle: it is on the launch's bundle list but has no compiled classes, and under the UI test application that stops the workbench from starting, which reports as no tests. Narrow the bundle set with workspacePlugins required, or build the workspace." //$NON-NLS-1$
								: "These come from the log of the platform that was launched, not from this IDE. The launch started and then failed on its own terms, so the tests never ran; read them as the platform's account of why, not as a fault of the test bundle."); //$NON-NLS-1$
			}
		}
		if (passed + failed + errors + ignored + unclassified != cases.size()) {
			counted.put("countsInconsistent", //$NON-NLS-1$
					"The counters do not sum to total; trust the tests array."); //$NON-NLS-1$
		}
		return counted.put("runId", run.id) //$NON-NLS-1$
				.put("scope", run.scope) //$NON-NLS-1$
				.put("launchedAs", run.launchedAs) //$NON-NLS-1$
				.put("state", run.state) //$NON-NLS-1$
				.put("elapsedMillis", (run.endedAt == 0 ? System.currentTimeMillis() : run.endedAt) - run.startedAt)
				.put("total", cases.size()) //$NON-NLS-1$
				.put("passed", passed) //$NON-NLS-1$
				.put("failed", failed) //$NON-NLS-1$
				.put("errors", errors) //$NON-NLS-1$
				.put("ignored", ignored) //$NON-NLS-1$
				.put("truncated", interesting.size() > reported.size()) //$NON-NLS-1$
				.put("omitted", interesting.size() - reported.size()) //$NON-NLS-1$
				.put("message", run.message) //$NON-NLS-1$
				.put("tests", reported); //$NON-NLS-1$
	}
}
