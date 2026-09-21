package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class SetBreeToolTest {

	private static final String TOOL = "eclipse_set_bree";

	private static final String PROJECT = "mcp-bree-test";

	private static final String FROM = "JavaSE-17";

	private static final String TO = "JavaSE-21";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void aDryRunReportsTheChangeWithoutWritingIt() throws Exception {
		IProject project = createPlugin();

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("bree", TO, "projects", List.of(PROJECT)));
		Map<String, Object> entry = only(result);

		assertEquals(Boolean.TRUE, result.get("dryRun"));
		assertEquals(FROM, entry.get("previousBree"));
		assertEquals(TO, entry.get("bree"));
		assertTrue(manifest(project).contains(FROM), "the manifest must be untouched by a dry run");
	}

	@Test
	void writesTheManifestHeaderAndMovesTheJreContainer() throws Exception {
		IProject project = createPlugin();
		String before = (String) only(TestFixture.callAndParse(TOOL, Map.of("bree", TO, "projects", List.of(PROJECT))))
				.get("previousJreContainer");

		Map<String, Object> entry = only(TestFixture.callAndParse(TOOL,
				Map.of("bree", TO, "projects", List.of(PROJECT), "dryRun", Boolean.FALSE)));

		assertEquals(Boolean.TRUE, entry.get("changed"));
		assertTrue(manifest(project).contains("Bundle-RequiredExecutionEnvironment: " + TO), manifest(project));
		String after = (String) entry.get("jreContainer");
		assertTrue(after != null && after.endsWith(TO), "the JRE container should follow the BREE, was " + after);
		assertNotEquals(before, after);
	}

	@Test
	void setsTheCompilerSettingsAlongsideTheHeader() throws Exception {
		IProject project = createPlugin();

		TestFixture.callAndParse(TOOL, Map.of("bree", TO, "projects", List.of(PROJECT), "dryRun", Boolean.FALSE));

		IJavaProject javaProject = JavaCore.create(project);
		assertEquals("21", javaProject.getOption(JavaCore.COMPILER_COMPLIANCE, true));
		assertEquals("21", javaProject.getOption(JavaCore.COMPILER_SOURCE, true));
		assertEquals("21", javaProject.getOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, true));
	}

	@Test
	void writesEveryOptionTheEnvironmentDictates() throws Exception {
		IProject project = createPlugin();
		IJavaProject javaProject = JavaCore.create(project);
		javaProject.setOption(JavaCore.COMPILER_RELEASE, JavaCore.DISABLED);

		TestFixture.callAndParse(TOOL, Map.of("bree", TO, "projects", List.of(PROJECT), "dryRun", Boolean.FALSE));

		assertEquals(JavaCore.ENABLED, javaProject.getOption(JavaCore.COMPILER_RELEASE, true));
		assertEquals(JavaCore.ERROR, javaProject.getOption(JavaCore.COMPILER_PB_ENUM_IDENTIFIER, true));
	}

	@Test
	void repairsAJreContainerThatLagsBehindTheHeader() throws Exception {
		IProject project = createPlugin(TO);
		setJreContainer(JavaCore.create(project), FROM);
		String manifestBefore = manifest(project);

		Map<String, Object> entry = only(TestFixture.callAndParse(TOOL,
				Map.of("bree", TO, "projects", List.of(PROJECT), "dryRun", Boolean.FALSE)));

		assertEquals(Boolean.TRUE, entry.get("changed"));
		assertTrue(String.valueOf(entry.get("previousJreContainer")).endsWith(FROM), entry.toString());
		assertTrue(String.valueOf(entry.get("jreContainer")).endsWith(TO), entry.toString());
		assertEquals(manifestBefore, manifest(project), "a matching header must not be rewritten");
	}

	@Test
	void addsAJreContainerWhenThereIsNone() throws Exception {
		IProject project = createPlugin();
		IJavaProject javaProject = JavaCore.create(project);
		javaProject.setRawClasspath(Arrays.stream(javaProject.getRawClasspath())
				.filter(e -> e.getEntryKind() != IClasspathEntry.CPE_CONTAINER).toArray(IClasspathEntry[]::new),
				new NullProgressMonitor());

		Map<String, Object> entry = only(TestFixture.callAndParse(TOOL,
				Map.of("bree", TO, "projects", List.of(PROJECT), "dryRun", Boolean.FALSE)));

		assertNull(entry.get("previousJreContainer"));
		assertEquals(JavaRuntime.newJREContainerPath(
				JavaRuntime.getExecutionEnvironmentsManager().getEnvironment(TO)).toString(), entry.get("jreContainer"));
	}

	@Test
	void leavesTheCompilerSettingsWhenNotAskedFor() throws Exception {
		IProject project = createPlugin();
		IJavaProject javaProject = JavaCore.create(project);
		String before = javaProject.getOption(JavaCore.COMPILER_COMPLIANCE, true);

		TestFixture.callAndParse(TOOL, Map.of("bree", TO, "projects", List.of(PROJECT), "dryRun", Boolean.FALSE,
				"updateCompliance", Boolean.FALSE));

		assertEquals(before, javaProject.getOption(JavaCore.COMPILER_COMPLIANCE, true));
		assertTrue(manifest(project).contains(TO), "the header should still have been written");
	}

	@Test
	void skipsAProjectThatAlreadyAgrees() throws Exception {
		createPlugin();
		TestFixture.callAndParse(TOOL, Map.of("bree", TO, "projects", List.of(PROJECT), "dryRun", Boolean.FALSE));

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("bree", TO, "projects", List.of(PROJECT), "dryRun", Boolean.FALSE));

		assertEquals(Integer.valueOf(0), result.get("changed"));
		assertTrue(String.valueOf(only(result).get("skippedBecause")).contains("already"));
	}

	@Test
	void selectsOnlyProjectsOnAGivenEnvironment() throws Exception {
		createPlugin();

		Map<String, Object> matching = TestFixture.callAndParse(TOOL,
				Map.of("bree", TO, "namePattern", PROJECT, "currentBree", FROM));
		assertEquals(Integer.valueOf(1), matching.get("total"));

		Map<String, Object> other = TestFixture.callAndParse(TOOL,
				Map.of("bree", TO, "namePattern", PROJECT, "currentBree", "JavaSE-11"));
		assertEquals(Integer.valueOf(0), other.get("total"));
	}

	@Test
	void ignoresProjectsThatAreNotPlugIns() throws Exception {
		fixture.createProject("mcp-bree-plain");

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("bree", TO, "projects", List.of("mcp-bree-plain")));

		assertEquals(Integer.valueOf(0), result.get("total"));
	}

	@Test
	void rejectsAnUnknownEnvironment() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("bree", "JavaSE-99", "namePattern", PROJECT));

		assertTrue(result.isError());
		assertTrue(result.text().contains("JavaSE-99"), result.text());
	}

	@Test
	void refusesToActWithoutASelection() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("bree", TO));

		assertTrue(result.isError());
		assertTrue(result.text().contains("every plug-in"), result.text());
	}

	private IProject createPlugin() throws Exception {
		return createPlugin(FROM);
	}

	private IProject createPlugin(String bree) throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		IProject project = javaProject.getProject();
		IProjectDescription description = project.getDescription();
		description.setNatureIds(new String[] { JavaCore.NATURE_ID, "org.eclipse.pde.PluginNature" });
		project.setDescription(description, new NullProgressMonitor());

		IFolder folder = project.getFolder("META-INF");
		folder.create(false, true, new NullProgressMonitor());
		String content = """
				Manifest-Version: 1.0
				Bundle-ManifestVersion: 2
				Bundle-Name: Bree Test
				Bundle-SymbolicName: %s;singleton:=true
				Bundle-Version: 1.0.0.qualifier
				Bundle-RequiredExecutionEnvironment: %s
				""".formatted(PROJECT, bree);
		project.getFile("META-INF/MANIFEST.MF").create(
				new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), false, new NullProgressMonitor());
		return project;
	}

	private static void setJreContainer(IJavaProject javaProject, String bree) throws Exception {
		IPath path = JavaRuntime.newJREContainerPath(JavaRuntime.getExecutionEnvironmentsManager().getEnvironment(bree));
		IClasspathEntry[] entries = javaProject.getRawClasspath();
		for (int i = 0; i < entries.length; i++) {
			if (entries[i].getEntryKind() == IClasspathEntry.CPE_CONTAINER) {
				entries[i] = JavaCore.newContainerEntry(path);
			}
		}
		javaProject.setRawClasspath(entries, new NullProgressMonitor());
	}

	private static String manifest(IProject project) throws Exception {
		return TestFixture.read(project.getFile("META-INF/MANIFEST.MF"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> only(Map<String, Object> result) {
		List<Map<String, Object>> projects = (List<Map<String, Object>>) result.get("projects");
		assertEquals(1, projects.size(), "expected exactly one project, got " + projects);
		return projects.get(0);
	}
}
