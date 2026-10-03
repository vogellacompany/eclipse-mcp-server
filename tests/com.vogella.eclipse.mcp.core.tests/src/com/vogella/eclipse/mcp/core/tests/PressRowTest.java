package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.PressWidgetTool;

/**
 * Selecting a Tree or Table row through eclipse_press_widget, on a real shell.
 */
class PressRowTest {

	// a private Display, disposed again, so no other test sees this thread as the UI thread
	private Display display;
	private Shell shell;
	private final List<Event> events = new ArrayList<>();

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
	void aTreeRowIsExpandedSelectedAndNotified() {
		Tree tree = new Tree(shell, SWT.SINGLE);
		TreeItem parent = new TreeItem(tree, SWT.NONE);
		parent.setText("parent");
		TreeItem child = new TreeItem(parent, SWT.NONE);
		child.setText("child");
		tree.addListener(SWT.Selection, events::add);
		shell.open();

		String result = PressWidgetTool.pressRow(child, "r0/r0", "Child", false).toString();

		assertTrue(parent.getExpanded());
		assertEquals(1, events.stream().filter(e -> e.type == SWT.Selection).count());
		assertSame(child, events.get(events.size() - 1).item);
		assertSame(child, tree.getSelection()[0]);
		assertTrue(result.contains("\"selectedItem\": \"child\""), result);
		assertTrue(result.contains("\"selectionHoldsItem\": true"), result);
	}

	@Test
	void defaultSelectionFollowsSelectionWhenAsked() {
		Tree tree = new Tree(shell, SWT.SINGLE);
		TreeItem row = new TreeItem(tree, SWT.NONE);
		row.setText("row");
		List<Integer> types = new ArrayList<>();
		tree.addListener(SWT.Selection, e -> types.add(e.type));
		tree.addListener(SWT.DefaultSelection, e -> types.add(e.type));
		shell.open();

		PressWidgetTool.pressRow(row, "r0", null, true);

		assertEquals(List.of(SWT.Selection, SWT.DefaultSelection), types);
	}

	@Test
	void defaultSelectionIsSkippedWhenTheRowWasDisposedByTheSelectionListener() {
		Tree tree = new Tree(shell, SWT.SINGLE);
		TreeItem row = new TreeItem(tree, SWT.NONE);
		row.setText("&row");
		List<Integer> types = new ArrayList<>();
		tree.addListener(SWT.Selection, e -> {
			types.add(e.type);
			row.dispose();
		});
		tree.addListener(SWT.DefaultSelection, e -> types.add(e.type));
		shell.open();

		String result = PressWidgetTool.pressRow(row, "r0", "row", true).toString();

		assertEquals(List.of(SWT.Selection), types);
		assertTrue(result.contains("\"widgetDisposed\": true"), result);
	}

	@Test
	void aTableRowIsSelectedAndNotified() {
		Table table = new Table(shell, SWT.SINGLE);
		new TableItem(table, SWT.NONE).setText("a");
		TableItem second = new TableItem(table, SWT.NONE);
		second.setText("b");
		table.addListener(SWT.Selection, events::add);
		shell.open();

		String result = PressWidgetTool.pressRow(second, "r1", null, false).toString();

		assertEquals(1, events.size());
		assertSame(second, events.get(0).item);
		assertSame(second, table.getSelection()[0]);
		assertTrue(result.contains("\"selectionHoldsItem\": true"), result);
	}

	@Test
	void aStaleLabelADisabledOrAnInvisibleTreeRefuses() {
		Tree tree = new Tree(shell, SWT.SINGLE);
		TreeItem row = new TreeItem(tree, SWT.NONE);
		row.setText("row");
		tree.addListener(SWT.Selection, events::add);
		shell.open();
		tree.deselectAll();

		assertTrue(PressWidgetTool.pressRow(row, "r0", "other", false).toString().contains("reads 'row'"));
		tree.setEnabled(false);
		assertTrue(PressWidgetTool.pressRow(row, "r0", null, false).toString().contains("disabled"));
		tree.setEnabled(true);
		tree.setVisible(false);
		assertTrue(PressWidgetTool.pressRow(row, "r0", null, false).toString().contains("not visible"));
		assertTrue(events.isEmpty());
		assertEquals(0, tree.getSelectionCount());
	}
}
