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

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * The repository status, read through a real repository on disk.
 * <p>
 * These are the tools a client correlates a problem set or a build result
 * against, so a wrong branch or a working tree reported clean when it is not is
 * the kind of mistake that gets one branch's failures attributed to another.
 * The repository is built here with JGit rather than taken from the workspace,
 * because the 'directory' argument is the path that needs no EGit project
 * mapping and therefore no workbench.
 */
class GetGitStatusToolTest {

	private static final String NAME = "eclipse_get_git_status";

	private Path directory;

	@BeforeEach
	void createRepository() throws Exception {
		directory = Files.createTempDirectory("mcp-git-status");
		try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
			Files.writeString(directory.resolve("tracked.txt"), "first\n");
			git.add().addFilepattern("tracked.txt").call();
			git.commit().setMessage("first").setAuthor("Test", "test@example.com")
					.setCommitter("Test", "test@example.com").call();
		}
	}

	@AfterEach
	void deleteRepository() throws Exception {
		if (directory == null || !Files.exists(directory)) {
			return;
		}
		try (var walk = Files.walk(directory)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				// jgit writes objects read-only, which Windows refuses to delete
				path.toFile().setWritable(true);
				Files.deleteIfExists(path);
			}
		}
	}

	@Test
	void reportsTheBranchTheCommitAndACleanTree() throws Exception {
		Map<String, Object> status = statusOf(Map.of("directory", directory.toString()));

		assertEquals("main", status.get("branch"), "got " + status);
		assertNotNull(status.get("head"), "a repository with a commit has a HEAD, got " + status);
		assertEquals(Boolean.TRUE, status.get("clean"), "nothing was changed after the commit, got " + status);
		assertEquals("SAFE", status.get("state"));
	}

	@Test
	void anUncommittedChangeMakesTheTreeDirty() throws Exception {
		Files.writeString(directory.resolve("tracked.txt"), "second\n");
		Files.writeString(directory.resolve("new.txt"), "new\n");

		Map<String, Object> status = statusOf(Map.of("directory", directory.toString()));

		assertEquals(Boolean.FALSE, status.get("clean"), "got " + status);
		assertEquals(List.of("tracked.txt"), pathsOf(status, "modified"), "got " + status);
		assertEquals(List.of("new.txt"), pathsOf(status, "untracked"), "got " + status);
	}

	@Test
	void stagedAndMissingChangesAreListedNotOnlyCounted() throws Exception {
		Files.writeString(directory.resolve("added.txt"), "new\n");
		try (Git git = Git.open(directory.toFile())) {
			git.add().addFilepattern("added.txt").call();
		}
		Files.delete(directory.resolve("tracked.txt"));

		Map<String, Object> status = statusOf(Map.of("directory", directory.toString()));

		assertEquals(Boolean.FALSE, status.get("clean"), "got " + status);
		assertEquals(List.of("added.txt"), pathsOf(status, "staged"), "got " + status);
		assertEquals(List.of("tracked.txt"), pathsOf(status, "missing"), "got " + status);
	}

	@Test
	void maxFilesCapsTheListingButNotTheCount() throws Exception {
		// the description promises "the counts are always complete", and a caller that
		// reads only the listed paths would otherwise believe a truncated answer is all
		for (int i = 0; i < 5; i++) {
			Files.writeString(directory.resolve("extra%d.txt".formatted(Integer.valueOf(i))), "x\n");
		}

		Map<String, Object> status = statusOf(Map.of("directory", directory.toString(), "maxFiles", Integer.valueOf(2)));

		assertEquals(2, pathsOf(status, "untracked").size(), "got " + status);
		assertEquals(5, ((Number) categoryOf(status, "untracked").get("total")).intValue(),
				"the total has to survive the cap, got " + status);
	}

	@Test
	void aDirectoryThatIsNoRepositoryIsRefusedWithBothWaysIn() throws Exception {
		Path empty = Files.createTempDirectory("mcp-git-none");
		try {
			McpToolResult result = TestFixture.call(NAME, Map.of("directory", empty.toString()));

			assertTrue(result.isError(), "got " + result.text());
			assertTrue(result.text().contains("project") && result.text().contains("directory"),
					"the refusal has to name both arguments, got " + result.text());
		} finally {
			Files.deleteIfExists(empty);
		}
	}

	private Map<String, Object> statusOf(Map<String, Object> arguments) throws Exception {
		return TestFixture.callAndParse(NAME, arguments);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> categoryOf(Map<String, Object> status, String category) {
		return (Map<String, Object>) status.get(category);
	}

	@SuppressWarnings("unchecked")
	private static List<String> pathsOf(Map<String, Object> status, String category) throws IOException {
		return (List<String>) categoryOf(status, category).get("paths");
	}
}
