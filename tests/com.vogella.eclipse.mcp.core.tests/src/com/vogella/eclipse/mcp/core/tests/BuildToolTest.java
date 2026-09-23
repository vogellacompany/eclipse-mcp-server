package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IJavaProject;
import org.osgi.framework.FrameworkUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class BuildToolTest {

	private static final String TOOL = "eclipse_build";

	private static final String STATUS_TOOL = "eclipse_get_build_status";

	private static final String PROJECT = "mcp-build-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void buildsAProjectAndReportsTheOutcome() throws Exception {
		fixture.createProject(PROJECT);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT));

		assertEquals("done", result.get("state"));
		assertEquals("incremental", result.get("kind"));
		assertEquals("projects", result.get("scope"));
		assertEquals(List.of(PROJECT), result.get("projects"));
		assertNotNull(result.get("buildId"));
		assertEquals(List.of(), result.get("builderFailures"));
	}

	@Test
	void countsTheProblemsThatFollowedTheBuild() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "broken", "Broken", "package broken;\npublic class Broken { Missing field; }\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "kind", "full"));

		assertEquals("done", result.get("state"));
		assertTrue(((Number) result.get("errors")).intValue() > 0, "expected the broken type to produce an error");
	}

	@Test
	@SuppressWarnings("unchecked")
	void namesTheBuiltProjectsOnlyWhenAsked() throws Exception {
		// a project counts as built when its build output changed, so it needs a source
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "built", "Built", "package built;\npublic class Built { }\n");

		Map<String, Object> quiet = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "kind", "full"));
		Map<String, Object> named = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "kind", "full", "includeBuiltProjects", Boolean.TRUE));

		// the list is several hundred names on a platform workspace, in every answer
		assertFalse(quiet.containsKey("builtProjects"), "got " + quiet);
		assertTrue(((Number) quiet.get("builtProjectCount")).intValue() > 0, "got " + quiet);
		List<String> names = (List<String>) named.get("builtProjects");
		assertTrue(names.contains(PROJECT), "got " + named);
		assertEquals(names.size(), ((Number) named.get("builtProjectCount")).intValue(), "got " + named);
	}

	@Test
	void omitsTheCountsWhenNotAskedFor() throws Exception {
		fixture.createProject(PROJECT);

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "returnProblems", Boolean.FALSE));

		assertEquals("done", result.get("state"));
		assertEquals(null, result.get("errors"));
		assertEquals(null, result.get("warnings"));
	}

	@Test
	void theStatusToolFindsTheBuildAgain() throws Exception {
		fixture.createProject(PROJECT);
		Map<String, Object> started = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT));
		String buildId = (String) started.get("buildId");

		Map<String, Object> polled = TestFixture.callAndParse(STATUS_TOOL, Map.of("buildId", buildId));

		assertEquals(buildId, polled.get("buildId"));
		assertEquals("done", polled.get("state"));
	}

	@Test
	void theStatusToolWithoutAnIdReportsTheLatestBuild() throws Exception {
		fixture.createProject(PROJECT);
		Map<String, Object> started = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT));

		Map<String, Object> latest = TestFixture.callAndParse(STATUS_TOOL, Map.of());

		assertEquals(started.get("buildId"), latest.get("buildId"));
	}

	@Test
	void rejectsAnUnknownBuildId() throws Exception {
		McpToolResult result = TestFixture.call(STATUS_TOOL, Map.of("buildId", "build-does-not-exist"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("build-does-not-exist"), result.text());
	}

	@Test
	void rejectsAnUnknownProject() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("project", "no-such-project"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("no-such-project"), result.text());
	}

	@Test
	void rejectsAnUnknownKind() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("kind", "rebuild"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("rebuild"), result.text());
	}

	@Test
	void waitFalseReturnsBeforeTheRefreshRuns() throws Exception {
		fixture.createProject(PROJECT);

		long before = System.currentTimeMillis();
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("wait", Boolean.FALSE, "refresh", Boolean.TRUE));
		long elapsed = System.currentTimeMillis() - before;

		// the refresh belongs inside the job; running it first made wait:false block for
		// the whole workspace refresh and blow the call timeout on a large workspace
		assertNotNull(result.get("buildId"));
		assertTrue(elapsed < 5000, "wait:false took " + elapsed + " ms, so something ran synchronously");
	}

	@Test
	void reportsTheRefreshCostSeparatelyFromTheBuild() throws Exception {
		fixture.createProject(PROJECT);

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "refresh", Boolean.TRUE));

		assertEquals("done", result.get("state"));
		assertNotNull(result.get("refreshMillis"), "a refresh that ran must be reported");
		assertNotNull(result.get("buildMillis"));
	}

	@Test
	void aCleanSaysThatItRebuiltNothing() throws Exception {
		fixture.createProject(PROJECT);

		Map<String, Object> plain = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "kind", "clean"));
		assertTrue(String.valueOf(plain.get("note")).contains("nothing is built"), String.valueOf(plain.get("note")));

		Map<String, Object> rebuilt = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "kind", "clean", "buildAfterClean", Boolean.TRUE));
		assertEquals(null, rebuilt.get("note"));
	}

	@Test
	void doesNotReportLogEntriesFromBeforeTheBuild() throws Exception {
		fixture.createProject(PROJECT);
		String message = "mcp stale log marker " + System.nanoTime();
		Platform.getLog(FrameworkUtil.getBundle(BuildToolTest.class))
				.log(new Status(IStatus.ERROR, "com.vogella.eclipse.mcp.core.tests", message));
		// the log timestamp has second resolution, so make sure the build starts in a later one
		Thread.sleep(1100);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT));

		assertFalse(String.valueOf(result.get("builderFailures")).contains(message),
				"an entry logged before the build must not be reported as a build failure");
	}

	@Test
	void returnsRunningWhenTheWaitIsTooShort() throws Exception {
		fixture.createProject(PROJECT);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "wait", Boolean.FALSE));

		// not waiting at all means the job may or may not have finished, but the handle must be usable either way
		assertNotNull(result.get("buildId"));
		assertTrue(List.of("running", "done").contains(result.get("state")), String.valueOf(result.get("state")));
	}
}
