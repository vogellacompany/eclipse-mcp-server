package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.ui.internal.SyntheticInput;

/**
 * Synthetic keys and clicks reach the target's listeners without the window system.
 */
class SyntheticDeliveryTest {

	// a private Display, disposed again, so no other test sees this thread as the UI thread
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
	void aKeyReachesTheKeyListenersWithItsModifiers() {
		Text text = new Text(shell, SWT.BORDER);
		List<String> seen = new ArrayList<>();
		text.addListener(SWT.KeyDown, e -> seen.add("down " + e.character + " " + e.stateMask));
		text.addListener(SWT.KeyUp, e -> seen.add("up " + e.character));

		SyntheticInput.key(text, List.of(Integer.valueOf(SWT.SHIFT)), 'a', 'A', new JsonObject());

		assertEquals(List.of("down A " + SWT.SHIFT, "up A"), seen);
	}

	@Test
	void escapeIsOfferedAsATraversal() {
		Text text = new Text(shell, SWT.BORDER);
		List<Integer> traversals = new ArrayList<>();
		text.addListener(SWT.Traverse, e -> {
			traversals.add(Integer.valueOf(e.detail));
			e.doit = true;
		});

		SyntheticInput.key(text, List.of(), SWT.ESC, (char) 0, new JsonObject());

		assertEquals(List.of(Integer.valueOf(SWT.TRAVERSE_ESCAPE)), traversals);
	}

	@Test
	void aClickReachesTheMouseListenersAtTheGivenPoint() {
		Canvas canvas = new Canvas(shell, SWT.NONE);
		canvas.setBounds(0, 0, 100, 50);
		List<String> seen = new ArrayList<>();
		canvas.addListener(SWT.MouseDown, e -> seen.add("down " + e.button + " " + e.x + "," + e.y));
		canvas.addListener(SWT.MouseUp, e -> seen.add("up " + e.button + " " + e.x + "," + e.y));

		SyntheticInput.click(canvas, new Point(30, 20), 1, new JsonObject());

		assertEquals(List.of("down 1 30,20", "up 1 30,20"), seen);
	}
}
