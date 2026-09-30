package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class SetProjectStateToolTest {

	private static final String TOOL = "eclipse_set_project_state";

	private static final String PROJECT = "mcp-state-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void closesAWholeClusterInOneCall() throws Exception {
		IProject depended = fixture.createProject(PROJECT + "-a");
		IProject dependent = fixture.createProject(PROJECT + "-b");
		reference(dependent, depended);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("state", "closed", "dryRun", Boolean.FALSE,
				"projects", List.of(depended.getName(), dependent.getName())));

		// the dependent is closing in the same call, so the build path errors the
		// refusal exists to prevent cannot happen and one pass is enough
		assertEquals(Integer.valueOf(2), result.get("changed"), "expected both to close, got " + result);
		assertEquals(Integer.valueOf(0), result.get("skipped"));
		assertFalse(depended.isOpen(), "the depended-on project should be closed");
		assertFalse(dependent.isOpen(), "the dependent should be closed");
	}

	@Test
	void stillRefusesWhenTheDependentStaysOpen() throws Exception {
		IProject depended = fixture.createProject(PROJECT + "-a");
		IProject dependent = fixture.createProject(PROJECT + "-b");
		reference(dependent, depended);

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("state", "closed", "dryRun", Boolean.FALSE, "projects", List.of(depended.getName())));

		assertEquals(Integer.valueOf(0), result.get("changed"));
		assertTrue(depended.isOpen(), "the refusal is the whole point when the dependent stays open");
		assertTrue(String.valueOf(only(result).get("skippedBecause")).contains("Pass force"),
				"got " + only(result));
	}

	@Test
	void aDryRunChangesNothing() throws Exception {
		IProject project = fixture.createProject(PROJECT);

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("state", "closed", "projects", List.of(PROJECT)));

		assertEquals(Boolean.TRUE, result.get("dryRun"));
		assertEquals(Integer.valueOf(1), result.get("changed"));
		assertEquals("closed", only(result).get("newState"));
		assertTrue(project.isOpen(), "a dry run must not actually close the project");
	}

	@Test
	void closesAndReopensAProject() throws Exception {
		IProject project = fixture.createProject(PROJECT);

		Map<String, Object> closed = TestFixture.callAndParse(TOOL,
				Map.of("state", "closed", "projects", List.of(PROJECT), "dryRun", Boolean.FALSE));
		assertEquals("open", only(closed).get("previousState"));
		assertEquals("closed", only(closed).get("newState"));
		assertFalse(project.isOpen());

		Map<String, Object> opened = TestFixture.callAndParse(TOOL,
				Map.of("state", "open", "projects", List.of(PROJECT), "dryRun", Boolean.FALSE));
		assertEquals("open", only(opened).get("newState"));
		assertTrue(project.isOpen());
	}

	@Test
	void skipsAProjectThatIsAlreadyInTheWantedState() throws Exception {
		fixture.createProject(PROJECT);

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("state", "open", "projects", List.of(PROJECT), "dryRun", Boolean.FALSE));

		assertEquals(Integer.valueOf(0), result.get("changed"));
		assertEquals(Integer.valueOf(1), result.get("skipped"));
		assertTrue(String.valueOf(only(result).get("skippedBecause")).contains("already"));
	}

	@Test
	void refusesToCloseAProjectThatOpenProjectsReference() throws Exception {
		IProject required = fixture.createProject(PROJECT);
		IProject dependent = fixture.createProject(PROJECT + "-dependent");
		reference(dependent, required);

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("state", "closed", "projects", List.of(PROJECT), "dryRun", Boolean.FALSE));

		assertEquals(Integer.valueOf(0), result.get("changed"));
		assertEquals(List.of(dependent.getName()), only(result).get("openDependents"));
		assertTrue(String.valueOf(only(result).get("skippedBecause")).contains("force"));
		assertTrue(required.isOpen(), "the project must still be open after a refusal");
	}

	@Test
	void closesADependedOnProjectWhenForced() throws Exception {
		IProject required = fixture.createProject(PROJECT);
		IProject dependent = fixture.createProject(PROJECT + "-dependent");
		reference(dependent, required);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("state", "closed", "projects",
				List.of(PROJECT), "dryRun", Boolean.FALSE, "force", Boolean.TRUE));

		assertEquals(Integer.valueOf(1), result.get("changed"));
		assertFalse(required.isOpen());
	}

	@Test
	void selectsByNameGlob() throws Exception {
		fixture.createProject(PROJECT);
		fixture.createProject("mcp-state-other");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("state", "closed", "namePattern", PROJECT));

		assertEquals(Integer.valueOf(1), result.get("total"));
		assertEquals(PROJECT, only(result).get("name"));
	}

	@Test
	void findsAPlatformMismatchFromTheManifestHeader() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		String foreignWs = "gtk".equals(Platform.getWS()) ? "win32" : "gtk";
		writeManifest(project, "Eclipse-PlatformFilter: (osgi.ws=%s)".formatted(foreignWs));

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("state", "closed", "platformMismatch", Boolean.TRUE, "namePattern", PROJECT));

		assertEquals(Integer.valueOf(1), result.get("total"));
		assertTrue(String.valueOf(only(result).get("platformReason")).contains("does not match"),
				String.valueOf(only(result).get("platformReason")));
	}

	@Test
	void leavesAProjectWhoseFilterMatchesThisPlatform() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		writeManifest(project, "Eclipse-PlatformFilter: (osgi.ws=%s)".formatted(Platform.getWS()));

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("state", "closed", "platformMismatch", Boolean.TRUE, "namePattern", PROJECT));

		assertEquals(Integer.valueOf(0), result.get("total"));
	}

	@Test
	void aPlatformMismatchStillRefusesWhenAMatchingDependentStaysOpen() throws Exception {
		IProject mismatched = fixture.createProject(PROJECT + "-a");
		IProject dependent = fixture.createProject(PROJECT + "-b");
		String foreignWs = "gtk".equals(Platform.getWS()) ? "win32" : "gtk";
		writeManifest(mismatched, "Eclipse-PlatformFilter: (osgi.ws=%s)".formatted(foreignWs));
		reference(dependent, mismatched);

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("state", "closed", "platformMismatch", Boolean.TRUE, "dryRun", Boolean.FALSE));

		assertTrue(mismatched.isOpen(), "the open dependent that does not mismatch has to block the close: " + result);
		assertTrue(dependent.isOpen());
	}

	@Test
	void refusesToActWithoutASelection() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("state", "closed"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("every project"), result.text());
	}

	@Test
	void rejectsAnUnknownState() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("state", "archived", "namePattern", PROJECT));

		assertTrue(result.isError());
	}

	@Test
	void rejectsAnUnknownProjectName() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("state", "closed", "projects", List.of("no-such-project")));

		assertTrue(result.isError());
		assertTrue(result.text().contains("no-such-project"), result.text());
	}

	private static void reference(IProject dependent, IProject required) throws Exception {
		IProjectDescription description = dependent.getDescription();
		description.setReferencedProjects(new IProject[] { required });
		dependent.setDescription(description, new NullProgressMonitor());
	}

	private static void writeManifest(IProject project, String header) throws Exception {
		IFolder folder = project.getFolder("META-INF");
		folder.create(false, true, new NullProgressMonitor());
		String content = """
				Manifest-Version: 1.0
				Bundle-ManifestVersion: 2
				Bundle-SymbolicName: %s
				%s
				""".formatted(project.getName(), header);
		project.getFile("META-INF/MANIFEST.MF").create(
				new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), false, new NullProgressMonitor());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> only(Map<String, Object> result) {
		List<Map<String, Object>> projects = (List<Map<String, Object>>) result.get("projects");
		assertEquals(1, projects.size(), "expected exactly one project, got " + projects);
		return projects.get(0);
	}
}
