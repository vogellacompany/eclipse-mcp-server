package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class ReadImageToolTest {

	private static final String TOOL = "eclipse_read_image";

	private static final String PROJECT = "mcp-image-test";

	/** A 1x1 PNG. */
	private static final byte[] PNG = Base64.getDecoder()
			.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

	/** A 3x2 GIF. */
	private static final byte[] GIF = Base64.getDecoder().decode("R0lGODlhAwACAIAAAP///wAAACwAAAAAAwACAAACAoRRADs=");

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void aPngComesBackAsAnImageWithItsDimensions() throws Exception {
		IFile file = file("icon.png", PNG);

		McpToolResult result = TestFixture.call(TOOL, Map.of("path", file.getFullPath().toString()));

		assertEquals(1, result.images().size());
		assertEquals("image/png", result.images().get(0).mimeType());
		assertArrayEquals(PNG, result.images().get(0).data());
		Map<String, Object> answer = TestFixture.parse(result.text());
		assertEquals(1, ((Number) answer.get("width")).intValue());
		assertEquals(1, ((Number) answer.get("height")).intValue());
	}

	@Test
	void theFormatIsReadFromTheContentNotTheExtension() throws Exception {
		IFile file = file("misnamed.png", GIF);

		McpToolResult result = TestFixture.call(TOOL, Map.of("path", file.getFullPath().toString()));

		assertEquals("image/gif", result.images().get(0).mimeType());
		Map<String, Object> answer = TestFixture.parse(result.text());
		assertEquals(3, ((Number) answer.get("width")).intValue());
		assertEquals(2, ((Number) answer.get("height")).intValue());
	}

	@Test
	void svgIsPointedAtReadFile() throws Exception {
		IFile file = file("icon.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes());

		McpToolResult result = TestFixture.call(TOOL, Map.of("path", file.getFullPath().toString()));

		assertTrue(result.isError());
		assertTrue(result.text().contains("eclipse_read_file"), result.text());
		assertTrue(result.images().isEmpty());
	}

	@Test
	void aFileOverMaxBytesIsRefused() throws Exception {
		IFile file = file("icon.png", PNG);

		McpToolResult result = TestFixture.call(TOOL, Map.of("path", file.getFullPath().toString(), "maxBytes", 10));

		assertTrue(result.isError());
		assertTrue(result.images().isEmpty());
	}

	private IFile file(String name, byte[] content) throws Exception {
		IProject project = fixture.createProject(PROJECT);
		IFile file = project.getFile(name);
		file.create(content, IResource.NONE, null);
		return file;
	}
}
