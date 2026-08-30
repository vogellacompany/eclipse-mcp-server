package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * What {@code eclipse_run_command} still checks now that the root allowlist is
 * gone: that the directory is named, absolute and real.
 */
class RunCommandToolTest {

	/** Windows has no echo.exe and no /bin/sh, so the shell form is the portable one. */
	private static final boolean WINDOWS = java.io.File.separatorChar == '\\';

	@Test
	@SuppressWarnings("unchecked")
	void runsACommandAndCapturesItsOutput() throws Exception {
		Path directory = Files.createTempDirectory("mcp-command-run");

		Map<String, Object> result = TestFixture.callAndParse("eclipse_run_command",
				Map.of("command", "echo from the command", "directory", directory.toString(),
						"wait", Boolean.TRUE, "timeoutSeconds", Integer.valueOf(20)));

		assertEquals("done", result.get("state"), "got " + result);
		assertEquals(Integer.valueOf(0), result.get("exitCode"), "got " + result);
		assertTrue(String.valueOf(result.get("output")).contains("from the command"), "got " + result);

		// the same handle answers again, which is what a long build polls
		Map<String, Object> polled = TestFixture.callAndParse("eclipse_get_command_output",
				Map.of("commandId", result.get("commandId")));
		assertEquals("done", polled.get("state"), "got " + polled);
	}

	@Test
	void reportsAFailingCommandAsFailedWithItsExitCode() throws Exception {
		Path directory = Files.createTempDirectory("mcp-command-fail");

		// the args form, which runs without a shell, so the shell is named explicitly
		List<String> failing = WINDOWS ? List.of("cmd.exe", "/c", "echo broken 1>&2& exit /b 3")
				: List.of("/bin/sh", "-c", "echo broken >&2; exit 3");
		Map<String, Object> result = TestFixture.callAndParse("eclipse_run_command",
				Map.of("args", failing, "directory",
						directory.toString(), "wait", Boolean.TRUE, "timeoutSeconds", Integer.valueOf(20)));

		assertEquals("failed", result.get("state"), "got " + result);
		assertEquals(Integer.valueOf(3), result.get("exitCode"), "got " + result);
		// stderr is merged, so the reason sits next to the step that produced it
		assertTrue(String.valueOf(result.get("output")).contains("broken"), "got " + result);
	}

	@Test
	void refusesADirectoryThatDoesNotExist() throws Exception {
		// absolute on every window system: a bare /no/such/... is relative on Windows,
		// where it would be refused for the wrong reason
		String missing = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
				.resolve("no-such-directory-here").toString();
		McpToolResult result = TestFixture.call("eclipse_run_command",
				Map.of("command", "echo hello", "directory", missing));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("no directory"), "got " + result.text());
	}

	@Test
	void refusesARelativeDirectory() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_run_command",
				Map.of("command", "echo hello", "directory", "some/relative/path"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("not absolute"), "got " + result.text());
	}

	@Test
	void refusesWithNoDirectoryAtAll() throws Exception {
		// the directory stays required: it is what keeps a command from inheriting
		// whatever the IDE's own working directory happens to be
		McpToolResult result = TestFixture.call("eclipse_run_command", Map.of("command", "echo hello"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("directory"), "got " + result.text());
	}
}
