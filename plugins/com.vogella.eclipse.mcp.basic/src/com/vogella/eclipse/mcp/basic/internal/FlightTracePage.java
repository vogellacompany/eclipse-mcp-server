package com.vogella.eclipse.mcp.basic.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

import com.vogella.eclipse.mcp.core.FlameGraph;
import com.vogella.eclipse.mcp.core.ProductFamily;
import com.vogella.eclipse.mcp.core.TracePages;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Renders a flight recording as a flame graph page and publishes it, weighted either by the
 * allocation sampler's bytes or by time samples.
 * <p>
 * The page and the answer both name the weight, since the two pictures look alike and answer
 * different questions. Both rank by the nearest non-JDK frame, because the JDK frames a
 * recording ends in, such as {@code Arrays.copyOf}, name no culprit.
 */
final class FlightTracePage {

	private FlightTracePage() {
	}

	/** Publishes the bytes page and records the URL, or why there is none, in {@code answer}. */
	static void publishAllocation(String name, String source, String filters, FlameGraph.Builder flame,
			ProductFamily product, JsonObject answer, boolean open) {
		answer.put("traceWeight", "estimated allocated bytes"); //$NON-NLS-1$ //$NON-NLS-2$
		if (unavailable(flame, answer,
				"No allocation stacks were recorded, so there was nothing to draw. The allocation sampler only fires on sampled allocations, so a short or quiet recording often has none.")) { //$NON-NLS-1$
			return;
		}
		String note = "The width of a frame is the estimated bytes allocated below it, not the time spent in it. " //$NON-NLS-1$
				+ "The list beside the graph credits each allocation to its nearest non-JDK frame. " //$NON-NLS-1$
				+ "Click a frame to zoom into it and Reset zoom to come back. " //$NON-NLS-1$
				+ "THE BYTES ARE ESTIMATES: the JFR allocation sampler is throttled and reports a weight per sample, so these figures rank allocators against each other and do not add up to everything the process allocated."; //$NON-NLS-1$
		List<FlameGraph.Stat> stats = List.of(
				new FlameGraph.Stat("Estimated allocation, for ranking only", "~" + FlameGraph.bytes(flame.total())), //$NON-NLS-1$ //$NON-NLS-2$
				new FlameGraph.Stat("Sampled stacks", String.valueOf(flame.stacks())), //$NON-NLS-1$
				new FlameGraph.Stat("Distinct frames", String.valueOf(flame.topTotal(Integer.MAX_VALUE).size()))); //$NON-NLS-1$
		List<FlameGraph.Table> tables = List.of(
				table("Allocated in the frame itself", "Estimated bytes", flame.topSelf(15), FlameGraph::bytes), //$NON-NLS-1$ //$NON-NLS-2$
				table("Allocated below the frame", "Estimated bytes", flame.topTotal(15), FlameGraph::bytes)); //$NON-NLS-1$ //$NON-NLS-2$
		publish("Allocation " + name, //$NON-NLS-1$
				"%s, allocation stacks WEIGHTED BY ESTIMATED BYTES%s".formatted(source, filters), //$NON-NLS-1$
				"bytes", flame, tables, note, stats, product, answer, open); //$NON-NLS-1$
	}

	/** Publishes the time page and records the URL, or why there is none, in {@code answer}. */
	static void publishTime(String name, String source, String filters, boolean includeIdle, FlameGraph.Builder flame,
			ProductFamily product, JsonObject answer, boolean open) {
		answer.put("traceWeight", "time samples"); //$NON-NLS-1$ //$NON-NLS-2$
		if (unavailable(flame, answer,
				"No execution or native samples are left to draw. Check the time block of this answer: the filters or the idle exclusion may have removed them, or the recording's settings did not enable the samplers.")) { //$NON-NLS-1$
			return;
		}
		String note = "The width of a frame is how many execution and native samples had it on the stack, so it is time spent below that frame rather than in it. " //$NON-NLS-1$
				+ "The list beside the graph credits each sample to its nearest non-JDK frame. " //$NON-NLS-1$
				+ "Click a frame to zoom into it and Reset zoom to come back. " //$NON-NLS-1$
				+ (includeIdle ? "Parked and waiting stacks are INCLUDED and hidden until the Idle chip is clicked." //$NON-NLS-1$
						: "Parked and waiting stacks are excluded, which is the default; re-read with includeIdle to see them.") //$NON-NLS-1$
				+ " Samples are not time: the JVM takes few native samples per tick across all threads, so compare threads with care."; //$NON-NLS-1$
		List<FlameGraph.Stat> stats = List.of(new FlameGraph.Stat("Samples", String.valueOf(flame.stacks())), //$NON-NLS-1$
				new FlameGraph.Stat("Distinct frames", String.valueOf(flame.topTotal(Integer.MAX_VALUE).size()))); //$NON-NLS-1$
		List<FlameGraph.Table> tables = List.of(
				table("Where the time was spent", "Samples", flame.topSelf(15), String::valueOf), //$NON-NLS-1$ //$NON-NLS-2$
				table("Most often on the stack", "Samples", flame.topTotal(15), String::valueOf)); //$NON-NLS-1$ //$NON-NLS-2$
		publish("Time " + name, "%s, stacks WEIGHTED BY TIME SAMPLES%s".formatted(source, filters), //$NON-NLS-1$ //$NON-NLS-2$
				"samples", flame, tables, note, stats, product, answer, open); //$NON-NLS-1$
	}

	private static boolean unavailable(FlameGraph.Builder flame, JsonObject answer, String empty) {
		if (!TracePages.isAvailable()) {
			answer.put("traceUrl", (Object) null).put("traceNote", TracePages.unavailable()); //$NON-NLS-1$ //$NON-NLS-2$
			return true;
		}
		if (flame.isEmpty()) {
			answer.put("traceUrl", (Object) null).put("traceNote", empty); //$NON-NLS-1$ //$NON-NLS-2$
			return true;
		}
		return false;
	}

	private static void publish(String title, String subtitle, String unit, FlameGraph.Builder flame,
			List<FlameGraph.Table> tables, String note, List<FlameGraph.Stat> stats, ProductFamily product,
			JsonObject answer, boolean open) {
		String url = TracePages.publish(title, FlameGraph.page(
				new FlameGraph.Spec(title, subtitle, unit, flame, tables, note, stats, true, List.of(), product)));
		answer.put("traceUrl", url); //$NON-NLS-1$
		if (open) {
			String failed = TracePages.open(url);
			answer.put("traceOpened", Boolean.valueOf(failed == null)); //$NON-NLS-1$
			if (failed != null) {
				answer.put("traceOpenNote", failed); //$NON-NLS-1$
			}
		}
	}

	private static FlameGraph.Table table(String caption, String column, List<FlameGraph.Builder.Ranked> ranked,
			LongFunction<String> format) {
		List<List<String>> rows = new ArrayList<>();
		for (FlameGraph.Builder.Ranked entry : ranked) {
			rows.add(List.of(entry.frame(), format.apply(entry.weight())));
		}
		return new FlameGraph.Table(caption, List.of("Frame", column), rows); //$NON-NLS-1$
	}
}
