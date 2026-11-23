package com.vogella.eclipse.mcp.server.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.server.internal.PrivateFiles;
import com.vogella.eclipse.mcp.server.internal.TokenStore;

/**
 * The token file, redirected into a temporary directory so the running server's own token is left alone.
 */
class TokenStoreTest {

	private static final String PROPERTY = "com.vogella.eclipse.mcp.tokenDirectory";

	@TempDir
	Path directory;

	private String previous;

	@BeforeEach
	void redirect() {
		previous = System.getProperty(PROPERTY);
		System.setProperty(PROPERTY, directory.toString());
	}

	@AfterEach
	void restore() {
		if (previous == null) {
			System.clearProperty(PROPERTY);
		} else {
			System.setProperty(PROPERTY, previous);
		}
	}

	@Test
	void theFirstCallGeneratesAndStoresAToken() throws Exception {
		String token = TokenStore.get();

		assertFalse(token.isBlank());
		assertEquals(token, Files.readString(directory.resolve("token")).strip());
	}

	@Test
	void theTokenIsStableAcrossCalls() {
		assertEquals(TokenStore.get(), TokenStore.get());
	}

	@Test
	void anExistingTokenIsReadBack() throws Exception {
		Files.writeString(directory.resolve("token"), "  from-an-earlier-run \n");

		assertEquals("from-an-earlier-run", TokenStore.get());
	}

	@Test
	void anEmptyFileIsReplacedRatherThanServed() throws Exception {
		Files.writeString(directory.resolve("token"), "\n");

		String token = TokenStore.get();

		assertFalse(token.isBlank());
		assertEquals(token, Files.readString(directory.resolve("token")).strip());
	}

	@Test
	void regeneratingReplacesTheStoredToken() throws Exception {
		String before = TokenStore.get();

		String after = TokenStore.regenerate();

		assertNotEquals(before, after);
		assertEquals(after, TokenStore.get());
		assertEquals(after, Files.readString(directory.resolve("token")).strip());
	}

	@Test
	void theFileIsReadableByItsOwnerOnlyAndNoTemporaryFileIsLeft() throws Exception {
		TokenStore.regenerate();
		Path file = directory.resolve("token");
		try {
			assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
		} catch (UnsupportedOperationException e) {
			assumeTrue(false, "no POSIX permissions here");
		}
		try (var listing = Files.list(directory)) {
			assertTrue(listing.allMatch(path -> path.equals(file)), "only the token file should remain");
		}
	}

	@Test
	void createKeepsTheFirstTokenAndLeavesNoTemporaryBehind() throws Exception {
		// two IDEs starting at once both find no token, and the second must adopt the first one's
		Path file = directory.resolve("token");

		assertTrue(PrivateFiles.create(file, "first"));
		assertFalse(PrivateFiles.create(file, "second"));

		assertEquals("first", Files.readString(file).strip());
		try (var listing = Files.list(directory)) {
			assertTrue(listing.allMatch(path -> path.equals(file)), "no temporary file should be left behind");
		}
	}

	@Test
	void concurrentWritesAllSucceedAndLeaveNoTemporaryBehind() throws Exception {
		Path file = directory.resolve("token");
		int writers = 8;
		int rounds = 20;
		List<String> contents = IntStream.range(0, writers).mapToObj(i -> "token-" + i).toList();
		CyclicBarrier start = new CyclicBarrier(writers);
		ExecutorService pool = Executors.newFixedThreadPool(writers);
		List<Future<Void>> results = new ArrayList<>();
		try {
			for (String content : contents) {
				results.add(pool.submit(() -> {
					start.await();
					for (int round = 0; round < rounds; round++) {
						PrivateFiles.write(file, content);
					}
					return null;
				}));
			}
			for (Future<Void> result : results) {
				result.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}
		assertTrue(contents.contains(Files.readString(file).strip()), "the file holds one of the written tokens");
		if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
			assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
		}
		try (var listing = Files.list(directory)) {
			assertTrue(listing.allMatch(path -> path.equals(file)), "no temporary file should be left behind");
		}
	}
}
