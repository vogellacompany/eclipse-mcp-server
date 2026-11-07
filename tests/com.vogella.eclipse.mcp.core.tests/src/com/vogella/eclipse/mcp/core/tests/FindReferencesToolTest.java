package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class FindReferencesToolTest {

	private static final String PROJECT = "mcp-references-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	@SuppressWarnings("unchecked")
	void answersManyQueriesInOneCall() throws Exception {
		IJavaProject project = fixture.createJavaProject("mcp-batch-references-test");
		TestFixture.addType(project, "example", "Holder", """
				package example;
				public class Holder {
					public static int USED = 1;
					public static int DEAD = 2;
				}
				""");
		TestFixture.addType(project, "example", "Reader", """
				package example;
				public class Reader {
					int read() { return Holder.USED; }
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_find_references",
				Map.of("project", "mcp-batch-references-test", "queries",
						List.of(Map.of("typeName", "example.Holder", "memberName", "USED"),
								Map.of("typeName", "example.Holder", "memberName", "DEAD"),
								Map.of("typeName", "example.Nowhere"))));

		List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
		assertEquals(3, results.size(), "one result per query, got " + results);
		assertEquals(Integer.valueOf(1), results.get(0).get("total"), "got " + results.get(0));
		assertEquals(Integer.valueOf(0), results.get(1).get("total"), "got " + results.get(1));
		// a name that does not resolve fails that query alone rather than the batch
		assertTrue(results.get(2).containsKey("error"), "got " + results.get(2));
	}

	@Test
	@SuppressWarnings("unchecked")
	void refreshesBeforeSearchingUnlessToldNotTo() throws Exception {
		IJavaProject project = fixture.createJavaProject("mcp-refresh-references-test");
		TestFixture.addType(project, "example", "Holder", """
				package example;
				public class Holder {
					public static int USED = 1;
				}
				""");
		TestFixture.build(project.getProject());
		// written the way a client's own shell writes, so the workspace has not seen it
		Path outside = project.getProject().getLocation().toPath().resolve("src/example/Reader.java");
		Files.writeString(outside, """
				package example;
				public class Reader {
					int read() { return Holder.USED; }
				}
				""");

		Map<String, Object> skipped = TestFixture.callAndParse("eclipse_find_references", Map.of("project",
				"mcp-refresh-references-test", "typeName", "example.Holder", "memberName", "USED", "refresh", false));
		assertEquals(Integer.valueOf(0), skipped.get("total"), "got " + skipped);
		assertEquals(Boolean.FALSE, skipped.get("refreshed"), "got " + skipped);
		assertFalse(skipped.containsKey("refreshMillis"), "got " + skipped);
		Map<String, Object> skippedBatch = TestFixture.callAndParse("eclipse_find_references",
				Map.of("project", "mcp-refresh-references-test", "refresh", false, "queries",
						List.of(Map.of("typeName", "example.Holder", "memberName", "USED"))));
		assertEquals(Boolean.FALSE, skippedBatch.get("refreshed"), "got " + skippedBatch);
		assertFalse(skippedBatch.containsKey("refreshMillis"), "got " + skippedBatch);
		assertEquals(Integer.valueOf(0), ((List<Map<String, Object>>) skippedBatch.get("results")).get(0).get("total"),
				"got " + skippedBatch);

		Map<String, Object> refreshed = TestFixture.callAndParse("eclipse_find_references", Map.of("project",
				"mcp-refresh-references-test", "typeName", "example.Holder", "memberName", "USED"));
		assertEquals(Integer.valueOf(1), refreshed.get("total"), "got " + refreshed);
		assertEquals(Boolean.TRUE, refreshed.get("refreshed"), "got " + refreshed);
		assertTrue(refreshed.get("refreshMillis") instanceof Number, "got " + refreshed);
		Map<String, Object> refreshedBatch = TestFixture.callAndParse("eclipse_find_references",
				Map.of("project", "mcp-refresh-references-test", "queries",
						List.of(Map.of("typeName", "example.Holder", "memberName", "USED"))));
		assertEquals(Boolean.TRUE, refreshedBatch.get("refreshed"), "got " + refreshedBatch);
		assertTrue(refreshedBatch.get("refreshMillis") instanceof Number, "got " + refreshedBatch);
	}

	@Test
	@SuppressWarnings("unchecked")
	void separatesTheOverloadsOfAMethod() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "example", "Loader", """
				package example;
				public class Loader {
					public static boolean load(String name) { return true; }
					public static boolean load(int size) { return false; }
				}
				""");
		TestFixture.addType(project, "example", "Caller", """
				package example;
				public class Caller {
					boolean a() { return Loader.load("x"); }
					boolean b() { return Loader.load("y"); }
					boolean c() { return Loader.load(3); }
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> merged = TestFixture.callAndParse("eclipse_find_references",
				Map.of("project", PROJECT, "typeName", "example.Loader", "memberName", "load"));
		assertEquals(Integer.valueOf(3), merged.get("total"), "got " + merged);
		List<Map<String, Object>> byMember = (List<Map<String, Object>>) merged.get("byMember");
		assertEquals(2, byMember.size(), "one entry per overload, got " + byMember);
		Map<Object, Object> counts = byMember.stream()
				.collect(Collectors.toMap(entry -> entry.get("signature"), entry -> entry.get("total")));
		assertEquals(Integer.valueOf(2), counts.get("load(String)"), "got " + counts);
		assertEquals(Integer.valueOf(1), counts.get("load(int)"), "got " + counts);
		List<Map<String, Object>> matches = (List<Map<String, Object>>) merged.get("matches");
		assertTrue(matches.stream().allMatch(match -> match.get("signature") != null), "got " + matches);

		// paramTypes narrows to the one overload, which is what a dead code sweep needs
		Map<String, Object> narrowed = TestFixture.callAndParse("eclipse_find_references", Map.of("project", PROJECT,
				"typeName", "example.Loader", "memberName", "load", "paramTypes", List.of("int")));
		assertEquals(Integer.valueOf(1), narrowed.get("total"), "got " + narrowed);
		assertEquals("example.Loader#load(int)", narrowed.get("resolved"), "got " + narrowed);
	}

	@Test
	@SuppressWarnings("unchecked")
	void countsOneOverloadPerQuery() throws Exception {
		IJavaProject project = fixture.createJavaProject("mcp-overload-batch-test");
		TestFixture.addType(project, "example", "Loader", """
				package example;
				public class Loader {
					public static boolean load(String name) { return true; }
					public static boolean load(int size) { return false; }
				}
				""");
		TestFixture.addType(project, "example", "Caller", """
				package example;
				public class Caller {
					boolean a() { return Loader.load("x"); }
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_find_references",
				Map.of("project", "mcp-overload-batch-test", "queries",
						List.of(Map.of("typeName", "example.Loader", "memberName", "load", "paramTypes",
								List.of("java.lang.String")),
								Map.of("typeName", "example.Loader", "memberName", "load", "paramTypes",
										List.of("int")))));

		List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
		assertEquals(Integer.valueOf(1), results.get(0).get("total"), "got " + results.get(0));
		assertEquals(Integer.valueOf(0), results.get(1).get("total"), "the int overload is dead, got " + results.get(1));
	}

	@Test
	@SuppressWarnings("unchecked")
	void searchesEveryCopyOfADuplicatedType() throws Exception {
		// SWT declares one qualified name once per window system, and a search bound
		// to one copy silently answers nothing about references resolving to another
		IJavaProject gtk = fixture.createJavaProject("mcp-copy-gtk-test");
		TestFixture.addType(gtk, "example", "Loader", """
				package example;
				public class Loader {
					public static boolean load(String name) { return true; }
				}
				""");
		TestFixture.addType(gtk, "example", "Widget", """
				package example;
				public class Widget {
					boolean a() { return Loader.load("gtk"); }
				}
				""");
		IJavaProject win32 = fixture.createJavaProject("mcp-copy-win32-test");
		TestFixture.addType(win32, "example", "Loader", """
				package example;
				public class Loader {
					public static boolean load(String name) { return true; }
				}
				""");
		TestFixture.addType(win32, "example", "Widget", """
				package example;
				public class Widget {
					boolean a() { return Loader.load("win32"); }
					boolean b() { return Loader.load("win32again"); }
				}
				""");
		TestFixture.build(gtk.getProject());
		TestFixture.build(win32.getProject());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_find_references",
				Map.of("typeName", "example.Loader", "memberName", "load"));

		assertEquals(Integer.valueOf(3), result.get("total"),
				"both copies of the type have to be searched, got " + result);
		List<String> declaredIn = (List<String>) result.get("declaredIn");
		assertEquals(2, declaredIn.size(), "got " + declaredIn);
	}

	@Test
	void countsADeclarationOnceHoweverManyProjectsReachIt() throws Exception {
		// findType follows the build path, so every dependent of the declaring
		// project answers with the same handle; that is one declaration, not many
		IJavaProject lib = fixture.createJavaProject("mcp-declared-once-lib-test");
		TestFixture.addType(lib, "example", "Loader", """
				package example;
				public class Loader {
					public static boolean load(String name) { return true; }
					boolean self() { return load("self"); }
				}
				""");
		IJavaProject client = fixture.createJavaProject("mcp-declared-once-client-test");
		client.setRawClasspath(append(client, JavaCore.newProjectEntry(lib.getPath())), null);
		TestFixture.addType(client, "client", "User", """
				package client;
				public class User {
					boolean a() { return example.Loader.load("client"); }
				}
				""");
		TestFixture.build(lib.getProject());
		TestFixture.build(client.getProject());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_find_references",
				Map.of("typeName", "example.Loader", "memberName", "load"));

		assertEquals(Integer.valueOf(2), result.get("total"), "got " + result);
		assertNull(result.get("declaredIn"), "one declaration must not be reported as several: " + result);
	}

	private static IClasspathEntry[] append(IJavaProject project, IClasspathEntry entry) throws Exception {
		IClasspathEntry[] existing = project.getRawClasspath();
		IClasspathEntry[] result = java.util.Arrays.copyOf(existing, existing.length + 1);
		result[existing.length] = entry;
		return result;
	}

	@Test
	void resolvesASecondaryType() throws Exception {
		IJavaProject project = fixture.createJavaProject("mcp-secondary-type-test");
		// a package-private type declared in a file named after a different type.
		// JDT's findType(String) excludes these by its own documentation, and only
		// the monitor-taking overload consults the index where they are known
		TestFixture.addType(project, "example", "Holder", """
				package example;
				public class Holder {
					Secondary use() { return new Secondary(); }
				}
				class Secondary {
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_find_references",
				Map.of("typeName", "example.Secondary", "project", "mcp-secondary-type-test"));

		assertTrue(((Number) result.get("total")).intValue() > 0,
				"references to a secondary type should be found, got " + result);
	}

	@Test
	@SuppressWarnings("unchecked")
	void findsAReferenceBetweenTwoTypes() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_find_references",
				Map.of("typeName", "example.Greeter", "project", PROJECT));

		assertEquals("example.Greeter", result.get("resolved"));
		List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
		assertTrue(matches.stream().anyMatch(match -> "/%s/src/example/Caller.java".formatted(PROJECT)
				.equals(match.get("path"))), "No reference from Caller.java, got " + matches);
	}

	@Test
	@SuppressWarnings("unchecked")
	void findsReferencesToAMethod() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_find_references",
				Map.of("typeName", "example.Greeter", "memberName", "greet", "project", PROJECT));

		assertEquals("example.Greeter#greet", result.get("resolved"));
		List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
		assertTrue(matches.size() >= 1, "No reference to greet(), got " + matches);
		assertTrue(matches.get(0).get("enclosingElement").toString().startsWith("example.Caller."),
				"Unexpected enclosing element in " + matches.get(0));
	}

	@Test
	void reportsTruncation() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_find_references",
				Map.of("typeName", "example.Greeter", "project", PROJECT, "maxResults", Integer.valueOf(1)));

		assertTrue(((Number) result.get("total")).intValue() > 1, "Fixture produced too few matches: " + result);
		assertEquals(Boolean.TRUE, result.get("truncated"));
	}

	@Test
	void reportsAnUnresolvableTypeAsAnErrorResult() throws Exception {
		createFixtureProject();

		McpToolResult result = TestFixture.call("eclipse_find_references",
				Map.of("typeName", "does.not.Exist", "project", PROJECT));

		assertTrue(result.isError(), "Expected an error result");
		assertTrue(result.text().contains("does.not.Exist"), "The message should name the type: " + result.text());
	}

	private void createFixtureProject() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		TestFixture.addType(javaProject, "example", "Greeter", """
				package example;

				public class Greeter {
					public String greet(String name) {
						return "Hello " + name;
					}
				}
				""");
		TestFixture.addType(javaProject, "example", "Caller", """
				package example;

				public class Caller {
					public String callOnce(Greeter greeter) {
						return greeter.greet("world");
					}

					public String callTwice(Greeter greeter) {
						return greeter.greet("again");
					}
				}
				""");
		TestFixture.build(javaProject.getProject());
	}
}
