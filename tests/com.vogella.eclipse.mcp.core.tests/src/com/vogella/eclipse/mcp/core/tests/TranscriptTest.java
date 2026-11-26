package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.ai.internal.Transcript;

class TranscriptTest {

	/** Lines in the shapes Claude Code 2.1 writes, the prompts and answers among them. */
	private static final String TRANSCRIPT = """
			{"type":"permission-mode","permissionMode":"auto","sessionId":"s"}
			{"parentUuid":null,"isSidechain":false,"type":"user","message":{"role":"user","content":"do you have a recent UI comparison?"},"timestamp":"2026-11-24T14:43:10.182Z"}
			{"parentUuid":"a","isSidechain":false,"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":"hmm"}]},"timestamp":"2026-11-24T14:43:11.000Z"}
			{"parentUuid":"a","isSidechain":false,"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Nothing current."},{"type":"tool_use","id":"t","name":"Bash","input":{}}]},"timestamp":"2026-11-24T14:43:12.000Z"}
			{"parentUuid":"b","isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"ok"}]}}
			{"parentUuid":"c","isSidechain":false,"type":"user","message":{"role":"user","content":"<task-notification>done</task-notification>"}}
			{"parentUuid":"c","isSidechain":true,"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"a subagent's answer"}]}}
			{"parentUuid":"c","isMeta":true,"type":"user","message":{"role":"user","content":"caveat"}}
			{"parentUuid":"d","isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"text","text":"run it"}]}}
			{"parentUuid":"e","isSidechain":false,"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Starting."},{"type":"text","text":"Done."}]}}
			{"parentUuid":"f","type":"user","message":{"role":"us""";

	@TempDir
	Path folder;

	@Test
	void derivesTheProjectFolderFromTheWorkingDirectory() {
		assertEquals("-home-vogella-git-eclipse-harness", Transcript.projectFolderName("/home/vogella/git/eclipse-harness"));
		assertEquals("-home-vogella--claude", Transcript.projectFolderName("/home/vogella/.claude"));
		assertEquals("-home-me-my-dir-a-b", Transcript.projectFolderName("/home/me/my dir/a_b"));
	}

	@Test
	void locatesATranscriptInTheDerivedFolderOrAnyOther() throws Exception {
		Path expected = Files.createDirectories(folder.resolve("-home-me-project")).resolve("s1.jsonl");
		Files.writeString(expected, "");
		Path elsewhere = Files.createDirectories(folder.resolve("-moved")).resolve("s2.jsonl");
		Files.writeString(elsewhere, "");

		assertEquals(expected, Transcript.locate(folder, "/home/me/project", "s1"));
		assertEquals(elsewhere, Transcript.locate(folder, "/home/me/project", "s2"));
		assertNull(Transcript.locate(folder, "/home/me/project", "s3"));
		assertNull(Transcript.locate(folder, "/home/me/project", null));
	}

	@Test
	void readsPromptsAndAnswersOnly() throws Exception {
		Path file = folder.resolve("s.jsonl");
		Files.writeString(file, TRANSCRIPT);

		Transcript.Contents contents = Transcript.read(file, 100);

		List<Transcript.Message> messages = contents.messages();
		assertEquals(List.of("do you have a recent UI comparison?", "Nothing current.", "run it", "Starting.\n\nDone."),
				messages.stream().map(Transcript.Message::text).toList());
		assertTrue(messages.get(0).fromUser());
		assertFalse(messages.get(1).fromUser());
		assertEquals("2026-11-24T14:43:10.182Z", messages.get(0).timestamp());
		assertEquals(0, contents.omitted());
	}

	@Test
	void keepsTheNewestMessagesWithinTheLimit() throws Exception {
		Path file = folder.resolve("s.jsonl");
		Files.writeString(file, TRANSCRIPT);

		Transcript.Contents contents = Transcript.read(file, 2);

		assertEquals(List.of("run it", "Starting.\n\nDone."),
				contents.messages().stream().map(Transcript.Message::text).toList());
		assertEquals(2, contents.omitted());
	}
}
