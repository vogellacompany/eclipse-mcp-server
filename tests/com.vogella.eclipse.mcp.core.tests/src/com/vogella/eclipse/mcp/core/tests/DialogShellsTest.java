package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.ui.internal.CloseWatch;
import com.vogella.eclipse.mcp.ui.internal.DismissDialogTool;
import com.vogella.eclipse.mcp.ui.internal.DragTool;
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
	void onlyAMoveBeyondRoundingDisturbsThePointer() {
		Point at = new Point(100, 100);
		assertFalse(DragTool.disturbed(at, new Point(102, 98)));
		assertTrue(DragTool.disturbed(at, new Point(103, 100)));
		assertTrue(DragTool.disturbed(at, new Point(100, 90)));
	}
}
