package com.vogella.eclipse.mcp.server.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.server.internal.ToolReferences;

class ToolReferencesTest {

	@Test
	void namesOnlyToolsThatAreNotInstalled() {
		assertEquals(Set.of("eclipse_open", "eclipse_hot_code_replace"),
				ToolReferences.missing(List.of("eclipse_click"),
						List.of("Use eclipse_click or eclipse_open, then eclipse_hot_code_replace.", "See eclipse.ini.")));
	}
}
