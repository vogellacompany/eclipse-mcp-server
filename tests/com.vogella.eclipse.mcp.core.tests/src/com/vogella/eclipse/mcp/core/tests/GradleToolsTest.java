package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.gradle.internal.GradleRuns;

/**
 * The Gradle tools that need no Gradle build: registration, schemas, the refusals and the run registry.
 * Nothing here starts Gradle, so the suite needs no distribution download.
 */
class GradleToolsTest {

	private static final List<String> STARTING = List.of("eclipse_refresh_gradle_project", "eclipse_run_gradle_tasks",
			"eclipse_list_gradle_tasks");

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void everyGradleToolIsRegisteredWithASchema() throws Exception {
		for (String name : List.of("eclipse_refresh_gradle_project", "eclipse_run_gradle_tasks",
				"eclipse_list_gradle_tasks", "eclipse_get_gradle_run")) {
			IMcpTool tool = TestFixture.tool(name);
			assertFalse(tool.getDescription().isBlank(), name);
			assertEquals("object", TestFixture.parse(tool.getInputSchema()).get("type"), name);
		}
	}

	@Test
	void theToolsThatStartGradleSayTheyRunIt() {
		for (String name : STARTING) {
			assertTrue(TestFixture.tool(name).getDescription().contains("RUNS "), name + " does not announce that it runs Gradle");
		}
		assertTrue(TestFixture.tool("eclipse_run_gradle_tasks").getDescription().contains("RUNS PROJECT CODE"));
		assertTrue(TestFixture.tool("eclipse_refresh_gradle_project").getDescription().contains("CHANGES THE WORKSPACE"));
	}

	@Test
	void theStartingToolsRequireAProject() throws Exception {
		for (String name : STARTING) {
			Object required = TestFixture.parse(TestFixture.tool(name).getInputSchema()).get("required");
			assertTrue(required instanceof List<?> list && list.contains("project"), name + " requires " + required);
		}
	}

	@Test
	void listingHonoursMaxResults() throws Exception {
		Map<String, Object> schema = TestFixture.parse(TestFixture.tool("eclipse_list_gradle_tasks").getInputSchema());
		assertNotNull(((Map<?, ?>) schema.get("properties")).get("maxResults"));
	}

	@Test
	void refusesAnUnknownProject() throws Exception {
		assumeTrue(buildshipResolved());
		for (String name : STARTING) {
			TestFixture.assertRefused(TestFixture.call(name, Map.of("project", "mcp-no-such-project", "tasks", List.of("build"))),
					"no project");
		}
	}

	@Test
	void refusesAProjectWithoutTheGradleNature() throws Exception {
		assumeTrue(buildshipResolved());
		fixture.createProject("mcp-not-gradle-test");
		for (String name : STARTING) {
			McpToolResult answer = TestFixture.call(name,
					Map.of("project", "mcp-not-gradle-test", "tasks", List.of("build")));
			TestFixture.assertRefused(answer, "not a Gradle project");
		}
	}

	@Test
	void refusesWithoutAProjectName() throws Exception {
		assumeTrue(buildshipResolved());
		for (String name : STARTING) {
			TestFixture.assertRefused(TestFixture.call(name, Map.of()), "'project'");
		}
	}

	@Test
	void aRunThatSucceedsIsDoneWithItsOutput() throws Exception {
		GradleRuns.Run run = GradleRuns.getInstance().start("run", "p", List.of("build"), (handle, monitor) -> {
			handle.append("BUILD SUCCESSFUL");
			return true;
		});

		assertTrue(run.await(10_000));
		Map<String, Object> described = TestFixture.parse(run.describe(10).toString());
		assertEquals("done", described.get("state"));
		assertEquals("BUILD SUCCESSFUL", described.get("output"));
	}

	@Test
	void aRunThatThrowsFailsWithTheReason() throws Exception {
		GradleRuns.Run run = GradleRuns.getInstance().start("run", "p", List.of("build"), (handle, monitor) -> {
			throw new IllegalStateException("no such task");
		});

		assertTrue(run.await(10_000));
		Map<String, Object> described = TestFixture.parse(run.describe(10).toString());
		assertEquals("failed", described.get("state"));
		assertTrue(String.valueOf(described.get("output")).contains("no such task"), "got " + described);
	}

	@Test
	void cancellingARunningRunEndsItAsCancelled() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch cancelled = new CountDownLatch(1);
		GradleRuns.Run run = GradleRuns.getInstance().start("run", "p", List.of("bootRun"), (handle, monitor) -> {
			handle.onCancel(cancelled::countDown);
			started.countDown();
			cancelled.await();
			return true;
		});
		assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));

		Map<String, Object> answer = TestFixture.callAndParse("eclipse_get_gradle_run",
				Map.of("runId", run.id(), "cancel", true));

		assertEquals("cancelled", answer.get("state"), "got " + answer);
	}

	@Test
	void anUnknownRunIdIsRefused() throws Exception {
		GradleRuns.getInstance().start("run", "p", List.of(), (handle, monitor) -> true);
		TestFixture.assertRefused(TestFixture.call("eclipse_get_gradle_run", Map.of("runId", "gradle-0")),
				"no Gradle run");
	}

	private static boolean buildshipResolved() {
		try {
			Class.forName("org.eclipse.buildship.core.GradleCore");
			Class.forName("org.gradle.tooling.ProjectConnection");
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}
}
