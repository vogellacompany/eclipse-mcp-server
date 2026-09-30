package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.UiDispatch;

class SetPreferenceToolTest {

	private static final String TOOL = "eclipse_set_preference";

	/** A qualifier with a key nothing else uses. */
	private static final String QUALIFIER = "org.eclipse.core.runtime";

	/** One the old allowlist refused, to show that it no longer does. */
	private static final String OTHER_QUALIFIER = "org.eclipse.ui.editors";

	private static final String KEY = "com.vogella.eclipse.mcp.test.key";

	private static final String THEME_QUALIFIER = "org.eclipse.e4.ui.css.swt.theme";

	private static final String THEME_KEY = "com.vogella.eclipse.mcp.test.theme.key";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void cleanUp() throws Exception {
		InstanceScope.INSTANCE.getNode(QUALIFIER).remove(KEY);
		InstanceScope.INSTANCE.getNode(QUALIFIER).flush();
		InstanceScope.INSTANCE.getNode(OTHER_QUALIFIER).remove(KEY);
		InstanceScope.INSTANCE.getNode(OTHER_QUALIFIER).flush();
		InstanceScope.INSTANCE.getNode(THEME_QUALIFIER).remove(THEME_KEY);
		InstanceScope.INSTANCE.getNode(THEME_QUALIFIER).remove("themeid");
		InstanceScope.INSTANCE.getNode(THEME_QUALIFIER).flush();
		fixture.dispose();
	}

	@Test
	void writesThroughTheRegisteredUiExecutor() throws Exception {
		// a preference write fires its listeners on the writing thread, and editors
		// answer with widget calls, so the write has to go where the UI registered it
		AtomicInteger dispatched = new AtomicInteger();
		UiDispatch.Executor recording = new UiDispatch.Executor() {
			@Override
			public <T> T call(Callable<T> work, int timeoutSeconds) throws Exception {
				dispatched.incrementAndGet();
				return work.call();
			}
		};
		UiDispatch.set(recording);
		try {
			Map<String, Object> result = TestFixture.callAndParse(TOOL,
					Map.of("qualifier", QUALIFIER, "key", KEY, "value", "dispatched"));
			assertEquals("dispatched", result.get("effective"));
			assertEquals(1, dispatched.get(), "the write has to go through the executor exactly once");

			UiDispatch.set(new UiDispatch.Executor() {
				@Override
				public <T> T call(Callable<T> work, int timeoutSeconds) throws Exception {
					throw new TimeoutException();
				}
			});
			McpToolResult timedOut = TestFixture.call(TOOL, Map.of("qualifier", QUALIFIER, "key", KEY, "value", "late"));
			assertTrue(timedOut.isError() && timedOut.text().contains("eclipse_get_preferences"),
					"a UI that did not get to it has to be an answer, got " + timedOut.text());
		} finally {
			UiDispatch.set(null);
		}
	}

	@Test
	void writesAValueAndReportsTheAbsentPrevious() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("qualifier", QUALIFIER, "key", KEY, "value", "first"));

		assertNull(result.get("previousValue"));
		assertEquals("first", result.get("effective"));
		assertEquals("instance", result.get("effectiveScope"));
		assertEquals("first", InstanceScope.INSTANCE.getNode(QUALIFIER).get(KEY, null));
	}

	@Test
	void returnsThePreviousValueSoTheChangeCanBeUndone() throws Exception {
		TestFixture.callAndParse(TOOL, Map.of("qualifier", QUALIFIER, "key", KEY, "value", "first"));

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("qualifier", QUALIFIER, "key", KEY, "value", "second"));

		assertEquals("first", result.get("previousValue"));
		assertEquals("second", result.get("effective"));
	}

	@Test
	void removesTheKeyWhenNoValueIsGiven() throws Exception {
		TestFixture.callAndParse(TOOL, Map.of("qualifier", QUALIFIER, "key", KEY, "value", "first"));

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("qualifier", QUALIFIER, "key", KEY));

		assertEquals("first", result.get("previousValue"));
		assertNull(result.get("effective"));
		assertNull(InstanceScope.INSTANCE.getNode(QUALIFIER).get(KEY, null));
	}

	@Test
	void writesAQualifierNoListEverNamed() throws Exception {
		// there is no allowlist: it stopped legitimate work while eclipse_write_file
		// and the IEclipsePreferences blocks of eclipse_apply_css wrote whatever they
		// liked. What is left is that the answer carries the previous value
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("qualifier", OTHER_QUALIFIER, "key", KEY, "value", "written"));

		assertEquals("written", result.get("effective"), "got " + result);
		assertNull(result.get("previousValue"), "the key did not exist before");
		assertEquals("written", InstanceScope.INSTANCE.getNode(OTHER_QUALIFIER).get(KEY, null));
	}

	@Test
	void refusesTheThemeidKeyAndNamesTheToolThatDoesItInstead() throws Exception {
		McpToolResult result = TestFixture.call(TOOL,
				Map.of("qualifier", THEME_QUALIFIER, "key", "themeid", "value", "some.theme"));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("themeid"), result.text());
		assertTrue(result.text().contains("eclipse_set_theme"), result.text());
		assertNull(InstanceScope.INSTANCE.getNode(THEME_QUALIFIER).get("themeid", null));
	}

	@Test
	void stillWritesAnotherKeyInTheSameThemeQualifier() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("qualifier", THEME_QUALIFIER, "key", THEME_KEY, "value", "kept"));

		assertEquals("kept", result.get("effective"));
		assertEquals("kept", InstanceScope.INSTANCE.getNode(THEME_QUALIFIER).get(THEME_KEY, null));
	}

	@Test
	void rejectsAProjectScopeWithoutAProject() throws Exception {
		McpToolResult result = TestFixture.call(TOOL,
				Map.of("qualifier", QUALIFIER, "key", KEY, "value", "x", "scope", "project"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("project"), result.text());
	}

	@Test
	void appliesAutoBuildThroughTheWorkspaceDescription() throws Exception {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		boolean before = workspace.isAutoBuilding();
		try {
			Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("qualifier",
					"org.eclipse.core.resources", "key", "description.autobuilding", "value", String.valueOf(!before)));

			assertEquals("IWorkspaceDescription.setAutoBuilding", result.get("appliedThrough"));
			assertEquals(String.valueOf(!before), result.get("effective"));
			assertEquals(!before, workspace.isAutoBuilding());
		} finally {
			IWorkspaceDescription description = workspace.getDescription();
			description.setAutoBuilding(before);
			workspace.setDescription(description);
		}
	}

	@Test
	void rejectsANonBooleanAutoBuildValue() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("qualifier", "org.eclipse.core.resources", "key",
				"description.autobuilding", "value", "sometimes"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("sometimes"), result.text());
	}

	@Test
	void writesIntoTheProjectScope() throws Exception {
		var project = fixture.createProject("mcp-set-preference-test");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("qualifier", QUALIFIER, "key", KEY, "value",
				"project-value", "scope", "project", "project", project.getName()));

		assertEquals("project", result.get("effectiveScope"));
		assertEquals("project-value", result.get("effective"));
	}
}
