package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Reading a file gives the saved text, and says so when a working copy has unsaved changes.
 */
class ReadFileToolTest {

	private static final String TOOL = "eclipse_read_file";

	private final WorkingCopyFixture fixture = new WorkingCopyFixture("mcp-read-file-test");

	@AfterEach
	void tearDown() throws Exception {
		fixture.dispose();
	}

	@Test
	void readsTheSavedFile() throws Exception {
		IFile file = fixture.write("Closed.java", "class Closed {\n}\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString()));

		assertEquals(Boolean.TRUE, result.get("read"), "got " + result);
		assertEquals("class Closed {\n}\n", result.get("content"));
		assertNull(result.get("unsavedInEditor"), "got " + result);
	}

	@Test
	void pointsToTheWorkingCopyWhenItHasUnsavedChanges() throws Exception {
		IFile file = fixture.write("Open.java", "class Open {\n}\n");
		fixture.connect(file).getDocument().set("class Open {\n\tint unsaved;\n}\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString()));

		assertEquals("class Open {\n}\n", result.get("content"), "the saved text, not the working copy");
		assertEquals(Boolean.TRUE, result.get("unsavedInEditor"), "got " + result);
		assertTrue(String.valueOf(result.get("note")).contains("eclipse_read_working_copy"), "got " + result);
	}

	@Test
	void saysNothingAboutAnUnchangedWorkingCopy() throws Exception {
		IFile file = fixture.write("Clean.java", "class Clean {\n}\n");
		fixture.connect(file);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", file.getFullPath().toString()));

		assertNull(result.get("unsavedInEditor"), "got " + result);
	}
}
