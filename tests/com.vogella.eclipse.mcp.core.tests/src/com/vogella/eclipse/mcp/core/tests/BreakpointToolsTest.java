package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * The breakpoint tools need no debug session, so they are covered fully headless.
 */
class BreakpointToolsTest {

	private static final String PROJECT = "mcp-breakpoints-test";

	private final TestFixture fixture = new TestFixture();

	@BeforeEach
	void createProject() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "sample", "Main", """
				package sample;
				public class Main {
					public static void main(String[] args) {
						System.out.println("line four");
						System.out.println("line five");
					}
				}
				""");
		TestFixture.build(project.getProject());
	}

	@AfterEach
	void deleteTestProjectsAndBreakpoints() throws Exception {
		fixture.dispose();
		// the exception breakpoint attaches to the workspace root, which outlives the
		// projects, so it would leak into every other method's list assertions
		for (org.eclipse.debug.core.model.IBreakpoint breakpoint : org.eclipse.debug.core.DebugPlugin.getDefault()
				.getBreakpointManager().getBreakpoints()) {
			if (breakpoint instanceof org.eclipse.jdt.debug.core.IJavaBreakpoint) {
				breakpoint.delete();
			}
		}
	}

	@Test
	void setsListsAndRemovesALineBreakpoint() throws Exception {
		Map<String, Object> answer = TestFixture.callAndParse("eclipse_set_breakpoint",
				Map.of("type", "sample.Main", "line", Integer.valueOf(4)));

		assertEquals(Boolean.TRUE, answer.get("created"), String.valueOf(answer));
		assertEquals(Boolean.FALSE, answer.get("updated"));
		String id = (String) answer.get("id");
		assertNotNull(id);
		assertEquals("sample.Main", answer.get("typeName"));
		assertEquals(Integer.valueOf(4), ((Number) answer.get("line")).intValue());
		assertEquals("line", answer.get("kind"));
		assertEquals(Boolean.TRUE, answer.get("enabled"));
		assertEquals("thread", answer.get("suspendPolicy"));
		assertTrue(String.valueOf(answer.get("resource")).endsWith("Main.java"), String.valueOf(answer));

		Map<String, Object> listed = TestFixture.callAndParse("eclipse_list_breakpoints", Map.of());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> breakpoints = (List<Map<String, Object>>) listed.get("breakpoints");
		assertEquals(1, breakpoints.size(), String.valueOf(listed));
		assertEquals(id, breakpoints.get(0).get("id"), "the id must survive a listing");

		Map<String, Object> removed = TestFixture.callAndParse("eclipse_set_breakpoint",
				Map.of("id", id, "remove", Boolean.TRUE));
		assertEquals(Boolean.TRUE, removed.get("removed"));

		listed = TestFixture.callAndParse("eclipse_list_breakpoints", Map.of());
		assertEquals(Integer.valueOf(0), ((Number) listed.get("total")).intValue(), String.valueOf(listed));
	}

	@Test
	void settingTheSameBreakpointAgainUpdatesInsteadOfDuplicating() throws Exception {
		Map<String, Object> first = TestFixture.callAndParse("eclipse_set_breakpoint",
				Map.of("type", "sample.Main", "line", Integer.valueOf(4)));
		Map<String, Object> second = TestFixture.callAndParse("eclipse_set_breakpoint",
				Map.of("type", "sample.Main", "line", Integer.valueOf(4)));

		assertEquals(Boolean.TRUE, first.get("created"), String.valueOf(first));
		assertEquals(Boolean.TRUE, second.get("updated"), String.valueOf(second));
		assertEquals(first.get("id"), second.get("id"));

		Map<String, Object> listed = TestFixture.callAndParse("eclipse_list_breakpoints", Map.of());
		assertEquals(Integer.valueOf(1), ((Number) listed.get("total")).intValue(),
				"a repeated set must not duplicate: " + listed);
	}

	@Test
	void updatesCarryConditionHitCountAndSuspendPolicy() throws Exception {
		Map<String, Object> created = TestFixture.callAndParse("eclipse_set_breakpoint",
				Map.of("type", "sample.Main", "line", Integer.valueOf(4)));

		Map<String, Object> updated = TestFixture.callAndParse("eclipse_set_breakpoint",
				Map.of("id", created.get("id"), "condition", "args.length == 0", "hitCount", Integer.valueOf(3),
						"suspendPolicy", "vm"));

		assertEquals("args.length == 0", updated.get("condition"));
		assertEquals(Integer.valueOf(3), ((Number) updated.get("hitCount")).intValue());
		assertEquals("vm", updated.get("suspendPolicy"));
	}

	@Test
	void refusesAMoveOfAnExistingBreakpointToAnotherLine() throws Exception {
		Map<String, Object> created = TestFixture.callAndParse("eclipse_set_breakpoint",
				Map.of("type", "sample.Main", "line", Integer.valueOf(4)));

		McpToolResult moved = TestFixture.call("eclipse_set_breakpoint",
				Map.of("id", created.get("id"), "line", Integer.valueOf(5)));

		assertTrue(moved.isError());
		assertTrue(moved.text().contains("line 4"), moved.text());
	}

	@Test
	void setsAnExceptionBreakpointWithItsDefaults() throws Exception {
		Map<String, Object> answer = TestFixture.callAndParse("eclipse_set_breakpoint",
				Map.of("exception", "java.lang.RuntimeException"));

		assertEquals("exception", answer.get("kind"));
		assertEquals("java.lang.RuntimeException", answer.get("typeName"));
		assertEquals(Boolean.FALSE, answer.get("caught"), "caught defaults to false");
		assertEquals(Boolean.TRUE, answer.get("uncaught"), "uncaught defaults to true");
	}

	@Test
	void reportsAnUninstalledBreakpointRatherThanCallingItSuccess() throws Exception {
		Map<String, Object> answer = TestFixture.callAndParse("eclipse_set_breakpoint",
				Map.of("type", "sample.Main", "line", Integer.valueOf(4)));

		if (Boolean.FALSE.equals(answer.get("installed"))) {
			assertNotNull(answer.get("note"), "an uninstalled breakpoint has to say why it will never be hit");
		}
	}

	@Test
	void filtersByTypeName() throws Exception {
		TestFixture.callAndParse("eclipse_set_breakpoint", Map.of("type", "sample.Main", "line", Integer.valueOf(4)));
		Map<String, Object> none = TestFixture.callAndParse("eclipse_list_breakpoints",
				Map.of("filter", "org.eclipse.swt"));
		assertEquals(Integer.valueOf(0), ((Number) none.get("total")).intValue());

		Map<String, Object> some = TestFixture.callAndParse("eclipse_list_breakpoints", Map.of("filter", "main"));
		assertEquals(Integer.valueOf(1), ((Number) some.get("total")).intValue(), String.valueOf(some));
	}

	@Test
	void removingWithoutALineIsRefusedAndDeletesNothing() throws Exception {
		TestFixture.callAndParse("eclipse_set_breakpoint", Map.of("type", "sample.Main", "line", Integer.valueOf(4)));
		TestFixture.callAndParse("eclipse_set_breakpoint", Map.of("type", "sample.Main", "line", Integer.valueOf(5)));

		McpToolResult result = TestFixture.call("eclipse_set_breakpoint",
				Map.of("type", "sample.Main", "remove", Boolean.TRUE));

		assertTrue(result.isError(), result.text());
		Map<String, Object> listed = TestFixture.callAndParse("eclipse_list_breakpoints", Map.of());
		assertEquals(2, ((Number) listed.get("total")).intValue(), String.valueOf(listed));
	}

	@Test
	void anInvalidSuspendPolicyIsRefusedBeforeAnythingIsCreated() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_set_breakpoint",
				Map.of("type", "sample.Main", "line", Integer.valueOf(4), "suspendPolicy", "bogus"));

		assertTrue(result.isError(), result.text());
		Map<String, Object> listed = TestFixture.callAndParse("eclipse_list_breakpoints", Map.of());
		assertEquals(0, ((Number) listed.get("total")).intValue(), String.valueOf(listed));
	}

	@Test
	void refusesArgumentsThatNameNothing() throws Exception {
		McpToolResult nothing = TestFixture.call("eclipse_set_breakpoint", Map.of());
		assertTrue(nothing.isError());

		McpToolResult both = TestFixture.call("eclipse_set_breakpoint",
				Map.of("type", "sample.Main", "line", Integer.valueOf(4), "exception", "java.lang.RuntimeException"));
		assertTrue(both.isError());
		assertTrue(both.text().contains("mutually exclusive"), both.text());

		McpToolResult unknownId = TestFixture.call("eclipse_set_breakpoint",
				Map.of("id", "bp-999999", "remove", Boolean.TRUE));
		assertTrue(unknownId.isError());
		assertFalse(unknownId.text().isBlank());
	}
}
