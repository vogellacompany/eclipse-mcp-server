package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Comparator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Reverting files to their HEAD content.
 * <p>
 * Run against a throw-away repository outside the workspace, which is the half
 * of the tool that needs no IDE: the workspace path writes through the workspace
 * so the discarded content lands in local history, and that cannot be exercised
 * headlessly. What is checked here is the part a caller depends on before
 * anything is destroyed, since this tool destroys uncommitted work: that the dry
 * run changes nothing, that an untracked file is refused rather than deleted,
 * and that a file already matching HEAD is left alone.
 */
class RevertFilesToolTest {

	private static final String NAME = "eclipse_revert_files";

	private Path directory;

	private Path tracked;

	@BeforeEach
	void createRepository() throws Exception {
		directory = Files.createTempDirectory("mcp-revert");
		tracked = directory.resolve("tracked.txt");
		try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
			Files.writeString(tracked, "committed\n");
			git.add().addFilepattern("tracked.txt").call();
			git.commit().setMessage("first").setAuthor("Test", "test@example.com")
					.setCommitter("Test", "test@example.com").setSign(Boolean.FALSE).call();
		}
	}

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
					// jgit reopens what it cached; the next attempt or the OS gets it
				}
			}
		} catch (IOException e) {
			// already gone, which is the outcome we wanted
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void theDryRunReportsTheLossAndWritesNothing() throws Exception {
		Files.writeString(tracked, "local edit\n");

		Map<String, Object> answer = TestFixture.callAndParse(NAME,
				Map.of("paths", List.of(tracked.toString())));

		assertEquals(Boolean.TRUE, answer.get("dryRun"), "got " + answer);
		assertEquals(Integer.valueOf(1), answer.get("wouldRevert"), "got " + answer);
		Map<String, Object> file = ((List<Map<String, Object>>) answer.get("files")).get(0);
		assertEquals("wouldRevert", file.get("state"), "got " + file);
		assertEquals("local edit\n", Files.readString(tracked), "a dry run must not write");
	}

	@Test
	@SuppressWarnings("unchecked")
	void revertingPutsTheHeadContentBack() throws Exception {
		Files.writeString(tracked, "local edit\n");

		Map<String, Object> answer = TestFixture.callAndParse(NAME,
				Map.of("paths", List.of(tracked.toString()), "dryRun", Boolean.FALSE));

		assertEquals(Integer.valueOf(1), answer.get("reverted"), "got " + answer);
		Map<String, Object> file = ((List<Map<String, Object>>) answer.get("files")).get(0);
		assertEquals("reverted", file.get("state"), "got " + file);
		assertEquals("committed\n", Files.readString(tracked), "the file has to hold what HEAD holds");
	}

	@Test
	@SuppressWarnings("unchecked")
	void anUntrackedFileIsRefusedRatherThanDeleted() throws Exception {
		Path untracked = directory.resolve("untracked.txt");
		Files.writeString(untracked, "never committed\n");

		Map<String, Object> answer = TestFixture.callAndParse(NAME,
				Map.of("paths", List.of(untracked.toString()), "dryRun", Boolean.FALSE));

		Map<String, Object> file = ((List<Map<String, Object>>) answer.get("files")).get(0);
		assertEquals("refused", file.get("state"), "got " + file);
		assertTrue(String.valueOf(file.get("reason")).contains("NOT deleted"), "got " + file);
		assertTrue(Files.exists(untracked), "the file must still be there");
		assertEquals("never committed\n", Files.readString(untracked), "and must be untouched");
	}

	@Test
	@SuppressWarnings("unchecked")
	void aFileAlreadyMatchingHeadIsLeftAlone() throws Exception {
		Map<String, Object> answer = TestFixture.callAndParse(NAME,
				Map.of("paths", List.of(tracked.toString()), "dryRun", Boolean.FALSE));

		assertEquals(Integer.valueOf(0), answer.get("reverted"), "nothing to do, got " + answer);
		Map<String, Object> file = ((List<Map<String, Object>>) answer.get("files")).get(0);
		assertEquals("unchanged", file.get("state"), "got " + file);
	}

	@Test
	void pathsIsRequired() throws Exception {
		McpToolResult result = TestFixture.call(NAME, Map.of());

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("paths"), "got " + result.text());
	}

	@Test
	void theDescriptionAnnouncesThatItDestroysWork() {
		String description = TestFixture.tool(NAME).getDescription();

		// the only place a model sees this before calling it
		assertTrue(description.contains("DESTROYS UNCOMMITTED WORK"), "got " + description);
		assertTrue(description.contains("DRY RUN"), "got " + description);
		assertTrue(description.contains("REFUSED BY NAME rather than deleted"), "got " + description);
	}
}
