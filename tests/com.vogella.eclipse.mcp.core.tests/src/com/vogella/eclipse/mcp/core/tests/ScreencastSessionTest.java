package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.ui.internal.Screencast;

/**
 * Stopping and resuming a recording around a frame that is being painted or
 * is still due, on a real shell.
 */
class ScreencastSessionTest {

	private static final int INTERVAL = 100;

	@TempDir
	Path directory;

	private Display display;
	private Canvas canvas;

	@BeforeEach
	void open() {
		try {
			display = new Display();
		} catch (SWTError | SWTException | LinkageError e) {
			assumeTrue(false, "no display: " + e.getMessage());
			return;
		}
		Shell shell = new Shell(display);
		shell.setLayout(new FillLayout());
		canvas = new Canvas(shell, SWT.NONE);
		shell.setSize(200, 100);
		shell.open();
		while (display.readAndDispatch()) {
			// let the window system place the shell
		}
	}

	@AfterEach
	void close() {
		Screencast.Session latest = Screencast.getInstance().findLatest();
		if (latest != null) {
			latest.stop("test end");
		}
		if (display != null) {
			display.dispose();
		}
	}

	@Test
	void aStopDuringThePaintDropsTheFrameInsteadOfFailingTheTick() {
		AtomicBoolean stopped = new AtomicBoolean();
		// the print reaches this listener in the middle of the first tick
		canvas.addListener(SWT.Paint, event -> {
			if (stopped.compareAndSet(false, true)) {
				Screencast.getInstance().findLatest().stop("stop");
			}
		});

		Screencast.Session session = start();

		assumeTrue(stopped.get(), "printing a control raises no paint event on this platform");
		assertFalse(session.running());
		assertEquals("stop", session.stoppedBy());
		assertEquals(0, session.frames(), "the frame painted across the stop is not recorded");
		assertTrue(session.awaitEncoded(5));
		assertEquals(0, session.failed());
	}

	@Test
	void aTimerArmedBeforeAStopDoesNotRecordBesideTheResumedSegment() throws InterruptedException {
		Screencast.Session session = start();
		session.stop("stop");
		long resumed = System.currentTimeMillis();
		Screencast.getInstance().resume(display, session, 1000, 0, null, null);

		while (System.currentTimeMillis() - resumed < 10 * INTERVAL) {
			if (!display.readAndDispatch()) {
				Thread.sleep(5);
			}
		}
		session.stop("stop");
		long elapsed = System.currentTimeMillis() - resumed;
		assertTrue(session.awaitEncoded(5));

		// one frame per segment start, then one timer chain; a second chain doubles the rest
		long most = 2 + elapsed / INTERVAL + 2;
		assertTrue(session.frames() <= most,
				"%d frames in %d ms, more than one timer can paint".formatted(Integer.valueOf(session.frames()),
						Long.valueOf(elapsed)));
	}

	private Screencast.Session start() {
		return Screencast.getInstance().start(display, canvas, false, "canvas", INTERVAL, 1000, 0, directory, null,
				null, null);
	}
}
