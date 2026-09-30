package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class RunTestsToolTest {

	private static final String TOOL = "eclipse_run_tests";

	private static final String PROJECT = "mcp-runtests-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	/** A project with JUnit 5 on its build path and one passing, one failing test. */
	private IJavaProject withTests() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		IClasspathEntry[] existing = project.getRawClasspath();
		IClasspathEntry[] withJUnit = new IClasspathEntry[existing.length + 1];
		System.arraycopy(existing, 0, withJUnit, 0, existing.length);
		// the literal container path, because JUnitCore is access restricted here: this
		// bundle does not require org.eclipse.jdt.junit.core, only the tool under test does
		withJUnit[existing.length] = JavaCore
				.newContainerEntry(new org.eclipse.core.runtime.Path("org.eclipse.jdt.junit.JUNIT_CONTAINER/5"));
		project.setRawClasspath(withJUnit, null);

		TestFixture.addType(project, "sample", "SampleTest", """
				package sample;
				import org.junit.jupiter.api.Test;
				import static org.junit.jupiter.api.Assertions.assertEquals;
				public class SampleTest {
					@Test
					public void passes() {
						assertEquals(1, 1);
					}
					@Test
					public void fails() {
						assertEquals("expected", "actual");
					}
				}
				""");
		TestFixture.build(project.getProject());
		return project;
	}

	@Test
	void aDryRunFindsTheTestTypesWithoutRunningThem() throws Exception {
		withTests();

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "dryRun", Boolean.TRUE));

		assertEquals(Boolean.TRUE, result.get("dryRun"));
		assertEquals("org.eclipse.jdt.junit.loader.junit5", result.get("testKind"),
				"JUnit 5 on the build path should be detected");
		@SuppressWarnings("unchecked")
		List<String> types = (List<String>) result.get("testTypes");
		assertTrue(types.contains("sample.SampleTest"), types.toString());
	}

	@Test
	void runsTheTestsAndReportsTheFailureWithItsTrace() throws Exception {
		withTests();

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "testClass", "sample.SampleTest", "timeoutSeconds", Integer.valueOf(120)));

		assertEquals("done", result.get("state"), "the run did not finish: " + result);
		assertEquals(Integer.valueOf(2), result.get("total"));
		assertEquals(Integer.valueOf(1), result.get("passed"));
		// failure versus error is JDT's classification of the throwable, not ours, so
		// assert that exactly one test did not pass rather than which bucket it landed in
		assertEquals(1, ((Number) result.get("failed")).intValue() + ((Number) result.get("errors")).intValue(),
				"exactly one test should not have passed: " + result);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> tests = (List<Map<String, Object>>) result.get("tests");
		assertEquals(1, tests.size(), "only the failure should be reported by default");
		Map<String, Object> failure = tests.get(0);
		assertEquals("fails", failure.get("method"));
		assertEquals("sample.SampleTest", failure.get("class"));
		assertEquals("expected", failure.get("expected"));
		assertEquals("actual", failure.get("actual"));
		assertNotNull(failure.get("trace"));
		assertTrue(List.of("FAILURE", "ERROR").contains(failure.get("result")), String.valueOf(failure));

		int counted = ((Number) result.get("passed")).intValue() + ((Number) result.get("failed")).intValue()
				+ ((Number) result.get("errors")).intValue() + ((Number) result.get("ignored")).intValue();
		// a summary that does not add up is how 38 errors were once reported as zero
		assertEquals(((Number) result.get("total")).intValue(), counted, "the counters must account for every case: " + result);
		assertNull(result.get("countsInconsistent"));
		assertNull(result.get("unclassified"));
		assertEquals("junit", result.get("launchedAs"));
		assertNull(result.get("caveat"), "a project with no PDE nature needs no warning");

		Map<String, Object> polled = TestFixture.callAndParse("eclipse_get_test_results",
				Map.of("runId", (String) result.get("runId"), "includePassed", Boolean.TRUE));
		assertEquals(result.get("runId"), polled.get("runId"));
		assertEquals(2, ((List<?>) polled.get("tests")).size(), "includePassed should report both");
	}

	@Test
	void runsASingleMethod() throws Exception {
		withTests();

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "testClass",
				"sample.SampleTest", "testMethod", "passes", "timeoutSeconds", Integer.valueOf(120)));

		assertEquals("done", result.get("state"), String.valueOf(result));
		assertEquals(Integer.valueOf(1), result.get("total"));
		assertEquals(Integer.valueOf(1), result.get("passed"));
	}

	@Test
	void aPlugInProjectRunAsPlainJUnitSaysWhyItsResultsAreSuspect() throws Exception {
		IJavaProject project = withTests();
		org.eclipse.core.resources.IProjectDescription description = project.getProject().getDescription();
		description.setNatureIds(
				new String[] { JavaCore.NATURE_ID, "org.eclipse.pde.PluginNature" });
		project.getProject().setDescription(description, new org.eclipse.core.runtime.NullProgressMonitor());

		// pluginTest false forces the plain launcher, which is the misleading case:
		// OSGi errors that read as broken tests. Launching a real platform here would
		// start a second Eclipse, which is too heavy for this suite.
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "testClass",
				"sample.SampleTest", "pluginTest", "false", "timeoutSeconds", Integer.valueOf(120)));

		assertEquals("junit", result.get("launchedAs"));
		assertTrue(String.valueOf(result.get("caveat")).contains("plain JUnit"), String.valueOf(result.get("caveat")));
	}

	@Test
	void rejectsAMethodWithoutAClass() throws Exception {
		withTests();

		McpToolResult result = TestFixture.call(TOOL, Map.of("project", PROJECT, "testMethod", "passes"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("testClass"), result.text());
	}

	@Test
	void rejectsAnUnknownTestClass() throws Exception {
		withTests();

		McpToolResult result = TestFixture.call(TOOL, Map.of("project", PROJECT, "testClass", "sample.NoSuchTest"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("NoSuchTest"), result.text());
	}

	@Test
	void rejectsANonJavaProject() throws Exception {
		fixture.createProject("mcp-runtests-plain");

		McpToolResult result = TestFixture.call(TOOL, Map.of("project", "mcp-runtests-plain"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("not a Java project"), result.text());
	}
}
