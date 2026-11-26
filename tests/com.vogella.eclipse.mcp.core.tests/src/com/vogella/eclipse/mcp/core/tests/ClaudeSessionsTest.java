package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.ai.internal.ClaudeSession;
import com.vogella.eclipse.mcp.ai.internal.Commands;
import com.vogella.eclipse.mcp.ai.internal.SessionDirectory;
import com.vogella.eclipse.mcp.ai.internal.SessionRow;
import com.vogella.eclipse.mcp.ai.internal.TerminalSession;
import com.vogella.eclipse.mcp.ai.internal.TmuxPane;

class ClaudeSessionsTest {

	/** A sessions file as Claude Code 2.1 writes it. */
	private static final String SESSION = """
			{"pid":12077,"sessionId":"fdb3ba12-3c33-48ed-8c91-ab6523ec5020","cwd":"/home/vogella/git/eclipse-harness",\
			"startedAt":1795707598686,"procStart":"50946","version":"2.1.332","peerProtocol":1,\
			"peerFeatures":["notify_idle"],"kind":"interactive","entrypoint":"cli",\
			"messagingSocketPath":"/run/user/1000/cc-socks/12077.sock","name":"Recent UI comparison",\
			"nameSource":"auto","updatedAt":1795718207896,"status":"shell","statusUpdatedAt":1795718207896}""";

	@TempDir
	Path folder;

	@Test
	void parsesASessionsFile() {
		ClaudeSession session = ClaudeSession.parse(SESSION);
		assertEquals(12077, session.pid());
		assertEquals("fdb3ba12-3c33-48ed-8c91-ab6523ec5020", session.sessionId());
		assertEquals("/home/vogella/git/eclipse-harness", session.cwd());
		assertEquals(1795707598686L, session.startedAt());
		assertEquals("2.1.332", session.version());
		assertEquals("interactive", session.kind());
		assertEquals("Recent UI comparison", session.displayName());
		assertEquals("eclipse-harness", session.folderName());
		assertEquals("shell", session.status());
		assertEquals(1795718207896L, session.statusUpdatedAt());
		assertTrue(session.isWorking());
		assertFalse(session.isWaitingForInput());
	}

	@Test
	void fallsBackToTheFolderAndTheStartWhenNameAndStatusTimeAreMissing() {
		ClaudeSession session = ClaudeSession.parse("{\"pid\":7,\"cwd\":\"/tmp/work\",\"startedAt\":5,\"status\":\"idle\"}");
		assertEquals("work", session.displayName());
		assertEquals(5, session.statusUpdatedAt());
		assertTrue(session.isWaitingForInput());
	}

	@Test
	void refusesTextThatIsNoSession() {
		assertNull(ClaudeSession.parse("{\"pid\":"));
		assertNull(ClaudeSession.parse("{\"sessionId\":\"x\"}"));
		assertNull(ClaudeSession.parse("[1]"));
		assertNull(ClaudeSession.parse(""));
	}

	@Test
	void listsOnlySessionsWhoseProcessRuns() throws Exception {
		Files.writeString(folder.resolve("12077.json"), SESSION);
		Files.writeString(folder.resolve("20.json"), "{\"pid\":20,\"cwd\":\"/a\",\"startedAt\":1795707598687}");
		Files.writeString(folder.resolve("30.json"), "{\"pid\":30,\"cwd\":\"/b\",\"startedAt\":1}");
		Files.writeString(folder.resolve("40.json"), "{\"pid\":4"); // being written
		Files.writeString(folder.resolve("12077.abc.key"), "secret");

		Set<Long> running = Set.of(12077L, 20L, 40L);
		List<ClaudeSession> sessions = new SessionDirectory(folder, running::contains).read();

		assertEquals(List.of(20L, 12077L), sessions.stream().map(ClaudeSession::pid).toList(), "newest first");
	}

	@Test
	void aMissingFolderHasNoSessions() {
		assertTrue(new SessionDirectory(folder.resolve("absent"), pid -> true).read().isEmpty());
	}

	@Test
	void ownSessionsComeFirstAndAreMatchedByPidOrSessionId() {
		ClaudeSession external = ClaudeSession.parse(SESSION);
		TerminalSession own = TerminalSession.claude("/bin/claude", "/home/me/project", "project");
		String ownSessionId = own.command().get(2);
		ClaudeSession ownFile = ClaudeSession.parse("{\"pid\":99,\"sessionId\":\"" + ownSessionId
				+ "\",\"cwd\":\"/home/me/project\",\"startedAt\":1,\"status\":\"busy\"}");
		TerminalSession starting = TerminalSession.claude("/bin/claude", "/home/me/other", "other");

		List<SessionRow> rows = SessionRow.of(List.of(external, ownFile), List.of(own, starting));

		assertEquals(3, rows.size());
		assertTrue(rows.get(0).owned() && rows.get(1).owned());
		assertSame(starting, rows.get(0).terminal(), "a terminal without a sessions file yet");
		assertEquals("starting", rows.get(0).status());
		assertSame(ownFile, rows.get(1).session());
		assertEquals(99, own.pid(), "the pid is learned from the sessions file");
		assertFalse(rows.get(2).owned());

		// after /clear the session id changes and only the pid still matches
		ClaudeSession cleared = ClaudeSession.parse("{\"pid\":99,\"sessionId\":\"new\",\"cwd\":\"/x\",\"startedAt\":1}");
		assertSame(own, SessionRow.of(List.of(cleared), List.of(own)).get(0).terminal());
	}

	@Test
	void formatsAges() {
		assertEquals("now", SessionRow.age(59_000));
		assertEquals("5m", SessionRow.age(5 * 60_000 + 10));
		assertEquals("3h", SessionRow.age(3 * 3_600_000 + 59 * 60_000));
		assertEquals("2d", SessionRow.age(50 * 3_600_000L));
	}

	@Test
	void readsTheTmuxPaneFromAProcessEnvironment() {
		byte[] environ = "HOME=/home/me\0TMUX=/tmp/tmux-1000/default,4242,0\0TMUX_PANE=%3\0".getBytes();
		TmuxPane pane = TmuxPane.fromEnvironment(environ);
		assertEquals("/tmp/tmux-1000/default", pane.socket());
		assertEquals("%3", pane.paneId());
		assertNull(TmuxPane.fromEnvironment("HOME=/home/me\0TMUX_PANE=%3\0".getBytes()));
		assertNull(TmuxPane.fromEnvironment("TMUX=\0TMUX_PANE=%3\0".getBytes()));
	}

	@Test
	void joinsArgumentsSoTheProcessConnectorSplitsThemBack() {
		assertEquals("--session-id abc \"/home/my dir\" \"say \\\"hi\\\"\" \"\" \"a\\\\b\"",
				Commands.join(List.of("--session-id", "abc", "/home/my dir", "say \"hi\"", "", "a\\b")));
	}

	@Test
	void dropsTheVariablesOfASurroundingSession() {
		List<String> environment = List.of(Commands.environment(Map.of("HOME", "/home/me", "CLAUDECODE", "1", "CLAUDE_CODE_CHILD_SESSION", "1",
				"TMUX", "/tmp/tmux", "TERM", "dumb")));
		assertTrue(environment.contains("HOME=/home/me"));
		assertTrue(environment.contains("TERM=xterm-256color"));
		assertFalse(environment.contains("TERM=dumb"));
		assertTrue(environment.stream().noneMatch(entry -> entry.startsWith("CLAUDE") || entry.startsWith("TMUX=")));
	}
}
