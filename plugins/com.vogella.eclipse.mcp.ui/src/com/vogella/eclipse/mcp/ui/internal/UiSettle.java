package com.vogella.eclipse.mcp.ui.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Waits until the UI looks to have stopped working, and says what it could not
 * see while deciding that.
 * <p>
 * THIS IS A HEURISTIC AND THE ANSWER SAYS SO. Three things are observed: work
 * queued with {@code asyncExec}, which a fence posted to the Display proves has
 * drained; the job manager, which reports what is running, waiting or sleeping;
 * and the text editors' reconcilers, through {@link Reconcilers}, which is the
 * only one of the three that is not public API.
 * <p>
 * The reconciler is there because it was the case that hurt most: it starts as a
 * job and hands over to a plain daemon thread, so it is invisible to the other
 * two, and semantic highlighting landed after everything observable had gone
 * quiet. Reading it costs a reflective reach into internals that can change in
 * any release, which is why an unreadable reconciler counts as busy rather than
 * idle.
 * <p>
 * What remains unobservable is any OTHER plain background thread, and work that
 * has not been scheduled yet.
 * <p>
 * So this reduces flakiness and cannot remove it. A caller that needs to be
 * right asserts the thing it cares about, the way a content assist scenario
 * checks that the proposal rows are there rather than waiting for them to be.
 * <p>
 * The wait is bounded by the server's call timeout and stops when the call is
 * cancelled, so an abandoned call does not keep posting fences.
 */
final class UiSettle {

	/** Long enough that a busy UI thread is visible, short enough not to dominate a pass. */
	private static final long FENCE_BUDGET_MILLIS = 250;

	private UiSettle() {
	}

	/**
	 * Runs rounds of fence-then-check until {@code quietPasses} consecutive ones
	 * find nothing, or the budget runs out.
	 *
	 * @param quietPasses how many consecutive quiet rounds count as settled
	 * @param timeoutMillis the whole budget, after which it answers unsettled
	 * @param pauseMillis how long to wait between rounds, which is what gives
	 *        work scheduled with a small delay a chance to appear
	 * @param monitor cancelled when the caller gave up, may be {@code null}
	 */
	static JsonObject settle(int quietPasses, long timeoutMillis, long pauseMillis, IProgressMonitor monitor) {
		if (UiThread.onUiThread()) {
			// a fence posted from the UI thread would be run by the very thread that
			// is waiting for it, so it proves nothing and could not drain anything
			// that is not already done
			return new JsonObject().put("settled", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", //$NON-NLS-1$
							"This call is already on the UI thread, which is what eclipse_run_script with atomic does. Nothing can drain the display queue from inside it, because the runnables waiting there are behind this one. Settle outside the atomic batch, before it starts.");
		}
		Display display = Workbenches.display();
		long requested = timeoutMillis;
		// the server aborts the call at its timeout and the loop would carry on
		// without anyone to answer, posting fences into an IDE nobody is waiting on
		timeoutMillis = Math.min(timeoutMillis, CallBudget.maxWaitSeconds() * 1000L);
		String clamped = timeoutMillis < requested
				? "Waited %d of the %d seconds asked for, because the server aborts any call that outlasts its %d second tool call timeout, which is set in Preferences > General > MCP Server. Call again to keep waiting." //$NON-NLS-1$
						.formatted(Long.valueOf(timeoutMillis / 1000), Long.valueOf(requested / 1000),
								Integer.valueOf(CallBudget.callTimeoutSeconds()))
				: null;
		long deadline = System.currentTimeMillis() + timeoutMillis;
		long started = System.currentTimeMillis();
		int rounds = 0;
		int consecutive = 0;
		long slowestFence = 0;
		long slowestProbe = 0;
		JsonObject jobs = null;
		Reconcilers.State reconcilers = null;
		while (System.currentTimeMillis() < deadline) {
			if (monitor != null && monitor.isCanceled()) {
				return answer(false, rounds, consecutive, started, slowestFence, slowestProbe, jobs, reconcilers,
						"The call was cancelled before the UI settled.", clamped); //$NON-NLS-1$
			}
			rounds++;
			// one hop does both: the fence proves the queue drained, and while it is
			// on the UI thread it also reads the reconcilers, which can only be asked
			// there
			Fenced fenced = fence(display, deadline - System.currentTimeMillis());
			long fence = fenced.millis();
			reconcilers = fenced.reconcilers();
			slowestFence = Math.max(slowestFence, Math.max(fence, 0));
			long probe = reconcilers == null ? 0 : reconcilers.probeMillis();
			slowestProbe = Math.max(slowestProbe, probe);
			jobs = jobSnapshot();
			boolean uiBusy = fence < 0 || fence > FENCE_BUDGET_MILLIS;
			boolean jobsBusy = Boolean.TRUE.equals(jobs.remove("busy")); //$NON-NLS-1$
			boolean reconciling = reconcilers != null && reconcilers.isBusy();
			if (uiBusy || jobsBusy || reconciling) {
				consecutive = 0;
			} else {
				consecutive++;
				if (consecutive >= quietPasses) {
					return answer(true, rounds, consecutive, started, slowestFence, slowestProbe, jobs, reconcilers,
							null, clamped);
				}
			}
			// never let the probes take more of the UI thread than the pauses leave
			// free: a slow probe stretches the pause rather than the freeze
			sleep(Math.max(pauseMillis, probe));
		}
		return answer(false, rounds, consecutive, started, slowestFence, slowestProbe, jobs, reconcilers,
				"The budget ran out with %d of the %d consecutive quiet rounds needed. Something kept the UI thread or the job manager busy; jobs below is what it looked like at the end."
						.formatted(Integer.valueOf(consecutive), Integer.valueOf(quietPasses)),
				clamped);
	}

