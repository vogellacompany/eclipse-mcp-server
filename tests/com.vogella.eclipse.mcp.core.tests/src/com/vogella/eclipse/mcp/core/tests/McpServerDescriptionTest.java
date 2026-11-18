package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpServerDescription;
import com.vogella.eclipse.mcp.core.McpServerDescription.Info;
import com.vogella.eclipse.mcp.core.McpServerDescription.Instructions;

class McpServerDescriptionTest {

	private static final McpServerDescription DEFAULTS = new McpServerDescription("eclipse-mcp", "4.0.0",
			"A running Eclipse IDE.");

	@Test
	void keepsTheDefaultsWithoutContributions() {
		assertEquals(DEFAULTS, McpServerDescription.read(DEFAULTS));
		assertEquals(DEFAULTS, McpServerDescription.compose(DEFAULTS, List.of(), List.of()));
	}

	@Test
	void appendsContributedTextsInContributorOrder() {
		McpServerDescription composed = McpServerDescription.compose(DEFAULTS,
				List.of(new Instructions("com.example.b", "  Second.\n", false),
						new Instructions("com.example.a", "First.", false), new Instructions("com.example.c", " ", false)),
				List.of());
		assertEquals("A running Eclipse IDE.\n\nFirst.\n\nSecond.", composed.instructions());
		assertEquals("eclipse-mcp", composed.name());
	}

	@Test
	void aReplacingContributionDropsOnlyTheDefault() {
		McpServerDescription composed = McpServerDescription.compose(DEFAULTS,
				List.of(new Instructions("com.example.product", "Product.", true),
						new Instructions("com.example.addon", "Addon.", false)),
				List.of());
		assertEquals("Addon.\n\nProduct.", composed.instructions());
	}

	@Test
	void takesTheServerInfoOfTheFirstContributor() {
		McpServerDescription composed = McpServerDescription.compose(DEFAULTS, List.of(),
				List.of(new Info("com.example.z", "zeta", "9.0.0"), new Info("com.example.a", "alpha", null)));
		assertEquals("alpha", composed.name());
		assertEquals("4.0.0", composed.version());
	}
}
