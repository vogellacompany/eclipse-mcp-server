package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.json.Json;
import com.vogella.eclipse.mcp.ui.internal.ControlMetrics;

/**
 * The metrics are what the control computes for itself, read without a workbench.
 */
class ControlMetricsTest {

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
	void aButtonReportsItsPreferredSizeAndHasNoScrollableFields() {
		Button button = new Button(shell, SWT.PUSH);
		button.setText("Apply");
		Point preferred = button.computeSize(SWT.DEFAULT, SWT.DEFAULT);

		Map<String, Object> metrics = metrics(button);

		assertEquals(preferred.x + "x" + preferred.y, metrics.get("preferredSize"));
		assertEquals(Boolean.TRUE, metrics.get("nativeHandle"));
		assertNotNull(metrics.get("fontHeight"));
		assertNull(metrics.get("clientArea"));
		assertNull(metrics.get("itemHeight"));
	}

	@Test
	void aTableReportsTrimClientAreaAndItemHeight() {
		Table table = new Table(shell, SWT.BORDER);
		table.setBounds(0, 0, 200, 100);

		Map<String, Object> metrics = metrics(table);

		assertEquals(table.getItemHeight(), ((Number) metrics.get("itemHeight")).intValue());
		assertNotNull(metrics.get("clientArea"));
		assertNotNull(metrics.get("trim"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> metrics(org.eclipse.swt.widgets.Control control) {
		return (Map<String, Object>) Json.parse(ControlMetrics.of(control).toString());
	}
}
