package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SaveWorkspaceToolTest {

	private static final String TOOL = "eclipse_save_workspace";

	@Test
	void aDryRunReportsWhatASaveWouldTouchAndSavesNothing() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of());

		assertEquals(Boolean.TRUE, result.get("dryRun"));
		assertTrue(result.containsKey("projects"), result.toString());
		assertTrue(result.containsKey("localHistory"), "a full save is the default, so its history cost is shown");
	}

	@Test
	void aSnapshotSaveFinishes() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("mode", "snapshot", "dryRun", Boolean.FALSE));

		assertEquals("done", result.get("state"), result.toString());
	}
}
