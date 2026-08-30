package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.server.McpPreferences;
import com.vogella.eclipse.mcp.ui.internal.RestartTool;

/**
 * The parts of the restart that can be exercised without restarting anything.
 * <p>
 * The restart itself must never run here: it would take the test IDE with it.
 * What is left is the argument handling, which is where a mistake would be
 * silent, because nothing on this side can observe what the launcher does with
 * the arguments afterwards.
 */
class RestartToolTest {

	@Test
	void refusesWithoutAWorkbench() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_restart", Map.of("splash", Boolean.FALSE));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().toLowerCase().contains("no running workbench"), result.text());
	}

	@Test
	void aLauncherThatWillNotRelaunchIsRefusedBeforeTheCloseRatherThanInADialog() {
		// Workbench.restart answers this case with a modal informNoRestart dialog,
		// which on an IDE nobody is looking at hangs the close instead of failing
		String commands = System.getProperty("eclipse.commands");
		String launcher = System.getProperty("eclipse.launcher");
		try {
			System.setProperty("eclipse.commands", "-data\n/tmp/ws\n--launcher.noRestart\n");
			System.setProperty("eclipse.launcher", "/opt/eclipse/eclipse");
			String reason = RestartTool.cannotRestartReason();
			assertNotNull(reason, "a noRestart launcher has to be refused");
			assertTrue(reason.contains("noRestart"), reason);

			System.setProperty("eclipse.commands", "-data\n/tmp/ws\n");
			assertNull(RestartTool.cannotRestartReason(), "an ordinary launcher can relaunch");

			System.clearProperty("eclipse.launcher");
			String noLauncher = RestartTool.cannotRestartReason();
			assertNotNull(noLauncher, "a JVM outside the launcher cannot relaunch itself");
			assertTrue(noLauncher.contains("launcher"), noLauncher);
		} finally {
			restore("eclipse.commands", commands);
			restore("eclipse.launcher", launcher);
		}
	}

	@Test
	void aRelaunchedProcessIsRecognisableFromTheLauncherArguments() {
		String commands = System.getProperty("eclipse.commands");
		try {
			System.setProperty("eclipse.commands", "-data\n/tmp/ws\n");
			assertFalse(RestartTool.cameFromARelaunch(), "a first start carries no relaunch marker");

			System.setProperty("eclipse.commands", "-data\n/tmp/ws\n--launcher.oldUserArgsStart\n");
			assertTrue(RestartTool.cameFromARelaunch(), "the launcher records a relaunch with this argument");
		} finally {
			restore("eclipse.commands", commands);
		}
	}

	private static void restore(String property, String value) {
		if (value == null) {
			System.clearProperty(property);
		} else {
			System.setProperty(property, value);
		}
	}

	@Test
	void anArgumentIsMatchedAsAWholeLine() {
		assertTrue(RestartTool.contains("-nosplash\n", "-nosplash"));
		assertTrue(RestartTool.contains("-data\nfile:/tmp/ws\n-nosplash\n", "-nosplash"));
		assertTrue(RestartTool.contains("  -nosplash  \n", "-nosplash"));
	}

	@Test
	void aLongerArgumentThatStartsTheSameIsNotAMatch() {
		// a substring match would find this one and skip adding the real argument
		assertFalse(RestartTool.contains("-nosplashscreen\n", "-nosplash"));
		assertFalse(RestartTool.contains("-showsplash\nsomething-nosplash\n", "-nosplash"));
		assertFalse(RestartTool.contains("", "-nosplash"));
	}

	@Test
	void theArgumentIsAddedOnceAndKeepsWhatWasThere() {
		String previous = System.getProperty(RestartTool.EXIT_DATA_PROPERTY);
		try {
			System.setProperty(RestartTool.EXIT_DATA_PROPERTY, "-data\nfile:/tmp/ws\n");

			assertTrue(RestartTool.appendNoSplash());
			String after = System.getProperty(RestartTool.EXIT_DATA_PROPERTY);
			assertTrue(after.startsWith("-data\nfile:/tmp/ws\n"), "existing arguments must survive, got " + after);
			assertTrue(RestartTool.contains(after, "-nosplash"), after);

			// a second call must not add it twice: the launcher gets one argument list
			assertTrue(RestartTool.appendNoSplash());
			assertEquals(after, System.getProperty(RestartTool.EXIT_DATA_PROPERTY));
		} finally {
			if (previous == null) {
				System.clearProperty(RestartTool.EXIT_DATA_PROPERTY);
			} else {
				System.setProperty(RestartTool.EXIT_DATA_PROPERTY, previous);
			}
		}
	}

	@Test
	void aFileUriIsAcceptedBecauseThatIsWhatTheToolUsedToReport() {
		// a caller handing back the workspace it was given would otherwise be refused.
		// Built from a real path rather than written out, because "file:/tmp/ws" is
		// not a path on Windows and this has to hold on every window system
		Path workspace = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("ws");

		assertEquals(workspace, RestartTool.pathOf(workspace.toUri().toString()));
		assertEquals(workspace, RestartTool.pathOf(workspace.toString()));
	}

	@Test
	void aRelativeWorkspaceIsRefused() {
		// the launcher would resolve it against a working directory nobody here knows
		McpToolResult refusal = RestartTool.checkWorkspace("some/workspace");

		assertNotNull(refusal);
		assertTrue(refusal.text().contains("absolute path"), refusal.text());
	}

	@Test
	void aWorkspaceThatIsAFileIsRefused(@TempDir Path dir) throws Exception {
		Path file = Files.createFile(dir.resolve("not-a-directory"));

		McpToolResult refusal = RestartTool.checkWorkspace(file.toString());

		assertNotNull(refusal);
		assertTrue(refusal.text().contains("not a directory"), refusal.text());
	}

	@Test
	void aWorkspaceThatIsNotThereIsCreated(@TempDir Path dir) {
		// a path that does not exist opens the workspace chooser and waits for a person
		assumeFalse(Platform.inDevelopmentMode(), "development mode refuses any switch");
		Path workspace = dir.resolve("fresh");

		assertNull(RestartTool.checkWorkspace(workspace.toString()));
		assertTrue(Files.isDirectory(workspace));
	}

	@Test
	void developmentModeRefusesTheSwitchInsteadOfSilentlyNotDoingIt(@TempDir Path dir) {
		// the workbench keeps the command line it was launched with, so the relaunch
		// arguments are dropped and the IDE would come back where it already was
		assumeTrue(Platform.inDevelopmentMode(), "only a development mode IDE refuses this");

		McpToolResult refusal = RestartTool.checkWorkspace(dir.resolve("elsewhere").toString());

		assertNotNull(refusal);
		assertTrue(refusal.text().contains("-data"), refusal.text());
	}

	@Test
	void theServerIsSwitchedOnInAWorkspaceThatNeverHadIt(@TempDir Path workspace) throws Exception {
		String answer = RestartTool.carryTheServerOver(workspace).toString();

		assertTrue(answer.contains("\"carriedOver\": true"), answer);
		Path settings = RestartTool.settingsFile(workspace);
		assertTrue(Files.isRegularFile(settings), "no preferences written to " + settings);
		String written = Files.readString(settings);
		assertTrue(written.contains(McpPreferences.KEY_ENABLED + "="), written);
	}

	@Test
	void settingsAWorkspaceAlreadyHasAreLeftAlone(@TempDir Path workspace) throws Exception {
		Path settings = RestartTool.settingsFile(workspace);
		Files.createDirectories(settings.getParent());
		Files.writeString(settings, "eclipse.preferences.version=1\nenabled=false\n");

		String answer = RestartTool.carryTheServerOver(workspace).toString();

		assertTrue(answer.contains("\"carriedOver\": false"), answer);
		assertEquals("eclipse.preferences.version=1\nenabled=false\n", Files.readString(settings));
	}

	@Test
	void aWorkspaceThatCannotTakeTheSettingsSaysSoInsteadOfClaimingSuccess(@TempDir Path workspace)
			throws Exception {
		// .metadata as a file makes creating the settings directory below it fail
		Files.createFile(workspace.resolve(".metadata"));

		String answer = RestartTool.carryTheServerOver(workspace).toString();

		assertTrue(answer.contains("\"carriedOver\": false"), answer);
		assertTrue(answer.contains("WITHOUT this server"), answer);
	}
}
