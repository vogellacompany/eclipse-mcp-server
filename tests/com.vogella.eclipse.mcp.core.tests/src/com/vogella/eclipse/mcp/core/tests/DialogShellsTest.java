package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.ui.internal.AccessibleNames;
import com.vogella.eclipse.mcp.ui.internal.ClipboardTool;
import com.vogella.eclipse.mcp.ui.internal.CloseWatch;
import com.vogella.eclipse.mcp.ui.internal.DismissDialogTool;
import com.vogella.eclipse.mcp.ui.internal.DragTool;
import com.vogella.eclipse.mcp.ui.internal.NativeDialogs;
import com.vogella.eclipse.mcp.ui.internal.Shells;
import com.vogella.eclipse.mcp.ui.internal.WidgetStyle;
import com.vogella.eclipse.mcp.ui.internal.WidgetTools;

/**
 * Telling dialogs apart, answering one that holds a close, and what the pointer tools report, on real shells.
 */
class DialogShellsTest {

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

	private static final class StyledWindow extends Window {
		StyledWindow(int style) {
			super((Shell) null);
			setShellStyle(style);
			setBlockOnOpen(false);
		}
	}

	private Shell confirmation(boolean withYes) {
		Shell dialog = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		dialog.setText("Exit Product");
		Button no = new Button(dialog, SWT.PUSH);
		no.setText("&No");
		if (withYes) {
			Button yes = new Button(dialog, SWT.PUSH);
			yes.setText("&Yes");
			yes.addListener(SWT.Selection, event -> dialog.dispose());
		}
		dialog.pack();
		return dialog;
	}

