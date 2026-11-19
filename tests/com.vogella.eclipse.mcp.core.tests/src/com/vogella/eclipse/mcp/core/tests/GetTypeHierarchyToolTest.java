package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GetTypeHierarchyToolTest {

	private static final String PROJECT = "mcp-hierarchy-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsObjectAmongTheSupertypes() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_type_hierarchy",
				Map.of("typeName", "example.Derived", "project", PROJECT, "direction", "supertypes"));

		assertEquals("example.Derived", result.get("type"));
		List<String> supertypes = (List<String>) result.get("supertypes");
		assertTrue(supertypes.contains("java.lang.Object"), "java.lang.Object missing in " + supertypes);
		assertTrue(supertypes.contains("example.Base"), "example.Base missing in " + supertypes);
		assertFalse(result.containsKey("subtypes"), "The subtype direction was not requested");
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsSubtypes() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_type_hierarchy",
				Map.of("typeName", "example.Base", "project", PROJECT, "direction", "subtypes"));

		List<String> subtypes = (List<String>) result.get("subtypes");
		assertTrue(subtypes.contains("example.Derived"), "example.Derived missing in " + subtypes);
	}

	@Test
	@SuppressWarnings("unchecked")
	void separatesTheDirectNeighboursFromTheWholeHierarchy() throws Exception {
		createFixtureProject();

		Map<String, Object> down = TestFixture.callAndParse("eclipse_get_type_hierarchy",
				Map.of("typeName", "example.Base", "project", PROJECT, "direction", "subtypes"));
		assertEquals(List.of("example.Derived"), down.get("directSubtypes"), "got " + down);
		assertEquals(List.of("example.Derived", "example.Leaf"), down.get("subtypes"), "got " + down);
		assertEquals(Integer.valueOf(2), down.get("subtypeTotal"), "got " + down);

		Map<String, Object> up = TestFixture.callAndParse("eclipse_get_type_hierarchy",
				Map.of("typeName", "example.Leaf", "project", PROJECT, "direction", "supertypes"));
		assertEquals("example.Derived", up.get("superclass"), "got " + up);
		assertTrue(((List<String>) up.get("interfaces")).contains("java.lang.Runnable"), "got " + up);
	}

	@Test
	void reportsTruncation() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_type_hierarchy",
				Map.of("typeName", "example.Derived", "project", PROJECT, "direction", "supertypes", "maxResults",
						Integer.valueOf(1)));

		assertEquals(Boolean.TRUE, result.get("truncated"));
	}

	@Test
	void reportsAnUnresolvableTypeAsAnErrorResult() throws Exception {
		createFixtureProject();

		assertTrue(TestFixture.call("eclipse_get_type_hierarchy", Map.of("typeName", "does.not.Exist", "project",
				PROJECT)).isError(), "Expected an error result");
	}

	private void createFixtureProject() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		TestFixture.addType(javaProject, "example", "Base", """
				package example;

				public class Base {
				}
				""");
		TestFixture.addType(javaProject, "example", "Derived", """
				package example;

				public class Derived extends Base {
				}
				""");
		TestFixture.addType(javaProject, "example", "Leaf", """
				package example;

				public class Leaf extends Derived implements Runnable {
					public void run() { }
				}
				""");
		TestFixture.build(javaProject.getProject());
	}
}
