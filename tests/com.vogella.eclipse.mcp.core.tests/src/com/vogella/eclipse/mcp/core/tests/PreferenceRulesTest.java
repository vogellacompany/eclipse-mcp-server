package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.PreferenceRules;

/**
 * The detection of {@code IEclipsePreferences} blocks in a CSS snippet.
 * <p>
 * This decision is made on the string before any engine sees the snippet, so
 * none of it needs a workbench.
 */
class PreferenceRulesTest {

	@Test
	void readsTheQualifierAndPairsOutOfABlock() {
		List<PreferenceRules.Rule> found = PreferenceRules.scan("""
				IEclipsePreferences#org-eclipse-jdt-ui:org-eclipse-jdt-ui {
					preferences: 'java_keyword=255,123,114' 'java_default=230,237,243';
				}
				""");

		assertEquals(1, found.size());
		PreferenceRules.Rule rule = found.get(0);
		assertEquals("org-eclipse-jdt-ui", rule.escapedId());
		assertEquals("org.eclipse.jdt.ui", rule.qualifier());
		assertEquals(Map.of("java_keyword", "255,123,114", "java_default", "230,237,243"), rule.values());
	}

	@Test
	void answersNothingForASnippetWithoutAPreferenceBlock() {
		assertTrue(PreferenceRules.scan("CTabFolder ToolBar {background-color: #00ff00;}").isEmpty());
		assertTrue(PreferenceRules.scan(null).isEmpty());
	}

	@Test
	void findsThePreferenceBlockBesideWidgetRules() {
		List<PreferenceRules.Rule> found = PreferenceRules.scan("""
				Label {color: #ff0000;}
				IEclipsePreferences#org-eclipse-ui-workbench {
					preferences: 'CONFLICTING_COLOR=240,15,66';
				}
				Shell {background-color: #000000;}
				""");

		assertEquals(1, found.size());
		assertEquals("org.eclipse.ui.workbench", found.get(0).qualifier());
		assertEquals(Map.of("CONFLICTING_COLOR", "240,15,66"), found.get(0).values());
	}

	@Test
	void reportsABlockWhosePairsItCannotRead() {
		List<PreferenceRules.Rule> found = PreferenceRules
				.scan("IEclipsePreferences#org-eclipse-jdt-ui { color: red; }");

		assertEquals(1, found.size());
		assertEquals(0, found.get(0).size());
	}

	@Test
	void namesTheMediaConditionAroundABlock() {
		// a skipped @media block and the engine's no-overwrite rule both leave the keys unchanged
		List<PreferenceRules.Rule> found = PreferenceRules.scan("""
				@media (-eclipse-min-bundle-version: "org.eclipse.ui.editors 3.99") {
				  IEclipsePreferences#org-eclipse-ui-editors { preferences: 'a=1'; }
				}
				IEclipsePreferences#org-eclipse-jdt-ui { preferences: 'b=2'; }
				""");

		assertEquals(2, found.size());
		assertEquals("(-eclipse-min-bundle-version: \"org.eclipse.ui.editors 3.99\")", found.get(0).media());
		assertNull(found.get(1).media());
	}
}
