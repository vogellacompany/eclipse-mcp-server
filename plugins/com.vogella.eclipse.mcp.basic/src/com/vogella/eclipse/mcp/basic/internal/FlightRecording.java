package com.vogella.eclipse.mcp.basic.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import com.vogella.eclipse.mcp.core.FlameGraph;
import com.vogella.eclipse.mcp.core.ServerFrames;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Everything that talks to Java Flight Recorder.
 * <p>
 * The {@code jdk.jfr} packages are imported optionally, so every caller has to
 * catch {@link LinkageError}, the way {@code CssStyling} isolates the CSS
 * engine. Keeping the references in one class is what makes that possible: a
 * JVM that does not export them then costs a refusal rather than a failure
 * somewhere unrelated.
 */
final class FlightRecording {

	/** Events read from one dump. Beyond this the answer says it stopped early. */
	static final int MAX_EVENTS = 200_000;

	private static final String ALLOCATION_SAMPLE = "jdk.ObjectAllocationSample"; //$NON-NLS-1$

	private static final String EXECUTION_SAMPLE = "jdk.ExecutionSample"; //$NON-NLS-1$

	private static final Map<String, Recording> RECORDINGS = new ConcurrentHashMap<>();

	private static final AtomicLong IDS = new AtomicLong();

	private static volatile String mostRecent;

	private FlightRecording() {
	}

	/** Starts a recording and returns its id. */
	static String start(String settings, long maxAgeSeconds, long maxSizeBytes, long durationSeconds, String name,
			Path dumpOnExitTo) throws IOException, ParseFailure {
		Configuration configuration = configuration(settings);
		Recording recording = new Recording(configuration);
		try {
			configure(recording, name, maxAgeSeconds, maxSizeBytes, durationSeconds, dumpOnExitTo);
			recording.start();
		} catch (IOException | RuntimeException e) {
			recording.close();
			throw e;
		}
		String id = "jfr-" + IDS.incrementAndGet(); //$NON-NLS-1$
		RECORDINGS.put(id, recording);
		mostRecent = id;
		return id;
	}

	private static void configure(Recording recording, String name, long maxAgeSeconds, long maxSizeBytes,
			long durationSeconds, Path dumpOnExitTo) throws IOException {
		recording.setName(name == null ? "MCP flight recording" : name); //$NON-NLS-1$
		// to disk with a bounded age and size: a recording nobody stops must cost a
		// known amount of disk rather than growing until somebody notices
		recording.setToDisk(true);
		if (maxAgeSeconds > 0) {
			recording.setMaxAge(Duration.ofSeconds(maxAgeSeconds));
		}
		if (maxSizeBytes > 0) {
			recording.setMaxSize(maxSizeBytes);
		}
		if (durationSeconds > 0) {
			recording.setDuration(Duration.ofSeconds(durationSeconds));
		}
		if (dumpOnExitTo != null) {
			// the only way to record what happens while this IDE shuts down: the tool
			// that would stop the recording is going down with it, so the JVM has to
			// write the file on its own way out
			recording.setDestination(dumpOnExitTo);
			recording.setDumpOnExit(true);
		}
	}

	private static Configuration configuration(String settings) throws IOException, ParseFailure {
		try {
			return Configuration.getConfiguration(settings);
		} catch (ParseException e) {
			throw new ParseFailure(e.getMessage());
		}
	}

	/** A settings file the JVM rejected, as a checked failure the tool can report. */
	static final class ParseFailure extends Exception {

		private static final long serialVersionUID = 1L;

		ParseFailure(String message) {
			super(message);
		}
	}

	static String mostRecentId() {
		return mostRecent;
	}

	/**
	 * Writes what has been recorded so far to {@code file}, and stops the recording
	 * unless {@code keepRunning}. Dumping a running recording is supported, which
	 * is what lets one session be read from several angles.
	 */
	static void dump(String id, Path file, boolean keepRunning) throws IOException {
		Recording recording = RECORDINGS.get(id);
		if (recording == null) {
			throw new IOException("No recording '%s'.".formatted(id)); //$NON-NLS-1$
		}
		recording.dump(file);
		if (!keepRunning) {
			// a recording with a duration has stopped by itself, and stopping it again throws
			if (recording.getState() == RecordingState.RUNNING) {
				recording.stop();
			}
			recording.close();
			RECORDINGS.remove(id);
		}
	}

