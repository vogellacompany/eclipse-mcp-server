package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Registering a repository that is already on disk with EGit.
 * <p>
 * The registration itself is a change to the IDE's own configuration and is
 * left to the dry run here, because a test that really registered one would
 * write into the preferences of whatever Eclipse ran the suite. What is checked
 * is the part a caller depends on: that a path which is no repository is
 * refused before anything is written, that both the working tree and the .git
 * directory are accepted as the same repository, and that the dry run reports
 * what it would do without doing it.
 */
class AddGitRepositoryToolTest {

	private static final String NAME = "eclipse_add_git_repository";

	private Path directory;

	@BeforeEach
	void createRepository() throws Exception {
		directory = Files.createTempDirectory("mcp-git-add");
		try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
			Files.writeString(directory.resolve("tracked.txt"), "first\n");
			git.add().addFilepattern("tracked.txt").call();
			git.commit().setMessage("first").setAuthor("Test", "test@example.com")
					.setCommitter("Test", "test@example.com").call();
		}
	}

	/**
	 * Removes the temporary repository, and never fails the test for not managing
	 * it.
	 * <p>
	 * jgit keeps a repository it has opened in its own cache and writes to the
	 * {@code .git} directory afterwards, so a file can appear between the walk and
	 * the delete and the directory is then not empty when its turn comes. That
	 * surfaced only on CI, as {@code DirectoryNotEmptyException} out of
	 * {@code @AfterEach}, which JUnit reports as an error on a test whose body
	 * passed. A leftover directory under the system temp folder is not worth a red
	 * build, so this retries once and then gives up quietly.
	 */
	@AfterEach
	void deleteRepository() {
		for (int attempt = 0; attempt < 2 && directory != null && Files.exists(directory); attempt++) {
			deleteQuietly(directory);
		}
	}

	private static void deleteQuietly(Path root) {
		try (var walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					// something reopened it; the next attempt or the operating system
					// gets it, and either way the test itself has already answered
				}
			}
		} catch (IOException e) {
			// the tree went away underneath us, which is the outcome we wanted
		}
	}

	@Test
	void theDryRunResolvesTheGitDirWithoutRegisteringAnything() throws Exception {
		Map<String, Object> answer = TestFixture.callAndParse(NAME, Map.of("directory", directory.toString()));

		assertEquals(Boolean.TRUE, answer.get("dryRun"), "got " + answer);
		assertEquals(Boolean.FALSE, answer.get("registered"), "a dry run must not register, got " + answer);
		assertNotNull(answer.get("gitDir"));
		assertTrue(String.valueOf(answer.get("gitDir")).endsWith(".git"),
				"the working tree has to resolve to its .git directory, got " + answer.get("gitDir"));
	}

	@Test
	void theGitDirectoryItselfResolvesToTheSameRepository() throws Exception {
		Map<String, Object> fromWorkTree = TestFixture.callAndParse(NAME, Map.of("directory", directory.toString()));
		Map<String, Object> fromGitDir = TestFixture.callAndParse(NAME,
				Map.of("directory", directory.resolve(".git").toString()));

		assertEquals(fromWorkTree.get("gitDir"), fromGitDir.get("gitDir"), "got " + fromGitDir);
	}

	@Test
	void aDirectoryThatIsNoRepositoryIsRefusedBeforeAnythingIsWritten() throws Exception {
		Path empty = Files.createTempDirectory("mcp-git-add-none");
		try {
			McpToolResult result = TestFixture.call(NAME, Map.of("directory", empty.toString()));

			assertTrue(result.isError(), "got " + result.text());
			assertTrue(result.text().contains("not a git repository"), "got " + result.text());
		} finally {
			Files.deleteIfExists(empty);
		}
	}

	@Test
	void addAndRemoveBothNeedADirectory() throws Exception {
		for (String action : new String[] { "add", "remove" }) {
			McpToolResult result = TestFixture.call(NAME, Map.of("action", action));

			assertTrue(result.isError(), action + " without a directory, got " + result.text());
			assertTrue(result.text().contains("directory"), "got " + result.text());
		}
	}

	@Test
	void anUnknownActionIsRefusedByName() throws Exception {
		McpToolResult result = TestFixture.call(NAME, Map.of("action", "clone"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("clone"), "the refusal has to quote what was passed, got " + result.text());
	}

	@Test
	void listReportsTheConfiguredRepositoriesWithoutArguments() throws Exception {
		Map<String, Object> answer = TestFixture.callAndParse(NAME, Map.of("action", "list"));

		assertTrue(answer.get("registered") instanceof List, "got " + answer);
	}

	@Test
	void aProjectAtTheRepositoryRootIsOneToConnect() throws Exception {
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("mcp-git-root-project");
		try {
			IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription(project.getName());
			description.setLocation(new org.eclipse.core.runtime.Path(directory.toString()));
			project.create(description, null);
			project.open(null);

			Map<String, Object> answer = TestFixture.callAndParse(NAME,
					Map.of("directory", directory.toString(), "connectProjects", Boolean.TRUE));

			assertEquals(List.of(project.getName()), answer.get("projectsToConnect"), "got " + answer);
		} finally {
			project.delete(false, true, null);
		}
	}

	@Test
	void connectProjectsNamesWhatItWouldConnect() throws Exception {
		// no workspace project lives in this throw-away repository, so the list is
		// empty; what matters is that the field is reported rather than omitted, since
		// that is how a caller sees the difference between nothing to do and not asked
		Map<String, Object> answer = TestFixture.callAndParse(NAME,
				Map.of("directory", directory.toString(), "connectProjects", Boolean.TRUE));

		assertEquals(List.of(), answer.get("projectsToConnect"), "got " + answer);
	}
}
