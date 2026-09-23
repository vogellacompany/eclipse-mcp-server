package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ListProjectsToolTest {

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsAProjectCreatedByTheTest() throws Exception {
		fixture.createJavaProject("mcp-list-projects-test");

		Map<String, Object> result = TestFixture.callAndParse("eclipse_list_projects", Map.of());
		List<Map<String, Object>> projects = (List<Map<String, Object>>) result.get("projects");
		Map<String, Object> project = projects.stream()
				.filter(entry -> "mcp-list-projects-test".equals(entry.get("name"))).findFirst()
				.orElseThrow(() -> new AssertionError("Test project not reported, got " + projects));

		assertEquals(Boolean.TRUE, project.get("open"));
		assertEquals("model", project.get("natureSource"));
		assertTrue(((List<String>) project.get("natures")).contains("org.eclipse.jdt.core.javanature"),
				"Java nature missing in " + project);
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsTheNaturesOfAClosedProjectFromItsProjectFile() throws Exception {
		IProject project = fixture.createJavaProject("mcp-list-closed-test").getProject();
		project.close(new NullProgressMonitor());

		Map<String, Object> entry = find("mcp-list-closed-test");

		assertEquals(Boolean.FALSE, entry.get("open"));
		// the Java model cannot answer for a closed project, and answering "none"
		// makes a client's classification depend on which projects happen to be open
		assertEquals("projectFile", entry.get("natureSource"));
		assertTrue(((List<String>) entry.get("natures")).contains("org.eclipse.jdt.core.javanature"),
				"the nature is in .project on disk whether or not the project is open, got " + entry);
	}

	@Test
	@SuppressWarnings("unchecked")
	void summaryCountsWithoutListing() throws Exception {
		fixture.createJavaProject("mcp-list-summary-open");
		fixture.createJavaProject("mcp-list-summary-closed").getProject().close(new NullProgressMonitor());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_list_projects", Map.of("summary", Boolean.TRUE));

		assertFalse(result.containsKey("projects"), "got " + result);
		int total = ((Number) result.get("total")).intValue();
		int open = ((Number) result.get("open")).intValue();
		int closed = ((Number) result.get("closed")).intValue();
		assertEquals(total, open + closed, "got " + result);
		assertTrue(open >= 1 && closed >= 1, "got " + result);
		// the closed project's nature is read from disk, so both are counted
		Map<String, Object> byNature = (Map<String, Object>) result.get("byNature");
		assertTrue(((Number) byNature.get("org.eclipse.jdt.core.javanature")).intValue() >= 2, "got " + result);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> find(String name) throws Exception {
		Map<String, Object> result = TestFixture.callAndParse("eclipse_list_projects", Map.of());
		List<Map<String, Object>> projects = (List<Map<String, Object>>) result.get("projects");
		return projects.stream().filter(entry -> name.equals(entry.get("name"))).findFirst()
				.orElseThrow(() -> new AssertionError("Test project not reported, got " + projects));
	}
}
