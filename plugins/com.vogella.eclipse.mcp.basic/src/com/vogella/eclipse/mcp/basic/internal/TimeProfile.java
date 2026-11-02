package com.vogella.eclipse.mcp.basic.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.vogella.eclipse.mcp.core.FlameGraph;
import com.vogella.eclipse.mcp.core.IdleFrames;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Aggregates the execution and native samples of a recording into where the time went.
 * <p>
 * Holds no {@code jdk.jfr} type, so it can be tested without a recording. The answer has the
 * shape of {@code eclipse_stop_sampling}: self time, presence on the stack and a merged tree.
 */
public final class TimeProfile {

	/** How many of the waits idle samples sat in are listed. */
	private static final int IDLE_FRAMES = 5;

	/** What to report and how much of it. */
	public record Limits(boolean includeIdle, int topMethods, int topThreads, int minSamples, int maxTreeNodes) {
	}

	private final Limits limits;

	private final Map<String, long[]> threads = new HashMap<>();

	private final Map<String, long[]> self = new HashMap<>();

	private final Map<String, long[]> presence = new HashMap<>();

	private final Map<String, Integer> idleFrames = new HashMap<>();

	private final List<StackAlignment.Stack> stacks = new ArrayList<>();

	private int execution;

	private int nativeSamples;

	private int idle;

	public TimeProfile(Limits limits) {
		this.limits = limits;
	}

	/** Counts a sample for byThread, before any thread filter, so the names to filter by stay visible. */
	public void observe(String thread, boolean isExecution, String[] frames) {
		long[] counts = threads.computeIfAbsent(String.valueOf(thread), unused -> new long[3]);
		counts[isExecution ? 0 : 1]++;
		if (IdleFrames.idleFrame(Arrays.asList(frames)) != null) {
			counts[2]++;
		}
	}

	/** Adds a selected sample, or only counts it when it is idle and idle samples are left out. */
	public void add(String thread, boolean isExecution, String[] frames, boolean truncated) {
		String wait = IdleFrames.idleFrame(Arrays.asList(frames));
		if (wait != null) {
			idle++;
			idleFrames.merge(wait, Integer.valueOf(1), Integer::sum);
			if (!limits.includeIdle()) {
				return;
			}
		}
		if (isExecution) {
			execution++;
		} else {
			nativeSamples++;
		}
		stacks.add(new StackAlignment.Stack(thread, frames, truncated, 1));
		if (frames.length > 0) {
			count(self, frames[0], isExecution);
			Set<String> seen = new HashSet<>();
			for (String frame : frames) {
				if (seen.add(frame)) {
					count(presence, frame, isExecution);
				}
			}
		}
	}

	private static void count(Map<String, long[]> into, String frame, boolean isExecution) {
		into.computeIfAbsent(frame, unused -> new long[2])[isExecution ? 0 : 1]++;
	}

	/** The stacks kept, for a flame graph of the same samples the numbers describe. */
	List<StackAlignment.Stack> stacks() {
		return stacks;
	}

	int samples() {
		return execution + nativeSamples;
	}

	public JsonObject toJson() {
		int samples = samples();
		JsonObject result = new JsonObject().put("samples", Integer.valueOf(samples)) //$NON-NLS-1$
				.put("execution", Integer.valueOf(execution)) //$NON-NLS-1$
				.put("native", Integer.valueOf(nativeSamples)) //$NON-NLS-1$
				.put("idleSamples", Integer.valueOf(idle)) //$NON-NLS-1$
				.put("idleExcluded", Integer.valueOf(limits.includeIdle() ? 0 : idle)); //$NON-NLS-1$
		JsonArray waits = new JsonArray();
		idleFrames.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
				.limit(IDLE_FRAMES).forEach(entry -> waits.add(new JsonObject().put("frame", entry.getKey()) //$NON-NLS-1$
						.put("samples", entry.getValue()))); //$NON-NLS-1$
		result.put("idleFrames", waits) //$NON-NLS-1$
				.put("idleFramesTotal", Integer.valueOf(idleFrames.size())) //$NON-NLS-1$
				.put("idleFramesTruncated", Boolean.valueOf(idleFrames.size() > IDLE_FRAMES)); //$NON-NLS-1$
		byThread(result);
		ranked(self, samples, "topBySelfTime", result); //$NON-NLS-1$
		Map<String, long[]> distinguishing = new HashMap<>(presence);
		JsonArray everywhere = new JsonArray();
		if (samples > 1) {
			presence.entrySet().stream().filter(entry -> entry.getValue()[0] + entry.getValue()[1] == samples)
					.map(Map.Entry::getKey).sorted().forEach(frame -> {
						everywhere.add(frame);
						distinguishing.remove(frame);
					});
		}
		ranked(distinguishing, samples, "topByPresence", result); //$NON-NLS-1$
		if (everywhere.size() > 0) {
			result.put("onEveryStack", everywhere); //$NON-NLS-1$
		}
		tree(result, samples);
		return result;
	}

