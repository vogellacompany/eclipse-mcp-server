package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GetCallHierarchyToolTest {

	private static final String TOOL = "eclipse_get_call_hierarchy";

	private static final String PROJECT = "mcp-call-hierarchy-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void aCallerThatCallsTheTargetSeveralTimesCountsOnceAndIsNotTruncated() throws Exception {
		fixtureProject();

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("typeName", "example.Target", "memberName",
				"target", "project", PROJECT, "depth", Integer.valueOf(1), "maxResults", Integer.valueOf(1)));

		assertEquals(1, ((List<?>) result.get("callers")).size(), "got " + result);
		assertEquals(Integer.valueOf(1), result.get("total"));
		assertEquals(Boolean.FALSE, result.get("truncated"), "nothing was dropped, got " + result);
	}

	@Test
	void followsCallersToTheRequestedDepth() throws Exception {
		fixtureProject();

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("typeName", "example.Target", "memberName",
				"target", "project", PROJECT, "depth", Integer.valueOf(2)));

		Map<?, ?> first = (Map<?, ?>) ((List<?>) result.get("callers")).get(0);
		assertEquals("example.Caller.a()", first.get("caller"));
		assertEquals(1, ((List<?>) first.get("callers")).size(), "b calls a, got " + first);
	}

	@Test
	void reportsTruncationWhenTheBudgetDropsANode() throws Exception {
		fixtureProject();

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("typeName", "example.Target", "memberName",
				"target", "project", PROJECT, "depth", Integer.valueOf(2), "maxResults", Integer.valueOf(1)));

		assertEquals(Boolean.TRUE, result.get("truncated"), "the second level did not fit, got " + result);
	}

	@Test
	void refreshesBeforeSearchingUnlessToldNotTo() throws Exception {
		IJavaProject project = fixtureProject();
		// written the way a client's own shell writes, so the workspace has not seen it
		Files.writeString(project.getProject().getLocation().toPath().resolve("src/example/Outside.java"), """
				package example;
				public class Outside {
					void c() { new Target().target(); }
				}
				""");

		Map<String, Object> skipped = TestFixture.callAndParse(TOOL, Map.of("typeName", "example.Target",
				"memberName", "target", "project", PROJECT, "depth", Integer.valueOf(1), "refresh", false));
		assertEquals(1, ((List<?>) skipped.get("callers")).size(), "got " + skipped);
		assertEquals(Boolean.FALSE, skipped.get("refreshed"), "got " + skipped);
		assertFalse(skipped.containsKey("refreshMillis"), "got " + skipped);

		Map<String, Object> refreshed = TestFixture.callAndParse(TOOL, Map.of("typeName", "example.Target",
				"memberName", "target", "project", PROJECT, "depth", Integer.valueOf(1)));
		assertEquals(2, ((List<?>) refreshed.get("callers")).size(), "got " + refreshed);
		assertEquals(Boolean.TRUE, refreshed.get("refreshed"), "got " + refreshed);
		assertTrue(refreshed.get("refreshMillis") instanceof Number, "got " + refreshed);
	}

	private IJavaProject fixtureProject() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "example", "Target", """
				package example;
				public class Target {
					public void target() { }
				}
				""");
		TestFixture.addType(project, "example", "Caller", """
				package example;
				public class Caller {
					void a() {
						Target t = new Target();
						t.target();
						t.target();
						t.target();
					}
					void b() { a(); }
				}
				""");
		TestFixture.build(project.getProject());
		return project;
	}
}
