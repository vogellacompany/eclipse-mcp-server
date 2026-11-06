package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GetOutlineToolTest {

	private static final String PROJECT = "mcp-outline-test";

	private static final String LIBRARY = "mcp-outline-library";

	private static final String USER = "mcp-outline-user";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void listsMembersWithSignaturesAndLinesButNoBodies() throws Exception {
		createFixtureProject();

		Map<String, Object> result = outline("example.Shapes");

		Map<String, Object> type = onlyType(result);
		assertEquals("example.Shapes", type.get("type"));
		assertEquals("class", type.get("kind"));
		assertEquals("public", type.get("modifiers"));
		assertEquals("<T extends Comparable<T>>", type.get("typeParameters"));
		assertNull(type.get("superclass"), "Object is not reported as a superclass");
		assertEquals(Boolean.FALSE, type.get("binary"));
		assertEquals(Boolean.TRUE, result.get("sourceAvailable"));
		assertEquals(Boolean.FALSE, result.get("truncated"));
		List<Map<String, Object>> members = members(type);
		assertEquals(List.of("field", "constructor", "method", "method", "method", "method", "enum"),
				members.stream().map(member -> member.get("kind")).toList());
		assertEquals("int count", members.get(0).get("signature"));
		assertEquals("private", members.get(0).get("modifiers"));
		assertEquals("Shapes(int count)", members.get(1).get("signature"));
		assertEquals("double area(double radius)", members.get(2).get("signature"));
		assertEquals("double area(double width, double height)", members.get(3).get("signature"));
		assertEquals("<E> List<E> copy(List<E> items) throws IOException", members.get(4).get("signature"));
		assertEquals("public static", members.get(4).get("modifiers"));
		assertFalse(result.toString().contains("radius * radius"), "The outline must not carry method bodies");

		Map<String, Object> area = members.get(2);
		assertEquals(10, ((Number) area.get("startLine")).intValue(), "The declaration starts at its Javadoc");
		assertEquals(15, ((Number) area.get("endLine")).intValue());
		assertNull(area.get("javadoc"), "Javadoc is only added on request");
	}

	@Test
	void writesVarargsAsDeclaredAndNotAsTransient() throws Exception {
		createFixtureProject();

		Map<String, Object> log = members(onlyType(outline("example.Shapes"))).get(5);
		assertEquals("void log(String format, Object... args)", log.get("signature"));
		assertEquals("public", log.get("modifiers"));
	}

	@Test
	void nestsMemberTypesWithTheirOwnMembers() throws Exception {
		createFixtureProject();

		Map<String, Object> nested = members(onlyType(outline("example.Shapes"))).get(6);
		assertEquals("example.Shapes.Kind", nested.get("type"));
		assertEquals("enum", nested.get("kind"));
		List<Map<String, Object>> constants = members(nested);
		assertEquals(List.of("CIRCLE", "SQUARE"), constants.stream().map(member -> member.get("name")).toList(),
				"The body of an enum constant is not a member of the enum");
		assertEquals(List.of("enumConstant", "enumConstant"),
				constants.stream().map(member -> member.get("kind")).toList());
	}

	@Test
	void resolvesABinaryMemberTypeName() throws Exception {
		createFixtureProject();

		assertEquals("example.Box.Inner", onlyType(outline("example.Box$Inner")).get("type"));
	}

	@Test
	void countsTheMembersOfANestedTypeLeftOutByMaxResults() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_outline",
				Map.of("typeName", "example.Box", "project", PROJECT, "maxResults", Integer.valueOf(2)));

		List<Map<String, Object>> members = members(onlyType(result));
		assertEquals(List.of("a", "example.Box.Inner"),
				members.stream().map(member -> member.containsKey("type") ? member.get("type") : member.get("name"))
						.toList());
		assertEquals(List.of(), members(members.get(1)), "The nested type's members are over the budget");
		assertEquals(4, ((Number) result.get("total")).intValue());
		assertEquals(Boolean.TRUE, result.get("truncated"));
	}

	@Test
	void outlinesARecordAndAnAnnotationType() throws Exception {
		createFixtureProject();

		Map<String, Object> point = onlyType(outline("example.Point"));
		assertEquals("record", point.get("kind"));
		List<Map<String, Object>> components = members(point).subList(0, 2);
		assertEquals(List.of("recordComponent", "recordComponent"),
				components.stream().map(member -> member.get("kind")).toList());
		assertEquals(List.of("int x", "int y"), components.stream().map(member -> member.get("signature")).toList());

		Map<String, Object> marker = onlyType(outline("example.Marker"));
		assertEquals("annotation", marker.get("kind"));
		assertEquals("String value()", members(marker).get(0).get("signature"));
	}

	@Test
	void addsTheFirstJavadocSentenceOnRequest() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_outline",
				Map.of("typeName", "example.Shapes", "project", PROJECT, "javadoc", Boolean.TRUE));

		Map<String, Object> type = onlyType(result);
		assertEquals("Computes shapes.", type.get("javadoc"));
		assertEquals("The area of a circle with radius.", members(type).get(2).get("javadoc"));
		assertNull(members(type).get(3).get("javadoc"));
		assertEquals("Copies the List<String> items, e.g. a list.", members(type).get(4).get("javadoc"));
	}

	@Test
	void outlinesEveryTopLevelTypeOfAFile() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_outline",
				Map.of("path", "/" + PROJECT + "/src/example/Pair.java"));

		assertEquals(List.of("example.Pair", "example.PairHelper"),
				types(result).stream().map(type -> type.get("type")).toList());
	}

	@Test
	void outlinesABinaryTypeAndTruncates() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_outline",
				Map.of("typeName", "java.lang.String", "project", PROJECT, "maxResults", Integer.valueOf(5)));

		Map<String, Object> type = onlyType(result);
		assertEquals(Boolean.TRUE, type.get("binary"));
		assertEquals("public final", type.get("modifiers"), "ACC_SUPER must not read as synchronized");
		assertEquals(5, members(type).size());
		assertEquals(Boolean.TRUE, result.get("truncated"));
		assertTrue(((Number) result.get("total")).intValue() > 5, "total counts the members left out");
	}

	@Test
	void writesBinaryMembersAsSourceWould() throws Exception {
		createFixtureProject();

		List<Map<String, Object>> stringMembers = members(onlyType(TestFixture.callAndParse("eclipse_get_outline",
				Map.of("typeName", "java.lang.String", "project", PROJECT, "maxResults", Integer.valueOf(5000)))));
		Map<String, Object> format = stringMembers.stream()
				.filter(member -> String.valueOf(member.get("signature")).startsWith("String format(String")
						&& String.valueOf(member.get("signature")).contains("Object..."))
				.findFirst().orElseThrow(() -> new AssertionError("No format(String, Object...) in " + stringMembers));
		assertEquals("public static", format.get("modifiers"), "A varargs method must not read as transient");

		Map<String, Object> entry = onlyType(TestFixture.callAndParse("eclipse_get_outline",
				Map.of("typeName", "java.util.Map$Entry", "project", PROJECT)));
		assertEquals("interface", entry.get("kind"));
		assertFalse(String.valueOf(entry.get("modifiers")).contains("abstract"),
				"An interface is implicitly abstract: " + entry.get("modifiers"));

		List<Map<String, Object>> collectors = members(onlyType(TestFixture.callAndParse("eclipse_get_outline", Map
				.of("typeName", "java.util.stream.Collectors", "project", PROJECT, "maxResults", Integer.valueOf(5000)))));
		assertTrue(collectors.stream().noneMatch(member -> String.valueOf(member.get("name")).contains("$")),
				"Compiler-generated members such as lambda$ are not part of the outline");
		assertTrue(collectors.stream().map(member -> String.valueOf(member.get("signature")))
				.noneMatch(signature -> signature.contains("$")), "Member types read with a dot");
	}

	@Test
	void aClassFileReadsWithTheModifiersItsSourceHas() throws Exception {
		IJavaProject library = fixture.createJavaProject(LIBRARY);
		useJava21(library);
		TestFixture.addType(library, "lib", "Holder", """
				package lib;

				public class Holder {
					public enum Mode {
						A, B
					}

					public interface Api {
						default int one() {
							return 1;
						}

						static int two() {
							return 2;
						}

						int three();
					}

					public record Pair(int a, int b) {
					}

					public int value$() {
						Runnable task = () -> {
						};
						task.run();
						return 0;
					}
				}
				""");
		TestFixture.build(library.getProject());
		IJavaProject user = fixture.createJavaProject(USER);
		user.setRawClasspath(new IClasspathEntry[] { user.getRawClasspath()[0], user.getRawClasspath()[1],
				JavaCore.newLibraryEntry(library.getProject().getFolder("bin").getFullPath(), null, null) },
				user.getOutputLocation(), null);

		Map<String, Object> source = onlyType(TestFixture.callAndParse("eclipse_get_outline",
				Map.of("typeName", "lib.Holder", "project", LIBRARY)));
		Map<String, Object> binary = onlyType(TestFixture.callAndParse("eclipse_get_outline",
				Map.of("typeName", "lib.Holder", "project", USER)));
		assertEquals(Boolean.FALSE, source.get("binary"));
		assertEquals(Boolean.TRUE, binary.get("binary"));
		for (String nested : List.of("lib.Holder.Mode", "lib.Holder.Api", "lib.Holder.Pair")) {
			assertEquals(member(source, nested).get("modifiers"), member(binary, nested).get("modifiers"),
					nested + " should read alike from source and class file");
			assertEquals(member(source, nested).get("kind"), member(binary, nested).get("kind"), nested);
		}
		Map<String, Object> sourceApi = member(source, "lib.Holder.Api");
		Map<String, Object> binaryApi = member(binary, "lib.Holder.Api");
		for (String method : List.of("one", "two", "three")) {
			assertEquals(named(sourceApi, method).get("modifiers"), named(binaryApi, method).get("modifiers"),
					method + " should read alike from source and class file");
		}
		assertEquals("default", named(binaryApi, "one").get("modifiers"));
		assertEquals("int value$()", named(binary, "value$").get("signature"), "A $ in a declared name is kept");
		assertTrue(members(binary).stream().noneMatch(member -> String.valueOf(member.get("name")).startsWith("lambda$")),
				"The compiler's lambda method is not part of the outline: " + members(binary));
	}

	@Test
	void outlinesABinaryEnum() throws Exception {
		createFixtureProject();

		Map<String, Object> timeUnit = onlyType(outline("java.util.concurrent.TimeUnit"));
		assertEquals("enum", timeUnit.get("kind"));
		assertEquals("public", timeUnit.get("modifiers"), "An enum is implicitly final");
		assertEquals("enumConstant", named(timeUnit, "SECONDS").get("kind"));
	}

	@Test
	void keepsADollarThatStartsASourceName() throws Exception {
		createFixtureProject();

		Map<String, Object> pair = types(TestFixture.callAndParse("eclipse_get_outline",
				Map.of("path", "/" + PROJECT + "/src/example/Pair.java"))).get(0);
		assertEquals("$Weird weird", named(pair, "weird").get("signature"));
	}

	@Test
	void findsATypeWrittenOutsideTheIdeInTheDefaultPackage() throws Exception {
		createFixtureProject();
		writeOutside("Loose.java", "public class Loose {\n\tint loose;\n}\n");

		assertEquals("Loose", onlyType(outline("Loose")).get("type"));
	}

	@Test
	void findsATypeWrittenOutsideTheIdeInAPackageThatIsNewItself() throws Exception {
		createFixtureProject();
		writeOutside("example/fresh/deep/Made.java", "package example.fresh.deep;\n\npublic class Made {\n}\n");

		assertEquals("example.fresh.deep.Made", onlyType(outline("example.fresh.deep.Made")).get("type"));
	}

	@Test
	void findsAFileWrittenOutsideTheIdeByPath() throws Exception {
		createFixtureProject();
		writeOutside("example/fresh/Drawn.java", "package example.fresh;\n\npublic class Drawn {\n}\n");

		assertEquals("example.fresh.Drawn", onlyType(TestFixture.callAndParse("eclipse_get_outline",
				Map.of("path", "/" + PROJECT + "/src/example/fresh/Drawn.java"))).get("type"));
	}

	@Test
	void refusesWithoutExactlyOneTargetAndForAnUnknownType() throws Exception {
		createFixtureProject();

		assertTrue(TestFixture.call("eclipse_get_outline", Map.of()).isError());
		assertTrue(TestFixture.call("eclipse_get_outline",
				Map.of("typeName", "example.Box", "path", "/" + PROJECT + "/src/example/Box.java")).isError());
		assertTrue(TestFixture.call("eclipse_get_outline", Map.of("typeName", "does.not.Exist", "project", PROJECT))
				.isError());
	}

	private static Map<String, Object> member(Map<String, Object> type, String typeName) {
		return members(type).stream().filter(member -> typeName.equals(member.get("type"))).findFirst()
				.orElseThrow(() -> new AssertionError("No " + typeName + " in " + members(type)));
	}

	private static Map<String, Object> named(Map<String, Object> type, String name) {
		return members(type).stream().filter(member -> name.equals(member.get("name"))).findFirst()
				.orElseThrow(() -> new AssertionError("No " + name + " in " + members(type)));
	}

	private static void useJava21(IJavaProject javaProject) {
		// records need Java 16
		Map<String, String> options = javaProject.getOptions(true);
		JavaCore.setComplianceOptions("21", options);
		javaProject.setOptions(options);
	}

	/** Writes a source file the way a client's own shell would, past the workspace. */
	private static void writeOutside(String relativePath, String content) throws Exception {
		Path file = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT).getLocation().toPath().resolve("src")
				.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	private static Map<String, Object> outline(String typeName) throws Exception {
		return TestFixture.callAndParse("eclipse_get_outline", Map.of("typeName", typeName, "project", PROJECT));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> types(Map<String, Object> result) {
		return (List<Map<String, Object>>) result.get("types");
	}

	private static Map<String, Object> onlyType(Map<String, Object> result) {
		List<Map<String, Object>> types = types(result);
		assertEquals(1, types.size(), "Expected one type, got " + types);
		return types.get(0);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> members(Map<String, Object> type) {
		return (List<Map<String, Object>>) type.get("members");
	}

	private void createFixtureProject() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		useJava21(javaProject);
		TestFixture.addType(javaProject, "example", "Shapes", """
				package example;

				/** Computes shapes. */
				public class Shapes<T extends Comparable<T>> {
					private int count;

					Shapes(int count) {
						this.count = count;
					}
					/**
					 * The area of a circle with {@code radius}. Uses pi.
					 */
					double area(double radius) {
						return Math.PI * radius * radius;
					}

					double area(double width, double height) {
						Runnable r = new Runnable() {
							public void run() {
							}
						};
						return width * height;
					}

					/**
					 * Copies the {@code List<String>} <b>items</b>, e.g. a list. Keeps the order.
					 * @return the copy
					 */
					public static <E> java.util.List<E> copy(java.util.List<E> items) throws java.io.IOException {
						return items;
					}

					public void log(String format, Object... args) {
					}

					enum Kind {
						CIRCLE {
							@Override
							public String toString() {
								return "circle";
							}
						},
						SQUARE
					}
				}
				""");
		TestFixture.addType(javaProject, "example", "Box", """
				package example;

				class Box {
					int a;

					static class Inner {
						int b;
						int c;
					}
				}
				""");
		TestFixture.addType(javaProject, "example", "Point", """
				package example;

				public record Point(int x, int y) {
					static Point origin() {
						return new Point(0, 0);
					}
				}
				""");
		TestFixture.addType(javaProject, "example", "Marker", """
				package example;

				public @interface Marker {
					String value() default "";
				}
				""");
		TestFixture.addType(javaProject, "example", "Pair", """
				package example;

				public class Pair {
					$Weird weird;
				}

				class PairHelper {
				}
				""");
		TestFixture.addType(javaProject, "example", "$Weird", """
				package example;

				class $Weird {
				}
				""");
		TestFixture.build(javaProject.getProject());
	}
}
