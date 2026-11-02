package com.vogella.eclipse.mcp.basic.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.vogella.eclipse.mcp.core.FlameGraph;
import com.vogella.eclipse.mcp.core.IdleFrames;

/**
 * Lines up stacks JFR cut at its stack depth, so that they merge under one root: per thread
 * the frames outside the outermost frame every truncated stack still shares are dropped.
 */
final class StackAlignment {

	/** One stack, innermost frame first, with what it weighs. */
	record Stack(String thread, String[] frames, boolean truncated, long weight) {
	}

	/** The stacks after alignment, and how many of them JFR had cut. */
	record Aligned(List<Stack> stacks, int truncated, int unaligned, List<String> anchors) {
	}

	private StackAlignment() {
	}

	static Aligned align(List<Stack> stacks) {
		Map<String, List<Stack>> byThread = new LinkedHashMap<>();
		for (Stack stack : stacks) {
			byThread.computeIfAbsent(String.valueOf(stack.thread()), unused -> new ArrayList<>()).add(stack);
		}
		List<Stack> aligned = new ArrayList<>(stacks.size());
		List<String> anchors = new ArrayList<>();
		int truncated = 0;
		int unaligned = 0;
		for (List<Stack> group : byThread.values()) {
			List<Stack> cut = group.stream().filter(Stack::truncated).toList();
			truncated += cut.size();
			String anchor = cut.isEmpty() ? null : anchor(cut);
			if (anchor == null) {
				unaligned += cut.size();
				aligned.addAll(group);
				continue;
			}
			anchors.add(anchor);
			for (Stack stack : group) {
				aligned.add(trimAt(stack, anchor));
			}
		}
		return new Aligned(aligned, truncated, unaligned, anchors);
	}

	/** Adds the stacks to a flame graph, each thread a branch of its own; an allocation is never idle. */
	static void draw(List<Stack> stacks, FlameGraph.Builder into, boolean markIdle) {
		for (Stack stack : stacks) {
			if (stack.frames().length == 0) {
				continue;
			}
			List<String> rootFirst = new ArrayList<>(stack.frames().length + 1);
			rootFirst.add(FlameGraph.THREAD_PREFIX + FlameGraph.threadBranch(stack.thread()));
			for (int i = stack.frames().length - 1; i >= 0; i--) {
				rootFirst.add(stack.frames()[i]);
			}
			into.add(rootFirst, stack.weight(), markIdle && IdleFrames.idleFrame(Arrays.asList(stack.frames())) != null);
		}
	}

	/** The outermost frame of a truncated stack that every truncated stack contains, nearest the leaf. */
	private static String anchor(List<Stack> cut) {
		Set<String> common = null;
		for (Stack stack : cut) {
			Set<String> frames = new HashSet<>(Arrays.asList(stack.frames()));
			if (common == null) {
				common = frames;
			} else {
				common.retainAll(frames);
			}
			if (common.isEmpty()) {
				return null;
			}
		}
		String[] first = cut.get(0).frames();
		Set<String> outermost = new HashSet<>();
		for (Stack stack : cut) {
			if (stack.frames().length > 0) {
				outermost.add(stack.frames()[stack.frames().length - 1]);
			}
		}
		outermost.retainAll(common);
		for (String frame : first) {
			if (outermost.contains(frame)) {
				return frame;
			}
		}
		return null;
	}

	private static Stack trimAt(Stack stack, String anchor) {
		String[] frames = stack.frames();
		for (int i = frames.length - 1; i >= 0; i--) {
			if (frames[i].equals(anchor)) {
				return i == frames.length - 1 ? stack
						: new Stack(stack.thread(), Arrays.copyOf(frames, i + 1), stack.truncated(), stack.weight());
			}
		}
		return stack;
	}
}
