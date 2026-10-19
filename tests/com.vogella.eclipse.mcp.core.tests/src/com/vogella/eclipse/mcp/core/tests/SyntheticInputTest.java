package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.swt.SWT;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.SyntheticInput;

/**
 * A synthetic key traverses where SWT's own key handling would.
 */
class SyntheticInputTest {

	@Test
	void tabAndShiftTabTraverseBetweenControls() {
		assertEquals(SWT.TRAVERSE_TAB_NEXT, SyntheticInput.traversal(SWT.TAB, 0));
		assertEquals(SWT.TRAVERSE_TAB_PREVIOUS, SyntheticInput.traversal(SWT.TAB, SWT.SHIFT));
	}

	@Test
	void escapeEnterAndCtrlPageTraverse() {
		assertEquals(SWT.TRAVERSE_ESCAPE, SyntheticInput.traversal(SWT.ESC, 0));
		assertEquals(SWT.TRAVERSE_RETURN, SyntheticInput.traversal(SWT.CR, 0));
		assertEquals(SWT.TRAVERSE_PAGE_NEXT, SyntheticInput.traversal(SWT.PAGE_DOWN, SWT.CTRL));
		assertEquals(SWT.TRAVERSE_NONE, SyntheticInput.traversal(SWT.PAGE_DOWN, 0));
	}

	@Test
	void otherKeysDoNotTraverse() {
		assertEquals(SWT.TRAVERSE_NONE, SyntheticInput.traversal('a', 0));
		assertEquals(SWT.TRAVERSE_NONE, SyntheticInput.traversal(SWT.ARROW_DOWN, 0));
	}
}
