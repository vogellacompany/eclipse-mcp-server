package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FindResourcesToolTest {

	private static final String TOOL = "eclipse_find_resources";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void countsEveryPatternAndReportsTruncationBehindMaxResults() throws Exception {
		IProject project = fixture.createProject("mcp-find-resources-test");
		file(project, "one.svg", "a");
		file(project, "two.svg", "b");
		file(project, "three.png", "c");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("namePatterns", List.of("*.svg", "*.png"),
				"scope", "workspace", "bundleFilter", "mcp-find-resources-test", "maxResults", Integer.valueOf(2)));

		assertEquals(Integer.valueOf(3), result.get("total"));
		assertEquals(Boolean.TRUE, result.get("truncated"));
		@SuppressWarnings("unchecked")
		Map<String, Object> counts = (Map<String, Object>) result.get("counts");
		assertEquals(Integer.valueOf(2), counts.get("*.svg"));
		assertEquals(Integer.valueOf(1), counts.get("*.png"));
	}

	@Test
	void dedupeReportsIdenticalContentOnce() throws Exception {
		IProject project = fixture.createProject("mcp-find-resources-dedupe");
		file(project, "a.svg", "same");
		file(project, "b.svg", "same");
		file(project, "c.svg", "different");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("namePattern", "*.svg", "scope",
				"workspace", "bundleFilter", "mcp-find-resources-dedupe", "dedupe", Boolean.TRUE));

		assertEquals(Integer.valueOf(2), result.get("total"));
	}

	@Test
	void countOnlyReportsNoHits() throws Exception {
		IProject project = fixture.createProject("mcp-find-resources-count");
		file(project, "a.svg", "x");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("namePattern", "*.svg", "scope",
				"workspace", "bundleFilter", "mcp-find-resources-count", "countOnly", Boolean.TRUE));

		assertEquals(Integer.valueOf(1), result.get("total"));
		assertTrue(!result.containsKey("hits"), result.toString());
	}

	private static void file(IProject project, String name, String content) throws Exception {
		project.getFile(name).create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());
	}
}
