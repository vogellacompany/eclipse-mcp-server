package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.undo.DocumentUndoManagerRegistry;
import org.eclipse.text.undo.IDocumentUndoManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Editing the working copy changes the text the user sees and leaves it unsaved, and refuses
 * rather than touch the disk when the working copy cannot be edited safely.
 */
class EditWorkingCopyToolTest {

	private static final String TOOL = "eclipse_edit_working_copy";

	private final WorkingCopyFixture fixture = new WorkingCopyFixture("mcp-edit-working-copy-test");

	@AfterEach
	void tearDown() throws Exception {
		fixture.dispose();
	}

	@Test
	void editsTheUnsavedTextAndLeavesTheDiskAlone() throws Exception {
		IFile file = fixture.write("Open.java", "class Open {\n}\n");
		ITextFileBuffer buffer = fixture.connect(file);
		buffer.getDocument().set("class Open {\n\tint unsaved;\n}\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString(),
				"oldText", "\tint unsaved;", "newText", "\tlong unsaved;"));

		assertEquals(Boolean.TRUE, result.get("edited"), "got " + result);
		assertEquals(Boolean.TRUE, result.get("dirty"), "got " + result);
		assertEquals(2, ((Number) result.get("firstChangedLine")).intValue(), "got " + result);
		assertEquals("class Open {\n\tlong unsaved;\n}\n", buffer.getDocument().get());
		assertEquals("class Open {\n}\n", TestFixture.read(file), "the edit must not save");
	}

	@Test
	void leavesACleanWorkingCopyDirty() throws Exception {
		IFile file = fixture.write("Clean.java", "class Clean {\n}\n");
		ITextFileBuffer buffer = fixture.connect(file);

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("path", file.getFullPath().toString(), "oldText", "Clean", "newText", "Tidy"));

		assertEquals(Boolean.TRUE, result.get("dirty"), "got " + result);
		assertEquals("class Tidy {\n}\n", buffer.getDocument().get());
		assertEquals("class Clean {\n}\n", TestFixture.read(file));
	}

	@Test
	void editsWhatAShellWroteToACleanWorkingCopy() throws Exception {
		IFile file = fixture.write("Shell.java", "class Shell {\n}\n");
		ITextFileBuffer buffer = fixture.connect(file);
		WorkingCopyFixture.writeOutside(file, "class Shell {\n\tint onDisk;\n}\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString(),
				"oldText", "int onDisk;", "newText", "int edited;"));

		assertEquals(Boolean.TRUE, result.get("edited"), "got " + result);
		assertEquals("class Shell {\n\tint edited;\n}\n", buffer.getDocument().get());
	}

	@Test
	void refusesAWorkingCopyTheDiskHasMovedAwayFrom() throws Exception {
		IFile file = fixture.write("Moved.java", "class Moved {\n}\n");
		ITextFileBuffer buffer = fixture.connect(file);
		buffer.getDocument().set("class Moved {\n\tint unsaved;\n}\n");
		WorkingCopyFixture.writeOutside(file, "class Moved {\n\tint onDisk;\n}\n");

		McpToolResult result = TestFixture.call(TOOL, Map.of("path", file.getFullPath().toString(),
				"oldText", "int unsaved;", "newText", "int edited;"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("changed on disk"), "got " + result.text());
		assertEquals("class Moved {\n\tint unsaved;\n}\n", buffer.getDocument().get());
		assertEquals("class Moved {\n\tint onDisk;\n}\n", TestFixture.read(file));
	}

	@Test
	void refusesAnAmbiguousPassage() throws Exception {
		IFile file = fixture.write("Twice.java", "int a;\nint a;\n");
		ITextFileBuffer buffer = fixture.connect(file);

		McpToolResult result = TestFixture.call(TOOL,
				Map.of("path", file.getFullPath().toString(), "oldText", "int a;", "newText", "int b;"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("2 times"), "got " + result.text());
		assertEquals("int a;\nint a;\n", buffer.getDocument().get());
	}

	@Test
	void replacesEveryOccurrenceWhenAsked() throws Exception {
		IFile file = fixture.write("All.java", "int a;\nint a;\nint c;\n");
		ITextFileBuffer buffer = fixture.connect(file);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString(),
				"oldText", "int a;", "newText", "long a;", "replaceAll", Boolean.TRUE));

		assertEquals(2, ((Number) result.get("replacements")).intValue(), "got " + result);
		assertEquals("long a;\nlong a;\nint c;\n", buffer.getDocument().get());
	}

	@Test
	void undoesAReplaceAllInOneStep() throws Exception {
		IFile file = fixture.write("Undo.java", "int a;\nint b;\nint a;\n");
		IDocument document = fixture.connect(file).getDocument();
		// what an editor's viewer does: share one undo manager for the document
		DocumentUndoManagerRegistry.connect(document);
		IDocumentUndoManager undo = DocumentUndoManagerRegistry.getDocumentUndoManager(document);
		undo.connect(this);
		try {
			TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString(), "oldText", "int a;",
					"newText", "long a;", "replaceAll", Boolean.TRUE));
			assertEquals("long a;\nint b;\nlong a;\n", document.get());

			undo.undo();

			assertEquals("int a;\nint b;\nint a;\n", document.get());
		} finally {
			undo.disconnect(this);
			DocumentUndoManagerRegistry.disconnect(document);
		}
	}

	@Test
	void matchesLfAgainstACrlfWorkingCopy() throws Exception {
		IFile file = fixture.write("Crlf.java", "class Crlf {\r\n\tint a;\r\n}\r\n");
		ITextFileBuffer buffer = fixture.connect(file);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString(),
				"oldText", "{\n\tint a;", "newText", "{\n\tint b;"));

		assertEquals(Boolean.TRUE, result.get("matchedAfterConvertingLineEndings"), "got " + result);
		assertEquals("class Crlf {\r\n\tint b;\r\n}\r\n", buffer.getDocument().get());
	}

	@Test
	void changesNothingOnADryRun() throws Exception {
		IFile file = fixture.write("Dry.java", "class Dry {\n}\n");
		ITextFileBuffer buffer = fixture.connect(file);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString(),
				"oldText", "Dry", "newText", "Wet", "dryRun", Boolean.TRUE));

		assertEquals(Boolean.FALSE, result.get("edited"), "got " + result);
		assertTrue(String.valueOf(result.get("context")).contains("class Wet"), "got " + result);
		assertEquals("class Dry {\n}\n", buffer.getDocument().get());
		assertEquals(Boolean.FALSE, Boolean.valueOf(buffer.isDirty()));
	}

	@Test
	void pointsToEditFileWhenThereIsNoWorkingCopy() throws Exception {
		IFile file = fixture.write("Closed.java", "class Closed {\n}\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("path", file.getFullPath().toString(), "oldText", "Closed", "newText", "Open"));

		assertEquals(Boolean.FALSE, result.get("edited"), "got " + result);
		assertEquals(Boolean.FALSE, result.get("workingCopy"), "got " + result);
		assertTrue(String.valueOf(result.get("reason")).contains("eclipse_edit_file"), "got " + result);
		assertEquals("class Closed {\n}\n", TestFixture.read(file));
	}

	@Test
	void reportsAMissingFile() throws Exception {
		IFile file = fixture.write("Present.java", "class Present {\n}\n");
		String missing = file.getProject().getFullPath().append("Missing.java").toString();

		McpToolResult result = TestFixture.call(TOOL, Map.of("path", missing, "oldText", "a", "newText", "b"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("No file at the workspace path"), "got " + result.text());
	}
}
