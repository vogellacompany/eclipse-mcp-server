package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class RefreshToolTest {

	private static final String TOOL = "eclipse_refresh";

	private static final String PROJECT = "mcp-refresh-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void seesAFileWrittenOutsideTheIde() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		// written behind the IDE's back, so the workspace does not know about it yet
		Files.write(project.getLocation().append("outside.txt").toFile().toPath(),
				"written by a shell".getBytes(StandardCharsets.UTF_8));
		assertFalse(project.getFile("outside.txt").exists(), "the workspace should not see it before the refresh");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT));

		assertEquals("done", result.get("state"));
		assertTrue(project.getFile("outside.txt").exists(), "the refresh should have made the file visible");
	}

	@Test
	void reportsARefreshAsRefreshAndCountsNoProblems() throws Exception {
		fixture.createProject(PROJECT);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT));

		assertEquals("refresh", result.get("kind"));
		assertNotNull(result.get("refreshMillis"));
		assertNull(result.get("buildMillis"), "a refresh must not report a build time");
		assertNull(result.get("errors"), "a refresh must not report marker counts, it did not build");
	}

	@Test
	void theStatusToolReportsTheRefresh() throws Exception {
		fixture.createProject(PROJECT);
		Map<String, Object> started = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT));

		Map<String, Object> polled = TestFixture.callAndParse("eclipse_get_build_status",
				Map.of("buildId", (String) started.get("buildId")));

		assertEquals(started.get("buildId"), polled.get("buildId"));
		assertEquals("refresh", polled.get("kind"));
	}

	@Test
	void returnsAHandleImmediatelyWhenNotWaiting() throws Exception {
		fixture.createProject(PROJECT);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("wait", Boolean.FALSE));

		assertNotNull(result.get("buildId"));
	}

	@Test
	void rejectsAnUnknownProject() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("project", "no-such-project"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("no-such-project"), result.text());
	}
}
