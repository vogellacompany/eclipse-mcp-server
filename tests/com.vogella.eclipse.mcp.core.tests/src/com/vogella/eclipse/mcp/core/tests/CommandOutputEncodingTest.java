package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.basic.internal.CommandRegistry;

/**
 * How the output of a spawned command is decoded.
 * <p>
 * The case that motivated this is Windows, where cmd.exe writes the OEM code
 * page while {@code native.encoding} reports the ANSI one, so an umlaut arrived
 * as a different character rather than as an unreadable one.
 */
class CommandOutputEncodingTest {

	private static final Charset CP850 = Charset.forName("IBM850");

	@Test
	void aLineThatIsNotUtf8FallsBackToTheConsoleCharset() {
		byte[] cp850 = "umlauts=äöüß".getBytes(CP850);
		assertEquals("umlauts=äöüß", CommandRegistry.decodeLine(cp850, CP850));
	}

	@Test
	void utf8WinsOverTheFallbackWhateverTheConsoleIsSetTo() {
		byte[] utf8 = "commit über alles".getBytes(StandardCharsets.UTF_8);
		assertEquals("commit über alles", CommandRegistry.decodeLine(utf8, CP850));
	}

	@Test
	void aCarriageReturnBeforeTheNewlineIsNotPartOfTheLine() {
		assertEquals("built", CommandRegistry.decodeLine("built\r".getBytes(StandardCharsets.UTF_8), CP850));
	}

	@Test
	void linesAreSplitOnNewlinesAndTheLastOneNeedsNone() throws Exception {
		byte[] output = "first\r\nsecond\nthird".getBytes(StandardCharsets.UTF_8);
		List<String> lines = new ArrayList<>();
		CommandRegistry.readLines(new ByteArrayInputStream(output), CP850, lines::add);
		assertEquals(List.of("first", "second", "third"), lines);
	}

	@Test
	void theCodePageIsReadOutOfWhateverLanguageChcpAnswersIn() {
		assertEquals(CP850, CommandRegistry.charsetOfCodePage("Aktive Codepage: 850."));
		assertEquals(CP850, CommandRegistry.charsetOfCodePage("Active code page: 850"));
		assertEquals(StandardCharsets.UTF_8, CommandRegistry.charsetOfCodePage("Active code page: 65001"));
		assertEquals(Charset.forName("windows-1252"), CommandRegistry.charsetOfCodePage("Active code page: 1252"));
	}

	@Test
	void aCodePageThisJvmHasNoCharsetForIsNotGuessedAt() {
		assertNull(CommandRegistry.charsetOfCodePage("Active code page: 4242"));
		assertNull(CommandRegistry.charsetOfCodePage("no number here"));
	}
}
