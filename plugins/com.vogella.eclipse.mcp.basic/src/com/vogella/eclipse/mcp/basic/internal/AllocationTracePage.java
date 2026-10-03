package com.vogella.eclipse.mcp.basic.internal;

import java.util.ArrayList;
import java.util.List;

import com.vogella.eclipse.mcp.core.FlameGraph;
import com.vogella.eclipse.mcp.core.TracePages;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Renders a flight recording's allocation stacks as a flame graph page and publishes it.
 * <p>
 * Weighted by bytes rather than by samples, so the wide frames are the ones the memory
 * went through. Those bytes are the allocation sampler's weights, which rank allocators
 * correctly without adding up to what the process really allocated, and the page says so
 * rather than letting a reader take the totals literally.
 */
final class AllocationTracePage {

	private AllocationTracePage() {
	}

	/** Publishes the page and records the URL, or why there is none, in {@code answer}. */
	static void publish(String title, String subtitle, FlameGraph.Builder flame, JsonObject answer, boolean open) {
		if (!TracePages.isAvailable()) {
			answer.put("traceUrl", (Object) null).put("traceNote", TracePages.unavailable()); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}
		if (flame.isEmpty()) {
			answer.put("traceUrl", (Object) null).put("traceNote", //$NON-NLS-1$ //$NON-NLS-2$
					"No allocation stacks were recorded, so there was nothing to draw. The allocation sampler only fires on sampled allocations, so a short or quiet recording often has none."); //$NON-NLS-1$
			return;
		}
		List<FlameGraph.Table> tables = List.of(table("Allocated in the frame itself", flame.topSelf(15)), //$NON-NLS-1$
				table("Allocated below the frame", flame.topTotal(15))); //$NON-NLS-1$
		String note = "The width of a frame is the bytes allocated below it, not the time spent in it. " //$NON-NLS-1$
				+ "Click a frame to zoom into it and Reset zoom to come back. " //$NON-NLS-1$
				+ "THE BYTES ARE ESTIMATES: the JFR allocation sampler is throttled and reports a weight per sample, so these figures rank allocators against each other and do not add up to everything the process allocated. " //$NON-NLS-1$
				+ "Stacks are cut at the recording's stackDepth, so callers sharing a top frame may be merged here."; //$NON-NLS-1$
		List<FlameGraph.Stat> stats = List.of(
				new FlameGraph.Stat("Allocated", FlameGraph.bytes(flame.total())), //$NON-NLS-1$
				new FlameGraph.Stat("Sampled stacks", String.valueOf(flame.stacks())), //$NON-NLS-1$
				new FlameGraph.Stat("Distinct frames", String.valueOf(flame.topTotal(Integer.MAX_VALUE).size()))); //$NON-NLS-1$
		String url = TracePages.publish(title,
				FlameGraph.page(new FlameGraph.Spec(title, subtitle, "bytes", flame, tables, note, stats))); //$NON-NLS-1$
		answer.put("traceUrl", url); //$NON-NLS-1$
		if (open) {
			String failed = TracePages.open(url);
			answer.put("traceOpened", Boolean.valueOf(failed == null)); //$NON-NLS-1$
			if (failed != null) {
				answer.put("traceOpenNote", failed); //$NON-NLS-1$
			}
		}
	}

	private static FlameGraph.Table table(String caption, List<FlameGraph.Builder.Ranked> ranked) {
		List<List<String>> rows = new ArrayList<>();
		for (FlameGraph.Builder.Ranked entry : ranked) {
			rows.add(List.of(entry.frame(), FlameGraph.bytes(entry.weight())));
		}
		return new FlameGraph.Table(caption, List.of("Frame", "Bytes"), rows); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
