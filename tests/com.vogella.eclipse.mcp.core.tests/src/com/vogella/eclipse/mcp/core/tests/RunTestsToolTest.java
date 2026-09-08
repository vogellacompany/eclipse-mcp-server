package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.ToolProvider;

import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.core.McpToolResult;

class RunTestsToolTest {

	private static final String TOOL = "eclipse_run_tests";

	private static final String PROJECT = "mcp-runtests-test";

	private static final String JUNIT6_PROJECT = "mcp-runtests-junit6-test";

	private final TestFixture fixture = new TestFixture();

	@TempDir
	Path temporary;

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

	/**
	 * A project whose Jupiter API is version 6, which JDT launches through its own
	 * runner. The jar is built here because the test IDE resolves JUnit 5 only;
	 * JDT reads the version off the jar file name, so the name is the fixture.
	 */
	private IJavaProject withJUnit6OnTheBuildPath() throws Exception {
		IJavaProject project = fixture.createJavaProject(JUNIT6_PROJECT);
		Path sources = temporary.resolve("src/org/junit/jupiter/api/Test.java");
		Files.createDirectories(sources.getParent());
		Files.writeString(sources, """
				package org.junit.jupiter.api;
				import java.lang.annotation.*;
				@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
				public @interface Test {}
				""");
		Path classes = temporary.resolve("classes");
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
				sources.toString()));
		Path jar = temporary.resolve("junit-jupiter-api-6.0.0.jar");
		try (JarOutputStream archive = new JarOutputStream(Files.newOutputStream(jar))) {
			archive.putNextEntry(new JarEntry("org/junit/jupiter/api/Test.class"));
			Files.copy(classes.resolve("org/junit/jupiter/api/Test.class"), archive);
			archive.closeEntry();
		}
		IClasspathEntry[] existing = project.getRawClasspath();
		IClasspathEntry[] withJUnit6 = new IClasspathEntry[existing.length + 1];
		System.arraycopy(existing, 0, withJUnit6, 0, existing.length);
		withJUnit6[existing.length] = JavaCore.newLibraryEntry(IPath.fromOSString(jar.toString()), null, null);
		project.setRawClasspath(withJUnit6, null);
		TestFixture.addType(project, "sample", "SampleTest", """
				package sample;
				import org.junit.jupiter.api.Test;
				public class SampleTest {
					@Test
					public void passes() {
					}
				}
				""");
		TestFixture.build(project.getProject());
		return project;
	}

	/**
	 * JUnit 6 has to be launched through JDT's junit6 runner, and picking junit5
	 * for it does not merely choose a different runner: the launch delegate
	 * rechecks the kind and aborts with "Cannot find
	 * 'org.junit.platform.commons.annotation.Testable' on project build path",
	 * naming the one library that IS on the path, so no test runs at all.
	 * <p>
	 * The dry run is what is assertable here, since this test IDE has no JUnit 6
	 * to launch; that a JUnit 6 project then runs was measured against a full SDK.
	 * Reported by JKutscha in PR #5.
	 */
	@Test
	void aJUnit6ProjectIsLaunchedAsJUnit6() throws Exception {
		withJUnit6OnTheBuildPath();

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", JUNIT6_PROJECT, "dryRun", Boolean.TRUE));

		assertEquals("org.eclipse.jdt.junit.loader.junit6", result.get("testKind"), String.valueOf(result));
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
	void theResultsToolFindsTheRunAgain() throws Exception {
		withTests();
		Map<String, Object> started = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "testClass", "sample.SampleTest", "timeoutSeconds", Integer.valueOf(120)));

		Map<String, Object> polled = TestFixture.callAndParse("eclipse_get_test_results",
				Map.of("runId", (String) started.get("runId"), "includePassed", Boolean.TRUE));

		assertEquals(started.get("runId"), polled.get("runId"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> tests = (List<Map<String, Object>>) polled.get("tests");
		assertEquals(2, tests.size(), "includePassed should report both");
	}

	@Test
	void theCountersAccountForEveryTest() throws Exception {
		withTests();

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "testClass", "sample.SampleTest", "timeoutSeconds", Integer.valueOf(120)));

		int total = ((Number) result.get("total")).intValue();
		int counted = ((Number) result.get("passed")).intValue() + ((Number) result.get("failed")).intValue()
				+ ((Number) result.get("errors")).intValue() + ((Number) result.get("ignored")).intValue();
		// a summary that does not add up is how 38 errors were once reported as zero
		assertEquals(total, counted, "the counters must account for every case: " + result);
		assertEquals(null, result.get("countsInconsistent"));
		assertEquals(null, result.get("unclassified"));
	}

	@Test
	void aPlainJavaProjectRunsAsPlainJUnit() throws Exception {
		withTests();

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "testClass", "sample.SampleTest", "timeoutSeconds", Integer.valueOf(120)));

		assertEquals("junit", result.get("launchedAs"));
		assertEquals(null, result.get("caveat"), "a project with no PDE nature needs no warning");
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
