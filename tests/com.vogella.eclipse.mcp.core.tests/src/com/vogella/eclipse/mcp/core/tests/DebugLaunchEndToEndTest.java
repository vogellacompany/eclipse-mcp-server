package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * The whole loop in one go: launch a tiny main under the debugger with a
 * breakpoint in it, wait for the suspend, read a variable, evaluate an
 * expression, step, resume and terminate. And the same launch in run mode,
 * where there is no debug target and only terminate applies.
 * <p>
 * Skipped cleanly when no JVM is registered with the IDE; a headless test
 * runtime sometimes has none.
 */
class DebugLaunchEndToEndTest {

	private static final String PROJECT = "mcp-debug-e2e";

	private static final String MAIN_SOURCE = """
			package sample;
			public class Main {
				public static void main(String[] args) throws Exception {
					int answer = 40 + 2;
					String greeting = "hello";
					System.out.println(greeting + " " + answer);
					int doubled = answer * 2;
					System.out.println(doubled);
				}
			}
			""";

	private static final String IDLE_SOURCE = """
			package sample;
			public class Idle {
				public static void main(String[] args) throws Exception {
					System.out.println("idling");
					Thread.sleep(120000);
				}
			}
			""";

	private final TestFixture fixture = new TestFixture();

	private TestInfo info;

	@BeforeEach
	void rememberTest(TestInfo info) {
		this.info = info;
	}

	@AfterEach
	void deleteTestProjects() throws Exception {
		// a failed assertion must not leave the program running past the project delete
		for (ILaunch launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
			if (launch.canTerminate()) {
				launch.terminate();
			}
		}
		fixture.dispose();
	}

	private IJavaProject projectWithBreakpoint() throws Exception {
		return projectWithBreakpoint(null);
	}

	/** The breakpoint sits on the {@code int doubled} line, where {@code answer} is 42. */
	private IJavaProject projectWithBreakpoint(String condition) throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		IFile file = TestFixture.addType(project, "sample", "Main", MAIN_SOURCE);
		TestFixture.build(project.getProject());

