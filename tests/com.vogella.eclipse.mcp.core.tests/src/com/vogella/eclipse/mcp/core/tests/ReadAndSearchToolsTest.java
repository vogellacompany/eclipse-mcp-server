package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Reading and searching files the Java model cannot see.
 */
class ReadAndSearchToolsTest {

	private static final String PROJECT = "mcp-read-search-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void readsAFileWithTheWorkspaceEncoding() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		write(project, "plugin.xml", "<plugin>\n  <extension point=\"a.b.c\"/>\n</plugin>\n");

		Map<String, Object> result = TestFixture.callAndParse("eclipse_read_file",
				Map.of("path", "/" + PROJECT + "/plugin.xml"));

		assertEquals(Boolean.TRUE, result.get("read"));
		assertEquals(Integer.valueOf(4), result.get("totalLines"));
		assertTrue(String.valueOf(result.get("content")).contains("<extension point=\"a.b.c\"/>"));
		assertEquals(Boolean.FALSE, result.get("truncated"));
	}

	@Test
	void readsAFileCreatedOutsideTheIde() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		java.nio.file.Files.writeString(project.getLocation().append("outside.txt").toFile().toPath(), "from a shell\n");

		Map<String, Object> result = TestFixture.callAndParse("eclipse_read_file",
				Map.of("path", "/" + PROJECT + "/outside.txt"));

		assertEquals(Boolean.TRUE, result.get("read"), result.toString());
		assertTrue(String.valueOf(result.get("content")).contains("from a shell"));
	}

	@Test
	void aPathWithoutAFileSegmentIsAnErrorNotAnException() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_read_file", Map.of("path", "/" + PROJECT));

		assertTrue(result.isError());
		assertTrue(result.text().contains("No file at the workspace path"), result.text());
	}

	@Test
	void readsALineRange() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		write(project, "lines.txt", "one\ntwo\nthree\nfour\n");

		Map<String, Object> result = TestFixture.callAndParse("eclipse_read_file",
				Map.of("path", "/" + PROJECT + "/lines.txt", "offset", 2, "limit", 2));

		assertEquals("two\nthree", result.get("content"));
		assertEquals(Integer.valueOf(2), result.get("firstLine"));
		assertEquals(Integer.valueOf(3), result.get("lastLine"));
		assertEquals(Boolean.TRUE, result.get("truncated"));
	}

	@Test
	void refusesABinaryFileRatherThanManglingIt() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		IFile file = project.getFile("thing.bin");
		file.create(new ByteArrayInputStream(new byte[] { 1, 2, 0, 3 }), true, new NullProgressMonitor());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_read_file",
				Map.of("path", "/" + PROJECT + "/thing.bin"));

		assertEquals(Boolean.FALSE, result.get("read"));
		assertEquals(Boolean.TRUE, result.get("binary"));
	}

	@Test
	void reportsAMissingFile() throws Exception {
		fixture.createProject(PROJECT);
		McpToolResult result = TestFixture.call("eclipse_read_file",
				Map.of("path", "/" + PROJECT + "/nowhere.txt"));
		assertTrue(result.isError());
		assertTrue(result.text().contains("No file at the workspace path"), result.text());
	}

	@Test
	void findsTextInAFileTheJavaModelCannotSee() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		write(project, "plugin.xml", "<plugin>\n  <matcher class=\"a.b.Matcher\"/>\n</plugin>\n");
		write(project, "notes.txt", "a.b.Matcher is mentioned here too\n");

		Map<String, Object> result = TestFixture.callAndParse("eclipse_search_text",
				Map.of("pattern", "a.b.Matcher", "projects", List.of(PROJECT)));

		assertEquals(Integer.valueOf(2), result.get("total"), "got " + result);
		assertEquals(Integer.valueOf(2), result.get("files"));
		Map<String, Object> match = inFile(result, "plugin.xml");
		assertEquals(Integer.valueOf(2), match.get("line"));
		assertTrue(String.valueOf(match.get("text")).contains("<matcher class="), "got " + match);
	}

	@Test
	void narrowsByFileName() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		write(project, "plugin.xml", "<plugin>a.b.Matcher</plugin>\n");
		write(project, "notes.txt", "a.b.Matcher\n");

		Map<String, Object> result = TestFixture.callAndParse("eclipse_search_text", Map.of("pattern", "a.b.Matcher",
				"projects", List.of(PROJECT), "fileNamePattern", "*.xml"));

		assertEquals(Integer.valueOf(1), result.get("total"));
		assertTrue(String.valueOf(first(result).get("file")).endsWith("plugin.xml"));
	}

	@Test
	void skipsDerivedResourcesUnlessAsked() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		IFile output = write(project, "built.txt", "a.b.Matcher\n");
		// build output is what makes a raw grep of this workspace useless: the same
		// type comes back once per copy under a target directory
		output.setDerived(true, new NullProgressMonitor());

		Map<String, Object> excluded = TestFixture.callAndParse("eclipse_search_text",
				Map.of("pattern", "a.b.Matcher", "projects", List.of(PROJECT)));
		assertEquals(Integer.valueOf(0), excluded.get("total"));

		Map<String, Object> included = TestFixture.callAndParse("eclipse_search_text",
				Map.of("pattern", "a.b.Matcher", "projects", List.of(PROJECT), "includeDerived", Boolean.TRUE));
		assertEquals(Integer.valueOf(1), included.get("total"));
	}

	@Test
	void excludesPathsThatAreNotMarkedDerived() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		project.getFolder("target").create(false, true, new NullProgressMonitor());
		write(project, "target/copy.xml", "a.b.Matcher\n");
		write(project, "plugin.xml", "a.b.Matcher\n");

		// Maven and Gradle output is not marked derived, so includeDerived false does
		// not exclude it and a search of a built tree is mostly build output
		Map<String, Object> all = TestFixture.callAndParse("eclipse_search_text",
				Map.of("pattern", "a.b.Matcher", "projects", List.of(PROJECT)));
		assertEquals(Integer.valueOf(2), all.get("total"));

		Map<String, Object> filtered = TestFixture.callAndParse("eclipse_search_text", Map.of("pattern",
				"a.b.Matcher", "projects", List.of(PROJECT), "excludePathPattern", "*/target/*"));
		assertEquals(Integer.valueOf(1), filtered.get("total"), "got " + filtered);
		assertEquals(Integer.valueOf(1), filtered.get("excludedByPath"));
	}

	@Test
	void searchesByRegularExpression() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		write(project, "schema.exsd", "<meta.attribute kind=\"java\" basedOn=\"a.b.C:\"/>\n");

		Map<String, Object> result = TestFixture.callAndParse("eclipse_search_text",
				Map.of("pattern", "kind=\"java\"\\s+basedOn", "isRegex", Boolean.TRUE, "projects", List.of(PROJECT)));

		assertEquals(Integer.valueOf(1), result.get("total"));
	}

	@Test
	void reportsABadRegularExpressionRatherThanFailing() throws Exception {
		fixture.createProject(PROJECT);
		McpToolResult result = TestFixture.call("eclipse_search_text",
				Map.of("pattern", "[unclosed", "isRegex", Boolean.TRUE, "projects", List.of(PROJECT)));
		assertTrue(result.isError());
		assertTrue(result.text().contains("regular expression"), result.text());
	}

	@Test
	void refusesAnUnknownProject() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_search_text",
				Map.of("pattern", "anything", "projects", List.of("no-such-project")));
		assertTrue(result.isError());
		assertFalse(result.text().isBlank());
	}

	@Test
	void countsOneFileOnceEvenWhenSeveralProjectsReachIt() throws Exception {
		IProject outer = fixture.createProject(PROJECT);
		outer.getFolder("inner").create(false, true, new NullProgressMonitor());
		write(outer, "inner/plugin.xml", "<plugin>a.b.Matcher</plugin>\n");
		// a project nested inside another one, which is the normal shape of a platform
		// workspace: the same file on disk is reachable through two workspace paths
		IProject nested = fixture.createProjectAt(PROJECT + "-nested",
				outer.getLocation().append("inner").toFile().toPath());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_search_text",
				Map.of("pattern", "a.b.Matcher", "projects", List.of(PROJECT, nested.getName())));

		assertEquals(Integer.valueOf(1), result.get("total"), "one file on disk is one match, got " + result);
		assertEquals(Integer.valueOf(1), result.get("files"));
		assertEquals(Integer.valueOf(1), result.get("duplicatePathsCollapsed"));
		assertNotNull(first(result).get("alsoVisibleAs"), "the other path should be reported, got " + first(result));
	}

	private static IFile write(IProject project, String name, String content) throws Exception {
		IFile file = project.getFile(name);
		file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());
		return file;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> inFile(Map<String, Object> result, String name) {
		for (Map<String, Object> match : (List<Map<String, Object>>) result.get("matches")) {
			if (String.valueOf(match.get("file")).endsWith(name)) {
				return match;
			}
		}
		throw new AssertionError("No match in %s, got %s".formatted(name, result));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> first(Map<String, Object> result) {
		List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
		assertFalse(matches.isEmpty(), "no matches in " + result);
		return matches.get(0);
	}
}
