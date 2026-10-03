package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;

import com.vogella.eclipse.mcp.core.FlameGraph;
import com.vogella.eclipse.mcp.core.TracePages;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Renders a sampling session as a flame graph page and publishes it.
 * <p>
 * The URL or the reason for failure goes into the tool's own answer, so a page never fails a call.
 */
final class TracePage {

	private TracePage() {
	}

	/** Publishes the page and records the outcome in {@code aggregate}. */
	static void publishSampling(SamplingRegistry.Session session, boolean includeIdle, String frameFilter,
			boolean includeServer, JsonObject aggregate, boolean open) {
		if (!TracePages.isAvailable()) {
			aggregate.put("traceUrl", (Object) null).put("traceNote", TracePages.unavailable()); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}
		FlameGraph.Builder flame = SamplingRegistry.flame(session, includeIdle, frameFilter, includeServer);
		String title = "Sampling " + session.id(); //$NON-NLS-1$
		String subtitle = "%d samples over %d ms at a %d ms interval%s".formatted(Integer.valueOf(flame.stacks()), //$NON-NLS-1$
				Long.valueOf(session.elapsedMillis()), Integer.valueOf(session.intervalMillis()),
				frameFilter == null ? "" : ", only stacks containing '%s'".formatted(frameFilter)); //$NON-NLS-1$ //$NON-NLS-2$

		List<FlameGraph.Table> tables = List.of(
				table("Where the time was spent", flame.topSelf(15)), //$NON-NLS-1$
				table("Most often on the stack", flame.topTotal(15))); //$NON-NLS-1$

		String note = "The width of a frame is how many samples had it on the stack, so it is time spent below that frame rather than in it. " //$NON-NLS-1$
				+ "Click a frame to zoom into it and Reset zoom to come back. " //$NON-NLS-1$
				+ (includeIdle ? "Parked and waiting threads are INCLUDED here." //$NON-NLS-1$
						: "Parked and waiting threads are excluded, which is the default; a freeze is usually parked, so re-read with includeIdleThreads to see it.") //$NON-NLS-1$
				+ " Sampling perturbs what it measures, so treat small differences as noise."; //$NON-NLS-1$

		List<FlameGraph.Stat> stats = List.of(
				new FlameGraph.Stat("Samples", String.valueOf(flame.stacks())), //$NON-NLS-1$
				new FlameGraph.Stat("Duration", session.elapsedMillis() + " ms"), //$NON-NLS-1$ //$NON-NLS-2$
				new FlameGraph.Stat("Interval", session.intervalMillis() + " ms"), //$NON-NLS-1$ //$NON-NLS-2$
				new FlameGraph.Stat("Distinct frames", String.valueOf(flame.topTotal(Integer.MAX_VALUE).size()))); //$NON-NLS-1$
		String url = TracePages.publish(title,
				FlameGraph.page(new FlameGraph.Spec(title, subtitle, "samples", flame, tables, note, stats))); //$NON-NLS-1$
		aggregate.put("traceUrl", url); //$NON-NLS-1$
		if (open) {
			String failed = TracePages.open(url);
			aggregate.put("traceOpened", Boolean.valueOf(failed == null)); //$NON-NLS-1$
			if (failed != null) {
				aggregate.put("traceOpenNote", failed); //$NON-NLS-1$
			}
		}
	}

	/**
	 * A ranking from the same tree the graph draws, rather than from the aggregate.
	 * The JSON model here is write only by design, so reading it back would mean
	 * widening it for a table.
	 */
	private static FlameGraph.Table table(String caption, List<FlameGraph.Builder.Ranked> ranked) {
		List<List<String>> rows = new ArrayList<>();
		for (FlameGraph.Builder.Ranked entry : ranked) {
			rows.add(List.of(entry.frame(), String.valueOf(entry.weight())));
		}
		return new FlameGraph.Table(caption, List.of("Frame", "Samples"), rows); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
