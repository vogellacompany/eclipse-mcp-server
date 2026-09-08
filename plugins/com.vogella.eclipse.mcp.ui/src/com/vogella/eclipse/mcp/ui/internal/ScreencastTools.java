package com.vogella.eclipse.mcp.ui.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.ClientSessions;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The two halves of a screencast, start and stop.
 */
public final class ScreencastTools {

	private static final long UI_TIMEOUT_SECONDS = 15;

	private ScreencastTools() {
	}

	private static JsonObject describe(Screencast.Session session) {
		return new JsonObject().put("sessionId", session.id()) //$NON-NLS-1$
				.put("target", session.target()) //$NON-NLS-1$
				.put("directory", session.directory().toString()) //$NON-NLS-1$
				.put("intervalMillis", Integer.valueOf(session.intervalMillis())) //$NON-NLS-1$
				.put("frames", Integer.valueOf(session.frames())) //$NON-NLS-1$
				.put("frameSize", session.frameSize()) //$NON-NLS-1$
				.put("downscaledTo", downscaled(session) ? Integer.valueOf(session.outputWidth()) : null) //$NON-NLS-1$
				.put("resampled", Boolean.valueOf(resampled(session))) //$NON-NLS-1$
				.put("zoom", Integer.valueOf(session.zoom())) //$NON-NLS-1$
				.put("segments", Integer.valueOf(session.segments())) //$NON-NLS-1$
				.put("caption", session.caption()) //$NON-NLS-1$
				.put("captionPosition", session.caption() == null ? null : session.captionPosition()) //$NON-NLS-1$
				.put("crop", session.crop() == null ? null //$NON-NLS-1$
						: "%d,%d %dx%d".formatted(Integer.valueOf(session.crop().x), Integer.valueOf(session.crop().y), //$NON-NLS-1$
								Integer.valueOf(session.crop().width), Integer.valueOf(session.crop().height)))
				.put("running", Boolean.valueOf(session.running())); //$NON-NLS-1$
	}

