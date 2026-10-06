package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.MonitorPlacement;

/**
 * Moving a shell onto a monitor through eclipse_set_shell_bounds, on a real shell.
 */
class MonitorPlacementTest {

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
		shell.setSize(300, 200);
		shell.open();
	}

	@AfterEach
	void close() {
		if (display != null) {
			display.dispose();
		}
	}

	private void onThisThread(Runnable step) {
		while (display.readAndDispatch()) {
			// drain what the previous step queued
		}
		step.run();
	}

	@Test
	void anUnknownMonitorChangesNothing() {
		onThisThread(() -> {
		});
		Rectangle before = shell.getBounds();

		String result = MonitorPlacement.move(this::onThisThread, shell, 99, null, null, null, null, null).toString();

		assertTrue(result.contains("\"changed\": false"), result);
		assertTrue(result.contains("No monitor 99"), result);
		assertEquals(before, shell.getBounds());
	}

	@Test
	void theShellLandsOnTheMonitorItIsAlreadyOn() {
		String result = MonitorPlacement.move(this::onThisThread, shell, 0, null, null, null, null, null).toString();

		assertTrue(result.contains("\"onRequestedMonitor\": true"), result);
		assertTrue(result.contains("\"placement\": \"bounds\""), result);
		assertTrue(result.contains("\"monitor\": 0"), result);
	}
}
