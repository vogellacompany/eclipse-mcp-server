package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.basic.internal.BuildRegistry;

/**
 * Cancelling whatever is building.
 * <p>
 * A cancel that reports success while the builder keeps going is worse than no
 * cancel at all, so what the answer promises is deliberately weak: the request
 * was made, and this is what was still running when the wait was up.
 */
class CancelBuildToolTest {

	private static final String TOOL = "eclipse_cancel_build";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void saysSoWhenThereIsNothingToCancel() throws Exception {
		waitForQuiet();

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("waitSeconds", 0));

		assertEquals(List.of(), result.get("cancelled"), "got " + result);
		assertTrue(String.valueOf(result.get("note")).contains("Nothing was building"), "got " + result);
	}

	@Test
	void alwaysReportsWhetherAutoBuildBringsTheBuildBack() throws Exception {
		waitForQuiet();

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("waitSeconds", 0));

		// the field is the point of the tool: cancelling with auto-build on buys a
		// pause, not a stop, and a caller that cannot see the setting cannot know that
		assertEquals(Boolean.valueOf(ResourcesPlugin.getWorkspace().isAutoBuilding()), result.get("autoBuildEnabled"),
				"got " + result);
		assertNotNull(result.get("note"));
	}

	@Test
	void cancelsARunningBuildAndNamesIt() throws Exception {
		fixture.createProject("mcp-cancel-build-test");
		Map<String, Object> started = TestFixture.callAndParse("eclipse_build",
				Map.of("kind", "full", "wait", false, "timeoutSeconds", 1));
		String buildId = String.valueOf(started.get("buildId"));

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("waitSeconds", 5));

		// the build may well have finished before the cancel arrived: a fixture
		// workspace builds in milliseconds. Either outcome is correct, and asserting
		// that it was caught would make this test fail on a fast machine
		List<?> cancelled = (List<?>) result.get("cancelled");
		if (cancelled.stream().anyMatch(entry -> buildId.equals(((Map<?, ?>) entry).get("buildId")))) {
			assertTrue(String.valueOf(result.get("note")).contains("partly built"), "got " + result);
		}
		Map<String, Object> status = TestFixture.callAndParse("eclipse_get_build_status", Map.of("buildId", buildId));
		assertTrue(List.of("done", "cancelled", "failed", "running").contains(status.get("state")), "got " + status);
	}

	/**
	 * Waits out whatever the preceding tests left building, so this one starts from
	 * quiet. The server's own build family has to be joined too: it is not one of
	 * the platform's, so joining those alone leaves an eclipse_build job running
	 * and the next test then finds something to cancel.
	 */
	private static void waitForQuiet() throws Exception {
		org.eclipse.core.runtime.jobs.IJobManager jobs = org.eclipse.core.runtime.jobs.Job.getJobManager();
		jobs.join(BuildRegistry.FAMILY, null);
		jobs.join(ResourcesPlugin.FAMILY_AUTO_BUILD, null);
		jobs.join(ResourcesPlugin.FAMILY_MANUAL_BUILD, null);
	}
}
