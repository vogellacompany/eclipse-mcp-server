package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;

import com.vogella.eclipse.mcp.core.McpToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GetProblemsToolTest {

	private static final String PROJECT = "mcp-problems-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	@SuppressWarnings("unchecked")
	void aMarkerReportsOnlyWhatChangedSince() throws Exception {
		IJavaProject project = fixture.createJavaProject("mcp-problem-marker-test");
		TestFixture.addType(project, "example", "Broken", """
				package example;
				public class Broken {
					Missing first;
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> mark = TestFixture.callAndParse("eclipse_mark_problems", Map.of());
		String marker = String.valueOf(mark.get("marker"));

		TestFixture.addType(project, "example", "AlsoBroken", """
				package example;
				public class AlsoBroken {
					AlsoMissing second;
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_problems",
				Map.of("project", "mcp-problem-marker-test", "marker", marker, "maxResults", Integer.valueOf(200)));

		// only the new one: the problem that was already there is what the marker
		// exists to leave out
		List<Map<String, Object>> problems = (List<Map<String, Object>>) result.get("problems");
		// exactly one, rather than a substring check: "AlsoMissing cannot be resolved"
		// contains "Missing cannot be resolved", so asserting on text passes either way
		assertEquals(1, problems.size(), "only the new problem should be reported, got " + problems);
		assertTrue(String.valueOf(problems.get(0).get("message")).startsWith("AlsoMissing"), "got " + problems);
		assertEquals(Boolean.TRUE, result.get("sinceMarker"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void aScopedQueryDiffsOnlyItsOwnScope() throws Exception {
		IJavaProject asked = fixture.createJavaProject("mcp-marker-scope-asked");
		IJavaProject other = fixture.createJavaProject("mcp-marker-scope-other");
		TestFixture.addType(other, "example", "Broken", """
				package example;
				public class Broken {
					Missing field;
				}
				""");
		TestFixture.build(other.getProject());

		String marker = String.valueOf(TestFixture.callAndParse("eclipse_mark_problems", Map.of()).get("marker"));

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_problems",
				Map.of("project", "mcp-marker-scope-asked", "marker", marker, "severity", "all"));

		// the marker is taken workspace wide and the query is not: without narrowing
		// the baseline to the scope asked about, every problem in every other project
		// is reported as resolved while it is still sitting there
		assertEquals(Integer.valueOf(0), result.get("resolvedCount"),
				"another project's problems are not this query's business, got " + result.get("resolved"));
		assertTrue(((List<Object>) result.get("problems")).isEmpty(), "got " + result);
	}

	@Test
	void rejectsAMarkerItNoLongerHolds() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_get_problems", Map.of("marker", "mcpproblems-999999"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("mark_problems"), result.text());
	}

	@Test
	void reportsAMarkerCreatedByTheTest() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		createMarker(project, "broken.txt", IMarker.SEVERITY_ERROR, "Something is broken", 42);

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_problems", Map.of("project", PROJECT));
		Map<String, Object> problem = onlyProblem(result);

		assertEquals("/%s/broken.txt".formatted(PROJECT), problem.get("path"));
		assertEquals(PROJECT, problem.get("project"));
		assertEquals("error", problem.get("severity"));
		assertEquals("Something is broken", problem.get("message"));
		assertEquals(Integer.valueOf(42), problem.get("line"));
	}

	@Test
	void filtersBySeverity() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		createMarker(project, "error.txt", IMarker.SEVERITY_ERROR, "An error", 1);
		createMarker(project, "warning.txt", IMarker.SEVERITY_WARNING, "A warning", 2);

		Map<String, Object> errors = TestFixture.callAndParse("eclipse_get_problems",
				Map.of("project", PROJECT, "severity", "error"));
		assertEquals("An error", onlyProblem(errors).get("message"));

		Map<String, Object> warnings = TestFixture.callAndParse("eclipse_get_problems",
				Map.of("project", PROJECT, "severity", "warning"));
		assertEquals("A warning", onlyProblem(warnings).get("message"));

		Map<String, Object> all = TestFixture.callAndParse("eclipse_get_problems",
				Map.of("project", PROJECT, "severity", "all"));
		assertEquals(Integer.valueOf(2), all.get("total"));
	}

	@Test
	void filtersByProject() throws Exception {
		IProject withMarker = fixture.createProject(PROJECT);
		fixture.createProject(PROJECT + "-other");
		createMarker(withMarker, "error.txt", IMarker.SEVERITY_ERROR, "An error", 1);

		Map<String, Object> other = TestFixture.callAndParse("eclipse_get_problems",
				Map.of("project", PROJECT + "-other"));
		assertEquals(Integer.valueOf(0), other.get("total"));
		Map<String, Object> marked = TestFixture.callAndParse("eclipse_get_problems", Map.of("project", PROJECT));
		assertEquals(Integer.valueOf(1), marked.get("total"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsTruncation() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		for (int i = 0; i < 3; i++) {
			createMarker(project, "broken%d.txt".formatted(i), IMarker.SEVERITY_ERROR, "Error " + i, i + 1);
		}

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_problems",
				Map.of("project", PROJECT, "maxResults", Integer.valueOf(1)));

		assertEquals(Integer.valueOf(3), result.get("total"));
		assertEquals(Boolean.TRUE, result.get("truncated"));
		assertEquals(1, ((List<Map<String, Object>>) result.get("problems")).size());
	}

	@Test
	@SuppressWarnings("unchecked")
	void refreshPicksUpAFileWrittenOutsideTheIde() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		Path packageDirectory = javaProject.getProject().getFolder("src").getLocation().toFile().toPath()
				.resolve("example");
		Files.createDirectories(packageDirectory);
		Files.writeString(packageDirectory.resolve("Broken.java"), """
				package example;

				public class Broken {
					Missing field;
				}
				""");

		Map<String, Object> stale = TestFixture.callAndParse("eclipse_get_problems",
				Map.of("project", PROJECT, "refresh", Boolean.FALSE));
		assertEquals(Integer.valueOf(0), stale.get("total"), "The IDE cannot know about the file yet");
		assertEquals(Boolean.FALSE, stale.get("upToDate"), "An unrefreshed answer must say so");

		// the refresh brings the file into the workspace; compiling it is a separate
		// decision with a separate cost, and this tool no longer makes it
		TestFixture.callAndParse("eclipse_get_problems", Map.of("project", PROJECT));
		TestFixture.build(javaProject.getProject());

		Map<String, Object> fresh = TestFixture.callAndParse("eclipse_get_problems", Map.of("project", PROJECT));
		List<Map<String, Object>> problems = (List<Map<String, Object>>) fresh.get("problems");
		assertTrue(problems.stream().anyMatch(problem -> "/%s/src/example/Broken.java".formatted(PROJECT)
				.equals(problem.get("path"))), "The compile error was not picked up, got " + problems);
	}

	@Test
	@SuppressWarnings("unchecked")
	void neverStartsABuildOfItsOwn() throws Exception {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IWorkspaceDescription description = workspace.getDescription();
		boolean autoBuilding = description.isAutoBuilding();
		description.setAutoBuilding(false);
		workspace.setDescription(description);
		try {
			IJavaProject javaProject = fixture.createJavaProject(PROJECT);
			Path packageDirectory = javaProject.getProject().getFolder("src").getLocation().toFile().toPath()
					.resolve("example");
			Files.createDirectories(packageDirectory);
			Files.writeString(packageDirectory.resolve("AlsoBroken.java"), """
					package example;

					public class AlsoBroken {
						Missing field;
					}
					""");

			Map<String, Object> result = TestFixture.callAndParse("eclipse_get_problems", Map.of("project", PROJECT));

			assertEquals(Boolean.FALSE, result.get("autoBuild"), "The fixture turned auto-build off");
			// this tool never starts a build. Asking for markers used to start an
			// incremental build that JDT turned into a batch compile of every open
			// project, so a read cost thirty seconds; the file is simply not compiled
			// yet and the answer says so rather than paying for it
			List<Map<String, Object>> problems = (List<Map<String, Object>>) result.get("problems");
			assertTrue(problems.stream().noneMatch(problem -> "/%s/src/example/AlsoBroken.java".formatted(PROJECT)
					.equals(problem.get("path"))), "nothing has compiled it, got " + problems);
			assertEquals(Boolean.FALSE, result.get("upToDate"));
			assertTrue(String.valueOf(result.get("staleness")).contains("eclipse_build"), "got " + result);

			TestFixture.build(javaProject.getProject());
			List<Map<String, Object>> afterBuild = (List<Map<String, Object>>) TestFixture
					.callAndParse("eclipse_get_problems", Map.of("project", PROJECT)).get("problems");
			assertTrue(afterBuild.stream().anyMatch(problem -> "/%s/src/example/AlsoBroken.java".formatted(PROJECT)
					.equals(problem.get("path"))), "after an explicit build it is there, got " + afterBuild);
		} finally {
			description.setAutoBuilding(autoBuilding);
			workspace.setDescription(description);
		}
	}

	@Test
	void rejectsAnUnknownProject() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_get_problems", Map.of("project", "mcp-no-such-project"));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("mcp-no-such-project"), result.text());
	}

	@Test
	void excludedTypesAreNotReportedAsResolvedAgainstAMarker() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		IFile file = project.getFile("typed.txt");
		file.create(new ByteArrayInputStream(new byte[0]), true, new NullProgressMonitor());
		IMarker typed = file.createMarker("org.eclipse.jdt.core.problem");
		typed.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
		typed.setAttribute(IMarker.MESSAGE, "Flickers");
		typed.setAttribute(IMarker.LINE_NUMBER, 1);
		String marker = String.valueOf(TestFixture.callAndParse("eclipse_mark_problems", Map.of()).get("marker"));

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_problems", Map.of("project", PROJECT,
				"marker", marker, "excludeTypes", List.of("org.eclipse.jdt.core.problem")));

		// the excluded type is filtered from the current side, so without the same
		// filter on the baseline the problem would read as fixed
		assertEquals(Integer.valueOf(0), result.get("resolvedCount"), "got " + result);
	}

	@Test
	void rejectsAnUnknownSeverity() throws Exception {
		assertTrue(TestFixture.call("eclipse_get_problems", Map.of("severity", "critical")).isError());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> onlyProblem(Map<String, Object> result) {
		List<Map<String, Object>> problems = (List<Map<String, Object>>) result.get("problems");
		assertEquals(1, problems.size(), "Expected exactly one problem, got " + problems);
		return problems.get(0);
	}

	private static void createMarker(IProject project, String fileName, int severity, String message, int line)
			throws Exception {
		IFile file = project.getFile(fileName);
		file.create(new ByteArrayInputStream(new byte[0]), true, new NullProgressMonitor());
		IMarker marker = file.createMarker(IMarker.PROBLEM);
		marker.setAttribute(IMarker.SEVERITY, severity);
		marker.setAttribute(IMarker.MESSAGE, message);
		marker.setAttribute(IMarker.LINE_NUMBER, line);
	}
}
