package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
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
		Map<String, Object> answer = TestFixture.callAndParse("eclipse_set_breakpoint",
				Map.of("type", "sample.Main", "line", Integer.valueOf(line)));
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
