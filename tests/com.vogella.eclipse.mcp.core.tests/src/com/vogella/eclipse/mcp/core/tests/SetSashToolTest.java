package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.SetSashTool;

/**
 * Moving a SashForm's sash through eclipse_set_sash, on a real shell.
 */
class SetSashToolTest {

	private Display display;
	private SashForm form;
	private Composite left;
	private Composite middle;
	private Composite right;

	@BeforeEach
	void open() {
		try {
			display = new Display();
		} catch (SWTError | SWTException | LinkageError e) {
			assumeTrue(false, "no display: " + e.getMessage());
			return;
		}
		Shell shell = new Shell(display);
		shell.setLayout(new FillLayout());
		form = new SashForm(shell, SWT.HORIZONTAL);
		left = new Composite(form, SWT.NONE);
		middle = new Composite(form, SWT.NONE);
		right = new Composite(form, SWT.NONE);
		shell.setSize(600, 300);
		shell.open();
		form.layout(true);
		while (display.readAndDispatch()) {
			// let the window system place the shell
		}
	}

	@AfterEach
	void close() {
		if (display != null) {
			display.dispose();
		}
	}

	@Test
	void theControlBeforeTheSashGetsThePosition() {
		AtomicInteger heard = new AtomicInteger();
		sash(0).addListener(SWT.Selection, event -> heard.incrementAndGet());

		String result = SetSashTool.set(form, null, 150, "0").toString();

		assertEquals(150, left.getBounds().width, result);
		assertEquals(2, heard.get(), "other listeners on the sash hear the move and the release");
		assertTrue(result.contains("\"position\": 150"), result);
		assertTrue(result.contains("\"clamped\": false"), result);
		assertTrue(result.contains("\"path\": \"0/0\""), result);
		int[] weights = form.getWeights();
		assertTrue(weights[0] < weights[1], "the weights follow the drag: " + result);
	}

	@Test
	void aSashPathPicksItsOwnIndex() {
		String result = SetSashTool.set(sash(1), null, 100, null).toString();

		assertEquals(100, middle.getBounds().width, result);
		assertTrue(result.contains("\"sashIndex\": 1"), result);
		assertTrue(SetSashTool.set(sash(1), Integer.valueOf(0), 100, null).toString().contains("\"changed\": false"));
	}

	@Test
	void aPositionPastTheEndIsClampedToTheMinimum() {
		int rightX = right.getBounds().x;

		String result = SetSashTool.set(form, Integer.valueOf(0), 5, null).toString();

		assertTrue(result.contains("\"clamped\": true"), result);
		assertEquals(20, left.getBounds().width, result);
		assertTrue(result.contains("\"position\": 20"), result);
		assertEquals(rightX, right.getBounds().x, "the control after the next sash stays put: " + result);
	}

	@Test
	void anotherListenersVetoIsNotAClamp() {
		// SashForm's own listener runs first, so a later veto changes nothing, as for a person's drag
		sash(0).addListener(SWT.Selection, event -> event.doit = false);

		String result = SetSashTool.set(form, null, 150, "").toString();

		assertEquals(150, left.getBounds().width, result);
		assertTrue(result.contains("\"clamped\": false"), result);
		assertTrue(result.contains("\"boundsInShell\""), result);
		assertTrue(result.contains("\"path\": \"0\""), "a SashForm at the root has unprefixed child paths: " + result);
	}

	@Test
	void aStackedSashFormSetsHeights() {
		form.setOrientation(SWT.VERTICAL);
		form.layout(true);

		String result = SetSashTool.set(form, null, 80, null).toString();

		assertEquals(80, left.getBounds().height, result);
		assertTrue(result.contains("\"orientation\": \"vertical\""), result);
	}

	@Test
	void whatIsNotASashFormIsRefused() {
		assertTrue(SetSashTool.set(left, null, 10, null).toString().contains("\"changed\": false"));
		assertTrue(SetSashTool.set(form, Integer.valueOf(5), 10, null).toString().contains("\"changed\": false"));
		form.setMaximizedControl(left);
		assertTrue(SetSashTool.set(form, null, 10, null).toString().contains("maximized"));
	}

	private Sash sash(int index) {
		int seen = 0;
		for (Control child : form.getChildren()) {
			if (child instanceof Sash sash && seen++ == index) {
				return sash;
			}
		}
		throw new AssertionError("no sash " + index);
	}
}
