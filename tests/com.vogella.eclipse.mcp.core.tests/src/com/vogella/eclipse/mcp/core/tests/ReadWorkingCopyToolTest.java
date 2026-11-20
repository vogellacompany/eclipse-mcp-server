package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Reading the working copy gives the text the user sees, unsaved changes included, and says when
 * there is none or when it no longer matches the disk.
 */
class ReadWorkingCopyToolTest {

	private static final String TOOL = "eclipse_read_working_copy";

	private final WorkingCopyFixture fixture = new WorkingCopyFixture("mcp-read-working-copy-test");

	@AfterEach
	void tearDown() throws Exception {
		fixture.dispose();
	}

	@Test
	void readsTheUnsavedText() throws Exception {
		IFile file = fixture.write("Open.java", "class Open {\n}\n");
		// what an open editor does: connect the shared buffer and type into its document
		fixture.connect(file).getDocument().set("class Open {\n\tint unsaved;\n}\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString()));

		assertEquals(Boolean.TRUE, result.get("workingCopy"), "got " + result);
		assertEquals(Boolean.TRUE, result.get("dirty"), "got " + result);
		assertEquals(Boolean.TRUE, result.get("synchronized"), "got " + result);
		assertEquals("class Open {\n\tint unsaved;\n}\n", result.get("content"));
		assertEquals("class Open {\n}\n", TestFixture.read(file), "reading must not save the working copy");
	}

	@Test
	void readsALineRange() throws Exception {
		IFile file = fixture.write("Range.java", "a\nb\nc\n");
		fixture.connect(file).getDocument().set("a\nB\nC\nd\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("path", file.getFullPath().toString(), "offset", 2, "limit", 2));

		assertEquals("B\nC", result.get("content"), "got " + result);
		assertEquals(5, ((Number) result.get("totalLines")).intValue(), "got " + result);
	}

	@Test
	void reportsAnUnchangedWorkingCopyAsClean() throws Exception {
		IFile file = fixture.write("Clean.java", "class Clean {\n}\n");
		fixture.connect(file);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString()));

		assertEquals(Boolean.FALSE, result.get("dirty"), "got " + result);
		assertEquals("class Clean {\n}\n", result.get("content"));
	}

	@Test
	void pointsToReadFileWhenThereIsNoWorkingCopy() throws Exception {
		IFile file = fixture.write("Closed.java", "class Closed {\n}\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString()));

		assertEquals(Boolean.FALSE, result.get("read"), "got " + result);
		assertEquals(Boolean.FALSE, result.get("workingCopy"), "got " + result);
		assertTrue(String.valueOf(result.get("reason")).contains("eclipse_read_file"), "got " + result);
	}

	@Test
	void reportsTheRealSizeOfAWorkingCopyOverMaxBytes() throws Exception {
		IFile file = fixture.write("Large.java", "class Large {\n}\n");
		fixture.connect(file).getDocument().set("0123456789".repeat(10));

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("path", file.getFullPath().toString(), "maxBytes", 40));

		assertEquals(Boolean.FALSE, result.get("read"), "got " + result);
		assertEquals(100, ((Number) result.get("bytes")).intValue(), "got " + result);
	}

	@Test
	void reportsAMissingFile() throws Exception {
		IFile file = fixture.write("Present.java", "class Present {\n}\n");
		String missing = file.getProject().getFullPath().append("Missing.java").toString();

		McpToolResult result = TestFixture.call(TOOL, Map.of("path", missing));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("No file at the workspace path"), "got " + result.text());
	}

	@Test
	void picksUpAnOutsideChangeToACleanWorkingCopy() throws Exception {
		IFile file = fixture.write("Shell.java", "class Shell {\n}\n");
		fixture.connect(file);
		// a client editing through its own shell, behind the workspace's back
		WorkingCopyFixture.writeOutside(file, "class Shell {\n\tint onDisk;\n}\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString()));

		assertEquals(Boolean.FALSE, result.get("dirty"), "got " + result);
		assertEquals(Boolean.TRUE, result.get("synchronized"), "got " + result);
		assertEquals("class Shell {\n\tint onDisk;\n}\n", result.get("content"), "got " + result);
	}

	@Test
	void keepsUnsavedTextTheDiskHasMovedAwayFrom() throws Exception {
		IFile file = fixture.write("Moved.java", "class Moved {\n}\n");
		fixture.connect(file).getDocument().set("class Moved {\n\tint unsaved;\n}\n");
		WorkingCopyFixture.writeOutside(file, "class Moved {\n\tint onDisk;\n}\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString()));

		assertEquals(Boolean.TRUE, result.get("dirty"), "got " + result);
		assertEquals(Boolean.FALSE, result.get("synchronized"), "got " + result);
		assertEquals("class Moved {\n\tint unsaved;\n}\n", result.get("content"), "got " + result);
	}
}
