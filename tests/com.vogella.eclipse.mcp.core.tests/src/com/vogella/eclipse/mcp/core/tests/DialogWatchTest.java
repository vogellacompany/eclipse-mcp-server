package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.DialogWatch;

/**
 * Seeing a dialog that work on the UI thread opened and is still inside, on real shells.
 */
class DialogWatchTest {

	// a private Display, disposed again, so no other test sees this thread as the UI thread
	private Display display;

	@BeforeEach
	void open() {
		try {
			display = new Display();
		} catch (SWTError | SWTException | LinkageError e) {
			assumeTrue(false, "no display: " + e.getMessage());
		}
	}

	@AfterEach
	void close() {
		if (display != null) {
			display.dispose();
		}
	}

	@Test
	void aModalDialogIsSeenWhileTheWorkThatOpenedItIsStillRunning() throws Exception {
		Shell parent = new Shell(display);
		DialogWatch watch = new DialogWatch();
		CompletableFuture<Void> work = new CompletableFuture<>();
		CompletableFuture<DialogWatch.Seen> seen = new CompletableFuture<>();
		display.asyncExec(() -> {
			watch.mark(display);
			Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
			dialog.setText("Held");
			dialog.open();
			// the nested loop a modal dialog runs
			while (!seen.isDone()) {
				if (!display.readAndDispatch()) {
					display.sleep();
				}
			}
			dialog.dispose();
			work.complete(null);
		});
		Thread waiter = new Thread(() -> {
			try {
				seen.complete(watch.await(work, System.currentTimeMillis() + 10_000));
			} catch (Throwable e) {
				seen.completeExceptionally(e);
			}
			display.wake();
		});
		waiter.start();
		long end = System.currentTimeMillis() + 15_000;
		while (!work.isDone() && System.currentTimeMillis() < end) {
			if (!display.readAndDispatch()) {
				display.sleep();
			}
		}
		DialogWatch.Seen dialog = seen.get(1, TimeUnit.SECONDS);
		assertNotNull(dialog, "the dialog was not seen while the work was inside it");
		assertEquals("Held", dialog.title());
		assertFalse(dialog.nativeDialog());
	}

	@Test
	void workThatFinishedOpensNoDialog() throws Exception {
		assertNull(new DialogWatch().await(CompletableFuture.completedFuture(null), System.currentTimeMillis() + 1_000));
	}
}