	/** {@code x,y widthxheight}, or {@code null} when that is not what was given. */
	public static Rectangle parseBounds(String bounds) {
		if (bounds == null) {
			return null;
		}
		String[] parts = bounds.trim().split("[ ,x]+"); //$NON-NLS-1$
		if (parts.length != 4) {
			return null;
		}
		try {
			int[] parsed = new int[4];
			for (int i = 0; i < 4; i++) {
				parsed[i] = Integer.parseInt(parts[i]);
			}
			return parsed[2] > 0 && parsed[3] > 0 ? new Rectangle(parsed[0], parsed[1], parsed[2], parsed[3]) : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** The union of the parts' bounds in the shell's client area, which is what a shell frame is drawn in. */
	private static Rectangle unionOfParts(Display display, Shell shell, List<String> partIds) {
		Rectangle union = null;
		for (String id : partIds) {
			Control control = ScreenshotTools.Capture.findPart(id, false);
			if (control == null) {
				throw new IllegalArgumentException(
						ScreenshotTools.Capture.noPartReason(id) + " It cannot bound the recording."); //$NON-NLS-1$
			}
			Rectangle inShell = display.map(control.getParent(), shell, control.getBounds());
			union = union == null ? inShell : union.union(inShell);
		}
		return union;
	}

	private static List<String> stringList(Object value) {
		List<String> result = new ArrayList<>();
		if (value instanceof List<?> list) {
			for (Object item : list) {
				if (item != null && !String.valueOf(item).isBlank()) {
					result.add(String.valueOf(item).trim());
				}
			}
		}
		return result;
	}

	private static boolean downscaled(Screencast.Session session) {
		return session.frameWidth() > 0 && session.outputWidth() < session.frameWidth();
	}

	/** Whether the cap forces a fractional scale, which is what turns text mushy. */
	private static boolean resampled(Screencast.Session session) {
		return downscaled(session) && session.frameWidth() % session.outputWidth() != 0;
	}

	/** Starts recording and returns a session id. */
	public static final class Start implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_start_screencast"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Starts recording a shell or a part as a sequence of PNG frames, and returns a sessionId for eclipse_stop_screencast, which assembles them into an animated GIF. This is what shows a change in motion rather than before and after: a tab overflowing while a sash is dragged, a hover appearing, a view repainting after a theme switch. WRITES FILES to a directory of its own under the temp directory, or under directory when given, and changes nothing in the IDE. Each frame is painted on the UI thread through Control.print, the same path eclipse_screenshot uses, so it records what the IDE paints and NOT native popups, menus or dialogs, and the window decorations are missing from a shell recording. A frame costs the UI thread one paint; 2 to 5 frames a second is what a full shell sustains without slowing the IDE, and a single part is cheaper. Recording stops on its own at maxFrames or when the target is disposed, and the stop answer reports late ticks, which is what a busy UI thread looks like from here: gaps in the recording rather than a smooth lie."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "target":         {"type":"string","enum":["part","shell"],"default":"shell","description":"What to record. Inferred from part when that is given."},
					    "part":           {"type":"string","description":"Part id, from eclipse_list_ui_targets. It has to be visible; a part behind another tab is not painted at all. Several open parts share one id, every Java editor above all; the active or a visible one is chosen, and a part's TITLE from eclipse_list_ui_targets names a particular one."},
					    "shellTitle":     {"type":"string","description":"Title of the shell to record, or a substring. Omit for the active shell."},
					    "intervalMillis": {"type":"integer","default":500,"minimum":100,"maximum":10000,"description":"Time between frames. The paint itself is added on top, so the real spacing is reported per frame in the GIF."},
					    "maxFrames":      {"type":"integer","default":120,"minimum":1,"maximum":1000,"description":"Recording stops on its own after this many frames."},
					    "maxWidth":       {"type":"integer","minimum":100,"maximum":4000,"description":"Cap the frame width. Defaults to the target's own width, which keeps every frame pixel-exact. A cap below the target width resamples every frame, and unless the target width is a whole multiple of the cap the fraction softens all the text; the answer reports downscaledTo and resampled so that is never a surprise. A GIF of full HD frames is tens of megabytes, which is the only reason to cap."},
					    "directory":      {"type":"string","description":"Absolute directory for the frames. Created when missing; defaults to a temporary directory."},
					    "bounds":         {"type":"string","description":"Record only this region, as x,y widthxheight in points: for a shell in its client area, the coordinate system eclipse_get_widget_tree reports as boundsInShell, for a part relative to the part. Clipped to the target."},
					    "parts":          {"type":"array","items":{"type":"string"},"description":"For a shell recording, record only the union of these parts' bounds, by part id, so a shell recording covers the editor area alone. The parts have to be visible. Cannot be combined with bounds."},
					    "caption":        {"type":"string","description":"Text drawn on every frame of this segment, white on a dark bar, for a recording a person reads."},
					    "captionPosition":{"type":"string","enum":["over","above","below"],"default":"over","description":"over draws the bar translucently over the bottom of the picture; above and below add an opaque bar of the bar's height to the frame outside the picture, so a cropped editor's page tabs stay readable. With resume, omitted keeps the previous segment's."},
					    "resume":         {"type":"string","description":"Session id of a STOPPED screencast to continue in the same frame directory as one more segment, so screenshots can be taken between segments and still end up in one GIF, or 'latest' for the most recently stopped one, which is what a static scenario needs since ids count up per IDE session. The target, interval and crop stay; maxFrames counts the new segment; caption replaces the previous segment's. The pause between the segments is shown as gapMillis."},
					    "gapMillis":      {"type":"integer","default":1000,"minimum":0,"maximum":60000,"description":"With resume, how long the last frame of the previous segment stays before the new one starts."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String part = args.getString("part"); //$NON-NLS-1$
			String target = args.getString("target", part != null ? "part" : "shell"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if ("part".equals(target) && part == null) { //$NON-NLS-1$
				return McpToolResult.error("The target 'part' needs a 'part' id. Use eclipse_list_ui_targets."); //$NON-NLS-1$
			}
			String shellTitle = args.getString("shellTitle"); //$NON-NLS-1$
			int interval = args.getInt("intervalMillis", 500, 100, 10000); //$NON-NLS-1$
			int maxFrames = args.getInt("maxFrames", 120, 1, 1000); //$NON-NLS-1$
			// 0 is no cap. It used to default to 800, which resampled every 1024 wide
			// workbench shell by 0.78 and blurred all the text in every recording
			int maxWidth = args.getInt("maxWidth", 0, 0, 4000); //$NON-NLS-1$
			String caption = args.getString("caption"); //$NON-NLS-1$
			String captionPosition = args.getString("captionPosition"); //$NON-NLS-1$
			if (captionPosition != null
					&& !List.of(Screencast.OVER, Screencast.ABOVE, Screencast.BELOW).contains(captionPosition)) {
				return McpToolResult.error("'captionPosition' is over, above or below."); //$NON-NLS-1$
			}
			String bounds = args.getString("bounds"); //$NON-NLS-1$
			List<String> partIds = stringList(arguments.get("parts")); //$NON-NLS-1$
			Rectangle requestedCrop = parseBounds(bounds);
			if (bounds != null && requestedCrop == null) {
				return McpToolResult.error("'bounds' has to be x,y widthxheight with a positive size, e.g. '0,120 900x600'."); //$NON-NLS-1$
			}
			if (requestedCrop != null && !partIds.isEmpty()) {
				return McpToolResult.error("Pass either 'bounds' or 'parts', not both."); //$NON-NLS-1$
			}
			String resume = args.getString("resume"); //$NON-NLS-1$
			if (resume != null) {
				boolean latest = "latest".equalsIgnoreCase(resume) || "true".equalsIgnoreCase(resume); //$NON-NLS-1$ //$NON-NLS-2$
				if (latest && !ClientSessions.canAssumeASingleClient()) {
					return McpToolResult.error(
							ClientSessions.ambiguousDefault("screencast", "resume", Screencast.getInstance().ids())); //$NON-NLS-1$ //$NON-NLS-2$
				}
				Screencast.Session previous = latest ? Screencast.getInstance().findLatestStopped()
						: Screencast.getInstance().find(resume);
				if (previous == null) {
					return McpToolResult.error(latest ? "No stopped screencast to resume." //$NON-NLS-1$
							: "No screencast with the id '%s' to resume.".formatted(resume)); //$NON-NLS-1$
				}
				if (previous.running()) {
					return McpToolResult.error("Screencast '%s' is still running; stop it first.".formatted(resume)); //$NON-NLS-1$
				}
				int gap = args.getInt("gapMillis", 1000, 0, 60000); //$NON-NLS-1$
				return UiThread.call(UI_TIMEOUT_SECONDS, () -> {
					if (previous.control().isDisposed()) {
						throw new IllegalStateException(
								"The %s that screencast '%s' recorded has been disposed, so it cannot be resumed; start a new one." //$NON-NLS-1$
										.formatted(previous.target(), resume));
					}
					int before = previous.frames();
					Screencast.Session session = Screencast.getInstance().resume(PlatformUI.getWorkbench().getDisplay(),
							previous, maxFrames, gap, caption, captionPosition);
					if (session.frames() == before) {
						throw new IllegalStateException("The first frame of the new segment could not be painted: " //$NON-NLS-1$
								+ session.stoppedBy());
					}
					return describe(session).put("maxFrames", Integer.valueOf(session.maxFrames())) //$NON-NLS-1$
							.put("resumedFrom", previous.id()).put("framesBefore", Integer.valueOf(before)) //$NON-NLS-1$ //$NON-NLS-2$
							.put("note", //$NON-NLS-1$
									"Recording again into the same directory. eclipse_stop_screencast assembles every segment into one GIF, with the last frame before this segment held for %d ms." //$NON-NLS-1$
											.formatted(Integer.valueOf(gap)));
				});
			}
			Path directory;
			try {
				String given = args.getString("directory"); //$NON-NLS-1$
				directory = given != null ? Files.createDirectories(Path.of(given))
						: Files.createTempDirectory("eclipse-screencast-"); //$NON-NLS-1$
			} catch (IOException | RuntimeException e) {
				return McpToolResult.error("Could not create the frame directory: " + e.getMessage()); //$NON-NLS-1$
			}
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> {
				Display display = PlatformUI.getWorkbench().getDisplay();
				Control control;
				String described;
				boolean composed = false;
				Rectangle crop = requestedCrop;
				if ("shell".equals(target)) { //$NON-NLS-1$
					Shell shell = ScreenshotTools.Capture.findShell(display, shellTitle);
					if (shell == null) {
						throw new IllegalArgumentException(shellTitle == null ? "This IDE has no window to record." //$NON-NLS-1$
								: "No shell matching '%s'.".formatted(shellTitle)); //$NON-NLS-1$
					}
					control = shell;
					composed = shell.getChildren().length > 0;
					described = "shell '" + shell.getText() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
					if (!partIds.isEmpty()) {
						crop = unionOfParts(display, shell, partIds);
					}
				} else {
					if (!partIds.isEmpty()) {
						throw new IllegalArgumentException("'parts' bounds a shell recording; for a part, pass 'bounds' relative to it."); //$NON-NLS-1$
					}
					control = ScreenshotTools.Capture.findPart(part, false);
					if (control == null) {
						throw new IllegalArgumentException(ScreenshotTools.Capture.noPartReason(part));
					}
					described = "part " + part; //$NON-NLS-1$
				}
				Rectangle own = composed ? ((Shell) control).getClientArea() : control.getBounds();
				if (crop != null && Screencast.clampCrop(crop, own.width, own.height) == null) {
					throw new IllegalArgumentException("The region %s lies entirely outside the %dx%d target." //$NON-NLS-1$
							.formatted(bounds != null ? bounds : partIds.toString(), Integer.valueOf(own.width),
									Integer.valueOf(own.height)));
				}
				Screencast.Session session = Screencast.getInstance().start(display, control, composed, described,
						interval, maxFrames, maxWidth, directory, crop, caption, captionPosition);
				if (session.frames() == 0) {
					throw new IllegalStateException("The first frame could not be painted: " + session.stoppedBy()); //$NON-NLS-1$
				}
				String note = "Recording. Drive the IDE now, then call eclipse_stop_screencast with this sessionId. Native popups, menus and dialogs are not in the frames."; //$NON-NLS-1$
				if (resampled(session)) {
					note += " maxWidth %d is below the %d pixel wide target and not a whole fraction of it, so every frame is resampled and its text softened; pass maxWidth at or above %d, or leave it out, for pixel-exact frames." //$NON-NLS-1$
							.formatted(Integer.valueOf(maxWidth), Integer.valueOf(session.frameWidth()),
									Integer.valueOf(session.frameWidth()));
				}
				return describe(session).put("maxFrames", Integer.valueOf(maxFrames)) //$NON-NLS-1$
						.put("note", note); //$NON-NLS-1$
			});
		}
	}

	/** Stops recording and assembles the GIF. */
	public static final class Stop implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_stop_screencast"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Stops a screencast and assembles its frames into an animated GIF, whose path is returned along with the frame directory. Each frame stays as long as it really did on screen, so a stall in the IDE is visible as a held frame. The GIF uses a fixed 256 colour palette, which posterises gradients; the PNG frames stay lossless in the directory for anything better, such as ffmpeg through eclipse_run_command. averagePaintMillis is what one frame cost the UI thread, which is added to the interval between frames; lateTicks and maxLatenessMillis say how long the timer waited past its due time, which is the recording's own measurement of how busy the IDE was with other things."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "sessionId":  {"type":"string","description":"Session returned by eclipse_start_screencast. Omit for the most recent."},
					    "gif":        {"type":"boolean","default":true,"description":"Assemble the animated GIF. Off leaves only the PNG frames."},
					    "loop":       {"type":"boolean","default":true,"description":"Loop the GIF instead of playing it once."},
					    "outputPath": {"type":"string","description":"Absolute file for the GIF. Defaults to screencast.gif in the frame directory."},
					    "keepFrames": {"type":"boolean","default":true,"description":"Leave the PNG frames on disk after the GIF is written."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String sessionId = args.getString("sessionId"); //$NON-NLS-1$
			Screencast registry = Screencast.getInstance();
			if (sessionId == null && !ClientSessions.canAssumeASingleClient()) {
				return McpToolResult.error(ClientSessions.ambiguousDefault("screencast", "sessionId", registry.ids())); //$NON-NLS-1$ //$NON-NLS-2$
			}
			Screencast.Session session = sessionId == null ? registry.findLatest() : registry.find(sessionId);
			if (session == null) {
				return McpToolResult.error(sessionId == null ? "No screencast has been started." //$NON-NLS-1$
						: "No screencast with the id '%s'.".formatted(sessionId)); //$NON-NLS-1$
			}
			session.stop("stop"); //$NON-NLS-1$
			int budget = Math.max(1, CallBudget.maxWaitSeconds() - 5);
			boolean encoded = session.awaitEncoded(budget);
			JsonObject result = describe(session).put("stoppedBy", session.stoppedBy()) //$NON-NLS-1$
					.put("durationMillis", Long.valueOf(session.elapsedMillis())) //$NON-NLS-1$
					.put("framesWritten", Integer.valueOf(session.written())) //$NON-NLS-1$
					.put("framesFailed", Integer.valueOf(session.failed())) //$NON-NLS-1$
					.put("lastFailure", session.lastFailure()) //$NON-NLS-1$
					.put("averagePaintMillis", Long.valueOf(session.averagePaintMillis())) //$NON-NLS-1$
					.put("lateTicks", Integer.valueOf(session.lateTicks())) //$NON-NLS-1$
					.put("maxLatenessMillis", Long.valueOf(session.maxLatenessMillis())); //$NON-NLS-1$
			if (!encoded) {
				return McpToolResult.of(result.put("gifPath", null) //$NON-NLS-1$
						.put("note", "The frames were still being encoded after %d seconds, so no GIF was written. Call again to assemble it once the encoder has caught up." //$NON-NLS-1$ //$NON-NLS-2$
								.formatted(Integer.valueOf(budget)))
						.toString());
			}
			if (!args.getBoolean("gif", true)) { //$NON-NLS-1$
				return McpToolResult.of(result.put("gifPath", null).toString()); //$NON-NLS-1$
			}
			Path output = args.getString("outputPath") != null ? Path.of(args.getString("outputPath")) //$NON-NLS-1$ //$NON-NLS-2$
					: session.directory().resolve("screencast.gif"); //$NON-NLS-1$
			try {
				List<ImageData> frames = new ArrayList<>();
				int[] delays = session.delaysMillis();
				List<Integer> kept = new ArrayList<>();
				for (int i = 0; i < delays.length; i++) {
					Path frame = session.frame(i);
					if (Files.exists(frame)) {
						frames.add(ScreencastGif.read(frame));
						kept.add(Integer.valueOf(delays[i]));
					}
				}
				long bytes = ScreencastGif.write(frames, kept.stream().mapToInt(Integer::intValue).toArray(),
						args.getBoolean("loop", true), output); //$NON-NLS-1$
				result.put("gifPath", output.toAbsolutePath().toString()) //$NON-NLS-1$
						.put("gifFrames", Integer.valueOf(frames.size())) //$NON-NLS-1$
						.put("gifBytes", Long.valueOf(bytes)); //$NON-NLS-1$
				if (!args.getBoolean("keepFrames", true)) { //$NON-NLS-1$
					for (int i = 0; i < delays.length; i++) {
						Files.deleteIfExists(session.frame(i));
					}
					result.put("framesKept", Boolean.FALSE); //$NON-NLS-1$
				}
			} catch (IOException | RuntimeException e) {
				result.put("gifPath", null).put("gifError", String.valueOf(e)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return McpToolResult.of(result.put("note", session.lateTicks() > 0 //$NON-NLS-1$
					? "The UI thread delivered %d frames late, so the recording has gaps where the IDE was busy; each frame's delay in the GIF is the time it really stayed." //$NON-NLS-1$
							.formatted(Integer.valueOf(session.lateTicks()))
					: "Every frame arrived on time.").toString()); //$NON-NLS-1$
		}
	}
}
