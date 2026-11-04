package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.ui.internal.ComboListTool;

/** The drop-down of a real Combo, opened without the pointer. */
class ComboListToolTest {

	// a private Display, disposed again, so no other test sees this thread as the UI thread
	private Display display;
	private Shell shell;
	private Combo combo;

	@BeforeEach
	void open() {
		try {
			display = new Display();
		} catch (SWTError | SWTException | LinkageError e) {
			assumeTrue(false, "no display: " + e.getMessage());
			return;
		}
		shell = new Shell(display);
		shell.setLayout(new FillLayout());
		combo = new Combo(shell, SWT.DROP_DOWN | SWT.READ_ONLY);
		combo.setItems("First", "Second", "Third");
		combo.select(1);
	}

	@AfterEach
	void close() {
		if (display != null) {
			display.dispose();
		}
	}

	@Test
	void refusesToOpenTheListOfAHiddenCombo() {
		String answer = ComboListTool.apply(combo, Boolean.TRUE, true, 100, new ComboListTool.Capture()).toString();
		assertTrue(answer.contains("\"found\": true") && answer.contains("refused") && answer.contains("not visible"), answer);
		assertFalse(combo.getListVisible());
	}

	@Test
	void opensReadsAndClosesTheNativeList() {
		assumeTrue("win32".equals(SWT.getPlatform()), "only Windows has the native list");
		shell.setSize(300, 100);
		shell.open();
		ComboListTool.Capture picture = new ComboListTool.Capture();
		JsonObject opened = ComboListTool.apply(combo, Boolean.TRUE, true, 100, picture);
		byte[] png = picture.finish();
		String answer = opened.toString();
		assertTrue(combo.getListVisible(), answer);
		assertTrue(answer.contains("\"highlightedRow\": 1"), answer);
		assertTrue(answer.contains("\"onScreen\": true"), answer);
		assertTrue(answer.contains("Second"), answer);
		assertTrue(answer.contains("fill"), answer);
		assertNotNull(png, answer);

		String closed = ComboListTool.apply(combo, Boolean.FALSE, true, 100, new ComboListTool.Capture()).toString();
		assertFalse(combo.getListVisible());
		assertFalse(closed.contains("captureNote"), closed);
		assertFalse(closed.contains("captureFailure"), closed);
	}

	@Test
	void aClosedListHasNoPicture() {
		assumeTrue("win32".equals(SWT.getPlatform()), "only Windows has the native list");
		shell.setSize(300, 100);
		shell.open();
		ComboListTool.Capture picture = new ComboListTool.Capture();
		String answer = ComboListTool.apply(combo, null, true, 100, picture).toString();
		assertTrue(answer.contains("captureNote"), answer);
		assertFalse(answer.contains("captureFailure"), answer);
		assertNull(picture.finish(), answer);
	}

	@Test
	void reportsEveryRowUpToMaxResults() {
		assumeTrue("win32".equals(SWT.getPlatform()), "only Windows has the native list");
		String[] items = new String[60];
		for (int i = 0; i < items.length; i++) {
			items[i] = "Item " + i;
		}
		combo.setItems(items);
		shell.setSize(300, 100);
		shell.open();
		String answer = ComboListTool.apply(combo, Boolean.TRUE, false, 50, new ComboListTool.Capture()).toString();
		combo.setListVisible(false);
		assertTrue(answer.contains("\"total\": 60"), answer);
		assertTrue(answer.contains("\"truncated\": true"), answer);
		assertTrue(answer.contains("Item 49") && !answer.contains("Item 50"), answer);
		assertTrue(answer.contains("\"onScreen\": true") && answer.contains("\"onScreen\": false"), answer);
	}

	@Test
	void elsewhereOnlyTheOpenStateIsReported() {
		assumeFalse("win32".equals(SWT.getPlatform()), "Windows reads the native list");
		ComboListTool.Capture picture = new ComboListTool.Capture();
		String answer = ComboListTool.apply(combo, null, true, 100, picture).toString();
		assertTrue(answer.contains("\"found\": true") && answer.contains("\"dropDown\": true"), answer);
		assertTrue(answer.contains("nativeListNote"), answer);
		assertFalse(answer.contains("\"nativeList\"") || answer.contains("nativeListFailure"), answer);
		assertNull(picture.finish(), answer);
	}

	@Test
	void aSimpleComboIsNotADropDown() {
		Combo simple = new Combo(shell, SWT.SIMPLE);
		String answer = ComboListTool.apply(simple, null, false, 100, new ComboListTool.Capture()).toString();
		assertTrue(answer.contains("\"dropDown\": false"), answer);
	}

	@Test
	void aMissingPathIsRefusedBeforeTheUi() {
		McpToolResult result = new ComboListTool().call(Map.of(), new NullProgressMonitor());
		assertTrue(result.isError() && result.text().contains("'path' is required"), result.toString());
	}
}
