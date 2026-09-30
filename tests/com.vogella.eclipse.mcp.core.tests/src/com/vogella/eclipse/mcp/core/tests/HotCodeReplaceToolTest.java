package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Drives {@code eclipse_hot_code_replace} against the JVM the tests run in, attach helper included.
 */
class HotCodeReplaceToolTest {

	private static final String TOOL = "eclipse_hot_code_replace";

	private static final String PROJECT = "HotCodeReplaceProject";

	private static final String PROBE = """
			package hot;
			public class Probe {
				public static String answer() {
					return "%s";
				}
			}
			""";

	private final TestFixture fixture = new TestFixture();

	private IJavaProject project;

	@BeforeEach
	void setUp() throws CoreException {
		project = fixture.createJavaProject(PROJECT);
	}

	@AfterEach
	void tearDown() throws CoreException {
		fixture.dispose();
	}

	@Test
	@SuppressWarnings("unchecked")
	void replacesTheBodyOfALoadedMethod() throws Exception {
		IFile source = TestFixture.addType(project, "hot", "Probe", PROBE.formatted("old"));
		TestFixture.build(project.getProject());
		URL bin = project.getProject().getFolder("bin").getLocation().toFile().toURI().toURL();
		try (URLClassLoader loader = new URLClassLoader(new URL[] { bin }, null)) {
			Method answer = loader.loadClass("hot.Probe").getMethod("answer");
			assertEquals("old", answer.invoke(null));

			rewrite(source, PROBE.formatted("new"));
			Map<String, Object> result = TestFixture.callAndParse(TOOL,
					Map.of("project", PROJECT, "classes", List.of("hot.Probe")));

			// a copy of the class from an earlier test can still be alive in this JVM,
			// and the tool replaces every copy it finds, so the count is at least one
			List<Map<String, Object>> redefined = (List<Map<String, Object>>) result.get("redefined");
			assertFalse(redefined.isEmpty(), result.toString());
			for (Map<String, Object> entry : redefined) {
				assertEquals("hot.Probe", entry.get("class"));
				assertEquals(Boolean.TRUE, entry.get("wasLoaded"));
				assertEquals(Boolean.FALSE, entry.get("sourceNewerThanClass"));
			}
			assertEquals("explicit", result.get("selection"));
			Map<String, Object> agent = (Map<String, Object>) result.get("agent");
			assertTrue(List.of("alreadyLoaded", "helperProcess").contains(agent.get("how")), agent.toString());
			assertEquals("new", answer.invoke(null), "the loaded class still runs the old body");
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void refusesASchemaChangeWithTheJvmsReason() throws Exception {
		IFile source = TestFixture.addType(project, "hot", "Probe", PROBE.formatted("old"));
		TestFixture.build(project.getProject());
		URL bin = project.getProject().getFolder("bin").getLocation().toFile().toURI().toURL();
		try (URLClassLoader loader = new URLClassLoader(new URL[] { bin }, null)) {
			Method answer = loader.loadClass("hot.Probe").getMethod("answer");

			rewrite(source, PROBE.formatted("changed").replace("}\n}", "}\npublic static int extra() { return 1; }\n}"));
			McpToolResult result = TestFixture.call(TOOL, Map.of("project", PROJECT, "classes", List.of("hot.Probe")));

			assertTrue(result.isError(), result.text());
			Map<String, Object> parsed = TestFixture.parse(result.text());
			List<Map<String, Object>> failed = (List<Map<String, Object>>) parsed.get("failed");
			assertFalse(failed.isEmpty(), result.text());
			for (Map<String, Object> entry : failed) {
				assertTrue(String.valueOf(entry.get("error")).contains("attempted to add a method"), result.text());
			}
			assertEquals(0, ((List<?>) parsed.get("redefined")).size(), result.text());
			assertEquals("old", answer.invoke(null), "a refused redefinition must leave the class alone");
		}
	}

	@Test
	void needsClassNamesWhenNoBundleCanBeCompared() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("project", PROJECT));
		assertTrue(result.isError());
		assertTrue(result.text().contains("classes"), result.text());
	}

	@Test
	void reportsMissingClassFilesByName() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("project", PROJECT, "classes", List.of("hot.Missing")));
		assertTrue(result.isError());
		assertTrue(result.text().contains("hot.Missing"), result.text());
	}

	private static void rewrite(IFile file, String content) throws CoreException {
		file.setContents(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), IResource.FORCE,
				new NullProgressMonitor());
		TestFixture.build(file.getProject());
	}
}