	private static JsonObject answer(boolean settled, int rounds, int consecutive, long started, long slowestFence,
			long slowestProbe, JsonObject jobs, Reconcilers.State reconcilers, String reason, String clamped) {
		return new JsonObject().put("settled", Boolean.valueOf(settled)) //$NON-NLS-1$
				.put("reconcilers", reconcilers == null ? null : reconcilers.describe()) //$NON-NLS-1$
				.put("rounds", Integer.valueOf(rounds)) //$NON-NLS-1$
				.put("consecutiveQuietRounds", Integer.valueOf(consecutive)) //$NON-NLS-1$
				.put("elapsedMillis", Long.valueOf(System.currentTimeMillis() - started)) //$NON-NLS-1$
				.put("slowestFenceMillis", Long.valueOf(slowestFence)) //$NON-NLS-1$
				.put("slowestProbeMillis", Long.valueOf(slowestProbe)) //$NON-NLS-1$
				.put("jobs", jobs) //$NON-NLS-1$
				.put("reason", reason) //$NON-NLS-1$
				.put("clamped", clamped) //$NON-NLS-1$
				.put("cannotSee", //$NON-NLS-1$
						"Any plain background thread other than a text editor's reconciler, and work that has not been scheduled yet. The reconcilers ARE checked, through internal fields that can change in any release, and an unreadable one counts as busy rather than idle; see the reconcilers block above. THIS IS STILL A HEURISTIC: assert what you actually need rather than trusting it.");
	}

	/** What one fence came back with: how long it waited, and what it saw while there. */
	private record Fenced(long millis, Reconcilers.State reconcilers) {
	}

	/**
	 * Posts a runnable to the Display and waits for it.
	 * <p>
	 * When it runs, everything queued before it has already run, which is the only
	 * way to drain the queue from outside the UI thread. How long it took is also
	 * the measurement: a fence that waits is a UI thread that was busy.
	 *
	 * @return the milliseconds it took, or -1 when it did not run in time
	 */
	private static Fenced fence(Display display, long budgetMillis) {
		if (budgetMillis <= 0) {
			return new Fenced(-1, null);
		}
		CompletableFuture<Fenced> ran = new CompletableFuture<>();
		long posted = System.currentTimeMillis();
		display.asyncExec(() -> {
			long waited = System.currentTimeMillis() - posted;
			Reconcilers.State state;
			try {
				state = Reconcilers.inspect();
			} catch (RuntimeException | LinkageError e) {
				// the reconciler probe reaches internals by name; losing it costs the
				// third signal and must not cost the fence
				state = null;
			}
			ran.complete(new Fenced(waited, state));
		});
		try {
			return ran.get(budgetMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Fenced(-1, null);
		} catch (Exception e) {
			return new Fenced(-1, null);
		}
	}

	/** What the job manager is doing, with a busy flag the caller removes before reporting. */
	private static JsonObject jobSnapshot() {
		JsonArray running = new JsonArray();
		boolean busy = false;
		for (Job job : Job.getJobManager().find(null)) {
			int state = job.getState();
			if (state == Job.NONE || job.isSystem()) {
				continue;
			}
			// a sleeping job is scheduled for later and counts as busy: it is exactly
			// the decoration-after-the-build case that a pixel comparison misses
			busy = true;
			if (running.size() < 20) {
				running.add(new JsonObject().put("name", job.getName()) //$NON-NLS-1$
						.put("state", state == Job.RUNNING ? "running" : state == Job.WAITING ? "waiting" : "sleeping")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			}
		}
		return new JsonObject().put("busy", Boolean.valueOf(busy)).put("nonSystemJobs", running); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
