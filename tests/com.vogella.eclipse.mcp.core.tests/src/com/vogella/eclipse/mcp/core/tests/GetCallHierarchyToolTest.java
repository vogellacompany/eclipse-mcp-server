package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

	private void fixtureProject() throws Exception {
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
	}
}
