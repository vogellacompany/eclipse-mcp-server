package com.vogella.eclipse.mcp.ui.internal;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import com.vogella.eclipse.mcp.core.FlameGraph;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Samples thread stacks on a daemon thread, so that a freeze can be diagnosed.
 * <p>
 * Nothing here touches the UI thread or takes a workspace lock: a profiler that
 * queues behind the freeze is useless for the one case it exists for.
 * {@link ThreadMXBean} does not need the sampled thread to be responsive.
 */
public final class SamplingRegistry {

	private static final SamplingRegistry INSTANCE = new SamplingRegistry();

	private final AtomicLong ids = new AtomicLong();

	private final Map<String, Session> sessions = new LinkedHashMap<>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) {
			return size() > 10 && !eldest.getValue().running;
		}
	};

	public static SamplingRegistry getInstance() {
		return INSTANCE;
	}

	private SamplingRegistry() {
	}

	/**
	 * One stack, and whose it was. Without the thread, two workers collapse into one
	 * entry in the aggregate and a saturated thread is indistinguishable from two
	 * half-busy ones; without the state, a stall cannot be told from work.
	 */
	public record Sample(long threadId, String threadName, Thread.State state, StackTraceElement[] stack) {
	}

	/** One sampling run. */
	public static final class Session {

		private final String id;
		private final Function<ThreadMXBean, long[]> threadIds;
		private final int intervalMillis;
		private final int maxSamples;
		private final int maxDepth;
		private final long startedAt = System.currentTimeMillis();
			private final List<Sample> samples = new ArrayList<>();

		private final Map<Long, long[]> cpuNanos = new LinkedHashMap<>();

		private long firstTickAt;

		private long lastTickAt;

		private volatile boolean running = true;
		private volatile long endedAt;
		private volatile int ticks;
		private volatile boolean stoppedByBudget;
		private Thread sampler;

		Session(String id, Function<ThreadMXBean, long[]> threadIds, int intervalMillis, int maxSamples, int maxDepth) {
			this.id = id;
			this.threadIds = threadIds;
			this.intervalMillis = intervalMillis;
			this.maxSamples = maxSamples;
			this.maxDepth = maxDepth;
		}

		public String id() {
			return id;
		}

		public boolean running() {
			return running;
		}

		/** Sampling rounds. One tick produces one stack per sampled thread. */
		public int ticks() {
			return ticks;
		}

		public boolean stoppedByBudget() {
			return stoppedByBudget;
		}

		public long elapsedMillis() {
			return (endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt;
		}

		public int intervalMillis() {
			return intervalMillis;
		}

		public int maxDepth() {
			return maxDepth;
		}

		void run() {
			ThreadMXBean threads = ManagementFactory.getThreadMXBean();
			boolean cpuSupported = threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled();
			while (running) {
				// resolved per tick, so a worker started after sampling began is sampled too
				ThreadInfo[] infos = threads.getThreadInfo(threadIds.apply(threads), maxDepth);
				long now = System.currentTimeMillis();
				synchronized (samples) {
					if (firstTickAt == 0) {
						firstTickAt = now;
					}
					lastTickAt = now;
					for (ThreadInfo info : infos) {
						if (info != null && info.getStackTrace().length > 0) {
							samples.add(new Sample(info.getThreadId(), info.getThreadName(), info.getThreadState(),
									info.getStackTrace()));
							if (cpuSupported) {
								long cpu = threads.getThreadCpuTime(info.getThreadId());
								// first and last, so the reported time is the thread's
								// own consumption over the run rather than a sum of
								// overlapping readings
								long[] range = cpuNanos.computeIfAbsent(Long.valueOf(info.getThreadId()),
										id -> new long[] { cpu, cpu });
								range[1] = cpu;
							}
						}
					}
					ticks++;
					// the budget counts rounds, not stacks. Counting stacks meant that
					// with 70 live threads a 200 budget ended after three rounds, long
					// before the operation being profiled had got going
					if (ticks >= maxSamples) {
						running = false;
						stoppedByBudget = true;
					}
				}
				try {
					Thread.sleep(intervalMillis);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					running = false;
				}
			}
			endedAt = System.currentTimeMillis();
		}

		void stop() {
			running = false;
			if (sampler != null) {
				sampler.interrupt();
			}
			if (endedAt == 0) {
				endedAt = System.currentTimeMillis();
			}
		}

		List<Sample> snapshot() {
			synchronized (samples) {
				return List.copyOf(samples);
			}
		}

		Map<Long, long[]> cpuRanges() {
			synchronized (samples) {
				return new LinkedHashMap<>(cpuNanos);
			}
		}

		/**
		 * The interval actually achieved, which is not the one requested: sampling
		 * every thread every 10ms does not fit in 10ms, and a caller drawing
		 * conclusions from sample counts needs to know the clock drifted.
		 */
		long achievedIntervalMillis() {
			synchronized (samples) {
				int rounds = ticks;
				return rounds < 2 ? intervalMillis : Math.round((lastTickAt - firstTickAt) / (double) (rounds - 1));
			}
		}
	}

	public synchronized Session start(Function<ThreadMXBean, long[]> threadIds, int intervalMillis, int maxSamples, int maxDepth) {
		String id = "sampling-" + ids.incrementAndGet(); //$NON-NLS-1$
		Session session = new Session(id, threadIds, intervalMillis, maxSamples, maxDepth);
		sessions.put(id, session);
		Thread sampler = new Thread(session::run, "MCP stack sampler " + id); //$NON-NLS-1$
		sampler.setDaemon(true);
		// below normal, so that sampling never competes with the work being measured
		sampler.setPriority(Thread.NORM_PRIORITY - 1);
		session.sampler = sampler;
		sampler.start();
		return session;
	}

	public synchronized Session find(String id) {
		return sessions.get(id);
	}

	/** The ids still held, oldest first, for a caller that has to name one. */
	public synchronized List<String> ids() {
		return List.copyOf(sessions.keySet());
	}

	public synchronized Session findLatest() {
		Session latest = null;
		for (Session session : sessions.values()) {
			latest = session;
		}
		return latest;
	}

	/**
	 * Aggregates the samples rather than returning them.
	 * <p>
	 * A hundred samples of seventy frames is seven thousand lines, which is unusable
	 * in a model context window and is the same mistake as an uncapped screenshot.
	 */
	public static JsonObject aggregate(Session session, int topMethods, int minSamples, boolean includeRaw,
			boolean includeIdle) {
		return aggregate(session, topMethods, minSamples, includeRaw, includeIdle, null, false);
	}

	public static JsonObject aggregate(Session session, int topMethods, int minSamples, boolean includeRaw,
			boolean includeIdle, String frameFilter) {
		return aggregate(session, topMethods, minSamples, includeRaw, includeIdle, frameFilter, false);
	}

	/**
	 * Aggregates the samples rather than returning them, optionally only those whose
	 * stack contains {@code frameFilter}.
	 * <p>
	 * The filter is applied here rather than while sampling, so one session can be
	 * read from several angles without being taken again. It earns its place because
	 * the top of an unfiltered IDE profile is Jetty accept loops, the AWT event
	 * pump and the reference handler, none of which is ever the answer to the
	 * question a caller is asking.
	 */
	/** The samples a set of options selects, so every view of a session picks the same ones. */
	private static Selection select(Session session, boolean includeIdle, String frameFilter) {
		List<Sample> everything = session.snapshot();
		List<Sample> all = everything;
		int filtered = 0;
		if (frameFilter != null) {
			String needle = frameFilter.toLowerCase(Locale.ROOT);
			List<Sample> matching = new ArrayList<>();
			for (Sample sample : all) {
				for (StackTraceElement element : sample.stack()) {
					if (frame(element).toLowerCase(Locale.ROOT).contains(needle)) {
						matching.add(sample);
						break;
					}
				}
			}
			filtered = all.size() - matching.size();
			all = matching;
		}
		int idle = 0;
		List<Sample> samples = new ArrayList<>();
		for (Sample sample : all) {
			if (isIdle(sample.stack())) {
				idle++;
				if (includeIdle) {
					samples.add(sample);
				}
			} else {
				samples.add(sample);
			}
		}
		return new Selection(everything, samples, idle, filtered);
	}

	private record Selection(List<Sample> everything, List<Sample> samples, int idle, int filtered) {
	}

	/**
	 * The selected samples merged into the tree a flame graph draws, weighted by
	 * sample count. Built from the same selection the aggregate reports, so the
	 * picture and the numbers beside it describe one set of samples.
	 */
	public static FlameGraph.Builder flame(Session session, boolean includeIdle, String frameFilter) {
		FlameGraph.Builder builder = FlameGraph.builder();
		for (Sample sample : align(select(session, includeIdle, frameFilter).samples(), session.maxDepth())
				.samples()) {
			StackTraceElement[] stack = sample.stack();
			List<String> frames = new ArrayList<>(stack.length);
			// outermost first, which is bottom up in the picture
			for (int i = stack.length - 1; i >= 0; i--) {
				frames.add(frame(stack[i]));
			}
			builder.add(frames, 1);
		}
		return builder;
	}

	public static JsonObject aggregate(Session session, int topMethods, int minSamples, boolean includeRaw,
			boolean includeIdle, String frameFilter, boolean includeAllThreads) {
		Selection selection = select(session, includeIdle, frameFilter);
		List<Sample> everything = selection.everything();
		List<Sample> samples = selection.samples();
		int filtered = selection.filtered();
		int idle = selection.idle();
		JsonArray threads = byThread(everything, contributing(samples), session, includeAllThreads);
		JsonObject result = new JsonObject().put("sessionId", session.id()) //$NON-NLS-1$
				.put("running", session.running()) //$NON-NLS-1$
				.put("ticks", session.ticks()) //$NON-NLS-1$
				.put("samples", samples.size()) //$NON-NLS-1$
				.put("idleSamplesExcluded", includeIdle ? 0 : idle) //$NON-NLS-1$
				.put("intervalMillis", session.intervalMillis()) //$NON-NLS-1$
				.put("achievedIntervalMillis", Long.valueOf(session.achievedIntervalMillis())) //$NON-NLS-1$
				.put("elapsedMillis", session.elapsedMillis()) //$NON-NLS-1$
				.put("byThread", threads); //$NON-NLS-1$
		int listed = threads.size();
		int sampled = contributing(everything).size();
		result.put("threadsSampled", Integer.valueOf(sampled)) //$NON-NLS-1$
				.put("threadsListed", Integer.valueOf(listed)) //$NON-NLS-1$
				.put("threadsOmitted", Integer.valueOf(sampled - listed)); //$NON-NLS-1$
		if (sampled > listed) {
			result.put("byThreadNote", //$NON-NLS-1$
					"byThread lists only the threads that contributed a sample this answer is about; %d parked or filtered ones are counted above and left out, because their state counts were most of the answer and said nothing about where the time went. Pass includeThreads true for all of them." //$NON-NLS-1$
							.formatted(Integer.valueOf(sampled - listed)));
		}
		long achieved = session.achievedIntervalMillis();
		if (achieved > session.intervalMillis() * 1.2) {
			// sampling every thread every 10ms does not fit in 10ms, and a caller
			// drawing conclusions from sample counts has to know the clock drifted.
			// The sampler also perturbs what it measures; both belong in the answer
			result.put("intervalWarning", //$NON-NLS-1$
					"Sampling could not keep the requested %d ms interval and achieved %d ms. Sample fewer threads or ask for a longer interval; note also that sampling itself slows the work being measured, measurably so at short intervals across many threads." //$NON-NLS-1$
							.formatted(Integer.valueOf(session.intervalMillis()), Long.valueOf(achieved)));
		}
		if (frameFilter != null) {
			result.put("frameFilter", frameFilter) //$NON-NLS-1$
					.put("samplesWithoutTheFilteredFrame", Integer.valueOf(filtered)); //$NON-NLS-1$
		}
		if (session.stoppedByBudget()) {
			result.put("note", //$NON-NLS-1$
					"Sampling stopped on its own after %d ticks because maxSamples was reached, %d ms in. If that is shorter than the operation you meant to profile, raise maxSamples." //$NON-NLS-1$
							.formatted(session.ticks(), session.elapsedMillis()));
		}
		if (samples.isEmpty()) {
			return result.put("note", idle > 0 //$NON-NLS-1$
					? "Every sample was a thread parked or waiting. Nothing was running; sample the ui thread to profile UI work." //$NON-NLS-1$
					: "No samples were taken. The threads may have been idle or already gone."); //$NON-NLS-1$
		}

		// self time: the innermost frame is where the thread actually was
		Map<String, Integer> self = new LinkedHashMap<>();
		Map<String, Integer> total = new LinkedHashMap<>();
		for (Sample entry : samples) {
			StackTraceElement[] sample = entry.stack();
			self.merge(frame(sample[0]), 1, Integer::sum);
			Set<String> seen = new LinkedHashSet<>();
			for (StackTraceElement element : sample) {
				if (seen.add(frame(element))) {
					total.merge(frame(element), 1, Integer::sum);
				}
			}
		}
		result.put("topBySelfTime", top(self, topMethods, samples.size())); //$NON-NLS-1$
		presence(total, samples.size(), topMethods, result);
		tree(samples, minSamples, session.maxDepth(), result);
		if (includeRaw) {
			JsonArray raw = new JsonArray();
			for (Sample entry : samples) {
				JsonArray frames = new JsonArray();
				for (StackTraceElement element : entry.stack()) {
					frames.add(frame(element));
				}
				// the thread and its state travel with the stack: a bare frame array
				// cannot say whose it was or whether it was running
				raw.add(new JsonObject().put("thread", entry.threadName()) //$NON-NLS-1$
						.put("state", String.valueOf(entry.state())) //$NON-NLS-1$
						.put("frames", frames)); //$NON-NLS-1$
			}
			result.put("rawSamples", raw); //$NON-NLS-1$
		}
		return result;
	}

	/**
	 * Per thread, with its states and the CPU time it actually burned.
	 * <p>
	 * Without this two workers collapse into one entry, so a saturated thread and
	 * two half-busy ones look identical, and a 24 second stall cannot be told to
	 * have been the UI thread. The states answer the other half: blocked on a lock
	 * and burning CPU are the same number of samples and opposite diagnoses.
	 */
	/** The threads that contributed a sample the answer is actually about. */
	private static java.util.Set<Long> contributing(List<Sample> samples) {
		java.util.Set<Long> ids = new java.util.HashSet<>();
		for (Sample sample : samples) {
			ids.add(Long.valueOf(sample.threadId()));
		}
		return ids;
	}

	private static JsonArray byThread(List<Sample> samples, java.util.Set<Long> contributing, Session session,
			boolean includeAll) {
		Map<Long, String> names = new LinkedHashMap<>();
		Map<Long, Integer> counts = new LinkedHashMap<>();
		Map<Long, Map<String, Integer>> states = new LinkedHashMap<>();
		for (Sample sample : samples) {
			Long id = Long.valueOf(sample.threadId());
			names.put(id, sample.threadName());
			counts.merge(id, Integer.valueOf(1), Integer::sum);
			states.computeIfAbsent(id, key -> new LinkedHashMap<>())
					.merge(String.valueOf(sample.state()), Integer.valueOf(1), Integer::sum);
		}
		Map<Long, long[]> cpu = session.cpuRanges();
		List<Map.Entry<Long, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort(Map.Entry.<Long, Integer>comparingByValue().reversed());
		JsonArray array = new JsonArray();
		for (Map.Entry<Long, Integer> entry : sorted) {
			// an IDE parks seventy threads in pools, and their state counts were most
			// of the answer while saying nothing about where the time went
			if (!includeAll && !contributing.contains(entry.getKey())) {
				continue;
			}
			JsonObject states0 = new JsonObject();
			states.get(entry.getKey()).forEach(states0::put);
			long[] range = cpu.get(entry.getKey());
			array.add(new JsonObject().put("thread", names.get(entry.getKey())) //$NON-NLS-1$
					.put("threadId", entry.getKey()) //$NON-NLS-1$
					.put("samples", entry.getValue()) //$NON-NLS-1$
					.put("states", states0) //$NON-NLS-1$
					// wall clock samples cannot separate "slow" from "waited"; this can
					.put("cpuMillis", range == null || range[0] < 0 ? null //$NON-NLS-1$
							: Long.valueOf((range[1] - range[0]) / 1_000_000)));
		}
		return array;
	}

	/**
	 * A stack parked in a pool is not where time is going. Without this, threads:all
	 * on an IDE with seventy pooled threads reports Unsafe.park as the hot frame.
	 */
	private static boolean isIdle(StackTraceElement[] sample) {
		String innermost = sample[0].getClassName() + '.' + sample[0].getMethodName();
		return innermost.equals("jdk.internal.misc.Unsafe.park") //$NON-NLS-1$
				|| innermost.equals("java.lang.Object.wait0") //$NON-NLS-1$
				|| innermost.equals("java.lang.Object.wait") //$NON-NLS-1$
				|| innermost.equals("java.lang.Thread.sleep0") //$NON-NLS-1$
				|| innermost.equals("java.lang.Thread.sleep") //$NON-NLS-1$
				|| innermost.startsWith("sun.nio.ch.EPoll.wait") //$NON-NLS-1$
				|| innermost.startsWith("sun.nio.ch.Net.poll"); //$NON-NLS-1$
	}

	private static JsonArray top(Map<String, Integer> counts, int limit, int samples) {
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
				.thenComparing(Comparator.comparing(Map.Entry::getKey)));
		JsonArray array = new JsonArray();
		for (Map.Entry<String, Integer> entry : sorted.subList(0, Math.min(limit, sorted.size()))) {
			array.add(new JsonObject().put("frame", entry.getKey()) //$NON-NLS-1$
					.put("samples", entry.getValue()) //$NON-NLS-1$
					.put("percent", Math.round(entry.getValue() * 1000.0 / samples) / 10.0)); //$NON-NLS-1$
		}
		return array;
	}

	/**
	 * Adds topByPresence, leaving out the frames on every sample and listing those
	 * once under onEveryStack.
	 * <p>
	 * A frame on every stack is the trunk, not a finding: under a frameFilter the
	 * list was five rows at 100 percent, the glue between the filter frame and the
	 * event loop, and said nothing about where the time went.
	 */
	public static void presence(List<Sample> samples, int topMethods, JsonObject result) {
		Map<String, Integer> total = new LinkedHashMap<>();
		for (Sample entry : samples) {
			Set<String> seen = new LinkedHashSet<>();
			for (StackTraceElement element : entry.stack()) {
				if (seen.add(frame(element))) {
					total.merge(frame(element), 1, Integer::sum);
				}
			}
		}
		presence(total, samples.size(), topMethods, result);
	}

	private static void presence(Map<String, Integer> total, int samples, int topMethods, JsonObject result) {
		JsonArray everywhere = new JsonArray();
		if (samples > 1) {
			for (Map.Entry<String, Integer> entry : new ArrayList<>(total.entrySet())) {
				if (entry.getValue().intValue() == samples) {
					everywhere.add(entry.getKey());
					total.remove(entry.getKey());
				}
			}
		}
		result.put("topByPresence", top(total, topMethods, samples)); //$NON-NLS-1$
		if (everywhere.size() > 0) {
			result.put("onEveryStack", everywhere) //$NON-NLS-1$
					.put("onEveryStackNote", //$NON-NLS-1$
							"These %d frames were on every sample and are left out of topByPresence, which otherwise lists the trunk shared by all samples instead of the frames that tell them apart." //$NON-NLS-1$
									.formatted(Integer.valueOf(everywhere.size())));
		}
	}

	/** Adds the merged call tree, and what was done about samples cut at maxDepth. */
	public static void tree(List<Sample> samples, int minSamples, int maxDepth, JsonObject result) {
		Alignment aligned = align(samples, maxDepth);
		result.put("tree", tree(aligned.samples(), minSamples)); //$NON-NLS-1$
		if (aligned.truncated() > 0) {
			result.put("truncatedSamples", Integer.valueOf(aligned.truncated())) //$NON-NLS-1$
					.put("truncatedNote", aligned.note()); //$NON-NLS-1$
		}
	}

	/**
	 * Samples with their outer ends aligned, so that stacks cut at maxDepth merge
	 * under one root.
	 * <p>
	 * A stack deeper than maxDepth loses its OUTERMOST frames, and a different
	 * number of them per sample as the leaf side grows and shrinks, so one path
	 * through the event loop arrived as five roots that were the same path five
	 * times, and the tree was five times the size it should have been. Per thread,
	 * the outermost frame every truncated sample still shares is taken as the root
	 * and the frames outside it are dropped from every sample of that thread.
	 */
	private static Alignment align(List<Sample> samples, int maxDepth) {
		Map<Long, List<Sample>> byThread = new LinkedHashMap<>();
		for (Sample sample : samples) {
			byThread.computeIfAbsent(Long.valueOf(sample.threadId()), id -> new ArrayList<>()).add(sample);
		}
		List<Sample> aligned = new ArrayList<>(samples.size());
		List<String> anchors = new ArrayList<>();
		int truncated = 0;
		int unaligned = 0;
		for (List<Sample> group : byThread.values()) {
			List<Sample> cut = group.stream().filter(sample -> sample.stack().length >= maxDepth).toList();
			if (cut.isEmpty()) {
				aligned.addAll(group);
				continue;
			}
			truncated += cut.size();
			String anchor = anchor(cut);
			if (anchor == null) {
				unaligned += cut.size();
				aligned.addAll(group);
				continue;
			}
			anchors.add(anchor);
			for (Sample sample : group) {
				aligned.add(trimAt(sample, anchor));
			}
		}
		String note = null;
		if (truncated > 0) {
			note = "%d samples were deeper than maxDepth %d and lost their outermost frames, a different number each. The tree is rooted per thread at the outermost frame every truncated stack of that thread still shares (%s), and the frames outside it were dropped from every sample of that thread so that they merge under one root. Raise maxDepth to see the outer frames." //$NON-NLS-1$
					.formatted(Integer.valueOf(truncated), Integer.valueOf(maxDepth), String.join(", ", anchors)); //$NON-NLS-1$
			if (unaligned > 0) {
				note += " %d truncated samples share no outermost frame with the others of their thread and are merged as they are, which is what produces several roots for one path." //$NON-NLS-1$
						.formatted(Integer.valueOf(unaligned));
			}
		}
		return new Alignment(aligned, truncated, note);
	}

	private record Alignment(List<Sample> samples, int truncated, String note) {
	}

	/**
	 * The outermost frame of one truncated sample that every truncated sample
	 * contains, choosing the one nearest the leaf, which is the one from the
	 * deepest stack and the one that trims the most.
	 */
	private static String anchor(List<Sample> cut) {
		List<Set<String>> frames = new ArrayList<>(cut.size());
		for (Sample sample : cut) {
			Set<String> set = new HashSet<>();
			for (StackTraceElement element : sample.stack()) {
				set.add(frame(element));
			}
			frames.add(set);
		}
		Set<String> candidates = new LinkedHashSet<>();
		for (Sample sample : cut) {
			candidates.add(frame(sample.stack()[sample.stack().length - 1]));
		}
		String best = null;
		int bestIndex = Integer.MAX_VALUE;
		StackTraceElement[] first = cut.get(0).stack();
		for (String candidate : candidates) {
			if (!frames.stream().allMatch(set -> set.contains(candidate))) {
				continue;
			}
			for (int i = 0; i < first.length; i++) {
				if (frame(first[i]).equals(candidate)) {
					if (i < bestIndex) {
						bestIndex = i;
						best = candidate;
					}
					break;
				}
			}
		}
		return best;
	}

	/** The sample without the frames outside {@code anchor}, or as it is when the anchor is not in it. */
	private static Sample trimAt(Sample sample, String anchor) {
		StackTraceElement[] stack = sample.stack();
		for (int i = stack.length - 1; i >= 0; i--) {
			if (frame(stack[i]).equals(anchor)) {
				if (i == stack.length - 1) {
					return sample;
				}
				StackTraceElement[] trimmed = new StackTraceElement[i + 1];
				System.arraycopy(stack, 0, trimmed, 0, i + 1);
				return new Sample(sample.threadId(), sample.threadName(), sample.state(), trimmed);
			}
		}
		return sample;
	}

	/** Merges the samples into one call tree, outermost frame first. */
	private static JsonArray tree(List<Sample> samples, int minSamples) {
		Node root = new Node("");  //$NON-NLS-1$
		for (Sample entry : samples) {
			StackTraceElement[] sample = entry.stack();
			Node current = root;
			for (int i = sample.length - 1; i >= 0; i--) {
				current = current.child(frame(sample[i]));
				current.count++;
			}
		}
		return root.toJson(minSamples);
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

		JsonArray toJson(int minSamples) {
			List<Node> sorted = new ArrayList<>(children.values());
			sorted.sort(Comparator.comparingInt((Node n) -> n.count).reversed());
			JsonArray array = new JsonArray();
			for (Node node : sorted) {
				if (node.count < minSamples) {
					continue;
				}
				JsonObject json = new JsonObject().put("frame", node.frame).put("samples", node.count); //$NON-NLS-1$ //$NON-NLS-2$
				// a run of frames with one child each and the same count is one path
				// with nothing to choose along it, forty nested objects for the launcher
				// and the event loop on every UI thread profile; folded, it is one line
				Node tail = node;
				JsonArray chain = new JsonArray();
				while (tail.children.size() == 1) {
					Node only = tail.children.values().iterator().next();
					if (only.count != tail.count) {
						break;
					}
					chain.add(only.frame);
					tail = only;
				}
				if (chain.size() > 0) {
					json.put("chain", chain); //$NON-NLS-1$
				}
				JsonArray children = tail.toJson(minSamples);
				if (children.size() > 0) {
					json.put("children", children); //$NON-NLS-1$
				}
				array.add(json);
			}
			return array;
		}
	}

	private static String frame(StackTraceElement element) {
		return element.getClassName() + '.' + element.getMethodName()
				+ (element.getLineNumber() > 0 ? ":" + element.getLineNumber() : ""); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
