package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.IDialogSettingsProvider;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * {@code eclipse_reset_dialog_settings} forgets remembered answers in memory and
 * on disk, and a dry run touches neither.
 */
class ResetDialogSettingsToolTest {

	private static final String TOOL = "eclipse_reset_dialog_settings";

	private static final String KEY = "resetDialogSettingsToolTest";

	@Test
	void forgetsRememberedAnswersInMemoryAndOnDisk() throws Exception {
		Bundle bundle = FrameworkUtil.getBundle(ResetDialogSettingsToolTest.class);
		IDialogSettingsProvider provider = PlatformUI.getDialogSettingsProvider(bundle);
		provider.getDialogSettings().put(KEY, "remembered");
		provider.saveDialogSettings();
		Path file = Platform.getStateLocation(bundle).append("dialog_settings.xml").toPath();
		assertTrue(Files.isRegularFile(file), file.toString());

		Map<String, Object> dry = TestFixture.callAndParse(TOOL, Map.of());
		assertEquals(Boolean.TRUE, dry.get("dryRun"));
		assertEquals(Boolean.TRUE, dry.get("inMemoryKnown"), dry.toString());
		assertTrue(String.valueOf(dry.get("bundles")).contains(bundle.getSymbolicName()), dry.toString());
		assertEquals("remembered", provider.getDialogSettings().get(KEY));
		assertTrue(Files.isRegularFile(file));

		Map<String, Object> real = TestFixture.callAndParse(TOOL,
				Map.of("bundles", List.of(bundle.getSymbolicName()), "dryRun", false));
		assertEquals(1, ((Number) real.get("total")).intValue(), real.toString());
		assertFalse(Files.exists(file), file.toString());
		assertNull(provider.getDialogSettings().get(KEY));
	}

	@Test
	void namesBundlesItDoesNotKnowOrThatHaveNothingToReset() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("bundles", List.of("no.such.bundle", "org.eclipse.core.runtime"), "dryRun", false));
		assertEquals(0, ((Number) result.get("total")).intValue(), result.toString());
		assertTrue(String.valueOf(result.get("unknownBundles")).contains("no.such.bundle"), result.toString());
		assertTrue(String.valueOf(result.get("nothingToReset")).contains("org.eclipse.core.runtime"), result.toString());
	}
}
