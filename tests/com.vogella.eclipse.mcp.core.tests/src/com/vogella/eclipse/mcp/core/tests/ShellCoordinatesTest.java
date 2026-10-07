package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.WidgetTools;

/**
 * Shell coordinates are measured from the shell's bounds, which is where a
 * screen read of the shell starts, including a title bar the bounds contain.
 */
class ShellCoordinatesTest {

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
		shell = new Shell(display, SWT.SHELL_TRIM);
	}

	@AfterEach
	void close() {
		if (display != null) {
			display.dispose();
		}
	}

	@Test
	void aChildIsPlacedRelativeToTheShellBounds() {
		Button button = new Button(shell, SWT.PUSH);
		button.setBounds(10, 20, 80, 30);
		shell.setBounds(100, 100, 300, 200);
		shell.open();
		while (display.readAndDispatch()) {
			// let the window system place the shell before reading its bounds
		}

		Rectangle inShell = WidgetTools.mapToCapture(display, shell, shell, button.getBounds());
		Rectangle inDisplay = display.map(shell, null, button.getBounds());
		Rectangle shellBounds = shell.getBounds();

		assertEquals(new Rectangle(inDisplay.x - shellBounds.x, inDisplay.y - shellBounds.y, 80, 30), inShell);
	}
}
