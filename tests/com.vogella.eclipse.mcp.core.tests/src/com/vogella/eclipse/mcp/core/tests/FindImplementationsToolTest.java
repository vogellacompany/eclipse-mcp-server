package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FindImplementationsToolTest {

	private static final String TOOL = "eclipse_find_implementations";

	private static final String PROJECT = "mcp-implementations-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void listsTheConcreteImplementationsOfAnInterfaceWithTheirLocation() throws Exception {
		fixtureProject();

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("typeName", "example.Shape", "project", PROJECT));

		List<Map<?, ?>> found = implementations(result);
		assertEquals(List.of("anonymous in example.Shapes.unit()", "example.Circle", "example.Kind", "example.Square"),
				found.stream().map(entry -> entry.get("type")).toList(), "got " + result);
		Map<?, ?> circle = found.get(1);
		assertEquals("class", circle.get("kind"), "got " + circle);
		assertEquals("/" + PROJECT + "/src/example/Circle.java", circle.get("path"), "got " + circle);
		assertEquals(Integer.valueOf(2), circle.get("line"), "got " + circle);
		assertEquals("enum", found.get(2).get("kind"), "got " + found.get(2));
		assertEquals(Integer.valueOf(4), result.get("total"));
		assertEquals(Boolean.FALSE, result.get("truncated"));
	}

	@Test
	void includesAbstractLevelsOnRequest() throws Exception {
		fixtureProject();

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("typeName", "example.Shape", "project", PROJECT, "includeAbstract", Boolean.TRUE));

		assertTrue(implementations(result).stream()
				.anyMatch(entry -> "example.Polygon".equals(entry.get("type"))
						&& "abstract class".equals(entry.get("kind"))),
				"got " + result);
	}

	@Test
	void findsTheMethodsImplementingAGenericInterfaceMethod() throws Exception {
		fixtureProject();

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("typeName", "example.Shape", "memberName", "scale", "project", PROJECT));

		List<?> methods = implementations(result).stream().map(entry -> entry.get("method")).toList();
		// Square inherits Polygon's implementation, so it does not appear on its own
		assertEquals(List.of("anonymous in example.Shapes.unit() scale(Double)", "example.Circle.scale(Double)",
				"example.Kind.scale(Double)", "example.Polygon.scale(Double)"), methods, "got " + result);
	}

	@Test
	void reportsTruncation() throws Exception {
		fixtureProject();

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("typeName", "example.Shape", "project", PROJECT, "maxResults", Integer.valueOf(1)));

		assertEquals(1, implementations(result).size(), "got " + result);
		assertEquals(Integer.valueOf(4), result.get("total"));
		assertEquals(Boolean.TRUE, result.get("truncated"));
	}

	@Test
	void refusesAMethodNothingCanOverride() throws Exception {
		fixtureProject();

		TestFixture.assertRefused(TestFixture.call(TOOL,
				Map.of("typeName", "example.Shapes", "memberName", "unit", "project", PROJECT)), "nothing can override");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<?, ?>> implementations(Map<String, Object> result) {
		return (List<Map<?, ?>>) result.get("implementations");
	}

	private IJavaProject fixtureProject() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "example", "Shape", """
				package example;
				public interface Shape<T extends Number> {
					double scale(T factor);
				}
				""");
		TestFixture.addType(project, "example", "Circle", """
				package example;
				public class Circle implements Shape<Double> {
					@Override
					public double scale(Double factor) { return factor; }
				}
				""");
		TestFixture.addType(project, "example", "Polygon", """
				package example;
				public abstract class Polygon implements Shape<Double> {
					@Override
					public double scale(Double factor) { return 2 * factor; }
				}
				""");
		TestFixture.addType(project, "example", "Square", """
				package example;
				public class Square extends Polygon {
				}
				""");
		TestFixture.addType(project, "example", "Kind", """
				package example;
				public enum Kind implements Shape<Double> {
					ONE;
					@Override
					public double scale(Double factor) { return 1; }
				}
				""");
		TestFixture.addType(project, "example", "Shapes", """
				package example;
				public class Shapes {
					static Shape<Double> unit() {
						return new Shape<>() {
							@Override
							public double scale(Double factor) { return 0; }
						};
					}
				}
				""");
		TestFixture.build(project.getProject());
		return project;
	}
}
