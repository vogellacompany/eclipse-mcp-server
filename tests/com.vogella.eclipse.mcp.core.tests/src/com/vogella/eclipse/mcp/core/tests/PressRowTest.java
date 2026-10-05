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
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
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
	void focusMovesTheKeyboardFocusToTheTreeBeforeSelecting() {
		Text other = new Text(shell, SWT.SINGLE);
		Tree tree = new Tree(shell, SWT.SINGLE);
		TreeItem row = new TreeItem(tree, SWT.NONE);
		row.setText("row");
		shell.open();
		shell.forceActive();
		other.forceFocus();

		String result = PressWidgetTool.pressRow(row, "r0", null, false, new PressWidgetTool.Focus(shell)).toString();

		for (String field : List.of("focusControlBefore", "focusControlAfter", "focused", "shellActive")) {
			assertTrue(result.contains("\"" + field + "\""), result);
		}
		assertTrue(result.contains("\"path\": \"1\""), result);
		boolean focused = result.contains("\"focused\": true");
		assertEquals(focused, display.getFocusControl() == tree, result);
		assertEquals(!focused, result.contains("\"focusNote\""), result);
		if (focused) {
			assertTrue(result.contains("\"class\": \"Tree\""), result);
		}
		assertSame(row, tree.getSelection()[0]);
	}

	@Test
	void focusMovesTheKeyboardFocusToTheButton() {
		Text other = new Text(shell, SWT.SINGLE);
		Button button = new Button(shell, SWT.PUSH);
		shell.open();
		shell.forceActive();
		other.forceFocus();

		String result = PressWidgetTool.pressButton(button, "1", new PressWidgetTool.Focus(shell)).toString();

		boolean focused = result.contains("\"focused\": true");
		assertEquals(focused, display.getFocusControl() == button, result);
		assertTrue(result.contains("\"focusControlBefore\""), result);
		assertTrue(result.contains("\"pressed\": true"), result);
	}

	@Test
	void aFocusListenerThatDisposesTheButtonGivesAStructuredAnswer() {
		Button button = new Button(shell, SWT.PUSH);
		Text other = new Text(shell, SWT.SINGLE);
		shell.open();
		shell.forceActive();
		other.forceFocus();
		button.addListener(SWT.FocusIn, e -> button.dispose());

		String result = PressWidgetTool.pressButton(button, "0", new PressWidgetTool.Focus(shell)).toString();

		// the display may not deliver FocusIn here, so the disposal path is then not reached
		assumeTrue(button.isDisposed(), result);
		assertTrue(result.contains("\"widgetDisposed\": true"), result);
		assertTrue(result.contains("\"pressed\": false"), result);
	}

	@Test
	void focusMovesTheKeyboardFocusToTheTableOfAColumnHeader() {
		Text other = new Text(shell, SWT.SINGLE);
		Table table = new Table(shell, SWT.NONE);
		TableColumn name = new TableColumn(table, SWT.NONE);
		name.setText("Name");
		name.setWidth(50);
		table.setHeaderVisible(true);
		shell.open();
		shell.forceActive();
		other.forceFocus();

		String result = PressWidgetTool.pressColumn(name, "1/i0", null, new PressWidgetTool.Focus(shell)).toString();

		boolean focused = result.contains("\"focused\": true");
		assertEquals(focused, display.getFocusControl() == table, result);
		assertTrue(result.contains("\"sortColumnBefore\""), result);
	}

	@Test
	void focusIsNotReportedUnlessAsked() {
		Tree tree = new Tree(shell, SWT.SINGLE);
		TreeItem row = new TreeItem(tree, SWT.NONE);
		shell.open();

		assertTrue(!PressWidgetTool.pressRow(row, "r0", null, false).toString().contains("focused"));
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

	@Test
	void aTableColumnPressSortsThroughItsListener() {
		Table table = new Table(shell, SWT.SINGLE);
		table.setHeaderVisible(true);
		TableColumn name = new TableColumn(table, SWT.NONE);
		name.setWidth(80);
		name.setText("&Name");
		name.addListener(SWT.Selection, e -> {
			events.add(e);
			table.setSortColumn(name);
			table.setSortDirection(SWT.UP);
		});
		shell.open();

		String result = PressWidgetTool.pressColumn(name, "0/i0", "name").toString();

		assertEquals(1, events.size());
		assertSame(name, events.get(0).widget);
		assertTrue(result.contains("\"sortColumnBefore\": null"), result);
		assertTrue(result.contains("\"sortDirectionBefore\": \"none\""), result);
		assertTrue(result.contains("\"sortColumnAfter\": \"Name\""), result);
		assertTrue(result.contains("\"sortDirectionAfter\": \"up\""), result);
		assertTrue(result.contains("\"selectionListeners\": 1"), result);
	}

	@Test
	void aStaleColumnLabelOrADisabledTableRefuses() {
		Table table = new Table(shell, SWT.SINGLE);
		table.setHeaderVisible(true);
		TableColumn name = new TableColumn(table, SWT.NONE);
		name.setWidth(80);
		name.setText("Name");
		name.addListener(SWT.Selection, events::add);
		shell.open();

		assertTrue(PressWidgetTool.pressColumn(name, "0/i0", "Size").toString().contains("reads 'Name'"));
		table.setEnabled(false);
		assertTrue(PressWidgetTool.pressColumn(name, "0/i0", null).toString().contains("disabled"));
		table.setEnabled(true);
		table.setHeaderVisible(false);
		assertTrue(PressWidgetTool.pressColumn(name, "0/i0", null).toString().contains("header hidden"));
		table.setHeaderVisible(true);
		name.setWidth(0);
		assertTrue(PressWidgetTool.pressColumn(name, "0/i0", null).toString().contains("zero width"));
		assertTrue(events.isEmpty());
	}

	@Test
	void aTreeColumnPressReportsTheTreesSortState() {
		Tree tree = new Tree(shell, SWT.SINGLE);
		tree.setHeaderVisible(true);
		TreeColumn size = new TreeColumn(tree, SWT.NONE);
		size.setWidth(80);
		size.setText("Size");
		size.addListener(SWT.Selection, e -> {
			events.add(e);
			tree.setSortColumn(size);
			tree.setSortDirection(SWT.DOWN);
		});
		shell.open();

		String result = PressWidgetTool.pressColumn(size, "0/i0", "size").toString();

		assertEquals(1, events.size());
		assertTrue(result.contains("\"sortColumnAfter\": \"Size\""), result);
		assertTrue(result.contains("\"sortDirectionAfter\": \"down\""), result);
	}
}