	private void byThread(JsonObject into) {
		JsonArray array = new JsonArray();
		threads.entrySet().stream()
				.sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> -(busy(e.getValue())))
						.thenComparing(Map.Entry::getKey))
				.limit(limits.topThreads()).forEach(entry -> array.add(new JsonObject().put("thread", entry.getKey()) //$NON-NLS-1$
						.put("execution", Long.valueOf(entry.getValue()[0])) //$NON-NLS-1$
						.put("native", Long.valueOf(entry.getValue()[1])) //$NON-NLS-1$
						.put("idle", Long.valueOf(entry.getValue()[2])))); //$NON-NLS-1$
		into.put("byThread", array) //$NON-NLS-1$
				.put("byThreadTotal", Integer.valueOf(threads.size())) //$NON-NLS-1$
				.put("byThreadTruncated", Boolean.valueOf(threads.size() > limits.topThreads())); //$NON-NLS-1$
	}

	private static long busy(long[] counts) {
		return counts[0] + counts[1] - counts[2];
	}

	private void ranked(Map<String, long[]> counts, int samples, String field, JsonObject into) {
		JsonArray array = new JsonArray();
		counts.entrySet().stream()
				.sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> -(e.getValue()[0] + e.getValue()[1]))
						.thenComparing(Map.Entry::getKey))
				.limit(limits.topMethods()).forEach(entry -> {
					long[] value = entry.getValue();
					long total = value[0] + value[1];
					array.add(new JsonObject().put("frame", entry.getKey()) //$NON-NLS-1$
							.put("samples", Long.valueOf(total)) //$NON-NLS-1$
							.put("percent", Double.valueOf(Math.round(total * 1000.0 / samples) / 10.0)) //$NON-NLS-1$
							.put("execution", Long.valueOf(value[0])) //$NON-NLS-1$
							.put("native", Long.valueOf(value[1]))); //$NON-NLS-1$
				});
		into.put(field, array).put(field + "Total", Integer.valueOf(counts.size())) //$NON-NLS-1$
				.put(field + "Truncated", Boolean.valueOf(counts.size() > limits.topMethods())); //$NON-NLS-1$
	}

	private void tree(JsonObject into, int samples) {
		StackAlignment.Aligned aligned = StackAlignment.align(stacks);
		Node root = new Node(""); //$NON-NLS-1$
		for (StackAlignment.Stack stack : aligned.stacks()) {
			Node current = root.child(FlameGraph.THREAD_PREFIX + FlameGraph.threadBranch(stack.thread()));
			current.count++;
			String[] frames = stack.frames();
			for (int i = frames.length - 1; i >= 0; i--) {
				current = current.child(frames[i]);
				current.count++;
			}
		}
		int minSamples = limits.minSamples() > 0 ? limits.minSamples() : Math.max(2, samples / 100);
		// widest first, so a budget spent on one deep branch cannot starve the others
		Set<Node> shown = new HashSet<>();
		PriorityQueue<Node> widest = new PriorityQueue<>(Comparator.comparingInt((Node n) -> -n.count));
		int total = 0;
		widest.addAll(root.children.values());
		while (!widest.isEmpty()) {
			Node node = widest.poll();
			if (node.count < minSamples) {
				continue;
			}
			total++;
			if (shown.size() < limits.maxTreeNodes()) {
				shown.add(node);
			}
			widest.addAll(node.children.values());
		}
		into.put("tree", root.toJson(shown)) //$NON-NLS-1$
				.put("treeMinSamples", Integer.valueOf(minSamples)) //$NON-NLS-1$
				.put("treeNodes", Integer.valueOf(shown.size())) //$NON-NLS-1$
				.put("treeNodesTotal", Integer.valueOf(total)) //$NON-NLS-1$
				.put("treeTruncated", Boolean.valueOf(total > shown.size())); //$NON-NLS-1$
		if (aligned.truncated() > 0) {
			into.put("truncatedStacks", Integer.valueOf(aligned.truncated())) //$NON-NLS-1$
					.put("truncatedNote", //$NON-NLS-1$
							"%d stacks were deeper than the JVM's JFR stack depth and lost their outermost frames. The tree and the flame graph are rooted per thread at the outermost frame every cut stack of that thread still shares, and %d cut stacks that share none are merged as they are, which gives one path several roots. Start the JVM with -XX:FlightRecorderOptions:stackdepth=256 to keep them." //$NON-NLS-1$
									.formatted(Integer.valueOf(aligned.truncated()), Integer.valueOf(aligned.unaligned())));
		}
	}

	private static final class Node {

		private final String frame;

		private final Map<String, Node> children = new LinkedHashMap<>();

		private int count;

		Node(String frame) {
			this.frame = frame;
		}

		Node child(String name) {
			return children.computeIfAbsent(name, Node::new);
		}

		JsonArray toJson(Set<Node> shown) {
			List<Node> sorted = new ArrayList<>(children.values());
			sorted.sort(Comparator.comparingInt((Node n) -> -n.count).thenComparing(n -> n.frame));
			JsonArray array = new JsonArray();
			for (Node node : sorted) {
				if (!shown.contains(node)) {
					continue;
				}
				JsonObject json = new JsonObject().put("frame", node.frame).put("samples", Integer.valueOf(node.count)); //$NON-NLS-1$ //$NON-NLS-2$
				// a run of single-child frames with equal counts is one path with nothing to choose along it
				Node tail = node;
				JsonArray chain = new JsonArray();
				while (tail.children.size() == 1) {
					Node only = tail.children.values().iterator().next();
					if (only.count != tail.count || !shown.contains(only)) {
						break;
					}
					chain.add(only.frame);
					tail = only;
				}
				if (chain.size() > 0) {
					json.put("chain", chain); //$NON-NLS-1$
				}
				JsonArray below = tail.toJson(shown);
				if (below.size() > 0) {
					json.put("children", below); //$NON-NLS-1$
				}
				array.add(json);
			}
			return array;
		}
	}
}
