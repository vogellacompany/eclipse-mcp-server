package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.git.internal.RepositoryLookup;

/**
 * The shared repository lookup, against a real repository on disk and a
 * directory that is none.
 */
class RepositoryLookupTest {

	private Path directory;

	@BeforeEach
	void createRepository() throws Exception {
		directory = Files.createTempDirectory("mcp-git-lookup");
		try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
			Files.writeString(directory.resolve("tracked.txt"), "first\n");
			git.add().addFilepattern("tracked.txt").call();
			git.commit().setMessage("first").setAuthor("Test", "test@example.com")
					.setCommitter("Test", "test@example.com").setSign(Boolean.FALSE).call();
		}
	}

	@AfterEach
	void deleteRepository() {
		// EGit's repository cache keeps the handle the lookup opened and can still write to .git
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
					// reopened meanwhile; the next attempt or the operating system gets it
				}
			}
		} catch (IOException e) {
			// the tree went away underneath us, which is the outcome we wanted
		}
	}

	@Test
	void aRealRepositoryIsReturnedWithoutAnError() throws Exception {
		RepositoryLookup.Result found = RepositoryLookup.find(null, directory.toString());

		assertFalse(found.failed(), "got " + found.error());
		assertNull(found.error());
		assertNotNull(found.repository());
		assertEquals(directory.toRealPath(), found.repository().getWorkTree().toPath().toRealPath());
	}

	@Test
	void aDirectoryThatIsNoRepositoryAnswersWithTheDefaultMessage() throws Exception {
		Path empty = Files.createTempDirectory("mcp-git-lookup-none");
		try {
			RepositoryLookup.Result found = RepositoryLookup.find(null, empty.toString());

			assertTrue(found.failed());
			assertTrue(found.error().isError());
			assertEquals(RepositoryLookup.NO_REPOSITORY, found.error().text());
		} finally {
			Files.deleteIfExists(empty);
		}
	}

	@Test
	void noProjectAndNoDirectoryIsAlsoAnError() throws Exception {
		RepositoryLookup.Result found = RepositoryLookup.find(null, null);

		assertTrue(found.failed());
		assertEquals(RepositoryLookup.NO_REPOSITORY, found.error().text());
	}
}