		// the line of the second declaration, which is executable code after output
		int line = -1;
		String[] lines = TestFixture.read(file).split("\n");
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].contains("int doubled")) {
				line = i + 1;
				break;
			}
		}
		assertTrue(line > 0, "the fixture source changed shape");
		Map<String, Object> arguments = new HashMap<>(Map.of("type", "sample.Main", "line", Integer.valueOf(line)));
		if (condition != null) {
			arguments.put("condition", condition);
		}
		Map<String, Object> answer = TestFixture.callAndParse("eclipse_set_breakpoint", arguments);
		assertEquals(Boolean.TRUE, answer.get("created"), String.valueOf(answer));
		return project;
	}

	@Test
	void launchesStopsAtABreakpointReadsStateAndTerminates() throws Exception {
		ensureDefaultVmInstall();
		projectWithBreakpoint();

		Map<String, Object> launched = TestFixture.callAndParse("eclipse_debug_launch", Map.of(
				"project", PROJECT, "mainType", "sample.Main", "waitForSuspendSeconds", Integer.valueOf(25)));

		assertTrue(Boolean.TRUE.equals(launched.get("suspended")),
				"the breakpoint did not suspend the program: " + launched);
		String sessionId = (String) launched.get("sessionId");
		assertNotNull(sessionId);

		// main is suspended at the breakpoint
		assertLocation(launched, "Main.main(Main.java");

		Map<String, Object> frames = TestFixture.callAndParse("eclipse_debug_get_frames",
				Map.of("sessionId", sessionId));
		assertTrue(((Number) frames.get("total")).intValue() >= 1, String.valueOf(frames));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> variables = (List<Map<String, Object>>) frames.get("variables");
		assertNotNull(variables, String.valueOf(frames));
		Map<String, Object> answerVariable = null;
		for (Map<String, Object> candidate : variables) {
			if ("answer".equals(candidate.get("name"))) {
				answerVariable = candidate;
			}
		}
		assertNotNull(answerVariable, "the frame should expose the 'answer' local: " + variables);
		assertTrue(String.valueOf(answerVariable.get("value")).contains("42"),
				"an int local should render as its value: " + answerVariable);

		Map<String, Object> evaluated = TestFixture.callAndParse("eclipse_debug_evaluate",
				Map.of("sessionId", sessionId, "expression", "answer * 2"));
		assertTrue(String.valueOf(evaluated.get("value")).contains("84"),
				"evaluation inside the frame should compute: " + evaluated);

		Map<String, Object> stepped = TestFixture.callAndParse("eclipse_debug_control",
				Map.of("sessionId", sessionId, "action", "stepOver", "waitForSuspendSeconds", Integer.valueOf(20)));
		assertTrue(Boolean.TRUE.equals(stepped.get("suspended")), "after a stepOver the thread is suspended again: "
				+ stepped);
		assertLocation(stepped, "Main.main(Main.java");

		Map<String, Object> resumed = TestFixture.callAndParse("eclipse_debug_control",
				Map.of("sessionId", sessionId, "action", "resume", "waitForSuspendSeconds", Integer.valueOf(5)));
		// the program has nothing left to stop at, so running out is expected
		assertTrue(Boolean.TRUE.equals(resumed.get("timedOut")), "resume should report that nothing stopped: "
				+ resumed);

		waitUntilTerminated(sessionId);
	}

	/**
	 * A condition the tool stored but left disabled suspended on every hit, so a
	 * false condition is what tells an evaluated condition from an ignored one.
	 */
	@Test
	void aFalseConditionDoesNotSuspend() throws Exception {
		ensureDefaultVmInstall();
		projectWithBreakpoint("answer == 0");

		Map<String, Object> launched = TestFixture.callAndParse("eclipse_debug_launch", Map.of(
				"project", PROJECT, "mainType", "sample.Main", "waitForSuspendSeconds", Integer.valueOf(10)));

		assertEquals(Boolean.FALSE, launched.get("suspended"), "a false condition suspended the program: " + launched);
		// the tiny main runs to its end well within the wait, so it passed the breakpoint line
		assertEquals(Boolean.TRUE, launched.get("terminated"), String.valueOf(launched));
		String sessionId = (String) launched.get("sessionId");
		assertNotNull(sessionId);
		waitUntilTerminated(sessionId);
	}

	@Test
	void aTrueConditionSuspends() throws Exception {
		ensureDefaultVmInstall();
		projectWithBreakpoint("answer == 42");

		Map<String, Object> launched = TestFixture.callAndParse("eclipse_debug_launch", Map.of(
				"project", PROJECT, "mainType", "sample.Main", "waitForSuspendSeconds", Integer.valueOf(25)));

		assertTrue(Boolean.TRUE.equals(launched.get("suspended")),
				"a true condition did not suspend the program: " + launched);
		assertLocation(launched, "Main.main(Main.java");
		String sessionId = (String) launched.get("sessionId");
		assertNotNull(sessionId);
		TestFixture.callAndParse("eclipse_debug_control", Map.of("sessionId", sessionId, "action", "terminate"));
		waitUntilTerminated(sessionId);
	}

	/**
	 * A run mode launch has no debug target at all, and asking for one is what
	 * made terminate refuse a process that was still running, with a message
	 * saying it had ended.
	 */
	@Test
	void aRunModeLaunchIsTerminatedRatherThanDeclaredEnded() throws Exception {
		ensureDefaultVmInstall();
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "sample", "Idle", IDLE_SOURCE);
		TestFixture.build(project.getProject());

		Map<String, Object> launched = TestFixture.callAndParse("eclipse_debug_launch",
				Map.of("project", PROJECT, "mainType", "sample.Idle", "mode", "run",
						"waitForSuspendSeconds", Integer.valueOf(0)));
		String sessionId = (String) launched.get("sessionId");
		assertNotNull(sessionId, String.valueOf(launched));
		assertTrue(runningProcess(sessionId), "the run mode launch never started a process: " + launched);

		McpToolResult suspended = TestFixture.call("eclipse_debug_control",
				Map.of("sessionId", sessionId, "action", "suspend"));
		assertTrue(suspended.isError(), "there is nothing to suspend without a debug target: " + suspended.text());
		assertTrue(suspended.text().contains("run mode"),
				"the refusal should say why there is no target: " + suspended.text());

		Map<String, Object> terminated = TestFixture.callAndParse("eclipse_debug_control",
				Map.of("sessionId", sessionId, "action", "terminate"));
		assertEquals("terminate", terminated.get("action"), String.valueOf(terminated));
		waitUntilTerminated(sessionId);
	}

	@Test
	void suspendStopsARunningProgramWithoutNamingAThread() throws Exception {
		ensureDefaultVmInstall();
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "sample", "Idle", IDLE_SOURCE);
		TestFixture.build(project.getProject());

		Map<String, Object> launched = TestFixture.callAndParse("eclipse_debug_launch",
				Map.of("project", PROJECT, "mainType", "sample.Idle", "waitForSuspendSeconds", Integer.valueOf(0)));
		String sessionId = (String) launched.get("sessionId");
		assertNotNull(sessionId, String.valueOf(launched));
		assertTrue(hasDebugTarget(sessionId), "the launch never got a debug target: " + launched);

		McpToolResult suspended = TestFixture.call("eclipse_debug_control",
				Map.of("sessionId", sessionId, "action", "suspend"));
		assertFalse(suspended.isError(), "suspending a running program needs no thread: " + suspended.text());
		Map<String, Object> answer = TestFixture.parse(suspended.text());
		assertEquals(Boolean.TRUE, answer.get("suspended"), suspended.text());
		assertEquals(Boolean.FALSE, answer.get("timedOut"), suspended.text());
		assertTrue(((List<?>) answer.get("threads")).stream()
				.anyMatch(t -> Boolean.TRUE.equals(((Map<?, ?>) t).get("suspended"))), suspended.text());

		TestFixture.callAndParse("eclipse_debug_control", Map.of("sessionId", sessionId, "action", "terminate"));
		waitUntilTerminated(sessionId);
	}

	}

	private static final String NOISY_SOURCE = """
			package sample;
			public class Noisy {
				public static void main(String[] args) {
					System.out.println("to-stdout");
					System.err.println("to-stderr");
					System.out.println("var=" + System.getenv("MCP_TEST_VAR"));
					System.exit(3);
				}
			}
			""";

	@Test
	void launchOutputHasBothStreamsTheExitCodeAndTheEnvironment() throws Exception {
		ensureDefaultVmInstall();
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "sample", "Noisy", NOISY_SOURCE);
		TestFixture.build(project.getProject());

		Map<String, Object> launched = TestFixture.callAndParse("eclipse_debug_launch",
				Map.of("project", PROJECT, "mainType", "sample.Noisy", "waitForSuspendSeconds", Integer.valueOf(0),
						"environment", Map.of("MCP_TEST_VAR", "forty-two")));
		String sessionId = (String) launched.get("sessionId");
		assertNotNull(sessionId, String.valueOf(launched));
		waitUntilTerminated(sessionId);

		Map<?, ?> process = null;
		for (int attempt = 0; attempt < 40; attempt++) {
			Map<String, Object> output = TestFixture.callAndParse("eclipse_get_launch_output",
					Map.of("sessionId", sessionId, "tailLines", Integer.valueOf(10)));
			List<?> processes = (List<?>) output.get("processes");
			if (!processes.isEmpty() && Boolean.TRUE.equals(((Map<?, ?>) processes.get(0)).get("terminated"))) {
				process = (Map<?, ?>) processes.get(0);
				break;
			}
			Thread.sleep(250);
		}
		assertNotNull(process, "the process never reported as terminated");
		assertEquals(3, ((Number) process.get("exitValue")).intValue(), String.valueOf(process));
		Map<?, ?> stdout = (Map<?, ?>) process.get("stdout");
		Map<?, ?> stderr = (Map<?, ?>) process.get("stderr");
		assertTrue(String.valueOf(stdout.get("text")).contains("to-stdout"), String.valueOf(stdout));
		assertTrue(String.valueOf(stdout.get("text")).contains("var=forty-two"), String.valueOf(stdout));
		assertTrue(String.valueOf(stderr.get("text")).contains("to-stderr"), String.valueOf(stderr));
		assertEquals(Boolean.FALSE, stdout.get("truncated"));

		Map<String, Object> tail = TestFixture.callAndParse("eclipse_get_launch_output",
				Map.of("sessionId", sessionId, "tailLines", Integer.valueOf(1)));
		Map<?, ?> tailed = (Map<?, ?>) ((Map<?, ?>) ((List<?>) tail.get("processes")).get(0)).get("stdout");
		assertEquals(Boolean.TRUE, tailed.get("truncated"), String.valueOf(tailed));
		assertEquals(1, ((Number) tailed.get("returned")).intValue());
		assertEquals("var=forty-two", tailed.get("text"));
	}

	private static final String READY_SOURCE = """
			package sample;
			public class Ready {
				public static void main(String[] args) throws Exception {
					System.out.println("booting");
					System.out.println("Started Ready in 0.1 seconds");
					Thread.sleep(120000);
				}
			}
			""";

	@Test
	void waitForOutputAnswersReadyForARunModeLaunchAndPatternFilters() throws Exception {
		ensureDefaultVmInstall();
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "sample", "Ready", READY_SOURCE);
		TestFixture.build(project.getProject());

		Map<String, Object> launched = TestFixture.callAndParse("eclipse_debug_launch",
				Map.of("project", PROJECT, "mainType", "sample.Ready", "mode", "run",
						"waitForOutput", "Started \\w+ in", "readyTimeoutSeconds", Integer.valueOf(20)));
		assertEquals(Boolean.TRUE, launched.get("ready"), String.valueOf(launched));
		String sessionId = (String) launched.get("sessionId");

		Map<String, Object> output = TestFixture.callAndParse("eclipse_get_launch_output",
				Map.of("sessionId", sessionId, "pattern", "^Started"));
		Map<?, ?> stdout = (Map<?, ?>) ((Map<?, ?>) ((List<?>) output.get("processes")).get(0)).get("stdout");
		assertEquals(1, ((Number) stdout.get("total")).intValue(), String.valueOf(stdout));
		assertTrue(String.valueOf(stdout.get("text")).startsWith("Started Ready"), String.valueOf(stdout));

		TestFixture.callAndParse("eclipse_debug_control", Map.of("sessionId", sessionId, "action", "terminate"));
		waitUntilTerminated(sessionId);
	}

	}

	@Test
	void waitForOutputReportsTerminatedWhenTheProgramDiesFirst() throws Exception {
		ensureDefaultVmInstall();
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "sample", "Noisy", NOISY_SOURCE);
		TestFixture.build(project.getProject());

		Map<String, Object> launched = TestFixture.callAndParse("eclipse_debug_launch",
				Map.of("project", PROJECT, "mainType", "sample.Noisy", "waitForOutput", "never printed",
						"readyTimeoutSeconds", Integer.valueOf(20)));
		assertEquals(Boolean.FALSE, launched.get("ready"), String.valueOf(launched));
		assertEquals(Boolean.TRUE, ((Map<?, ?>) launched.get("readiness")).get("terminated"), String.valueOf(launched));
		assertTrue(String.valueOf(launched.get("processOutputTail")).contains("to-stdout"), String.valueOf(launched));
		assertNotNull(launched.get("outputNote"));
	}

	@Test
	void aLaunchFileOfAnUninstalledTypeIsListedAsNotLaunchable() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
				+ "<launchConfiguration type=\"com.example.nowhere.launchType\">\n</launchConfiguration>\n";
		project.getProject().getFile("Ghost.launch").create(
				new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)), true, null);

		Map<String, Object> listed = TestFixture.callAndParse("eclipse_list_launch_configurations",
				Map.of("nameFilter", "Ghost"));
		Map<?, ?> ghost = (Map<?, ?>) ((List<?>) listed.get("configurations")).get(0);
		assertEquals(Boolean.FALSE, ghost.get("launchable"), String.valueOf(ghost));
		assertEquals("com.example.nowhere.launchType", ghost.get("missingType"));
	}

	@Test
	void aSavedLaunchConfigurationIsListedAndLaunchableByName() throws Exception {
		ensureDefaultVmInstall();
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "sample", "Noisy", NOISY_SOURCE);
		TestFixture.build(project.getProject());
		String name = "mcp-saved-" + System.nanoTime();
		Map<String, Object> save = new HashMap<>(Map.of("name", name, "project", PROJECT, "mainType", "sample.Noisy",
				"environment", Map.of("MCP_TEST_VAR", "saved")));

		Map<String, Object> dry = TestFixture.callAndParse("eclipse_save_launch_configuration", save);
		assertEquals(Boolean.TRUE, dry.get("dryRun"), String.valueOf(dry));
		assertEquals(0, ((List<?>) TestFixture.callAndParse("eclipse_list_launch_configurations",
				Map.of("nameFilter", name)).get("configurations")).size());

		save.put("dryRun", Boolean.FALSE);
		try {
			Map<String, Object> saved = TestFixture.callAndParse("eclipse_save_launch_configuration", save);
			assertEquals(Boolean.TRUE, saved.get("saved"), String.valueOf(saved));
			Map<String, Object> listed = TestFixture.callAndParse("eclipse_list_launch_configurations",
					Map.of("nameFilter", name, "includeAttributes", Boolean.TRUE));
			Map<?, ?> entry = (Map<?, ?>) ((List<?>) listed.get("configurations")).get(0);
			assertEquals(Boolean.TRUE, entry.get("launchable"), String.valueOf(entry));

			Map<String, Object> launched = TestFixture.callAndParse("eclipse_debug_launch",
					Map.of("configuration", name, "waitForSuspendSeconds", Integer.valueOf(0)));
			String sessionId = (String) launched.get("sessionId");
			assertNotNull(sessionId, String.valueOf(launched));
			waitUntilTerminated(sessionId);
			String text = "";
			for (int attempt = 0; attempt < 20 && !text.contains("var=saved"); attempt++) {
				Map<?, ?> process = (Map<?, ?>) ((List<?>) TestFixture.callAndParse("eclipse_get_launch_output",
						Map.of("sessionId", sessionId)).get("processes")).get(0);
				text = String.valueOf(((Map<?, ?>) process.get("stdout")).get("text"));
				Thread.sleep(250);
			}
			assertTrue(text.contains("var=saved"), text);
		} finally {
			for (var configuration : DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurations()) {
				if (configuration.getName().equals(name)) {
					configuration.delete();
				}
			}
		}
	}

	/** Whether the debug connection is up, which the launch establishes after it registers the process. */
	private static boolean hasDebugTarget(String sessionId) throws Exception {
		for (int attempt = 0; attempt < 40; attempt++) {
			Map<?, ?> session = (Map<?, ?>) ((List<?>) TestFixture
					.callAndParse("eclipse_debug_status", Map.of("sessionId", sessionId)).get("sessions")).get(0);
			if (session.get("threads") instanceof List<?> threads && !threads.isEmpty()) {
				return true;
			}
			Thread.sleep(500);
		}
		return false;
	}

	/** Whether the launch has a live process yet, which it registers a moment later. */
	private static boolean runningProcess(String sessionId) throws Exception {
		for (int attempt = 0; attempt < 40; attempt++) {
			Map<?, ?> session = (Map<?, ?>) ((List<?>) TestFixture
					.callAndParse("eclipse_debug_status", Map.of("sessionId", sessionId)).get("sessions")).get(0);
			if (session.get("pid") != null && !Boolean.TRUE.equals(session.get("terminated"))) {
				return true;
			}
			Thread.sleep(500);
		}
		return false;
	}

	private static void assertLocation(Map<String, Object> state, String expectedFragment) {
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> threads = (List<Map<String, Object>>) state.get("threads");
		assertNotNull(threads, String.valueOf(state));
		for (Map<String, Object> thread : threads) {
			if (Boolean.TRUE.equals(thread.get("suspended"))) {
				String location = String.valueOf(thread.get("location"));
				assertTrue(location.contains(expectedFragment),
						"expected the top frame near " + expectedFragment + ", got " + location);
				return;
			}
		}
		throw new AssertionError("no suspended thread in " + state);
	}

	private static void waitUntilTerminated(String sessionId) throws Exception {
		for (int second = 0; second < 30; second++) {
			Map<String, Object> status = TestFixture.callAndParse("eclipse_debug_status",
					Map.of("sessionId", sessionId));
			if (Boolean.TRUE.equals(((Map<?, ?>) ((List<?>) status.get("sessions")).get(0)).get("terminated"))) {
				return;
			}
			Thread.sleep(1000);
		}
		// last resort so no JVM outlives the suite
		TestFixture.callAndParse("eclipse_debug_control", Map.of("sessionId", sessionId, "action", "terminate"));
	}

	/**
	 * A headless runtime often starts without any registered VM. The one this
	 * platform itself runs on can stand in; if even that fails the test skips,
	 * because there is nothing here to debug with.
	 */
	private void ensureDefaultVmInstall() throws Exception {
		if (JavaRuntime.getDefaultVMInstall() != null) {
			return;
		}
		IVMInstallType standardType = null;
		for (IVMInstallType candidate : JavaRuntime.getVMInstallTypes()) {
			if (candidate.getId().contains("StandardVMType")) {
				standardType = candidate;
				break;
			}
		}
		assumeTrue(standardType != null, () -> info.getDisplayName() + ": no standard VM type is installed");
		File home = new File(System.getProperty("java.home"));
		assumeTrue(home.isDirectory(), () -> info.getDisplayName() + ": java.home does not exist");
		IVMInstall vm = standardType.findVMInstallByName("mcp-e2e-vm");
		if (vm == null) {
			vm = standardType.createVMInstall("mcp-e2e-" + System.nanoTime());
			vm.setName("mcp-e2e-vm");
			vm.setInstallLocation(home);
			JavaRuntime.setDefaultVMInstall(vm, new NullProgressMonitor());
		}
		assumeTrue(JavaRuntime.getDefaultVMInstall() != null,
				() -> info.getDisplayName() + ": no JVM could be registered with the IDE");
	}
}
