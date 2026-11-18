package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameRequestor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * JDT's quick fixes, computed and applied through jdt.core.manipulation without JDT UI.
 */
class QuickFixToolsTest {

	private static final String LIST = "eclipse_get_quick_fixes";

	private static final String APPLY = "eclipse_apply_quick_fix";

	private static final String PROJECT = "mcp-quickfix-test";

	private static final String PATH = "/" + PROJECT + "/src/example/Holder.java";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void importsAnUnresolvedType() throws Exception {
		IFile file = holder("""
				package example;
				public class Holder {
					List<String> names;
				}
				""");

		Map<String, Object> problem = problem(IProblem.UndefinedType);
		String label = fixContaining(problem, "java.util");

		Map<String, Object> dry = apply(problem, label, true);
		assertTrue(String.valueOf(dry.get("changes")).contains("import java.util.List;"), "got " + dry);
		assertFalse(TestFixture.read(file).contains("import java.util.List;"), "a dry run must not change the file");

		Map<String, Object> applied = apply(problem, label, false);
		assertTrue(TestFixture.read(file).contains("import java.util.List;"), TestFixture.read(file));
		assertEquals(List.of(), applied.get("remainingErrors"), "got " + applied);
	}

	@Test
	void addsAThrowsDeclarationForAnUnhandledException() throws Exception {
		IFile file = holder("""
				package example;
				public class Holder {
					void load() {
						Thread.sleep(10);
					}
				}
				""");

		Map<String, Object> problem = problem(IProblem.UnhandledException);
		assertNotNull(fixContaining(problem, "try/catch"), "surround with try/catch should be offered: " + problem);
		apply(problem, fixContaining(problem, "throws"), false);

		assertTrue(TestFixture.read(file).contains("void load() throws InterruptedException"), TestFixture.read(file));
	}

	@Test
	void addsUnimplementedMethods() throws Exception {
		IFile file = holder("""
				package example;
				public class Holder implements Runnable {
				}
				""");

		Map<String, Object> problem = problem(IProblem.AbstractMethodMustBeImplemented);
		Map<String, Object> applied = apply(problem, fixContaining(problem, "unimplemented"), false);

		assertTrue(TestFixture.read(file).contains("public void run()"), TestFixture.read(file));
		assertEquals(List.of(), applied.get("remainingErrors"), "got " + applied);
	}

	@Test
	void createsAMissingMethod() throws Exception {
		IFile file = holder("""
				package example;
				public class Holder {
					int size() {
						return count("a");
					}
				}
				""");

		Map<String, Object> problem = problem(IProblem.UndefinedMethod);
		Map<String, Object> applied = apply(problem, fixContaining(problem, "count(String)"), false);

		assertTrue(TestFixture.read(file).contains("count(String"), TestFixture.read(file));
		assertEquals(List.of(), applied.get("remainingErrors"), "got " + applied);
	}

	@Test
	void removesAnUnusedImport() throws Exception {
		IFile file = holder("""
				package example;
				import java.util.Map;
				public class Holder {
				}
				""");

		Map<String, Object> problem = problem(IProblem.UnusedImport);
		apply(problem, fixContaining(problem, "import"), false);

		assertFalse(TestFixture.read(file).contains("java.util.Map"), TestFixture.read(file));
	}

	@Test
	void refusesAnUnknownFixWithTheOffered() throws Exception {
		holder("""
				package example;
				public class Holder {
					List<String> names;
				}
				""");
		Map<String, Object> problem = problem(IProblem.UndefinedType);

		Map<String, Object> arguments = new HashMap<>(target(problem));
		arguments.put("fix", "Invented fix");
		McpToolResult result = TestFixture.call(APPLY, arguments);

		assertTrue(result.isError());
		assertTrue(result.text().contains("java.util"), "the refusal should list the offered fixes: " + result.text());
	}

