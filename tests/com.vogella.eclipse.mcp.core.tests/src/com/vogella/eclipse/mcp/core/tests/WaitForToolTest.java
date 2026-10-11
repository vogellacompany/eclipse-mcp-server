package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.ui.internal.UiThread;
import com.vogella.eclipse.mcp.ui.internal.WaitCondition;
import com.vogella.eclipse.mcp.ui.internal.WaitForTool;

/**
 * The conditions of eclipse_wait_for evaluated against a real shell, and the configurable UI wait.
 */
class WaitForToolTest {

	// a private Display, disposed again, so no other test sees this thread as the UI thread
	private Display display;

	@AfterEach
	void close() {
		if (display != null) {
			display.dispose();
		}
	}

	private Shell shell(String title) {
		if (display == null) {
			try {
				display = new Display();
			} catch (SWTError | SWTException | LinkageError e) {
				assumeTrue(false, "no display: " + e.getMessage());
			}
		}
		Shell shell = new Shell(display);
		shell.setText(title);
		return shell;
	}

	private WaitCondition.Result evaluate(Map<String, Object> condition) {
		return WaitCondition.parse(condition).evaluate(display);
	}

	@Test
	void malformedConditionsAreRefusedBeforeAnyWait() {
		assertThrows(IllegalArgumentException.class, () -> WaitCondition.parse(Map.of("kind", "window")));
		assertThrows(IllegalArgumentException.class, () -> WaitCondition.parse(Map.of("kind", "shell")));
		assertThrows(IllegalArgumentException.class,
				() -> WaitCondition.parse(Map.of("kind", "widget", "path", "first/row")));
		assertThrows(IllegalArgumentException.class,
				() -> WaitCondition.parse(Map.of("kind", "widget", "path", "0", "find", "x")));
		assertThrows(IllegalArgumentException.class,
				() -> WaitCondition.parse(Map.of("kind", "shell", "shellTitle", "New", "state", "gone")));
		assertThrows(IllegalArgumentException.class, () -> WaitCondition.parse(Map.of("kind", "selection")));
		assertThrows(IllegalArgumentException.class,
				() -> WaitCondition.parse(Map.of("kind", "widget", "path", "0", "selected", "yes")));
		assertThrows(IllegalArgumentException.class,
				() -> WaitCondition.parse(Map.of("kind", "widget", "path", "0", "rowCount", "many")));
		assertThrows(IllegalArgumentException.class, () -> WaitCondition
				.parse(Map.of("kind", "editor", "input", "pom.xml", "state", "closed", "dirty", Boolean.TRUE)));

		McpToolResult missing = new WaitForTool().call(Map.of(), null);
		assertTrue(missing.isError() && missing.text().contains("'conditions' is required"), missing.text());
		McpToolResult match = new WaitForTool().call(Map.of("conditions",
				List.of(Map.of("kind", "jobs")), "match", "most"), null);
		assertTrue(match.isError() && match.text().contains("'match'"), match.text());
	}

	/** Runs the wait on a worker while this thread, the display's own, dispatches its events. */
	private JsonObject waitFor(List<Map<String, Object>> conditions, boolean any, int seconds, int pollMillis) {
		return waitFor(conditions, any, seconds, seconds, pollMillis);
	}

	private JsonObject waitFor(List<Map<String, Object>> conditions, boolean any, int requested, int budget,
			int pollMillis) {
		List<WaitCondition> parsed = conditions.stream().map(WaitCondition::parse).toList();
		CompletableFuture<JsonObject> answer = CompletableFuture
				.supplyAsync(() -> WaitForTool.waitFor(display, parsed, any, requested, budget, pollMillis, null));
		while (!answer.isDone()) {
			// no display.sleep(): the answer completing posts nothing that would wake it
			if (!display.readAndDispatch()) {
				java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
			}
		}
		return answer.join();
	}

	@Test
	void anEventEndsTheWaitLongBeforeThePollWouldHave() {
		Shell shell = shell("Late Wizard");
		display.timerExec(200, shell::open);

		String answer = waitFor(List.of(Map.of("kind", "shell", "shellTitle", "Late Wizard")), false, 10, 2000).toString();

		assertTrue(answer.contains("\"met\": true"), answer);
		long elapsed = Long.parseLong(answer.replaceAll("(?s).*\"elapsedMillis\": (\\d+).*", "$1"));
		assertTrue(elapsed < 1500, "a Show or Activate event should wake the wait, took " + elapsed);
	}