	/**
	 * Reads one dump and aggregates it: where the bytes were allocated, where the
	 * time was spent, and what the collector did. When {@code flame} is given it is
	 * filled with the allocation stacks in the same pass.
	 */
	static JsonObject aggregate(Path file, Aggregation options, FlameGraph.Builder flame) throws IOException {
		Map<String, long[]> byClass = new HashMap<>();
		Map<String, long[]> byStack = new HashMap<>();
		Map<String, long[]> hotMethods = new HashMap<>();
		Map<String, Integer> eventCounts = new HashMap<>();
		Map<String, Integer> matchedCounts = new HashMap<>();
		int matched = 0;
		long gcCount = 0;
		long longestPauseNanos = 0;
		int read = 0;
		int serverDropped = 0;
		int serverElided = 0;
		boolean truncated = false;

		try (RecordingFile recordingFile = new RecordingFile(file)) {
			while (recordingFile.hasMoreEvents()) {
				if (read >= MAX_EVENTS) {
					truncated = true;
					break;
				}
				RecordedEvent event = recordingFile.readEvent();
				read++;
				String type = event.getEventType().getName();
				eventCounts.merge(type, Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + b.intValue()));
				List<RecordedFrame> frames = event.getStackTrace() == null ? List.of() : event.getStackTrace().getFrames();
				if (!options.includeServer() && !frames.isEmpty()) {
					RecordedThread thread = event.hasField("eventThread") ? event.getThread("eventThread") : null; //$NON-NLS-1$ //$NON-NLS-2$
					List<String> classes = new ArrayList<>(frames.size());
					for (RecordedFrame frame : frames) {
						classes.add(frame.getMethod() == null ? null : frame.getMethod().getType().getName());
					}
					switch (ServerFrames.classify(thread == null ? null : thread.getJavaName(), classes)) {
					case DROP -> {
						serverDropped++;
						continue;
					}
					case ELIDE -> {
						serverElided++;
						frames = frames.stream().filter(frame -> frame.getMethod() == null
								|| !ServerFrames.isOwn(frame.getMethod().getType().getName())).toList();
					}
					case KEEP -> {
						// the application's own
					}
					}
				}
				// filter on the WHOLE stack and render afterwards: matching the rendered
				// text made stackDepth silently narrow what the filter could see, so a
				// deep frame that was being searched for could not be found at all
				if (!matches(frames, options.frameFilter())) {
					continue;
				}
				matched++;
				matchedCounts.merge(type, Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + b.intValue()));
				String stack = render(frames, options.stackDepth());
				if (ALLOCATION_SAMPLE.equals(type)) {
					long weight = event.hasField("weight") ? event.getLong("weight") : 0; //$NON-NLS-1$ //$NON-NLS-2$
					add(byClass, className(event), weight);
					if (stack != null) {
						add(byStack, stack, weight);
						if (flame != null) {
							flame.add(FlameGraph.parseArrowStack(stack), weight);
						}
					}
				} else if (EXECUTION_SAMPLE.equals(type) && stack != null) {
					add(hotMethods, topFrame(frames), 1);
				} else if (type.startsWith("jdk.GC") && event.getDuration() != null) { //$NON-NLS-1$
					gcCount++;
					longestPauseNanos = Math.max(longestPauseNanos, event.getDuration().toNanos());
				}
			}
		}

		JsonObject result = new JsonObject().put("eventsRead", Integer.valueOf(read)) //$NON-NLS-1$
				.put("eventsMatched", Integer.valueOf(matched)) //$NON-NLS-1$
				.put("serverEventsExcluded", options.includeServer() ? null : Integer.valueOf(serverDropped)) //$NON-NLS-1$
				.put("serverFramesElidedFrom", options.includeServer() ? null : Integer.valueOf(serverElided)) //$NON-NLS-1$
				.put("eventsTruncated", Boolean.valueOf(truncated)) //$NON-NLS-1$
				.put("allocationByClass", top(byClass, options.topClasses(), "class")) //$NON-NLS-1$ //$NON-NLS-2$
				.put("allocationByStack", top(byStack, options.topStacks(), "stack")) //$NON-NLS-1$ //$NON-NLS-2$
				.put("hotMethods", counts(hotMethods, options.topClasses())); //$NON-NLS-1$
		JsonArray events = new JsonArray();
		eventCounts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(15)
				.forEach(entry -> events.add(new JsonObject().put("type", entry.getKey()) //$NON-NLS-1$
						.put("count", entry.getValue()))); //$NON-NLS-1$
		JsonArray matchedEvents = new JsonArray();
		matchedCounts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(15)
				.forEach(entry -> matchedEvents.add(new JsonObject().put("type", entry.getKey()) //$NON-NLS-1$
						.put("count", entry.getValue()))); //$NON-NLS-1$
		return result.put("eventTypes", events) //$NON-NLS-1$
				.put("matchedEventTypes", matchedEvents) //$NON-NLS-1$
				.put("counting", //$NON-NLS-1$
						"eventTypes counts everything in the file, which grows with the recording and is the same for every frameFilter. matchedEventTypes counts only what this call's filter kept, which is what one phase can be weighed against another with.") //$NON-NLS-1$
				.put("gc", new JsonObject().put("events", Long.valueOf(gcCount)) //$NON-NLS-1$ //$NON-NLS-2$
						.put("longestPauseMillis", Long.valueOf(longestPauseNanos / 1_000_000L))); //$NON-NLS-1$
	}

	/** What to aggregate, and how much of it to report. */
	record Aggregation(int topClasses, int topStacks, int stackDepth, String frameFilter, boolean includeServer) {
	}

	private static void add(Map<String, long[]> into, String key, long weight) {
		if (key == null) {
			return;
		}
		long[] totals = into.computeIfAbsent(key, unused -> new long[2]);
		totals[0] += weight;
		totals[1]++;
	}

	/**
	 * The heaviest entries first. The byte figures come from the sampler's own
	 * weight, so they are an estimate of what was allocated and not a count of it.
	 */
	private static JsonArray top(Map<String, long[]> counts, int limit, String keyName) {
		JsonArray array = new JsonArray();
		counts.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0])
				.reversed().thenComparing(Map.Entry::getKey)).limit(limit)
				.forEach(entry -> array.add(new JsonObject().put(keyName, entry.getKey())
						.put("bytes", Long.valueOf(entry.getValue()[0])) //$NON-NLS-1$
						.put("samples", Long.valueOf(entry.getValue()[1])))); //$NON-NLS-1$
		return array;
	}

	/** Execution samples are counted, not weighed, so they carry no byte figure. */
	private static JsonArray counts(Map<String, long[]> counts, int limit) {
		JsonArray array = new JsonArray();
		counts.entrySet().stream()
				.sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1]).reversed()
						.thenComparing(Map.Entry::getKey))
				.limit(limit).forEach(entry -> array.add(new JsonObject().put("method", entry.getKey()) //$NON-NLS-1$
						.put("samples", Long.valueOf(entry.getValue()[1])))); //$NON-NLS-1$
		return array;
	}

	private static String className(RecordedEvent event) {
		try {
			return event.getClass("objectClass") == null ? null : event.getClass("objectClass").getName(); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String topFrame(List<RecordedFrame> frames) {
		if (frames.isEmpty()) {
			return null;
		}
		return frame(frames.get(0));
	}

	/**
	 * Whether the filter appears anywhere in the stack, at any depth.
	 * <p>
	 * Deliberately not on the rendered text: rendering cuts at stackDepth, so a
	 * caller who narrowed the display was also narrowing the search without being
	 * told, and asking for a deep frame with a shallow depth returned nothing.
	 */
	private static boolean matches(List<RecordedFrame> frames, String filter) {
		if (filter == null) {
			return true;
		}
		for (RecordedFrame frame : frames) {
			String rendered = frame(frame);
			if (rendered != null && rendered.contains(filter)) {
				return true;
			}
		}
		return false;
	}

	private static String render(List<RecordedFrame> frames, int depth) {
		if (frames.isEmpty()) {
			return null;
		}
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < Math.min(depth, frames.size()); i++) {
			if (i > 0) {
				text.append(" <- "); //$NON-NLS-1$
			}
			text.append(frame(frames.get(i)));
		}
		return text.toString();
	}

	private static String frame(RecordedFrame frame) {
		if (frame.getMethod() == null) {
			return "unknown"; //$NON-NLS-1$
		}
		return frame.getMethod().getType().getName() + '.' + frame.getMethod().getName();
	}

	/** A temporary file for one dump, in the workspace-independent temp directory. */
	static Path temporaryFile() throws IOException {
		return Files.createTempFile("mcp-flight-recording", ".jfr"); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
