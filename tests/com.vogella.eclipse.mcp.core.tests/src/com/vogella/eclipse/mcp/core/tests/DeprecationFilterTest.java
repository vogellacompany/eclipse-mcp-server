package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reporting deprecation from the Java model rather than from a text search.
 * <p>
 * The fixture is built around the case that makes text search wrong: the
 * annotation and the Javadoc tag do not have to agree, and each of the two
 * alone answers a different question.
 */
class DeprecationFilterTest {

	private static final String TOOL = "eclipse_list_declarations";

	private static final String PROJECT = "mcp-deprecation-test";

	private final TestFixture fixture = new TestFixture();

	@BeforeEach
	void createTheFixture() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		TestFixture.addType(javaProject, "api", "BothMarks", """
				package api;
				/**
				 * A type carrying both marks.
				 *
				 * @deprecated Use {@link Current} instead.
				 * @since 3.0
				 */
				@Deprecated(since = "4.2", forRemoval = true)
				public class BothMarks {
				}
				""");
		TestFixture.addType(javaProject, "api", "AnnotationOnly", """
				package api;
				@Deprecated
				public class AnnotationOnly {
				}
				""");
		TestFixture.addType(javaProject, "api", "JavadocOnly", """
				package api;
				/**
				 * @deprecated Replaced by
				 *    nothing at all.
				 */
				public class JavadocOnly {
				}
				""");
		TestFixture.addType(javaProject, "api", "Current", """
				package api;
				public class Current {
				}
				""");
	}

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void bothMarksAreReportedSeparatelyWithTheReplacementAdvice() throws Exception {
		Map<String, Object> entry = find(all(), "api.BothMarks");

		assertEquals(Boolean.TRUE, entry.get("deprecated"), "got " + entry);
		assertEquals(List.of("annotation", "javadoc"), entry.get("deprecatedBy"));
		assertEquals(Boolean.TRUE, entry.get("forRemoval"));
		assertEquals("4.2", entry.get("deprecatedSince"));
		assertEquals("Use {@link Current} instead.", entry.get("deprecationNote"), "got " + entry);
		// @since follows the tag and describes something else entirely
		assertFalse(String.valueOf(entry.get("deprecationNote")).contains("3.0"), "got " + entry);
		assertNull(entry.get("deprecationMismatch"), "the two marks agree here");
	}

	@Test
	void anAnnotationWithoutTheTagSaysItsCommitDoesNotDateTheDeprecation() throws Exception {
		Map<String, Object> entry = find(all(), "api.AnnotationOnly");

		assertEquals(List.of("annotation"), entry.get("deprecatedBy"), "got " + entry);
		assertTrue(String.valueOf(entry.get("deprecationMismatch")).contains("bulk sweep"), "got " + entry);
	}

	@Test
	void aTagWithoutTheAnnotationSaysNoCallSiteIsWarned() throws Exception {
		Map<String, Object> entry = find(all(), "api.JavadocOnly");

		assertEquals(List.of("javadoc"), entry.get("deprecatedBy"), "got " + entry);
		assertEquals("Replaced by nothing at all.", entry.get("deprecationNote"), "the lines join without their stars");
		assertTrue(String.valueOf(entry.get("deprecationMismatch")).contains("no deprecation warning"), "got " + entry);
	}

	@Test
	void whatIsNotDeprecatedCarriesNoneOfTheseFields() throws Exception {
		Map<String, Object> entry = find(all(), "api.Current");

		assertNull(entry.get("deprecated"), "got " + entry);
		assertNull(entry.get("deprecatedBy"));
	}

	@Test
	void eachFilterSelectsExactlyItsOwnCase() throws Exception {
		assertEquals(List.of("api.AnnotationOnly", "api.BothMarks", "api.JavadocOnly"), names(filtered("yes")));
		assertEquals(List.of("api.Current"), names(filtered("no")));
		assertEquals(List.of("api.BothMarks"), names(filtered("forRemoval")));
		assertEquals(List.of("api.AnnotationOnly"), names(filtered("annotationOnly")));
		assertEquals(List.of("api.JavadocOnly"), names(filtered("javadocOnly")));
	}

	private static Map<String, Object> all() throws Exception {
		return TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "maxResults", 5000));
	}

	private static Map<String, Object> filtered(String deprecated) throws Exception {
		return TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "deprecated", deprecated, "maxResults", 5000));
	}

	private static List<String> names(Map<String, Object> result) {
		return ((List<?>) result.get("declarations")).stream().map(entry -> ((Map<?, ?>) entry).get("name"))
				.map(String::valueOf).sorted().toList();
	}

	private static Map<String, Object> find(Map<String, Object> result, String name) {
		for (Object entry : (List<?>) result.get("declarations")) {
			if (entry instanceof Map<?, ?> map && name.equals(map.get("name"))) {
				@SuppressWarnings("unchecked")
				Map<String, Object> typed = (Map<String, Object>) map;
				return typed;
			}
		}
		throw new AssertionError("No declaration named %s in %s".formatted(name, result));
	}
}