	private void spinUntil(BooleanSupplier done) {
		long deadline = System.currentTimeMillis() + 5000;
		while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
			if (!display.readAndDispatch()) {
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	@Test
	void aTitledNonModalJFaceWindowIsADialog() {
		StyledWindow window = new StyledWindow(SWT.TITLE | SWT.CLOSE | SWT.BORDER);
		window.open();
		assertEquals("dialog", Shells.kind(window.getShell()));
	}

	@Test
	void anUntitledJFaceWindowAndAPlainTitledShellStayPopups() {
		StyledWindow window = new StyledWindow(SWT.ON_TOP | SWT.TOOL);
		window.open();
		assertEquals("popup", Shells.kind(window.getShell()));
		assertEquals("popup", Shells.kind(new Shell(display, SWT.TITLE)));
	}

	@Test
	void aModalShellIsADialog() {
		assertEquals("dialog", Shells.kind(confirmation(false)));
	}

	@Test
	void aDialogCanBeAddressedByIndex() {
		Shell dialog = confirmation(true);
		dialog.open();
		int index = Shells.select(display, "0") == dialog ? 0 : 1;
		String answer = DismissDialogTool.dismiss(display, String.valueOf(index), null, null, true).toString();
		assertTrue(answer.contains("Exit Product"), answer);
		assertTrue(answer.contains("Yes"), answer);
		assertFalse(dialog.isDisposed(), "a dry run touches nothing");
		assertTrue(DismissDialogTool.dismiss(display, "99", null, null, true).toString().contains("No dialog matching"));
	}

	@Test
	void aHiddenShellIsToldApartFromNoShell() {
		Shell hidden = confirmation(true);
		String index = String.valueOf(List.of(display.getShells()).indexOf(hidden));
		assertTrue(DismissDialogTool.dismiss(display, index, null, null, true).toString().contains("hidden shell"));
	}

	@Test
	void aPointOnNoMonitorIsRefusedWithoutMovingThePointer() {
		Point before = display.getCursorLocation();
		Point nowhere = new Point(99_999, 99_999);
		String failure = WidgetTools.Click.warp(display, nowhere, new JsonObject(), "nothing was pressed", "clicking").failure();
		assertTrue(failure.contains("on no monitor"), failure);
		assertTrue(failure.contains("nothing was pressed"), failure);
		assertEquals(before, display.getCursorLocation());
		Rectangle first = display.getMonitors()[0].getBounds();
		assertNull(WidgetTools.Click.offScreen(display, new Point(first.x, first.y)));
	}

	@Test
	void aForcedCloseAnswersTheDialogWithYes() {
		List<JsonObject> held = new ArrayList<>();
		CloseWatch watch = CloseWatch.start(display, true, "exit", held::add);
		Shell dialog = confirmation(true);
		dialog.open();
		spinUntil(dialog::isDisposed);
		watch.stop();
		assertTrue(dialog.isDisposed(), "the Yes button was not pressed");
		assertTrue(watch.answered().toString().contains("Yes"), watch.answered().toString());
		assertTrue(held.isEmpty(), held.toString());
	}

	@Test
	void aDialogThatSurvivesTheForcedPressIsReported() {
		List<JsonObject> held = new ArrayList<>();
		CloseWatch watch = CloseWatch.start(display, true, "exit", held::add);
		Shell dialog = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		dialog.setText("Refuses");
		new Button(dialog, SWT.PUSH).setText("&Yes");
		dialog.pack();
		dialog.open();
		spinUntil(() -> !held.isEmpty());
		watch.stop();
		assertTrue(watch.answered().toString().contains("Refuses"), watch.answered().toString());
		assertEquals(1, held.size(), held.toString());
		assertTrue(held.get(0).toString().contains("Refuses"), held.toString());
	}

	@Test
	void anUnforcedCloseReportsTheDialogAndLeavesItOpen() {
		List<JsonObject> held = new ArrayList<>();
		CloseWatch watch = CloseWatch.start(display, false, "exit", held::add);
		Shell dialog = confirmation(true);
		dialog.open();
		spinUntil(() -> !held.isEmpty());
		watch.stop();
		assertFalse(dialog.isDisposed());
		assertEquals(1, held.size());
		assertTrue(held.get(0).toString().contains("heldByDialog"), held.toString());
		assertTrue(held.get(0).toString().contains("Exit Product"), held.toString());
	}

	@Test
	void aForcedCloseWithoutAYesButtonReportsTheDialog() {
		List<JsonObject> held = new ArrayList<>();
		CloseWatch watch = CloseWatch.start(display, true, "exit", held::add);
		Shell dialog = confirmation(false);
		dialog.open();
		spinUntil(() -> !held.isEmpty());
		watch.stop();
		assertFalse(dialog.isDisposed());
		assertEquals(1, held.size());
	}

	@Test
	void aDialogOpenBeforeTheCloseIsNotTouched() {
		Shell dialog = confirmation(true);
		dialog.open();
		List<JsonObject> held = new ArrayList<>();
		CloseWatch watch = CloseWatch.start(display, true, "exit", held::add);
		long end = System.currentTimeMillis() + 1300;
		spinUntil(() -> System.currentTimeMillis() > end);
		watch.stop();
		assertFalse(dialog.isDisposed());
		assertTrue(held.isEmpty(), held.toString());
	}

	@Test
	void stylesAreNamedForTheClass() {
		assertTrue(WidgetStyle.names(new Sash(shell, SWT.SMOOTH | SWT.VERTICAL)).contains("SMOOTH"));
		assertTrue(WidgetStyle.names(new SashForm(shell, SWT.SMOOTH)).contains("SMOOTH"));
		assertTrue(WidgetStyle.names(new Text(shell, SWT.READ_ONLY | SWT.BORDER)).containsAll(List.of("READ_ONLY", "BORDER")));
		// READ_ONLY and PUSH are one bit, which only the class tells apart
		List<String> button = WidgetStyle.names(new Button(shell, SWT.PUSH));
		assertTrue(button.contains("PUSH"), button.toString());
		assertFalse(button.contains("READ_ONLY"), button.toString());
	}

	@Test
	void aRefusedPointNamesWhatIsThere() {
		shell.setBounds(100, 100, 300, 200);
		shell.open();
		Point inside = new Point(200, 200);
		JsonObject named = new JsonObject();
		assertTrue(WidgetTools.Click.whatIsThere(display, inside, "Notepad", named).contains("'Notepad'"));
		assertTrue(named.toString().contains("coveredBy"), named.toString());
		assertTrue(WidgetTools.Click.whatIsThere(display, inside, null, new JsonObject()).contains("in front of this IDE's window"));
		assertTrue(WidgetTools.Click.whatIsThere(display, new Point(-50_000, -50_000), null, new JsonObject())
				.contains("check the coordinates"));
	}

	@Test
	void aNativeMessageBoxIsFoundAndAnsweredByItsButton() {
		assumeTrue(SWT.getPlatform().equals("win32"), "native dialogs are only looked for on Windows");
		shell.open();
		// Cancel lets the safety timer end a box the press did not reach, instead of the test blocking on it
		MessageBox box = new MessageBox(shell, SWT.YES | SWT.NO | SWT.CANCEL | SWT.APPLICATION_MODAL);
		box.setText("Native Question");
		box.setMessage("Answered by the test");
		List<String> titles = new ArrayList<>();
		List<String> answers = new ArrayList<>();
		Runnable[] answer = new Runnable[1];
		answer[0] = () -> {
			// the box may not be up yet on a slow machine
			if (NativeDialogs.list(display).isEmpty()) {
				display.timerExec(100, answer[0]);
				return;
			}
			try {
				NativeDialogs.list(display).forEach(dialog -> titles.add(dialog.title()));
				Set<Long> open = NativeDialogs.handles(display);
				answers.add(NativeDialogs.listNew(display, open).isEmpty() ? "known" : "reported as new");
				answers.add(DismissDialogTool.dismiss(display, null, "native question", null, true).toString());
				// the labels follow the language of Windows, the order does not
				String no = List.copyOf(NativeDialogs.list(display).get(0).buttons().keySet()).get(1);
				answers.add(DismissDialogTool.dismiss(display, null, null, "&" + no, false).toString());
			} catch (RuntimeException e) {
				answers.add(e.toString());
			}
		};
		display.timerExec(100, answer[0]);
		display.timerExec(5000, () -> NativeDialogs.list(display).forEach(NativeDialogs::close));
		assertEquals(SWT.NO, box.open(), answers::toString);
		assertEquals(List.of("Native Question"), titles);
		assertEquals("known", answers.get(0), "A dialog already open is not a new one");
		assertTrue(answers.get(1).contains("\"native\""), answers.toString());
		assertTrue(NativeDialogs.list(display).isEmpty());
	}

	@Test
	void aNativeFilePickerIsFoundAndClosed() {
		assumeTrue(SWT.getPlatform().equals("win32"), "native dialogs are only looked for on Windows");
		shell.open();
		FileDialog picker = new FileDialog(shell, SWT.OPEN);
		picker.setText("Native Picker");
		List<String> titles = new ArrayList<>();
		Runnable[] close = new Runnable[1];
		close[0] = () -> {
			List<NativeDialogs.Dialog> dialogs = NativeDialogs.list(display);
			if (dialogs.isEmpty()) {
				display.timerExec(100, close[0]);
			}
			dialogs.forEach(dialog -> {
				titles.add(dialog.title());
				NativeDialogs.close(dialog);
			});
		};
		display.timerExec(100, close[0]);
		assertNull(picker.open());
		assertEquals(List.of("Native Picker"), titles);
	}

	@Test
	void aCustomControlIsNamedThroughItsAccessibilityListener() {
		Canvas canvas = new Canvas(shell, SWT.NONE);
		assertNull(AccessibleNames.of(canvas));
		canvas.getAccessible().addAccessibleListener(new AccessibleAdapter() {
			@Override
			public void getName(AccessibleEvent e) {
				e.result = "Balls to the Wall";
			}
		});
		assertEquals("Balls to the Wall", AccessibleNames.of(canvas));
	}

	@Test
	void aTreeRowReportsEveryColumn() {
		Tree tree = new Tree(shell, SWT.NONE);
		new TreeColumn(tree, SWT.NONE);
		new TreeColumn(tree, SWT.NONE);
		TreeItem row = new TreeItem(tree, SWT.NONE);
		row.setText(new String[] { "Name", "VARCHAR" });
		assertEquals("[\"Name\",\"VARCHAR\"]", WidgetTools.cellsOf(row).toString().replaceAll("\\s", ""));
		assertNull(WidgetTools.cellsOf(new TreeItem(new Tree(shell, SWT.NONE), SWT.NONE)));
		Table table = new Table(shell, SWT.NONE);
		new TableColumn(table, SWT.NONE);
		new TableColumn(table, SWT.NONE);
		TableItem tableRow = new TableItem(table, SWT.NONE);
		tableRow.setText(new String[] { "Id", "INT" });
		assertEquals("[\"Id\",\"INT\"]", WidgetTools.cellsOf(tableRow).toString().replaceAll("\\s", ""));
	}

	@Test
	void aTabWhoseListenerDisposesTheItemStillAnswers() {
		CTabFolder folder = new CTabFolder(shell, SWT.NONE);
		new CTabItem(folder, SWT.NONE).setText("First");
		CTabItem diagram = new CTabItem(folder, SWT.NONE);
		diagram.setText("Diagram");
		folder.addListener(SWT.Selection, event -> diagram.dispose());
		String answer = WidgetTools.SelectTab.apply(folder, diagram, true).toString();
		assertTrue(answer.contains("Diagram"), answer);
		assertTrue(answer.contains("\"selected\""), answer);
		assertFalse(answer.contains("folderDisposed"), answer);
	}

	@Test
	void aTabWhoseListenerDisposesTheFolderStillAnswers() {
		CTabFolder folder = new CTabFolder(shell, SWT.NONE);
		CTabItem tab = new CTabItem(folder, SWT.NONE);
		tab.setText("Diagram");
		folder.addListener(SWT.Selection, event -> folder.dispose());
		String answer = WidgetTools.SelectTab.apply(folder, tab, true).toString();
		assertTrue(answer.contains("Diagram"), answer);
		assertTrue(answer.contains("folderDisposed"), answer);
		assertFalse(answer.contains("The page is rendered"), answer);
	}

	@Test
	void aTabWhoseListenerThrowsStillSwitches() {
		CTabFolder folder = new CTabFolder(shell, SWT.NONE);
		new CTabItem(folder, SWT.NONE).setText("First");
		CTabItem second = new CTabItem(folder, SWT.NONE);
		second.setText("Second");
		folder.addListener(SWT.Selection, event -> {
			throw new IllegalStateException("stale viewer");
		});
		String answer = WidgetTools.SelectTab.apply(folder, second, true).toString();
		assertEquals(second, folder.getSelection());
		assertTrue(answer.contains("stale viewer"), answer);
	}

	@Test
	void aQuietTabSwitchCarriesNoFailureField() {
		CTabFolder folder = new CTabFolder(shell, SWT.NONE);
		CTabItem only = new CTabItem(folder, SWT.NONE);
		only.setText("Only");
		String answer = WidgetTools.SelectTab.apply(folder, only, true).toString();
		assertFalse(answer.contains("listenerFailure"), answer);
	}

	@Test
	void theClipboardIsReadWithoutFailing() {
		assertTrue(ClipboardTool.read(display, 10).toString().contains("hasText"));
	}

	@Test
	void onlyAMoveBeyondRoundingDisturbsThePointer() {
		Point at = new Point(100, 100);
		assertFalse(DragTool.disturbed(at, new Point(102, 98)));
		assertTrue(DragTool.disturbed(at, new Point(103, 100)));
		assertTrue(DragTool.disturbed(at, new Point(100, 90)));
	}
}