	@Test
	void asksForAnOffsetWhenALineHasTwoSuchProblems() throws Exception {
		holder("""
				package example;
				public class Holder {
					List<String> first; List<String> second;
				}
				""");
		Map<String, Object> problem = problem(IProblem.UndefinedType);

		Map<String, Object> arguments = new HashMap<>(Map.of("path", PATH, "problemId", problem.get("problemId"),
				"line", problem.get("line"), "fix", fixContaining(problem, "java.util")));
		McpToolResult result = TestFixture.call(APPLY, arguments);

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("offset"), "the refusal should ask for an offset: " + result.text());
	}

	@Test
	void listsOnlyAsManyProblemsAsAsked() throws Exception {
		holder("""
				package example;
				public class Holder {
					List<String> names;
					Set<String> keys;
				}
				""");

		Map<String, Object> one = TestFixture.callAndParse(LIST, Map.of("path", PATH, "maxResults", Integer.valueOf(1)));
		assertEquals(1, ((List<?>) one.get("problems")).size(), "got " + one);
		assertEquals(Boolean.TRUE, one.get("truncated"), "got " + one);
		assertEquals(2, ((Number) one.get("total")).intValue(), "got " + one);
	}

	@Test
	void leavesOutWarningsWhenAsked() throws Exception {
		holder("""
				package example;
				import java.util.Map;
				public class Holder {
				}
				""");

		Map<String, Object> errorsOnly = TestFixture.callAndParse(LIST,
				Map.of("path", PATH, "includeWarnings", Boolean.FALSE));
		assertEquals(List.of(), errorsOnly.get("problems"), "the unused import is a warning: " + errorsOnly);
	}

	@Test
	@SuppressWarnings("unchecked")
	void raisesTheProjectComplianceForANewerFeature() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		Map<String, String> options = new HashMap<>(project.getOptions(false));
		JavaCore.setComplianceOptions(JavaCore.VERSION_1_8, options);
		project.setOptions(options);
		TestFixture.addType(project, "example", "Holder", """
				package example;
				public class Holder {
					int size(int x) {
						switch (x) {
						case 1, 2:
							return 1;
						default:
							return 0;
						}
					}
				}
				""");

		Map<String, Object> listed = TestFixture.callAndParse(LIST, Map.of("path", PATH));
		Map<String, Object> problem = null;
		String label = null;
		for (Object entry : (List<Object>) listed.get("problems")) {
			for (Object fix : (List<Object>) ((Map<String, Object>) entry).get("fixes")) {
				String candidate = String.valueOf(((Map<String, Object>) fix).get("label"));
				if (candidate.startsWith("Change project compliance to ")) {
					problem = (Map<String, Object>) entry;
					label = candidate;
				}
			}
		}
		assertNotNull(label, "a compliance fix should be offered: " + listed);
		assertFalse(String.valueOf(listed).contains("workspace compliance"), "got " + listed);

		Map<String, Object> dry = apply(problem, label, true);
		String changes = String.valueOf(dry.get("changes"));
		assertTrue(changes.contains(JavaCore.COMPILER_COMPLIANCE) && changes.contains(PROJECT), "got " + dry);

		apply(problem, label, false);
		assertFalse(JavaCore.VERSION_1_8.equals(project.getOption(JavaCore.COMPILER_COMPLIANCE, false)),
				"the project compliance should have been raised");
	}

	@Test
	void addsAMissingJavadocComment() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		Map<String, String> options = new HashMap<>(project.getOptions(false));
		options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
		options.put(JavaCore.COMPILER_PB_MISSING_JAVADOC_COMMENTS, JavaCore.WARNING);
		options.put(JavaCore.COMPILER_PB_MISSING_JAVADOC_COMMENTS_VISIBILITY, JavaCore.PUBLIC);
		project.setOptions(options);
		IFile file = TestFixture.addType(project, "example", "Holder", """
				package example;
				/** Holds. */
				public class Holder {
					public int size(int x) {
						return x;
					}
				}
				""");

		Map<String, Object> problem = problem(IProblem.JavadocMissing);
		apply(problem, fixContaining(problem, "Javadoc"), false);

		assertTrue(TestFixture.read(file).contains("@param x"), TestFixture.read(file));
	}

	private IFile holder(String source) throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		IFile file = TestFixture.addType(project, "example", "Holder", source);
		// the import proposals come from the type index, which a fresh workspace is still building
		new SearchEngine().searchAllTypeNames(null, SearchPattern.R_EXACT_MATCH, "List".toCharArray(),
				SearchPattern.R_EXACT_MATCH, IJavaSearchConstants.TYPE, SearchEngine.createWorkspaceScope(),
				new TypeNameRequestor() {
					// only waiting
				}, IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, null);
		return file;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> problem(int problemId) throws Exception {
		Map<String, Object> listed = TestFixture.callAndParse(LIST, Map.of("path", PATH));
		for (Object entry : (List<Object>) listed.get("problems")) {
			Map<String, Object> problem = (Map<String, Object>) entry;
			if (((Number) problem.get("problemId")).intValue() == problemId) {
				return problem;
			}
		}
		throw new AssertionError("no problem " + problemId + " in " + listed);
	}

	@SuppressWarnings("unchecked")
	private static String fixContaining(Map<String, Object> problem, String text) {
		for (Object entry : (List<Object>) problem.get("fixes")) {
			String label = String.valueOf(((Map<String, Object>) entry).get("label"));
			if (label.contains(text)) {
				return label;
			}
		}
		throw new AssertionError("no fix containing '" + text + "' in " + problem);
	}

	private static Map<String, Object> target(Map<String, Object> problem) {
		return Map.of("path", PATH, "problemId", problem.get("problemId"), "offset", problem.get("offset"));
	}

	private static Map<String, Object> apply(Map<String, Object> problem, String label, boolean dryRun)
			throws Exception {
		Map<String, Object> arguments = new HashMap<>(target(problem));
		arguments.put("fix", label);
		arguments.put("dryRun", Boolean.valueOf(dryRun));
		return TestFixture.callAndParse(APPLY, arguments);
	}
}
