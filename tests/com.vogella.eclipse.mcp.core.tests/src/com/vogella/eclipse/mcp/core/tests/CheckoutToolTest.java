package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Switching branches through EGit, against a repository on disk.
 */
class CheckoutToolTest {

	private static final String NAME = "eclipse_checkout";

	private Path directory;

	@BeforeEach
	void createRepository() throws Exception {
		directory = Files.createTempDirectory("mcp-git-checkout");
		try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
			Files.writeString(directory.resolve("tracked.txt"), "first\n");
			git.add().addFilepattern("tracked.txt").call();
			git.commit().setMessage("first").setAuthor("Test", "test@example.com")
					.setCommitter("Test", "test@example.com").call();
			git.branchCreate().setName("other").call();
		}
	}

	@AfterEach
	void deleteRepository() throws Exception {
		if (directory == null || !Files.exists(directory)) {
			return;
		}
		try (var walk = Files.walk(directory)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				path.toFile().setWritable(true);
				Files.deleteIfExists(path);
			}
		}
	}

	@Test
	void theDryRunReportsTheStateAndSwitchesNothing() throws Exception {
		Map<String, Object> answer = TestFixture.callAndParse(NAME,
				Map.of("directory", directory.toString(), "target", "other"));

		assertEquals(Boolean.TRUE, answer.get("dryRun"), "got " + answer);
		assertEquals(Boolean.FALSE, answer.get("switched"), "got " + answer);
		assertEquals("main", answer.get("fromBranch"), "got " + answer);
		assertEquals(Boolean.TRUE, answer.get("wasClean"), "got " + answer);
		try (Git git = Git.open(directory.toFile())) {
			assertEquals("main", git.getRepository().getBranch());
		}
	}

	@Test
	void aRealSwitchMovesTheBranch() throws Exception {
		Map<String, Object> answer = TestFixture.callAndParse(NAME,
				Map.of("directory", directory.toString(), "target", "other", "dryRun", Boolean.FALSE));

		assertEquals(Boolean.TRUE, answer.get("switched"), "got " + answer);
		assertEquals("other", answer.get("toBranch"), "got " + answer);
	}

	@Test
	void aTargetThatResolvesToNothingIsRefused() throws Exception {
		McpToolResult result = TestFixture.call(NAME,
				Map.of("directory", directory.toString(), "target", "no-such-branch"));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("no-such-branch"), result.text());
	}
}
