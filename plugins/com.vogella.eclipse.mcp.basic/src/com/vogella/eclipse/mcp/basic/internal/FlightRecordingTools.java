package com.vogella.eclipse.mcp.basic.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
			return "Starts a Java Flight Recorder recording of this IDE's JVM and returns a recordingId for eclipse_stop_flight_recording. This is the tool for WHERE THE MEMORY GOES and what the collector is doing, which eclipse_start_sampling cannot answer: that one samples call stacks by time, so code that allocates heavily but computes little is invisible to it. Use this to tell a leak from ordinary garbage, to find which code allocates, and to see GC pauses. Recording is in-process through jdk.jfr, so no external tool and no JVM flag at startup is needed. settings profile costs a few percent and includes the allocation and execution samples; default costs about one percent and covers GC and threads. The recording is bounded by maxAgeSeconds and maxSizeMegabytes and writes to disk, so one that is never stopped costs a known amount rather than growing; durationSeconds stops it on its own, and 0 means it runs until stopped."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "settings":          {"type":"string","enum":["default","profile"],"default":"profile","description":"profile adds the allocation and execution samples this is usually started for."},
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
				String id = FlightRecording.start(settings, maxAge, maxSize * 1024L * 1024L, duration,
						args.getString("name"), dumpTo == null ? null : Path.of(dumpTo)); //$NON-NLS-1$
				return McpToolResult.of(new JsonObject().put("recordingId", id) //$NON-NLS-1$
						.put("settings", settings) //$NON-NLS-1$
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
			return "Dumps a recording and returns it aggregated: which classes the bytes went into, which call chains allocated them, where the time was spent, and what the collector did. The raw file is not returned unless outputPath is given, because a recording is megabytes of binary and the aggregate is the answer. READ THE BYTES AS AN ESTIMATE: the allocation sampler is throttled and reports a weight per sample, so the figures rank allocators correctly and do not add up to what the process allocated. allocationByStack is the field that names a culprit, because a class such as Path or byte[] says nothing on its own. Pass keepRunning to read a recording several times, for instance with different frameFilter values, without ending it."; //$NON-NLS-1$
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
					    "outputPath":   {"type":"string","description":"Keep the .jfr file at this absolute path, for opening it in JDK Mission Control. Omit to delete it after reading."},
					    "includeMcpFrames": {"type":"boolean","default":false,"description":"Keep what this MCP server itself contributed. Off by default: events on the server's own threads, events whose running code is the server's, and events carrying its request machinery are left out and counted in serverEventsExcluded, and server frames that only dispatched the application's work are removed from otherwise kept stacks and counted in serverFramesElidedFrom. Turn it ON to profile the server itself. A .jfr file from another JVM has no server frames, so nothing is removed there."},
					    "show":         {"type":"boolean","default":false,"description":"Also render the allocation stacks as a flame graph on a page this IDE serves, weighted by bytes, and return its URL under traceUrl. Dark themed, self contained, held in memory only."},
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
			FlightRecording.Aggregation options = new FlightRecording.Aggregation(
					args.getInt("topClasses", 15, 1, 200), //$NON-NLS-1$
					args.getInt("topStacks", 10, 1, 100), //$NON-NLS-1$
					args.getInt("stackDepth", 8, 1, 64), //$NON-NLS-1$
					args.getString("frameFilter"), //$NON-NLS-1$
					args.getBoolean("includeMcpFrames", false)); //$NON-NLS-1$
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
						AllocationTracePage.publish("Allocation " + id, //$NON-NLS-1$
								"%s recorded, allocation stacks weighted by bytes".formatted(FlameGraph.bytes(size)), //$NON-NLS-1$
								flame, result, open);
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
					AllocationTracePage.publish("Allocation " + file.getFileName(), //$NON-NLS-1$
							"%s read from %s, allocation stacks weighted by bytes".formatted(FlameGraph.bytes(size), //$NON-NLS-1$
									file.getFileName()),
							flame, result, open);
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
