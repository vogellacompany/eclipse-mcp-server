package com.vogella.eclipse.mcp.basic.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.FlameGraph;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Records the JVM with Java Flight Recorder and reports what it recorded.
 */
public final class FlightRecordingTools {

	/** What a JVM without the jdk.jfr packages gets told. */
	private static final String UNAVAILABLE = "This JVM does not expose Java Flight Recorder to the IDE: the jdk.jfr packages are not on the classpath of the framework. An Eclipse launched with --add-modules=ALL-SYSTEM, which is what eclipse.ini does by default, has them."; //$NON-NLS-1$

	private FlightRecordingTools() {
	}

	/** Starts a recording, which runs until it is stopped or its duration is up. */
	public static final class Start implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_start_flight_recording"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Starts a Java Flight Recorder recording of this IDE's JVM and returns a recordingId for eclipse_stop_flight_recording. This is the tool for WHERE THE MEMORY GOES and what the collector is doing, which eclipse_start_sampling cannot answer: that one samples call stacks by time, so code that allocates heavily but computes little is invisible to it. Use this to tell a leak from ordinary garbage, to find which code allocates, and to see GC pauses. Recording is in-process through jdk.jfr, so no external tool and no JVM flag at startup is needed. settings profile costs a few percent and includes the allocation and execution samples; default costs about one percent and covers GC and threads. settingsOverrides changes single settings on top of either, such as a shorter sample period for a short operation. The recording is bounded by maxAgeSeconds and maxSizeMegabytes and writes to disk, so one that is never stopped costs a known amount rather than growing; durationSeconds stops it on its own, and 0 means it runs until stopped."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "settings":          {"type":"string","enum":["default","profile"],"default":"profile","description":"profile adds the allocation and execution samples this is usually started for."},
					    "settingsOverrides": {"type":"object","additionalProperties":{"type":"string"},"description":"JFR settings applied on top of settings, as event#setting to value, for instance jdk.ExecutionSample#period to 1 ms, jdk.NativeMethodSample#period to 5 ms or jdk.JavaExceptionThrow#enabled to true. The default profile gives few samples over a startup of a few seconds, so a short operation wants a shorter period; that costs more and the JVM may not reach it, so read perSecond in the stop answer. A setting a JDK event does not have is refused rather than silently ignored."},
					    "durationSeconds":   {"type":"integer","default":1800,"minimum":0,"maximum":86400,"description":"Stop on its own after this long. 0 runs until eclipse_stop_flight_recording, which then has to happen."},
					    "maxAgeSeconds":     {"type":"integer","default":600,"minimum":0,"maximum":86400,"description":"Keep only this much history, so a problem that appears after hours can still be dumped at the moment it does. 0 keeps everything."},
					    "maxSizeMegabytes":  {"type":"integer","default":100,"minimum":1,"maximum":4096,"description":"Ceiling on the recording buffer. Once it is full the oldest events are dropped, so a long recording keeps the end rather than the start."},
					    "name":              {"type":"string","description":"Label for the recording, shown in JDK Mission Control."},
				    "dumpOnExitTo":      {"type":"string","description":"Absolute path the JVM writes the recording to when it EXITS. This is what makes the IDE's own shutdown measurable: eclipse_stop_flight_recording cannot run then, because the server is going down with the workbench. Read the file afterwards with eclipse_stop_flight_recording passing file. The recording can still be stopped normally before that."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String settings = args.getString("settings", "profile"); //$NON-NLS-1$ //$NON-NLS-2$
			if (!"default".equals(settings) && !"profile".equals(settings)) { //$NON-NLS-1$ //$NON-NLS-2$
				return McpToolResult.error("Unknown settings '%s', expected 'default' or 'profile'.".formatted(settings)); //$NON-NLS-1$
			}
			int duration = args.getInt("durationSeconds", 1800, 0, 86400); //$NON-NLS-1$
			int maxAge = args.getInt("maxAgeSeconds", 600, 0, 86400); //$NON-NLS-1$
			int maxSize = args.getInt("maxSizeMegabytes", 100, 1, 4096); //$NON-NLS-1$
			try {
				String dumpTo = args.getString("dumpOnExitTo"); //$NON-NLS-1$
				Map<String, String> overrides = new TreeMap<>();
				if (arguments != null && arguments.get("settingsOverrides") instanceof Map<?, ?> given) { //$NON-NLS-1$
					given.forEach((key, value) -> overrides.put(String.valueOf(key), String.valueOf(value)));
				}
				String id = FlightRecording.start(settings, overrides, maxAge, maxSize * 1024L * 1024L, duration,
						args.getString("name"), dumpTo == null ? null : Path.of(dumpTo)); //$NON-NLS-1$
				JsonObject overridden = new JsonObject();
				overrides.forEach(overridden::put);
				return McpToolResult.of(new JsonObject().put("recordingId", id) //$NON-NLS-1$
						.put("settings", settings) //$NON-NLS-1$
						.put("settingsOverrides", overrides.isEmpty() ? null : overridden) //$NON-NLS-1$
						.put("durationSeconds", Integer.valueOf(duration)) //$NON-NLS-1$
						.put("maxAgeSeconds", Integer.valueOf(maxAge)) //$NON-NLS-1$
						.put("maxSizeMegabytes", Integer.valueOf(maxSize)) //$NON-NLS-1$
						.put("running", Boolean.TRUE) //$NON-NLS-1$
						.put("dumpOnExitTo", dumpTo) //$NON-NLS-1$
						.put("note", duration == 0 //$NON-NLS-1$
								? "This recording has no duration, so it runs until eclipse_stop_flight_recording is called. It is bounded by maxAge and maxSize, so it cannot fill the disk, but it does cost the profiling overhead until then." //$NON-NLS-1$
								: "Let it run while the behaviour you are after happens, then call eclipse_stop_flight_recording. It stops on its own after durationSeconds either way.") //$NON-NLS-1$
						.toString());
			} catch (LinkageError e) {
				return McpToolResult.error(UNAVAILABLE);
			} catch (FlightRecording.ParseFailure | IOException | RuntimeException e) {
				return McpToolResult.error("Could not start the recording: " + e); //$NON-NLS-1$
			}
		}
	}

	/** Dumps a recording and reports the aggregate. */
	public static final class Stop implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_stop_flight_recording"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Dumps a recording and returns it aggregated: which classes the bytes went into, which call chains allocated them, where the time was spent, what was thrown, and what the collector did. The raw file is not returned unless outputPath is given, because a recording is megabytes of binary and the aggregate is the answer. READ THE BYTES AS AN ESTIMATE: the allocation sampler is throttled and reports a weight per sample, so the figures rank allocators correctly and do not add up to what the process allocated. allocationByStack is the field that names a culprit, because a class such as Path or byte[] says nothing on its own. WHERE THE TIME WENT is under time: execution and native samples told apart, with parked stacks left out and counted in idleExcluded, topBySelfTime, topByPresence (frames anywhere on the stack; those on every stack are under onEveryStack), a merged call tree with a branch per thread (a run of equal counts folded into chain), byThread for the names threadNames can select, and the achieved rate under sampling. hotMethods is only the innermost frame of execution samples, which on a flat profile such as a startup says little; prefer topByPresence and tree. thrown groups jdk.JavaErrorThrow and jdk.JavaExceptionThrow by class, message and nearest non-JDK frame. threadNames, fromSeconds and toSeconds select what every part of the answer is about, and one file is read once per call, so splitting startup from idle and shutdown is one call per phase. Pass keepRunning to read a recording several times without ending it."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "recordingId":  {"type":"string","description":"Id from eclipse_start_flight_recording. Omit for the most recent."},
				    "file":         {"type":"string","description":"Absolute path of an existing .jfr file to read instead of a recording of this IDE, such as the one a launch made with flightRecording. Nothing is stopped and the file is left where it is."},
					    "keepRunning":  {"type":"boolean","default":false,"description":"Report what has been recorded so far without stopping."},
					    "topClasses":   {"type":"integer","default":15,"minimum":1,"maximum":200,"description":"How many of the classes that allocated most to report."},
					    "topStacks":    {"type":"integer","default":10,"minimum":1,"maximum":100,"description":"How many of the allocation stacks that allocated most to report. Each one is many lines, so this is the field that decides how big the answer gets."},
					    "stackDepth":   {"type":"integer","default":8,"minimum":1,"maximum":64,"description":"Frames per aggregated call chain. Deeper separates callers that share a top frame; shallower merges them."},
					    "frameFilter":  {"type":"string","description":"Aggregate only events whose stack contains this text at ANY depth, independent of stackDepth, which cuts the rendering and not the search. Applied when reading, so one recording can be read from several angles; eventsMatched and matchedEventTypes then count what this filter kept, which is how one phase is weighed against another."},
					    "threadNames":  {"type":"array","items":{"type":"string"},"description":"Aggregate only events of these threads. Each entry is an exact name ignoring case, so main is not Domain-main, or a prefix with a trailing star, such as Worker-*. time.byThread still lists the other threads so a name can be chosen, time.threadNamesNotFound lists entries nothing matched, and the gc block is not narrowed, since the collector belongs to no such thread."},
					    "fromSeconds":  {"type":"number","minimum":0,"description":"Aggregate only events starting at or after this many seconds from the recording's start, which is the earliest event in the file and is reported as time.recordingStart. time.recordingSeconds says how long the file is."},
					    "toSeconds":    {"type":"number","minimum":0,"description":"Aggregate only events starting before this many seconds from the recording's start. With fromSeconds this separates startup from idle time and shutdown in one file."},
					    "includeIdle":  {"type":"boolean","default":false,"description":"Keep samples parked in a wait, such as Display.sleep, OS.WaitMessage, Unsafe.park, Object.wait or WEPoll.wait. Off by default: they are left out of topBySelfTime, topByPresence, tree and the time flame graph and counted in time.idleExcluded with the waits under time.idleFrames, because a sleeping event loop and poll threads otherwise swamp the answer. Turn it ON to diagnose a stall, where the wait is the finding."},
					    "sampleKind":   {"type":"string","enum":["execution","native"],"description":"Limit time to jdk.ExecutionSample, Java code running, or jdk.NativeMethodSample, a thread in a native call. Omit for both. Each frame reports its execution and native counts either way, and time.otherKindExcluded counts what this removed."},
					    "topMethods":   {"type":"integer","default":10,"minimum":1,"maximum":200,"description":"How many frames to report in time.topBySelfTime and time.topByPresence."},
					    "topThreads":   {"type":"integer","default":10,"minimum":1,"maximum":200,"description":"How many threads to report in time.byThread, the busiest first."},
					    "topThrown":    {"type":"integer","default":10,"minimum":1,"maximum":200,"description":"How many groups to report in thrown.groups."},
					    "minSamples":   {"type":"integer","minimum":1,"description":"Prune time.tree branches seen fewer times than this. Default is one percent of the samples and at least 2; time.treeMinSamples reports what was used."},
					    "maxTreeNodes": {"type":"integer","default":80,"minimum":1,"maximum":5000,"description":"How many frames time.tree holds, the widest first, so it stays small enough to read; time.treeNodesTotal and time.treeTruncated report what the limit cut."},
					    "outputPath":   {"type":"string","description":"Keep the .jfr file at this absolute path, for opening it in JDK Mission Control. Omit to delete it after reading."},
					    "includeMcpFrames": {"type":"boolean","default":false,"description":"Keep what this MCP server itself contributed. Off by default: events on the server's own threads, events whose running code is the server's, and events carrying its request machinery are left out and counted in serverEventsExcluded, and server frames that only dispatched the application's work are removed from otherwise kept stacks and counted in serverFramesElidedFrom. Turn it ON to profile the server itself. A .jfr file from another JVM has no server frames, so nothing is removed there."},
					    "show":         {"type":"boolean","default":false,"description":"Also render a flame graph on a page this IDE serves and return its URL under traceUrl, with traceWeight naming what its width means. flameWeight picks the weight, every filter here applies to it, and each thread is a branch. Dark themed, self contained, held in memory only."},
					    "flameWeight":  {"type":"string","enum":["allocation","time"],"default":"allocation","description":"What a frame's width means on the show page. allocation is the estimated bytes allocated below it, time is the execution and native samples below it, idle ones left out unless includeIdle."},
					    "open":         {"type":"boolean","default":false,"description":"Open that page in the machine's browser. Implies show. VISIBLE TO WHOEVER IS AT THE IDE, since a browser window appears."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String outputPath = args.getString("outputPath"); //$NON-NLS-1$
			boolean keepRunning = args.getBoolean("keepRunning", false); //$NON-NLS-1$
			String flameWeight = args.getString("flameWeight", "allocation"); //$NON-NLS-1$ //$NON-NLS-2$
			if (!"allocation".equals(flameWeight) && !"time".equals(flameWeight)) { //$NON-NLS-1$ //$NON-NLS-2$
				return McpToolResult.error("Unknown flameWeight '%s', expected 'allocation' or 'time'.".formatted(flameWeight)); //$NON-NLS-1$
			}
			String sampleKind = args.getString("sampleKind"); //$NON-NLS-1$
			if (sampleKind != null && !"execution".equals(sampleKind) && !"native".equals(sampleKind)) { //$NON-NLS-1$ //$NON-NLS-2$
				return McpToolResult.error("Unknown sampleKind '%s', expected 'execution' or 'native'.".formatted(sampleKind)); //$NON-NLS-1$
			}
			Double from;
			Double to;
			try {
				from = seconds(arguments, "fromSeconds"); //$NON-NLS-1$
				to = seconds(arguments, "toSeconds"); //$NON-NLS-1$
			} catch (NumberFormatException e) {
				return McpToolResult.error("fromSeconds and toSeconds have to be numbers: " + e.getMessage()); //$NON-NLS-1$
			}
			if (from != null && to != null && to.doubleValue() <= from.doubleValue()) {
				return McpToolResult.error("toSeconds %s has to be after fromSeconds %s.".formatted(to, from)); //$NON-NLS-1$
			}
			boolean includeIdle = args.getBoolean("includeIdle", false); //$NON-NLS-1$
			List<String> threadNames = arguments != null && arguments.get("threadNames") instanceof List<?> list //$NON-NLS-1$
					&& !list.isEmpty() ? list.stream().map(String::valueOf).toList() : null;
			FlightRecording.Aggregation options = new FlightRecording.Aggregation(
					args.getInt("topClasses", 15, 1, 200), //$NON-NLS-1$
					args.getInt("topStacks", 10, 1, 100), //$NON-NLS-1$
					args.getInt("stackDepth", 8, 1, 64), //$NON-NLS-1$
					args.getString("frameFilter"), //$NON-NLS-1$
					args.getBoolean("includeMcpFrames", false), //$NON-NLS-1$
					threadNames, from, to, sampleKind,
					args.getInt("topThrown", 10, 1, 200), //$NON-NLS-1$
					new TimeProfile.Limits(includeIdle, args.getInt("topMethods", 10, 1, 200), //$NON-NLS-1$
							args.getInt("topThreads", 10, 1, 200), //$NON-NLS-1$
							args.getInt("minSamples", 0, 0, 1_000_000), //$NON-NLS-1$
							args.getInt("maxTreeNodes", 80, 1, 5000)), //$NON-NLS-1$
					"time".equals(flameWeight)); //$NON-NLS-1$
			boolean open = args.getBoolean("open", false); //$NON-NLS-1$
			boolean show = open || args.getBoolean("show", false); //$NON-NLS-1$
			String existing = args.getString("file"); //$NON-NLS-1$
			if (existing != null) {
				return read(Path.of(existing), options, show, open);
			}
			try {
				String id = args.getString("recordingId", FlightRecording.mostRecentId()); //$NON-NLS-1$
				if (id == null) {
					return McpToolResult.error(
							"No recording has been started in this session. Use eclipse_start_flight_recording first."); //$NON-NLS-1$
				}
				Path file = outputPath == null ? FlightRecording.temporaryFile() : Path.of(outputPath);
				try {
					FlightRecording.dump(id, file, keepRunning);
					long size = Files.size(file);
					FlameGraph.Builder flame = show ? FlameGraph.builder() : null;
					JsonObject result = FlightRecording.aggregate(file, options, flame)
							.put("recordingId", id) //$NON-NLS-1$
							.put("stillRunning", Boolean.valueOf(keepRunning)) //$NON-NLS-1$
							.put("recordingBytes", Long.valueOf(size)); //$NON-NLS-1$
					if (outputPath != null) {
						result.put("file", file.toString()); //$NON-NLS-1$
					}
					result.put("note", //$NON-NLS-1$
							"The byte figures are the allocation sampler's weights, so they rank allocators rather than adding up to everything allocated. allocationByStack is what names a caller; a class on its own rarely does."); //$NON-NLS-1$
					if (flame != null) {
						publish(options, id, "%s recorded".formatted(FlameGraph.bytes(size)), flame, result, open); //$NON-NLS-1$
					}
					return McpToolResult.of(result.toString());
				} finally {
					if (outputPath == null) {
						Files.deleteIfExists(file);
					}
				}
			} catch (LinkageError e) {
				return McpToolResult.error(UNAVAILABLE);
			} catch (IOException | RuntimeException e) {
				return McpToolResult.error("Could not read the recording: " + e); //$NON-NLS-1$
			}
		}

		private static Double seconds(Map<String, Object> arguments, String name) {
			Object value = arguments == null ? null : arguments.get(name);
			if (value == null) {
				return null;
			}
			return value instanceof Number number ? Double.valueOf(number.doubleValue())
					: Double.valueOf(String.valueOf(value));
		}

		private static void publish(FlightRecording.Aggregation options, String name, String source,
				FlameGraph.Builder flame, JsonObject result, boolean open) {
			StringBuilder filters = new StringBuilder();
			if (options.threadNames() != null) {
				filters.append(", threads ").append(String.join(", ", options.threadNames())); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (options.fromSeconds() != null || options.toSeconds() != null) {
				filters.append(", from %s s to %s s".formatted(options.fromSeconds() == null ? "0" : options.fromSeconds(), //$NON-NLS-1$ //$NON-NLS-2$
						options.toSeconds() == null ? "the end" : options.toSeconds())); //$NON-NLS-1$
			}
			if (options.frameFilter() != null) {
				filters.append(", only stacks containing '%s'".formatted(options.frameFilter())); //$NON-NLS-1$
			}
			if (options.timeFlame()) {
				if (options.sampleKind() != null) {
					filters.append(", only ").append(options.sampleKind()).append(" samples"); //$NON-NLS-1$ //$NON-NLS-2$
				}
				FlightTracePage.publishTime(name, source, filters.toString(), options.time().includeIdle(), flame, result,
						open);
			} else {
				FlightTracePage.publishAllocation(name, source, filters.toString(), flame, result, open);
			}
		}

		/** A recording somebody else wrote, most likely a launched program's own. */
		private static McpToolResult read(Path file, FlightRecording.Aggregation options, boolean show, boolean open) {
			if (!Files.isReadable(file)) {
				return McpToolResult.error(
						"There is no readable file at '%s'. A launch that records itself writes the file when the JVM EXITS, so it is absent while the program still runs and stays absent if the program was killed rather than ended." //$NON-NLS-1$
								.formatted(file));
			}
			try {
				long size = Files.size(file);
				if (size == 0) {
					return McpToolResult.error(
							"'%s' is empty, which is what a recording looks like before its JVM has written it out.".formatted(file)); //$NON-NLS-1$
				}
				FlameGraph.Builder flame = show ? FlameGraph.builder() : null;
				JsonObject result = FlightRecording.aggregate(file, options, flame).put("file", file.toString()) //$NON-NLS-1$
						.put("recordingBytes", Long.valueOf(size)) //$NON-NLS-1$
						.put("of", "another JVM, read from its file; nothing of this IDE was recorded or stopped") //$NON-NLS-1$ //$NON-NLS-2$
						.put("note", //$NON-NLS-1$
								"The byte figures are the allocation sampler's weights, so they rank allocators rather than adding up to everything allocated. allocationByStack is what names a caller; a class on its own rarely does."); //$NON-NLS-1$
				if (flame != null) {
					publish(options, String.valueOf(file.getFileName()),
							"%s read from %s".formatted(FlameGraph.bytes(size), file.getFileName()), flame, result, open); //$NON-NLS-1$
				}
				return McpToolResult.of(result.toString());
			} catch (LinkageError e) {
				return McpToolResult.error(UNAVAILABLE);
			} catch (IOException | RuntimeException e) {
				return McpToolResult.error("Could not read '%s': %s".formatted(file, e)); //$NON-NLS-1$
			}
		}
	}
}
