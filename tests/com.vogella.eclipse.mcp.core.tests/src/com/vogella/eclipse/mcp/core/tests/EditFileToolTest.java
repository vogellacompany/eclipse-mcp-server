package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Editing one passage of a file rather than resending the whole of it.
 * <p>
 * The refusals matter more than the happy path: an edit that lands somewhere
 * unintended is worse than one that does not happen, and the only defence is
 * that the caller says what it expects to find.
 */
class EditFileToolTest {

	private static final String TOOL = "eclipse_edit_file";

	private static final String PROJECT = "mcp-edit-file-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void replacesTheNamedPassageAndKeepsTheRest() throws Exception {
		IFile file = write("Sample.java", "class Sample {\n\tint width = 1;\n\tint height = 2;\n}\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString(),
				"oldText", "int height = 2;", "newText", "int height = 42;"));

		assertEquals(Boolean.TRUE, result.get("edited"), "got " + result);
		assertEquals(1, ((Number) result.get("replacements")).intValue());
		assertEquals(3, ((Number) result.get("firstChangedLine")).intValue(), "got " + result);
		assertEquals("class Sample {\n\tint width = 1;\n\tint height = 42;\n}\n", read(file));
		assertTrue(String.valueOf(result.get("context")).contains("int height = 42;"), "got " + result);
	}

	@Test
	void matchesACrlfFileAgainstTextGivenWithLf() throws Exception {
		// what a repository checked out on Windows looks like. A client composes its
		// oldText with LF, so without this every multi-line edit of such a file is
		// refused as if the caller had read a different file
		IFile file = write("Crlf.java", "class Crlf {\r\n\tint width = 1;\r\n\tint height = 2;\r\n}\r\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString(),
				"oldText", "\tint width = 1;\n\tint height = 2;", "newText", "\tint width = 3;\n\tint height = 4;"));

		assertEquals(Boolean.TRUE, result.get("edited"), "got " + result);
		assertEquals(Boolean.TRUE, result.get("matchedAfterConvertingLineEndings"), "got " + result);
		// the file keeps its own delimiter: the edit is not what converts it
		assertEquals("class Crlf {\r\n\tint width = 3;\r\n\tint height = 4;\r\n}\r\n", read(file));
		assertTrue(String.valueOf(result.get("context")).contains("int width = 3;"), "got " + result);
		assertTrue(String.valueOf(result.get("context")).indexOf('\r') < 0,
				"the context must not carry the file's carriage returns: " + result);
	}

	@Test
	void refusesWhenTheExpectedTextIsNotThere() throws Exception {
		IFile file = write("Sample.java", "class Sample {\n}\n");

		McpToolResult result = TestFixture.call(TOOL, Map.of("path", file.getFullPath().toString(), //
				"oldText", "int missing = 1;", "newText", "int missing = 2;"));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("does not occur"), result.text());
		// the file is what it was: a refused edit must not be a partial edit
		assertEquals("class Sample {\n}\n", read(file));
	}

	@Test
	void refusesAnAmbiguousMatchAndSaysHowMany() throws Exception {
		IFile file = write("Sample.java", "int a = 0;\nint b = 0;\nint c = 0;\n");

		McpToolResult result = TestFixture.call(TOOL, Map.of("path", file.getFullPath().toString(), //
				"oldText", "= 0;", "newText", "= 1;"));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("3 times"), result.text());
		assertEquals("int a = 0;\nint b = 0;\nint c = 0;\n", read(file));
	}

	@Test
	void replaceAllChangesEveryOccurrence() throws Exception {
		IFile file = write("Sample.java", "int a = 0;\nint b = 0;\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString(),
				"oldText", "= 0;", "newText", "= 1;", "replaceAll", Boolean.TRUE));

		assertEquals(2, ((Number) result.get("replacements")).intValue(), "got " + result);
		assertEquals("int a = 1;\nint b = 1;\n", read(file));
	}

	@Test
	void aDryRunShowsTheResultAndWritesNothing() throws Exception {
		IFile file = write("Sample.java", "int a = 0;\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString(),
				"oldText", "= 0;", "newText", "= 7;", "dryRun", Boolean.TRUE));

		assertEquals(Boolean.FALSE, result.get("edited"), "got " + result);
		assertTrue(String.valueOf(result.get("context")).contains("= 7;"), "the context should show the result");
		assertEquals("int a = 0;\n", read(file), "a dry run must not write");
	}

	@Test
	void namesItsRequiredArgumentsAndRefusesAMissingFile() throws Exception {
		assertTrue(TestFixture.call(TOOL, Map.of("path", "/x/y.txt", "newText", "a")).text().contains("'oldText'"));
		assertTrue(TestFixture.call(TOOL, Map.of("path", "/x/y.txt", "oldText", "a")).text().contains("'newText'"));
		fixture.createProject(PROJECT);
		assertTrue(TestFixture.call(TOOL, Map.of("path", "/" + PROJECT + "/missing.txt", //
				"oldText", "a", "newText", "b")).text().contains("No file at the workspace path"));
	}

	private IFile write(String name, String content) throws Exception {
		IProject project = fixture.createProject(PROJECT);
		IFile file = project.getFile(name);
		if (file.exists()) {
			file.delete(true, new NullProgressMonitor());
		}
		file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());
		return file;
	}

	private static String read(IFile file) throws Exception {
		return new String(file.getContents(true).readAllBytes(), file.getCharset());
	}
}
