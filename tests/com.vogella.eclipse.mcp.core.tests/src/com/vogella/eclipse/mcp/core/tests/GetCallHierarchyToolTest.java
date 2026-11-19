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

	@Test
	void listsTheCalleesOfAMethodOncePerTargetWithTheirCount() throws Exception {
		fixtureProject();

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("typeName", "example.Caller", "memberName",
				"a", "project", PROJECT, "direction", "callees", "depth", Integer.valueOf(1)));

		List<?> callees = (List<?>) result.get("callees");
		Map<?, ?> target = callees.stream().map(Map.class::cast)
				.filter(node -> "example.Target.target()".equals(node.get("callee"))).findFirst().orElseThrow(
						() -> new AssertionError("Target.target() missing in " + result));
		assertEquals(Integer.valueOf(3), target.get("calls"), "got " + target);
		assertEquals(Integer.valueOf(5), target.get("line"), "the first call, got " + target);
		assertFalse(result.containsKey("callers"), "callers were not asked for, got " + result);
	}

	@Test
	void followsCalleesIntoSourceToTheRequestedDepthAndHidesJdkCalls() throws Exception {
		fixtureProject();

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("typeName", "example.Caller", "memberName",
				"b", "project", PROJECT, "direction", "callees", "depth", Integer.valueOf(2)));

		Map<?, ?> a = (Map<?, ?>) ((List<?>) result.get("callees")).get(0);
		assertEquals("example.Caller.a()", a.get("callee"), "got " + result);
		List<?> deeper = (List<?>) a.get("callees");
		assertTrue(deeper.stream().map(Map.class::cast).anyMatch(n -> "example.Target.target()".equals(n.get("callee"))),
				"a calls target, got " + a);
		assertTrue(deeper.stream().map(Map.class::cast).noneMatch(n -> String.valueOf(n.get("callee")).startsWith("java.")),
				"JDK calls are hidden by default, got " + a);
		assertTrue(((Number) result.get("jdkCallsHidden")).intValue() > 0, "a builds a StringBuilder, got " + result);
	}

	@Test
	void listsTheCalleesOfEveryOverloadEvenWhenOneCallsAnother() throws Exception {
		IJavaProject project = fixtureProject();
		TestFixture.addType(project, "example", "Overloads", """
				package example;
				public class Overloads {
					void run() { run(1); }
					void run(int times) { new Target().target(); }
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("typeName", "example.Overloads",
				"memberName", "run", "project", PROJECT, "direction", "callees", "depth", Integer.valueOf(2)));

		List<Map<?, ?>> callees = ((List<?>) result.get("callees")).stream().<Map<?, ?>>map(n -> (Map<?, ?>) n).toList();
		assertTrue(callees.stream().anyMatch(n -> "run()".equals(n.get("from"))
				&& "example.Overloads.run(int)".equals(n.get("callee"))), "got " + result);
		assertTrue(callees.stream().anyMatch(n -> "run(int)".equals(n.get("from"))
				&& "example.Target.target()".equals(n.get("callee"))), "run(int) is walked as a root too, got " + result);
	}

	@Test
	void saysWhyAnAbstractMethodHasNoCalleesInsteadOfAnsweringEmpty() throws Exception {
		IJavaProject project = fixtureProject();
		TestFixture.addType(project, "example", "Port", """
				package example;
				public interface Port {
					void send();
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("typeName", "example.Port", "memberName",
				"send", "project", PROJECT, "direction", "callees"));

		Map<?, ?> entry = (Map<?, ?>) ((List<?>) result.get("withoutBody")).get(0);
		assertEquals("example.Port.send()", entry.get("method"), "got " + result);
		assertEquals("abstract", entry.get("reason"), "got " + result);
	}

	@Test
	void reportsBothDirectionsInOneCall() throws Exception {
		fixtureProject();

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("typeName", "example.Caller", "memberName",
				"a", "project", PROJECT, "direction", "both", "depth", Integer.valueOf(1)));

		Map<?, ?> caller = (Map<?, ?>) ((List<?>) result.get("callers")).get(0);
		assertEquals("example.Caller.b()", caller.get("caller"), "got " + result);
		assertEquals(Integer.valueOf(10), caller.get("line"), "got " + caller);
		assertFalse(((List<?>) result.get("callees")).isEmpty(), "got " + result);
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
						new StringBuilder().append(t).toString();
					}
					void b() { a(); }
				}
				""");
		TestFixture.build(project.getProject());
		return project;
	}
}
