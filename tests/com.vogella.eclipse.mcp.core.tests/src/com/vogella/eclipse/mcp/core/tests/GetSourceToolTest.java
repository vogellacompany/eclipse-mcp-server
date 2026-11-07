package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class GetSourceToolTest {

	private static final String PROJECT = "mcp-source-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void returnsTheSourceOfAType() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_source",
				Map.of("typeName", "example.Documented", "project", PROJECT));

		assertEquals("example.Documented", result.get("type"));
		assertEquals(Boolean.FALSE, result.get("binary"));
		assertEquals(Boolean.TRUE, result.get("sourceAvailable"));
		String source = onlyElement(result).get("source").toString();
		assertTrue(source.contains("class Documented"), "Unexpected source: " + source);
		assertTrue(source.contains("public String greet"), "The member should be part of the type source");
	}

	@Test
	void returnsTheSourceOfAMemberIncludingItsJavadoc() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_source",
				Map.of("typeName", "example.Documented", "memberName", "greet", "project", PROJECT));

		Map<String, Object> element = onlyElement(result);
		String source = element.get("source").toString();
		assertTrue(source.startsWith("/**"), "The Javadoc should be included: " + source);
		assertTrue(source.contains("Greets somebody."), "The Javadoc text is missing: " + source);
		assertTrue(source.contains("return \"Hello \" + name;"), "The body is missing: " + source);
		assertEquals("example.Documented.greet(String)", element.get("element"));
		assertTrue(((Number) element.get("line")).intValue() > 1, "A line number was expected");
	}

	@Test
	@SuppressWarnings("unchecked")
	void returnsEveryOverload() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_source",
				Map.of("typeName", "example.Documented", "memberName", "overloaded", "project", PROJECT));

		List<Map<String, Object>> elements = (List<Map<String, Object>>) result.get("elements");
		assertEquals(3, elements.size(), "Every overload was expected, got " + elements);
	}

	@Test
	void paramTypesSelectOneOverload() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_source", Map.of("typeName",
				"example.Documented", "memberName", "overloaded", "paramTypes", List.of("int"), "project", PROJECT));

		Map<String, Object> element = onlyElement(result);
		assertEquals("example.Documented.overloaded(int)", element.get("element"));
		assertEquals(14, ((Number) element.get("line")).intValue());
		assertEquals(15, ((Number) element.get("endLine")).intValue());
	}

	@Test
	void paramTypesMatchQualifiedNamesAndTheEmptyList() throws Exception {
		createFixtureProject();

		assertEquals("example.Documented.overloaded(String)", onlyElement(overload(List.of("java.lang.String")))
				.get("element"));
		assertEquals("example.Documented.overloaded()", onlyElement(overload(List.of())).get("element"));
	}

	@Test
	void paramTypesMatchABinaryMemberTypeBySimpleName() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_source", Map.of("typeName", "java.util.Map",
				"memberName", "ofEntries", "paramTypes", List.of("Map$Entry..."), "project", PROJECT));

		String element = onlyElement(result).get("element").toString();
		assertTrue(element.startsWith("java.util.Map.ofEntries(Entry<") && !element.contains("$"),
				"The varargs Map.Entry overload was expected: " + element);
	}

	@Test
	void aQualifiedParameterTypeTellsOverloadsOfTheSameSimpleNameApart() throws Exception {
		createFixtureProject();

		assertTrue(dates("java.sql.Date").contains("return 2;"), "The java.sql.Date overload was expected");
		assertTrue(dates("java.util.Date").contains("return 1;"),
				"The java.util.Date overload was expected");
		McpToolResult ambiguous = TestFixture.call("eclipse_get_source", Map.of("typeName", "example.Dates",
				"memberName", "set", "paramTypes", List.of("Date"), "project", PROJECT));
		assertTrue(ambiguous.isError() && ambiguous.text().contains("set(java.util.Date)")
				&& ambiguous.text().contains("set(java.sql.Date)"), ambiguous.text());
		McpToolResult unmatched = TestFixture.call("eclipse_get_source", Map.of("typeName", "example.Dates",
				"memberName", "set", "paramTypes", List.of("long"), "project", PROJECT));
		assertTrue(unmatched.isError() && unmatched.text().contains("set(java.sql.Date)"),
				"Overloads that read alike are listed qualified: " + unmatched.text());
		assertTrue(TestFixture.call("eclipse_get_source", Map.of("typeName", "java.util.Map", "memberName",
				"ofEntries", "paramTypes", List.of("java.lang.Map.Entry..."), "project", PROJECT)).isError(),
				"A binary parameter is matched by its package too");
	}

	private static String dates(String parameterType) throws Exception {
		return onlyElement(TestFixture.callAndParse("eclipse_get_source", Map.of("typeName", "example.Dates",
				"memberName", "set", "paramTypes", List.of(parameterType), "project", PROJECT))).get("source")
				.toString();
	}

	@Test
	void aTypeVariableIsMatchedByItsName() throws Exception {
		createFixtureProject();

		assertEquals("example.Documented.generic(T)", onlyElement(TestFixture.callAndParse("eclipse_get_source",
				Map.of("typeName", "example.Documented", "memberName", "generic", "paramTypes", List.of("T"),
						"project", PROJECT))).get("element"));
		assertTrue(TestFixture.call("eclipse_get_source", Map.of("typeName", "example.Documented", "memberName",
				"generic", "paramTypes", List.of("Object"), "project", PROJECT)).isError(),
				"A type variable is not its erasure");
	}

	@Test
	void anUnknownTypeIsNamedAsItWasGiven() throws Exception {
		createFixtureProject();

		McpToolResult result = TestFixture.call("eclipse_get_source",
				Map.of("typeName", "example.Missing$Thing", "project", PROJECT));
		assertTrue(result.isError() && result.text().contains("example.Missing$Thing"), result.text());
	}

	@Test
	void malformedParamTypesAreRefused() throws Exception {
		createFixtureProject();

		Map<String, Object> notAList = new HashMap<>(Map.of("typeName", "example.Documented", "memberName",
				"overloaded", "project", PROJECT, "paramTypes", "int"));
		assertTrue(TestFixture.call("eclipse_get_source", notAList).isError(), "A string is not a list");
		Map<String, Object> withNull = new HashMap<>(notAList);
		withNull.put("paramTypes", Arrays.asList("int", null));
		assertTrue(TestFixture.call("eclipse_get_source", withNull).isError(), "A null entry is no type");
		Map<String, Object> blank = new HashMap<>(notAList);
		blank.put("paramTypes", List.of(" "));
		assertTrue(TestFixture.call("eclipse_get_source", blank).isError(), "A blank entry is no type");
	}

	@Test
	void resolvesABinaryMemberTypeName() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_source",
				Map.of("typeName", "example.Documented$Inner", "project", PROJECT));

		assertEquals("example.Documented.Inner", result.get("type").toString().replace('$', '.'));
	}

	private static Map<String, Object> overload(List<String> paramTypes) throws Exception {
		return TestFixture.callAndParse("eclipse_get_source", Map.of("typeName", "example.Documented", "memberName",
				"overloaded", "paramTypes", paramTypes, "project", PROJECT));
	}

	@Test
	void anUnmatchedOverloadListsTheOverloads() throws Exception {
		createFixtureProject();

		McpToolResult result = TestFixture.call("eclipse_get_source", Map.of("typeName", "example.Documented",
				"memberName", "overloaded", "paramTypes", List.of("long"), "project", PROJECT));

		assertTrue(result.isError(), "Expected an error result");
		assertTrue(result.text().contains("overloaded(String)") && result.text().contains("overloaded(int)"),
				"The overloads should be listed: " + result.text());
	}

	@Test
	void readsATypeFromTheClasspath() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_source",
				Map.of("typeName", "java.lang.String", "memberName", "trim", "project", PROJECT));

		assertEquals(Boolean.TRUE, result.get("binary"));
		// the JRE in the test runtime may or may not have sources attached, both are valid answers
		assertTrue(result.containsKey("sourceAvailable"), "sourceAvailable should always be reported");
	}

	@Test
	void truncatesLongSource() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_source",
				Map.of("typeName", "example.Documented", "project", PROJECT, "maxLength", Integer.valueOf(100)));

		Map<String, Object> element = onlyElement(result);
		assertEquals(Boolean.TRUE, element.get("truncated"));
		assertEquals(100, element.get("source").toString().length());
	}

	@Test
	void reportsAnUnresolvableTypeAsAnErrorResult() throws Exception {
		createFixtureProject();

		assertTrue(TestFixture.call("eclipse_get_source", Map.of("typeName", "does.not.Exist", "project", PROJECT))
				.isError(), "Expected an error result");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> onlyElement(Map<String, Object> result) {
		List<Map<String, Object>> elements = (List<Map<String, Object>>) result.get("elements");
		assertEquals(1, elements.size(), "Expected exactly one element, got " + elements);
		return elements.get(0);
	}

	private void createFixtureProject() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		TestFixture.addType(javaProject, "example", "Documented", """
				package example;

				public class Documented {
					/**
					 * Greets somebody.
					 */
					public String greet(String name) {
						return "Hello " + name;
					}

					public void overloaded(String value) {
					}

					public void overloaded(int value) {
					}

					public void overloaded() {
					}

					static class Inner {
					}

					public <T> void generic(T value) {
					}
				}
				""");
		TestFixture.addType(javaProject, "example", "Dates", """
				package example;

				import java.util.Date;

				public class Dates {
					public int set(Date day) {
						return 1;
					}

					public int set(java.sql.Date day) {
						return 2;
					}
				}
				""");
		TestFixture.build(javaProject.getProject());
	}
}
