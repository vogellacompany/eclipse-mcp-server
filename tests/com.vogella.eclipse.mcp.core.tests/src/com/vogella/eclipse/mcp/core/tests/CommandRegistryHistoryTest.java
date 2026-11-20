package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.basic.internal.CommandRegistry;
import com.vogella.eclipse.mcp.basic.internal.CommandRegistry.Execution;
import com.vogella.eclipse.mcp.core.FileLocations;

/**
 * A still-running command must not stop the history of finished commands from
 * being trimmed. Eviction that only ever looks at the eldest entry skipped every
 * insert while the oldest execution ran, so the map grew without bound for a
 * command that never ended.
 */
class CommandRegistryHistoryTest {

	/** Both batches exceed the number of finished commands the registry keeps. */
	private static final int FIRST_BATCH = 30;
	private static final int SECOND_BATCH = 10;
	private static final long AWAIT_MILLIS = 10_000L;

	private static final Path DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"));

	@AfterEach
	void clearHistory() {
		CommandRegistry.getInstance().clear();
	}

	@Test
	void finishedCommandsStayBoundedWhileTheOldestKeepsRunning() {
		assumeFalse(FileLocations.isWindows(), "the long command is a Unix sleep");
		CommandRegistry registry = CommandRegistry.getInstance();
		registry.clear();
		Execution running = registry.start(longRunning(), DIRECTORY, Map.of());
		try {
			assertTrue(running.isRunning(), "the long command should be running");

			insert(registry, FIRST_BATCH);
			int bounded = registry.knownIds().size();

			insert(registry, SECOND_BATCH);
			int afterMore = registry.knownIds().size();

			assertTrue(bounded < FIRST_BATCH,
					"history was not trimmed: " + bounded + " entries after " + FIRST_BATCH + " short commands");
			assertEquals(bounded, afterMore,
					"history kept growing once it should have been bounded: " + bounded + " -> " + afterMore);
			assertTrue(running.isRunning(), "the long command ended, so this proves nothing");
			assertTrue(registry.knownIds().contains(running.id()), "the running command was evicted");
		} finally {
			running.cancel();
			running.await(AWAIT_MILLIS);
		}
	}

	/** Each short command finishes before the next insert, so eviction has finished entries to act on. */
	private static void insert(CommandRegistry registry, int count) {
		for (int i = 0; i < count; i++) {
			Execution shortRun = registry.start(shortCommand(), DIRECTORY, Map.of());
			assertTrue(shortRun.await(AWAIT_MILLIS), "a short command did not finish");
		}
	}

	private static List<String> longRunning() {
		return List.of("/bin/sh", "-c", "sleep 30");
	}

	private static List<String> shortCommand() {
		return List.of("/bin/sh", "-c", "true");
	}
}
