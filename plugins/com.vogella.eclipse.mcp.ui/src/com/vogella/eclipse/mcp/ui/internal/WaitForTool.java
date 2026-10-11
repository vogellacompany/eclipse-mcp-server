package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Waits until shells, widgets, the selection, editors or jobs reach a state, and reports what it last saw.
 */
public final class WaitForTool implements IMcpTool {

	/** The events after which a condition is likely to have changed, so the next probe runs at once. */
	private static final int[] WAKING_EVENTS = { SWT.Activate, SWT.Deactivate, SWT.Show, SWT.Hide, SWT.Dispose,
			SWT.Selection, SWT.Modify, SWT.Expand, SWT.Collapse };

	/** The least a probe waits for the UI thread, so the probe at the deadline is not counted as a busy UI. */
	private static final long PROBE_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

	private static final long SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

	@Override
	public String getName() {
		return "eclipse_wait_for"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Waits until IDE conditions hold and answers as soon as they do, instead of a fixed pause before a check. Changes nothing. Each condition has a kind: 'shell' (a visible shell whose title contains shellTitle is open, closed or active), 'widget' (a widget addressed like eclipse_get_widget_tree, by part or shell plus path, or by 'find', the first widget, row or item in tree order whose text contains it case insensitively, exists and has every given property: exists, selected, enabled, visible, textEquals, textContains, rowCount, minRowCount), 'selection' (the selection of a part, or the workbench selection without one, is empty or holds an element whose label or workspace path contains 'contains', or, in an editor, selected text that contains it), 'editor' (an editor whose input path, exact when it starts with /, or title contains 'input' is open, closed or active, optionally dirty or not) and 'jobs' (no job is running or waiting: scope 'all' counts user jobs, 'build' the build and refresh jobs including a build scheduled to start, jobName restricts to jobs whose name contains it). match 'all' needs every condition, 'any' one. shellTitle and contains are case sensitive. It polls every pollMillis and also probes at once after SWT events that change such state (activation, show, hide, dispose, selection, modify, expand) and after a job ends. WHAT IT CANNOT SEE: rows under a collapsed node and virtual rows not populated yet, which find skips rather than populate (a row addressed by path is read and so populated), sleeping jobs outside scope build, and work that runs outside jobs, such as reconcilers and queued asyncExec runnables (eclipse_wait_until_settled covers those). A part is never created by the wait: a part that is not open counts as not there. A path that does not resolve is not an error, it is a widget that does not exist yet. Jobs conditions are read off the UI thread, so one probe does not see all conditions at the same instant. RUNNING OUT IS AN ANSWER, NOT AN ERROR: met false and timedOut true, with each condition's last observed state. The wait is capped below the server's tool call timeout; when that cut it short, stillWaiting is true and calling again continues. A probe the UI thread did not answer marks the widget conditions uiBusy, while jobs conditions keep their answer, and a cancelled call answers cancelled. Inside eclipse_run_script with atomic it refuses, because a wait on the UI thread blocks the thread it waits for."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "conditions": {"type":"array","minItems":1,"maxItems":10,"description":"What to wait for.","items":{
				      "type":"object",
				      "properties":{
				        "kind":         {"type":"string","enum":["shell","widget","selection","editor","jobs"]},
				        "shellTitle":   {"type":"string","description":"shell: title substring, case sensitive. widget: the shell to look in, as in eclipse_get_widget_tree."},
				        "shell":        {"type":"string","description":"widget: shell independent of title, 'popup', an index or bounds from eclipse_list_ui_targets. Wins over shellTitle."},
				        "state":        {"type":"string","enum":["open","closed","active"],"default":"open","description":"shell and editor."},
				        "part":         {"type":"string","description":"widget: the part the path or find is rooted in. selection: the part whose selection provider is read."},
				        "path":         {"type":"string","description":"widget: a path from eclipse_get_widget_tree, such as 1/0, 0/i2 or 0/r25. With neither path nor find the part or shell itself is checked, which waits for it to exist."},
				        "find":         {"type":"string","description":"widget: instead of path, the first widget, row or item whose text contains this, case insensitively. Rows under a collapsed node do not exist yet."},
				        "exists":       {"type":"boolean","default":true,"description":"widget: false waits for it to disappear."},
				        "selected":     {"type":"boolean","description":"widget: a row in its Tree or Table selection, a check box, toggle or radio, a tab."},
				        "enabled":      {"type":"boolean"},
				        "visible":      {"type":"boolean"},
				        "textEquals":   {"type":"string"},
				        "textContains": {"type":"string"},
				        "rowCount":     {"type":"integer","minimum":0,"description":"widget: top level rows of a Tree or Table, children of a tree row, items of a Combo or List."},
				        "minRowCount":  {"type":"integer","minimum":0},
				        "contains":     {"type":"string","description":"selection: substring of an element's label or workspace path."},
				        "empty":        {"type":"boolean","description":"selection."},
				        "input":        {"type":"string","description":"editor: workspace path (exact, starting with /) or a substring of the path or the editor title, case sensitive."},
				        "dirty":        {"type":"boolean","description":"editor."},
				        "scope":        {"type":"string","enum":["all","build"],"default":"all","description":"jobs."},
				        "jobName":      {"type":"string","description":"jobs: only jobs whose name contains this, system jobs included."}
				      },
				      "required":["kind"],
				      "additionalProperties": false}},
				    "match":          {"type":"string","enum":["all","any"],"default":"all"},
				    "timeoutSeconds": {"type":"integer","default":10,"minimum":1,"maximum":900,"description":"How long to wait at most, capped below the tool call timeout; the answer says clamped and stillWaiting when it was."},
				    "pollMillis":     {"type":"integer","default":100,"minimum":20,"maximum":2000,"description":"The longest gap between probes when no event wakes it."}
				  },
				  "required": ["conditions"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		List<WaitCondition> conditions = new ArrayList<>();
		if (!(arguments.get("conditions") instanceof List<?> list) || list.isEmpty()) { //$NON-NLS-1$
			return McpToolResult.error("The argument 'conditions' is required: a list of objects with a kind of shell, widget, selection, editor or jobs."); //$NON-NLS-1$
		}
		try {
			for (Object value : list) {
				conditions.add(WaitCondition.parse(value));
			}
		} catch (IllegalArgumentException e) {
			return McpToolResult.error(e.getMessage());
		}
		String match = args.getString("match", "all"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!"all".equals(match) && !"any".equals(match)) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("'match' is all or any, not '%s'.".formatted(match)); //$NON-NLS-1$
		}
		if (!Workbenches.running()) {
			return McpToolResult.error(UiThread.NO_WORKBENCH);
		}
		if (UiThread.onUiThread()) {
			return McpToolResult.error("eclipse_wait_for cannot wait inside eclipse_run_script with atomic, because the UI thread it would wait on is the one running the script."); //$NON-NLS-1$
		}
		int requested = args.getInt("timeoutSeconds", 10, 1, 900); //$NON-NLS-1$
		int pollMillis = args.getInt("pollMillis", 100, 20, 2000); //$NON-NLS-1$
		int budget = CallBudget.boundedWaitSeconds(requested);
		Display display = Workbenches.display();
		if (display == null || display.isDisposed()) {
			return McpToolResult.error(UiThread.NO_WORKBENCH);
		}
		return McpToolResult.of(waitFor(display, conditions, "any".equals(match), requested, budget, //$NON-NLS-1$
				pollMillis, monitor).toString());
	}

	/** Waits off the UI thread of {@code display}, which may be null when no condition needs it, until the conditions hold or {@code budget} seconds have passed. */
	public static JsonObject waitFor(Display display, List<WaitCondition> conditions, boolean any, int requested,
			int budget, int pollMillis, IProgressMonitor monitor) {
		long start = System.nanoTime();
		long deadline = start + TimeUnit.SECONDS.toNanos(budget);
		Wake wake = new Wake();
		Listener listener = event -> wake.signal();
		IJobChangeListener jobs = new JobChangeAdapter() {
			@Override
			public void done(IJobChangeEvent event) {
				wake.signal();
			}
		};
		boolean watchJobs = conditions.stream().anyMatch(condition -> !condition.needsUi());
		boolean watchUi = conditions.stream().anyMatch(WaitCondition::needsUi);
		int probes = 0;
		long uiBusyNanos = 0;
		List<WaitCondition.Result> last = null;
		boolean met = false;
		boolean cancelled = false;
		try {
			if (watchJobs) {
				Job.getJobManager().addJobChangeListener(jobs);
			}
			if (watchUi) {
				try {
					display.asyncExec(() -> {
						try {
							for (int type : WAKING_EVENTS) {
								display.addFilter(type, listener);
							}
						} catch (SWTException e) {
							// disposed after this was queued, which the probe reports
						}
					});
				} catch (SWTException e) {
					// the probe reports the disposed display
				}
			}
			while (true) {
				long probeStart = System.nanoTime();
				Probe probe = probe(display, conditions, any, deadline, monitor);
				probes++;
				if (probe.uiBusy()) {
					uiBusyNanos += System.nanoTime() - probeStart;
				}
				last = probe.results();
				met = any ? last.stream().anyMatch(WaitCondition.Result::met)
						: last.stream().allMatch(WaitCondition.Result::met);
				cancelled = monitor != null && monitor.isCanceled() || Thread.currentThread().isInterrupted();
				long remaining = deadline - System.nanoTime();
				if (met || remaining <= 0 || cancelled) {
					break;
				}
				wake.await(Math.min(TimeUnit.MILLISECONDS.toNanos(pollMillis), remaining));
			}
		} finally {
			if (watchJobs) {
				Job.getJobManager().removeJobChangeListener(jobs);
			}
			if (watchUi) {
				try {
					display.asyncExec(() -> {
						try {
							for (int type : WAKING_EVENTS) {
								display.removeFilter(type, listener);
							}
						} catch (SWTException e) {
							// disposed after this was queued, which the probe reports
						}
					});
				} catch (SWTException e) {
					// the display is gone, and its filters with it
				}
			}
		}
		return answer(conditions, last, met, cancelled, requested, budget, start, probes, uiBusyNanos);
	}

	private static JsonObject answer(List<WaitCondition> conditions, List<WaitCondition.Result> last, boolean met,
			boolean cancelled, int requested, int budget, long start, int probes, long uiBusyNanos) {
		long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		boolean clamped = budget < requested;
		boolean timedOut = !met && !cancelled;
		JsonArray observed = new JsonArray();
		for (int i = 0; i < conditions.size(); i++) {
			observed.add(new JsonObject().put("kind", conditions.get(i).kind()) //$NON-NLS-1$
					.put("met", Boolean.valueOf(last.get(i).met())).put("observed", last.get(i).observed())); //$NON-NLS-1$ //$NON-NLS-2$
		}
		JsonObject answer = new JsonObject().put("met", Boolean.valueOf(met)).put("timedOut", Boolean.valueOf(timedOut)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("stillWaiting", Boolean.valueOf(timedOut && clamped)) //$NON-NLS-1$
				.put("elapsedMillis", Long.valueOf(elapsed)).put("probes", Integer.valueOf(probes)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("requestedSeconds", Integer.valueOf(requested)).put("budgetSeconds", Integer.valueOf(budget)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("clamped", Boolean.valueOf(clamped)).put("conditions", observed); //$NON-NLS-1$ //$NON-NLS-2$
		if (cancelled && !met) {
			answer.put("cancelled", Boolean.TRUE); //$NON-NLS-1$
		}
		if (uiBusyNanos > 0) {
			answer.put("uiBusyMillis", Long.valueOf(TimeUnit.NANOSECONDS.toMillis(uiBusyNanos))); //$NON-NLS-1$
		}
		if (cancelled && !met) {
			answer.put("note", "The call was cancelled before the conditions held; 'conditions' shows what each one last observed."); //$NON-NLS-1$ //$NON-NLS-2$
		} else if (timedOut) {
			answer.put("note", clamped //$NON-NLS-1$
					? "Not met within %d seconds, as long as one call may wait before the server's tool call timeout; call again to go on waiting.".formatted(Integer.valueOf(budget)) //$NON-NLS-1$
					: "Not met within %d seconds; 'conditions' shows what each one last observed.".formatted(Integer.valueOf(budget))); //$NON-NLS-1$
		}
		return answer;
	}

	/** One evaluation of every condition; {@code uiBusy} when the UI thread did not run its part before the deadline. */
	private record Probe(List<WaitCondition.Result> results, boolean uiBusy) {
	}

	/**
	 * Evaluates the conditions, the ones without widgets right here. When the UI thread
	 * does not get to its part, those are reported as not met and uiBusy, while the others keep their answer.
	 */
	private static Probe probe(Display display, List<WaitCondition> conditions, boolean any, long deadline,
			IProgressMonitor monitor) {
		List<WaitCondition.Result> results = new ArrayList<>();
		boolean needsUi = false;
		for (WaitCondition condition : conditions) {
			results.add(condition.needsUi() ? null : condition.evaluate(display));
			needsUi |= condition.needsUi();
		}
		if (!needsUi) {
			return new Probe(results, false);
		}
		CompletableFuture<List<WaitCondition.Result>> pending = new CompletableFuture<>();
		try {
			display.asyncExec(() -> {
				if (pending.isDone()) {
					return;
				}
				try {
					List<WaitCondition.Result> onUi = new ArrayList<>(results);
					for (int i = 0; i < conditions.size(); i++) {
						if (conditions.get(i).needsUi()) {
							onUi.set(i, conditions.get(i).evaluate(display));
						}
					}
					pending.complete(onUi);
				} catch (Throwable e) {
					pending.completeExceptionally(e);
				}
			});
			long until = Math.max(deadline, System.nanoTime() + PROBE_GRACE_NANOS);
			while (true) {
				try {
					return new Probe(pending.get(Math.min(SLICE_NANOS, Math.max(1, until - System.nanoTime())),
							TimeUnit.NANOSECONDS), false);
				} catch (TimeoutException e) {
					// sliced so that a cancelled call, or a jobs condition that alone settles match any, stops waiting on a busy UI thread
					if (System.nanoTime() >= until || monitor != null && monitor.isCanceled()) {
						throw e;
					}
					if (any) {
						List<WaitCondition.Result> refreshed = offUi(display, conditions, results);
						if (refreshed.stream().anyMatch(result -> result != null && result.met())) {
							pending.cancel(false);
							return new Probe(fill(refreshed, new JsonObject().put("uiBusy", Boolean.TRUE)), true); //$NON-NLS-1$
						}
					}
				}
			}
		} catch (TimeoutException e) {
			pending.cancel(false);
			// the jobs results are as old as the wait on the UI thread, which can be the whole budget
			return new Probe(fill(offUi(display, conditions, results), new JsonObject().put("uiBusy", Boolean.TRUE)), true); //$NON-NLS-1$
		} catch (InterruptedException e) {
			pending.cancel(false);
			Thread.currentThread().interrupt();
			return new Probe(fill(results, new JsonObject().put("interrupted", Boolean.TRUE)), false); //$NON-NLS-1$
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			return new Probe(fill(results, new JsonObject().put("error", Workbenches.describe(cause))), false); //$NON-NLS-1$
		} catch (SWTException e) {
			return new Probe(fill(results, new JsonObject().put("error", "The display is disposed.")), false); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/** The results with the conditions that do not need the UI thread evaluated again. */
	private static List<WaitCondition.Result> offUi(Display display, List<WaitCondition> conditions,
			List<WaitCondition.Result> results) {
		List<WaitCondition.Result> refreshed = new ArrayList<>(results);
		for (int i = 0; i < conditions.size(); i++) {
			if (!conditions.get(i).needsUi()) {
				refreshed.set(i, conditions.get(i).evaluate(display));
			}
		}
		return refreshed;
	}

	/** The results with every condition the UI thread did not evaluate set to unmet with {@code observed}. */
	private static List<WaitCondition.Result> fill(List<WaitCondition.Result> results, JsonObject observed) {
		List<WaitCondition.Result> filled = new ArrayList<>(results);
		for (int i = 0; i < filled.size(); i++) {
			if (filled.get(i) == null) {
				filled.set(i, new WaitCondition.Result(false, observed));
			}
		}
		return filled;
	}

	/** Lets an event cut the gap between two probes short. */
	private static final class Wake {

		private static final long MIN_GAP_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

		private boolean signalled;

		synchronized void signal() {
			signalled = true;
			notifyAll();
		}

		/** Waits up to {@code nanos}, and at least {@link #MIN_GAP_NANOS} so a stream of events cannot turn the wait into a busy loop. */
		synchronized void await(long nanos) {
			long now = System.nanoTime();
			long until = now + nanos;
			long earliest = now + Math.min(nanos, MIN_GAP_NANOS);
			try {
				for (long left = earliest - now; left > 0; left = earliest - System.nanoTime()) {
					TimeUnit.NANOSECONDS.timedWait(this, left);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			try {
				while (!signalled) {
					long left = until - System.nanoTime();
					if (left <= 0) {
						break;
					}
					TimeUnit.NANOSECONDS.timedWait(this, left);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			signalled = false;
		}
	}
}