	@Test
	void anyNeedsOneConditionAndAllNeedsEvery() {
		shell("Present").open();
		List<Map<String, Object>> conditions = List.of(Map.of("kind", "shell", "shellTitle", "Absent"),
				Map.of("kind", "shell", "shellTitle", "Present"));

		assertTrue(waitFor(conditions, true, 5, 50).toString().contains("\"met\": true"));
		String all = waitFor(conditions, false, 1, 50).toString();
		assertTrue(all.contains("\"met\": false") && all.contains("\"timedOut\": true")
				&& all.contains("\"stillWaiting\": false"), all);
	}

	@Test
	void aBusyUiThreadMarksOnlyTheWidgetConditions() {
		shell("Never Asked");
		List<WaitCondition> conditions = List.of(
				WaitCondition.parse(Map.of("kind", "jobs", "jobName", "no job is called this")),
				WaitCondition.parse(Map.of("kind", "shell", "shellTitle", "Never Asked")));

		// this thread owns the display and does not dispatch, so the UI part never runs
		String answer = CompletableFuture
				.supplyAsync(() -> WaitForTool.waitFor(display, conditions, true, 1, 1, 50, null)).join().toString();

		assertTrue(answer.contains("\"met\": true"), "the jobs condition holds without the UI thread: " + answer);
		assertTrue(answer.contains("\"uiBusy\": true") && answer.contains("\"uiBusyMillis\""), answer);
	}

	@Test
	void aShellIsOpenOnlyWhileItIsVisible() {
		Shell shell = shell("New Plug-in Project");
		Map<String, Object> open = Map.of("kind", "shell", "shellTitle", "New");
		Map<String, Object> closed = Map.of("kind", "shell", "shellTitle", "New", "state", "closed");

		assertFalse(evaluate(open).met());
		assertTrue(evaluate(closed).met());

		shell.open();
		WaitCondition.Result result = evaluate(open);
		assertTrue(result.met());
		assertTrue(result.observed().toString().contains("\"title\": \"New Plug-in Project\""), result.observed().toString());
		assertFalse(evaluate(closed).met());

		shell.dispose();
		assertTrue(evaluate(closed).met());
	}

	@Test
	void aRowFoundByTextIsWaitedForUntilItIsSelected() {
		Shell shell = shell("Rows");
		Tree tree = new Tree(shell, SWT.SINGLE);
		new TreeItem(tree, SWT.NONE).setText("first");
		TreeItem wanted = new TreeItem(tree, SWT.NONE);
		wanted.setText("com.example.e4.rcp");
		shell.open();
		Map<String, Object> condition = Map.of("kind", "widget", "shellTitle", "Rows", "find", "E4.RCP", "selected", Boolean.TRUE);

		WaitCondition.Result before = evaluate(condition);
		assertFalse(before.met());
		assertTrue(before.observed().toString().contains("\"selected\": false"), before.observed().toString());

		tree.setSelection(wanted);
		assertTrue(evaluate(condition).met());
	}

	@Test
	void widgetPropertiesAreAllChecked() {
		Shell shell = shell("Props");
		Tree tree = new Tree(shell, SWT.NONE);
		new TreeItem(tree, SWT.NONE).setText("a");
		new TreeItem(tree, SWT.NONE).setText("b");
		Button button = new Button(shell, SWT.CHECK);
		button.setText("Generate an &activator for R&&D");
		shell.open();

		assertTrue(evaluate(Map.of("kind", "widget", "shellTitle", "Props", "path", "0", "rowCount", Integer.valueOf(2))).met());
		assertFalse(evaluate(Map.of("kind", "widget", "shellTitle", "Props", "path", "0", "minRowCount", Integer.valueOf(3))).met());
		assertTrue(evaluate(Map.of("kind", "widget", "shellTitle", "Props", "path", "1", "textContains", "activator",
				"enabled", Boolean.TRUE, "selected", Boolean.FALSE)).met());

		assertTrue(evaluate(Map.of("kind", "widget", "shellTitle", "Props", "path", "1", "textEquals",
				"Generate an activator for R&D")).met());

		button.setEnabled(false);
		assertFalse(evaluate(Map.of("kind", "widget", "shellTitle", "Props", "path", "1", "enabled", Boolean.TRUE)).met());
	}

