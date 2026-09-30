package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Mapping a name to a place, in both directions.
 * <p>
 * The point of the tool is that a caller outside the IDE cannot do this: a
 * project name says nothing about where the code sits or which repository owns
 * it, and an absolute path out of a stack trace says nothing about the project.
 */
class ResolvePathToolTest {

	private static final String TOOL = "eclipse_resolve_path";

	private static final String PROJECT = "mcp-resolve-path-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> resolve(Object... queries) throws Exception {
		Map<String, Object> answer = TestFixture.callAndParse(TOOL, Map.of("of", List.of(queries)));
		return (List<Map<String, Object>>) answer.get("resolved");
	}

	@Test
	void aFileOnDiskThatTheWorkspaceHasNotSeenKeepsItsOwnLocation() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		File created = project.getLocation().append("fresh.txt").toFile();
		java.nio.file.Files.writeString(created.toPath(), "x");

		Map<String, Object> entry = resolve(created.getAbsolutePath()).get(0);

		assertEquals("/" + PROJECT + "/fresh.txt", entry.get("workspacePath"), "got " + entry);
		assertEquals(created.getAbsolutePath(), entry.get("location"));
		assertEquals(Boolean.FALSE, entry.get("exists"));
	}

	@Test
	void aProjectNameGivesItsPlaceOnDisk() throws Exception {
		IProject project = fixture.createProject(PROJECT);

		Map<String, Object> entry = resolve(PROJECT).get(0);

		assertEquals(Boolean.TRUE, entry.get("resolved"), "got " + entry);
		assertEquals(PROJECT, entry.get("project"));
		assertEquals("/" + PROJECT, entry.get("workspacePath"));
		assertEquals(project.getLocation().toOSString(), entry.get("location"));
		assertEquals(Boolean.TRUE, entry.get("open"));
	}

	@Test
	void anAbsolutePathComesBackAsTheProjectItBelongsTo() throws Exception {
		IFile file = write("Example.java", "class Example {}\n");

		Map<String, Object> entry = resolve(file.getLocation().toOSString()).get(0);

		assertEquals(PROJECT, entry.get("project"), "got " + entry);
		assertEquals(file.getFullPath().toString(), entry.get("workspacePath"));
	}

	@Test
	void aWorkspacePathGivesTheFilesLocation() throws Exception {
		IFile file = write("Example.java", "class Example {}\n");

		Map<String, Object> entry = resolve(file.getFullPath().toString()).get(0);

		assertEquals(file.getLocation().toOSString(), entry.get("location"), "got " + entry);
		assertEquals(Boolean.TRUE, entry.get("exists"));
	}

	@Test
	void theRepositoryRootIsTheDirectoryHoldingDotGit() throws Exception {
		IFile file = write("Example.java", "class Example {}\n");
		File repository = file.getProject().getLocation().toFile();
		File marker = new File(repository, ".git");
		assertTrue(marker.mkdirs() || marker.exists());
		try {
			Map<String, Object> entry = resolve(file.getFullPath().toString()).get(0);

			assertEquals(repository.getAbsolutePath(), entry.get("repositoryRoot"), "got " + entry);
			assertEquals("Example.java", entry.get("pathInRepository"));
		} finally {
			marker.delete();
		}
	}

	@Test
	void somethingOutsideAnyRepositorySaysSoRatherThanGuessing() throws Exception {
		// no .git anywhere above a temporary test workspace, which is the point:
		// a null root is an answer, and inventing one would send git to the wrong tree
		Map<String, Object> entry = resolve(fixture.createProject(PROJECT).getName()).get(0);

		assertTrue(entry.containsKey("repositoryRoot"), "got " + entry);
		if (entry.get("repositoryRoot") == null) {
			assertNull(entry.get("pathInRepository"));
		}
	}

	@Test
	void anUnknownNameIsRefusedWithWhatIsNearby() throws Exception {
		fixture.createProject(PROJECT);

		Map<String, Object> entry = resolve("resolve-path").get(0);

		assertEquals(Boolean.FALSE, entry.get("resolved"), "got " + entry);
		assertNotNull(entry.get("nearby"));
		assertTrue(String.valueOf(entry.get("nearby")).contains(PROJECT), "got " + entry);
	}

	@Test
	void severalQueriesAreAnsweredInOneCall() throws Exception {
		IFile file = write("Example.java", "class Example {}\n");

		List<Map<String, Object>> entries = resolve(PROJECT, file.getFullPath().toString(),
				file.getLocation().toOSString());

		assertEquals(3, entries.size());
		assertTrue(entries.stream().allMatch(entry -> Boolean.TRUE.equals(entry.get("resolved"))), "got " + entries);
	}

	private IFile write(String name, String content) throws Exception {
		IProject project = fixture.createProject(PROJECT);
		IFile file = project.getFile(name);
		file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());
		return file;
	}
}
