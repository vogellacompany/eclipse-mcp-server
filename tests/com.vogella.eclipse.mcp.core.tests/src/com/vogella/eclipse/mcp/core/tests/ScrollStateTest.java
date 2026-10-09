package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.ScrollTool;

/**
 * The scroll state eclipse_scroll reports, on a real shell.
 */
class ScrollStateTest {

	private Display display;
	private Shell shell;

	@BeforeEach
	void open() {
		try {
			display = new Display();
		} catch (SWTError | SWTException | LinkageError e) {
			assumeTrue(false, "no display: " + e.getMessage());
			return;
		}
		shell = new Shell(display);
	}

	@AfterEach
	void close() {
		if (display != null) {
			display.dispose();
		}
	}

	@Test
	void aTreeReportsItsBarsTopItemAndSelection() {
		Tree tree = new Tree(shell, SWT.V_SCROLL | SWT.H_SCROLL);
		for (int i = 0; i < 50; i++) {
			new TreeItem(tree, SWT.NONE).setText("row" + i);
		}
		tree.setSize(100, 100);
		shell.open();

		String state = ScrollTool.state(tree).toString();

		assertTrue(state.contains("\"maximum\""), state);
		assertTrue(state.contains("\"topItem\": \"row0\""), state);
		assertTrue(state.contains("\"selectionCount\""), state);
	}

	@Test
	void theScrolledWidgetIsTheNearestOneWithABarInThatDirection() {
		Composite outer = new Composite(shell, SWT.H_SCROLL);
		Composite inner = new Composite(outer, SWT.V_SCROLL);
		Composite plain = new Composite(inner, SWT.NONE);

		assertSame(inner, ScrollTool.scrollableOf(plain, false));
		assertSame(outer, ScrollTool.scrollableOf(plain, true));
		assertNull(ScrollTool.scrollableOf(new Composite(shell, SWT.NONE), true));
	}
}
