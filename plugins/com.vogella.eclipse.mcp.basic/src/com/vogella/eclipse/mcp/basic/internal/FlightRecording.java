package com.vogella.eclipse.mcp.basic.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jdk.jfr.Configuration;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.SettingDescriptor;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
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

	private static final String NATIVE_SAMPLE = "jdk.NativeMethodSample"; //$NON-NLS-1$

	private static final String ERROR_THROW = "jdk.JavaErrorThrow"; //$NON-NLS-1$

	private static final String EXCEPTION_THROW = "jdk.JavaExceptionThrow"; //$NON-NLS-1$

	private static final String ACTIVE_SETTING = "jdk.ActiveSetting"; //$NON-NLS-1$

	private static final String[] NO_FRAMES = {};

	private static final String TIME_NOTE = "Samples are not time. The JVM takes few native samples per tick across ALL threads, so a thread's native count falls when others sit in native code, and a short period gives fewer samples than it asks for; compare perSecond under sampling with the period. A thread filter selects samples and cannot add any."; //$NON-NLS-1$

	private static final Map<String, Recording> RECORDINGS = new ConcurrentHashMap<>();

	private static final AtomicLong IDS = new AtomicLong();

	private static volatile String mostRecent;

	private FlightRecording() {
	}

	/** Starts a recording and returns its id. */
	static String start(String settings, Map<String, String> overrides, long maxAgeSeconds, long maxSizeBytes,
			long durationSeconds, String name, Path dumpOnExitTo) throws IOException, ParseFailure {
		Configuration configuration = configuration(settings);
		checkOverrides(overrides);
		Recording recording = new Recording(configuration);
		try {
			if (!overrides.isEmpty()) {
				Map<String, String> merged = new HashMap<>(recording.getSettings());
				merged.putAll(overrides);
				recording.setSettings(merged);
			}
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

	/**
	 * Refuses what the JVM would silently ignore: a misspelt setting records with the
	 * old value and nobody notices. Only JDK events are checked, because an
	 * application's own event may not be registered yet.
	 */
	private static void checkOverrides(Map<String, String> overrides) throws ParseFailure {
		Map<String, EventType> known = new HashMap<>();
		for (EventType type : FlightRecorder.getFlightRecorder().getEventTypes()) {
			known.put(type.getName(), type);
		}
		for (String key : overrides.keySet()) {
			int separator = key.indexOf('#');
			if (separator <= 0 || separator == key.length() - 1) {
				throw new ParseFailure("'%s' is not a setting: expected event#setting, such as jdk.ExecutionSample#period.".formatted(key)); //$NON-NLS-1$
			}
			String event = key.substring(0, separator);
			String setting = key.substring(separator + 1);
			EventType type = known.get(event);
			if (type == null) {
				if (event.startsWith("jdk.")) { //$NON-NLS-1$
					throw new ParseFailure("There is no event '%s' in this JVM.".formatted(event)); //$NON-NLS-1$
				}
				continue;
			}
			List<String> names = type.getSettingDescriptors().stream().map(SettingDescriptor::getName).toList();
			if (!names.contains(setting)) {
				throw new ParseFailure("Event '%s' has no setting '%s'; it has %s.".formatted(event, setting, names)); //$NON-NLS-1$
			}
		}
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
	 * Reads one dump once and aggregates it: where the bytes were allocated, where the
	 * time was spent, what was thrown and what the collector did. When {@code flame} is
	 * given it is filled with the stacks {@code options.timeFlame()} selects.
	 * <p>
	 * Events are held and filtered after the read: a file is not in time order, so the
	 * recording's start, which a time window is relative to, is only known at the end.
	 */
	static JsonObject aggregate(Path file, Aggregation options, FlameGraph.Builder flame) throws IOException {
		Map<String, Integer> eventCounts = new HashMap<>();
		List<Seen> seen = new ArrayList<>();
		Map<RecordedMethod, String> frameNames = new IdentityHashMap<>();
		Map<String, String> settings = new HashMap<>();
		List<String[]> activeSettings = new ArrayList<>();
		long first = Long.MAX_VALUE;
		long last = Long.MIN_VALUE;
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
				eventCounts.merge(type, Integer.valueOf(1), Integer::sum);
				long start = nanos(event.getStartTime());
				first = Math.min(first, start);
				last = Math.max(last, nanos(event.getEndTime()));
				if (ACTIVE_SETTING.equals(type)) {
					activeSettings.add(new String[] { String.valueOf(event.getLong("id")), event.getString("name"), //$NON-NLS-1$ //$NON-NLS-2$
							event.getString("value") }); //$NON-NLS-1$
				}
				RecordedStackTrace stackTrace = event.getStackTrace();
				List<RecordedFrame> frames = stackTrace == null ? List.of() : stackTrace.getFrames();
				String thread = threadName(event);
				if (!options.includeServer() && !frames.isEmpty()) {
					List<String> classes = new ArrayList<>(frames.size());
					for (RecordedFrame frame : frames) {
						classes.add(frame.getMethod() == null ? null : frame.getMethod().getType().getName());
					}
					switch (ServerFrames.classify(thread, classes)) {
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
				boolean allocation = ALLOCATION_SAMPLE.equals(type);
				boolean throwing = isThrow(type);
				boolean needsStack = options.frameFilter() != null || allocation || isSample(type) || throwing;
				seen.add(new Seen(type, start, type.startsWith("jdk.GC") ? event.getDuration().toNanos() : 0, thread, //$NON-NLS-1$
						needsStack ? names(frames, frameNames) : NO_FRAMES,
						stackTrace != null && stackTrace.isTruncated(),
						allocation && event.hasField("weight") ? event.getLong("weight") : 0, //$NON-NLS-1$ //$NON-NLS-2$
						allocation ? className(event, "objectClass") : throwing ? className(event, "thrownClass") : null, //$NON-NLS-1$ //$NON-NLS-2$
						throwing ? event.getString("message") : null)); //$NON-NLS-1$
			}
			Map<Long, String> typeNames = new HashMap<>();
			for (EventType eventType : recordingFile.readEventTypes()) {
				typeNames.put(Long.valueOf(eventType.getId()), eventType.getName());
			}
			for (String[] setting : activeSettings) {
				settings.put(typeNames.get(Long.valueOf(setting[0])) + '#' + setting[1], setting[2]);
			}
		}

		Map<String, long[]> byClass = new HashMap<>();
		Map<String, long[]> byStack = new HashMap<>();
		Map<String, long[]> hotMethods = new HashMap<>();
		Map<String, Integer> matchedCounts = new HashMap<>();
		Set<String> threadFiltersUsed = new HashSet<>();
		List<StackAlignment.Stack> allocationStacks = new ArrayList<>();
		TimeProfile time = new TimeProfile(options.time());
		ThrownEvents thrown = new ThrownEvents();
		int matched = 0;
		int outsideWindow = 0;
		int otherThreads = 0;
		int otherKind = 0;
		int allExecution = 0;
		int allNative = 0;
		long gcCount = 0;
		long longestPauseNanos = 0;
		long windowStart = options.fromSeconds() == null ? Long.MIN_VALUE : first + nanos(options.fromSeconds());
		long windowEnd = options.toSeconds() == null ? Long.MAX_VALUE : first + nanos(options.toSeconds());
		for (Seen event : seen) {
			boolean sample = isSample(event.type());
			boolean execution = EXECUTION_SAMPLE.equals(event.type());
			if (execution) {
				allExecution++;
			} else if (sample) {
				allNative++;
			}
			if (event.start() < windowStart || event.start() >= windowEnd) {
				outsideWindow++;
				continue;
			}
			// filter on the WHOLE stack and render afterwards: matching the rendered
			// text made stackDepth silently narrow what the filter could see, so a
			// deep frame that was being searched for could not be found at all
			if (!matches(event.frames(), options.frameFilter())) {
				continue;
			}
			// the collector belongs to no thread of the application, so a thread filter leaves it standing
			if (event.type().startsWith("jdk.GC")) { //$NON-NLS-1$
				gcCount++;
				longestPauseNanos = Math.max(longestPauseNanos, event.duration());
			}
			if (sample) {
				time.observe(event.thread(), execution, event.frames());
			}
			if (!threadSelected(event.thread(), options.threadNames(), threadFiltersUsed)) {
				otherThreads++;
				continue;
			}
			matched++;
			matchedCounts.merge(event.type(), Integer.valueOf(1), Integer::sum);
			if (ALLOCATION_SAMPLE.equals(event.type())) {
				add(byClass, event.detail(), event.weight());
				String stack = render(event.frames(), options.stackDepth());
				if (stack != null) {
					add(byStack, stack, event.weight());
					allocationStacks.add(new StackAlignment.Stack(event.thread(), event.frames(), event.truncated(),
							event.weight()));
				}
			} else if (sample) {
				if (execution && event.frames().length > 0) {
					add(hotMethods, event.frames()[0], 1);
				}
				if (options.sampleKind() == null || options.sampleKind().equals(execution ? "execution" : "native")) { //$NON-NLS-1$ //$NON-NLS-2$
					time.add(event.thread(), execution, event.frames(), event.truncated());
				} else {
					otherKind++;
				}
			} else if (isThrow(event.type())) {
				thrown.add(ERROR_THROW.equals(event.type()), event.detail(), event.message(), event.frames());
			}
		}

		if (flame != null) {
			StackAlignment.draw(StackAlignment.align(options.timeFlame() ? time.stacks() : allocationStacks).stacks(),
					flame);
		}

		JsonObject result = new JsonObject().put("eventsRead", Integer.valueOf(read)) //$NON-NLS-1$
				.put("eventsMatched", Integer.valueOf(matched)) //$NON-NLS-1$
				.put("serverEventsExcluded", options.includeServer() ? null : Integer.valueOf(serverDropped)) //$NON-NLS-1$
				.put("serverFramesElidedFrom", options.includeServer() ? null : Integer.valueOf(serverElided)) //$NON-NLS-1$
				.put("eventsTruncated", Boolean.valueOf(truncated)); //$NON-NLS-1$
		if (truncated) {
			result.put("eventsTruncatedNote", //$NON-NLS-1$
					"Reading stopped at the event limit. A file is not in time order, so what is missing can be from anywhere in the recording, inside a time window too."); //$NON-NLS-1$
		}
		listed(result, "allocationByClass", top(byClass, options.topClasses(), "class"), byClass.size(), //$NON-NLS-1$ //$NON-NLS-2$
				options.topClasses());
		listed(result, "allocationByStack", top(byStack, options.topStacks(), "stack"), byStack.size(), //$NON-NLS-1$ //$NON-NLS-2$
				options.topStacks());
		listed(result, "hotMethods", counts(hotMethods, options.topClasses()), hotMethods.size(), options.topClasses()); //$NON-NLS-1$
		long durationNanos = first == Long.MAX_VALUE ? 0 : last - first;
		JsonObject timeAnswer = time.toJson();
		timeAnswer.put("recordingStart", first == Long.MAX_VALUE ? null : instant(first).toString()) //$NON-NLS-1$
				.put("recordingSeconds", Double.valueOf(Math.round(durationNanos / 1_000_000.0) / 1000.0)) //$NON-NLS-1$
				.put("eventsOutsideWindow", Integer.valueOf(outsideWindow)) //$NON-NLS-1$
				.put("eventsOnOtherThreads", Integer.valueOf(otherThreads)) //$NON-NLS-1$
				.put("otherKindExcluded", Integer.valueOf(otherKind)) //$NON-NLS-1$
				.put("sampling", new JsonObject() //$NON-NLS-1$
						.put("execution", rate(settings, EXECUTION_SAMPLE, allExecution, durationNanos)) //$NON-NLS-1$
						.put("native", rate(settings, NATIVE_SAMPLE, allNative, durationNanos))) //$NON-NLS-1$
				.put("note", TIME_NOTE); //$NON-NLS-1$
		if (options.threadNames() != null) {
			JsonArray unused = new JsonArray();
			options.threadNames().stream().filter(name -> !threadFiltersUsed.contains(name)).forEach(unused::add);
			if (unused.size() > 0) {
				timeAnswer.put("threadNamesNotFound", unused); //$NON-NLS-1$
			}
		}
		result.put("time", timeAnswer) //$NON-NLS-1$
				.put("thrown", thrown.toJson(options.topThrown(), settings.get(EXCEPTION_THROW + "#throttle"))); //$NON-NLS-1$ //$NON-NLS-2$
		JsonArray events = new JsonArray();
		eventCounts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(15)
				.forEach(entry -> events.add(new JsonObject().put("type", entry.getKey()) //$NON-NLS-1$
						.put("count", entry.getValue()))); //$NON-NLS-1$
		JsonArray matchedEvents = new JsonArray();
		matchedCounts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(15)
				.forEach(entry -> matchedEvents.add(new JsonObject().put("type", entry.getKey()) //$NON-NLS-1$
						.put("count", entry.getValue()))); //$NON-NLS-1$
		listed(result, "eventTypes", events, eventCounts.size(), 15); //$NON-NLS-1$
		listed(result, "matchedEventTypes", matchedEvents, matchedCounts.size(), 15); //$NON-NLS-1$
		return result.put("counting", //$NON-NLS-1$
				"eventTypes counts everything in the file, which grows with the recording and is the same for every filter. matchedEventTypes counts only what this call's filters kept, which is what one phase can be weighed against another with.") //$NON-NLS-1$
				.put("gc", new JsonObject().put("events", Long.valueOf(gcCount)) //$NON-NLS-1$ //$NON-NLS-2$
						.put("longestPauseMillis", Long.valueOf(longestPauseNanos / 1_000_000L))); //$NON-NLS-1$
	}

	/** What to aggregate, and how much of it to report. */
	record Aggregation(int topClasses, int topStacks, int stackDepth, String frameFilter, boolean includeServer,
			List<String> threadNames, Double fromSeconds, Double toSeconds, String sampleKind, int topThrown,
			TimeProfile.Limits time, boolean timeFlame) {
	}

	/** What is kept of one event, so that filtering needs no second read. */
	private record Seen(String type, long start, long duration, String thread, String[] frames, boolean truncated,
			long weight, String detail, String message) {
	}

	private static boolean isSample(String type) {
		return EXECUTION_SAMPLE.equals(type) || NATIVE_SAMPLE.equals(type);
	}

	private static boolean isThrow(String type) {
		return ERROR_THROW.equals(type) || EXCEPTION_THROW.equals(type);
	}

	private static long nanos(Instant instant) {
		return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
	}

	private static long nanos(Double seconds) {
		return (long) (seconds.doubleValue() * 1_000_000_000L);
	}

	private static Instant instant(long nanos) {
		return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
	}

	/** A sample names its thread sampledThread; everything else has eventThread. */
	private static String threadName(RecordedEvent event) {
		RecordedThread thread = event.hasField("sampledThread") ? event.getThread("sampledThread") //$NON-NLS-1$ //$NON-NLS-2$
				: event.hasField("eventThread") ? event.getThread("eventThread") : null; //$NON-NLS-1$ //$NON-NLS-2$
		if (thread == null) {
			return null;
		}
		return thread.getJavaName() != null ? thread.getJavaName() : thread.getOSName();
	}

	/** Exact and ignoring case, or a prefix with a trailing star, so that "main" is not "Domain-1". */
	private static boolean threadMatches(String thread, String pattern) {
		if (pattern.endsWith("*")) { //$NON-NLS-1$
			return thread.regionMatches(true, 0, pattern, 0, pattern.length() - 1);
		}
		return thread.equalsIgnoreCase(pattern);
	}

	private static boolean threadSelected(String thread, List<String> patterns, Set<String> used) {
		if (patterns == null) {
			return true;
		}
		if (thread == null) {
			return false;
		}
		for (String pattern : patterns) {
			if (threadMatches(thread, pattern)) {
				used.add(pattern);
				return true;
			}
		}
		return false;
	}

	private static JsonObject rate(Map<String, String> settings, String type, int samples, long durationNanos) {
		return new JsonObject().put("samples", Integer.valueOf(samples)) //$NON-NLS-1$
				.put("enabled", settings.get(type + "#enabled")) //$NON-NLS-1$ //$NON-NLS-2$
				.put("period", settings.get(type + "#period")) //$NON-NLS-1$ //$NON-NLS-2$
				.put("perSecond", durationNanos <= 0 ? null //$NON-NLS-1$
						: Double.valueOf(Math.round(samples * 10_000_000_000.0 / durationNanos) / 10.0));
	}

	private static void listed(JsonObject into, String field, JsonArray list, int total, int limit) {
		into.put(field, list).put(field + "Total", Integer.valueOf(total)) //$NON-NLS-1$
				.put(field + "Truncated", Boolean.valueOf(total > limit)); //$NON-NLS-1$
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

	private static String className(RecordedEvent event, String field) {
		try {
			return event.getClass(field) == null ? null : event.getClass(field).getName();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Whether the filter appears anywhere in the stack, at any depth.
	 * <p>
	 * Deliberately not on the rendered text: rendering cuts at stackDepth, so a
	 * caller who narrowed the display was also narrowing the search without being
	 * told, and asking for a deep frame with a shallow depth returned nothing.
	 */
	private static boolean matches(String[] frames, String filter) {
		if (filter == null) {
			return true;
		}
		for (String frame : frames) {
			if (frame.contains(filter)) {
				return true;
			}
		}
		return false;
	}

	private static String render(String[] frames, int depth) {
		if (frames.length == 0) {
			return null;
		}
		return String.join(" <- ", Arrays.copyOf(frames, Math.min(depth, frames.length))); //$NON-NLS-1$
	}

	/** Names per method once, since a stack repeats them and a recording repeats stacks. */
	private static String[] names(List<RecordedFrame> frames, Map<RecordedMethod, String> cache) {
		String[] names = new String[frames.size()];
		for (int i = 0; i < names.length; i++) {
			RecordedMethod method = frames.get(i).getMethod();
			names[i] = method == null ? "unknown" //$NON-NLS-1$
					: cache.computeIfAbsent(method, m -> m.getType().getName() + '.' + m.getName());
		}
		return names;
	}

	/** A temporary file for one dump, in the workspace-independent temp directory. */
	static Path temporaryFile() throws IOException {
		return Files.createTempFile("mcp-flight-recording", ".jfr"); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
