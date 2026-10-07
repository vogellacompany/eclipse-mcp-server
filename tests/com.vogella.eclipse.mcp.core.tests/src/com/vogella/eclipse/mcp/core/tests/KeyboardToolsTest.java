package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.KeyboardTools;

/**
 * Parsing key names into codes and modifiers, which needs no display.
 * <p>
 * Posting the events needs a focused workbench and is left to the real IDE;
 * what is testable is that a name resolves to the SWT code a client expects,
 * since a wrong code sends the wrong key with no error.
 */
class KeyboardToolsTest {

	@Test
	@SuppressWarnings("unchecked")
	void resolvesNamedKeysAndModifiers() throws Exception {
		Map<String, Object> ctrlSpace = TestFixture.parse(KeyboardTools.describe("Ctrl+Space").toString());
		assertEquals(Integer.valueOf(' '), ctrlSpace.get("keyCode"));
		assertEquals(List.of(Integer.valueOf(SWT.CTRL)), ctrlSpace.get("modifiers"));

		Map<String, Object> escape = TestFixture.parse(KeyboardTools.describe("Escape").toString());
		assertEquals(Integer.valueOf(SWT.ESC), escape.get("keyCode"));
		assertTrue(((List<Object>) escape.get("modifiers")).isEmpty());

		assertEquals(Integer.valueOf(SWT.ARROW_DOWN),
				TestFixture.parse(KeyboardTools.describe("Down").toString()).get("keyCode"));
		assertEquals(Integer.valueOf(SWT.CR),
				TestFixture.parse(KeyboardTools.describe("Enter").toString()).get("keyCode"));
	}

	@Test
	void aSingleCharacterCarriesItselfAsTheCharacter() throws Exception {
		Map<String, Object> a = TestFixture.parse(KeyboardTools.describe("A").toString());
		assertEquals("A", a.get("character"));
		assertEquals(Integer.valueOf('a'), a.get("keyCode"), "the code is the lower-case key");

		Map<String, Object> shiftA = TestFixture.parse(KeyboardTools.describe("Shift+a").toString());
		assertEquals(List.of(Integer.valueOf(SWT.SHIFT)), shiftA.get("modifiers"));
	}

	@Test
	void anUnknownKeyIsRejected() {
		try {
			KeyboardTools.describe("Ctrl+Nonsense");
			throw new AssertionError("a multi-character unknown key must be rejected");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("Nonsense"), "got " + expected.getMessage());
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void aTrailingPlusIsThePlusKey() throws Exception {
		Map<String, Object> zoom = TestFixture.parse(KeyboardTools.describe("Ctrl++").toString());
		assertEquals("+", zoom.get("character"));
		assertEquals(List.of(Integer.valueOf(SWT.CTRL)), zoom.get("modifiers"));

		Map<String, Object> plain = TestFixture.parse(KeyboardTools.describe("+").toString());
		assertEquals("+", plain.get("character"));
		assertTrue(((List<Object>) plain.get("modifiers")).isEmpty());
	}

	@Test
	void theMenuKeyIsRefusedWithShiftF10AsTheWayToAContextMenu() throws Exception {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> KeyboardTools.describe("Menu"));
		assertTrue(refused.getMessage().contains("Shift+F10"), refused.getMessage());
		for (String alias : List.of("ContextMenu", "Apps", "application")) {
			assertThrows(IllegalArgumentException.class, () -> KeyboardTools.describe(alias), alias);
		}
		assertEquals(Integer.valueOf(SWT.F10),
				TestFixture.parse(KeyboardTools.describe("Shift+F10").toString()).get("keyCode"));
	}
}