	@Test
	void anUnresolvedPathIsAWidgetThatDoesNotExistYet() {
		Shell shell = shell("Empty");
		shell.open();

		WaitCondition.Result result = evaluate(Map.of("kind", "widget", "shellTitle", "Empty", "path", "3/r1"));
		assertFalse(result.met());
		assertTrue(result.observed().toString().contains("\"exists\": false"), result.observed().toString());
		assertTrue(evaluate(Map.of("kind", "widget", "shellTitle", "Empty", "path", "3/r1", "exists", Boolean.FALSE)).met());
	}

	@Test
	void aRunningJobKeepsTheJobsConditionUnmet() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(1);
		Job job = new Job("wait-for-test job") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				started.countDown();
				try {
					release.await(20, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return Status.OK_STATUS;
			}
		};
		job.setSystem(true);
		Map<String, Object> condition = Map.of("kind", "jobs", "jobName", "WAIT-FOR-TEST");
		job.schedule();
		try {
			assertTrue(started.await(10, TimeUnit.SECONDS));
			WaitCondition.Result busy = WaitCondition.parse(condition).evaluate(null);
			assertFalse(busy.met());
			assertTrue(busy.observed().toString().contains("wait-for-test job"), busy.observed().toString());
		} finally {
			release.countDown();
			job.join(10_000, null);
		}
		assertTrue(WaitCondition.parse(condition).evaluate(null).met());
	}

	@Test
	void aWithdrawnRequestIsMarkedForAClientToRetry() {
		McpToolResult withdrawn = new UiThread.Outcome(null, "prose", Integer.valueOf(25)).errorResult();
		McpToolResult failed = new UiThread.Outcome(null, "The part was disposed.", null).errorResult();

		assertTrue(withdrawn.isError() && withdrawn.text().contains("\"withdrawn\": true")
				&& withdrawn.text().contains("\"waitedSeconds\": 25") && withdrawn.text().contains("will not run later"),
				withdrawn.text());
		assertEquals("The part was disposed.", failed.text(), "only a withdrawn request answers JSON");
	}

	@Test
	void aJobEndingWakesTheWait() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		Job job = new Job("wait-for-wakeup job") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					release.await(20, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return Status.OK_STATUS;
			}
		};
		job.schedule();
		List<WaitCondition> conditions = List.of(WaitCondition.parse(Map.of("kind", "jobs", "jobName", "wait-for-wakeup")));
		new Thread(() -> {
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			release.countDown();
		}).start();

		// jobs conditions never need the UI thread, so no display is involved
		String answer = WaitForTool.waitFor(null, conditions, false, 10, 10, 2000, null).toString();

		assertTrue(answer.contains("\"met\": true"), answer);
		long elapsed = Long.parseLong(answer.replaceAll("(?s).*\"elapsedMillis\": (\\d+).*", "$1"));
		assertTrue(elapsed < 1500, "the job's end should wake the wait, took " + elapsed);
	}

	@Test
	void aWaitCutShortByTheCallTimeoutSaysToCallAgain() {
		shell("Clamp");
		String answer = waitFor(List.of(Map.of("kind", "shell", "shellTitle", "Absent")), false, 10, 1, 50).toString();

		assertTrue(answer.contains("\"clamped\": true") && answer.contains("\"stillWaiting\": true")
				&& answer.contains("\"timedOut\": true"), answer);
	}

	@Test
	void aCancelledCallAnswersCancelledRatherThanTimedOut() {
		NullProgressMonitor monitor = new NullProgressMonitor();
		monitor.setCanceled(true);
		Job blocker = new Job("wait-for-cancel job") {
			@Override
			protected IStatus run(IProgressMonitor jobMonitor) {
				while (!jobMonitor.isCanceled()) {
					try {
						Thread.sleep(20);
					} catch (InterruptedException e) {
						return Status.CANCEL_STATUS;
					}
				}
				return Status.CANCEL_STATUS;
			}
		};
		blocker.schedule();
		try {
			String cancelled = WaitForTool.waitFor(null,
					List.of(WaitCondition.parse(Map.of("kind", "jobs", "jobName", "wait-for-cancel"))), false, 10, 10, 50,
					monitor).toString();
			assertTrue(cancelled.contains("\"cancelled\": true") && cancelled.contains("\"timedOut\": false"), cancelled);
		} finally {
			blocker.cancel();
		}
	}
}
