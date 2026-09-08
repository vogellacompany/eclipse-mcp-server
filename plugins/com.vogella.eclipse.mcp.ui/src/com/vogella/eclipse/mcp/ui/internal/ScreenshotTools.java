package com.vogella.eclipse.mcp.ui.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.views.IViewDescriptor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Captures the IDE, and lists what can be captured.
 */
public final class ScreenshotTools {

	private static final long UI_TIMEOUT_SECONDS = 15;

	private ScreenshotTools() {
	}

	/**
	 * On GTK4 the SWT code that fills a GC from a window is a no-op, so a capture
	 * returns a blank image with no error at all. Refusing is better than a silently
	 * empty screenshot, which is the worst possible answer.
	 */
	static String unsupportedReason() {
		if (!"gtk".equals(SWT.getPlatform())) { //$NON-NLS-1$
			return null;
		}
		String gtk4 = System.getenv("SWT_GTK4"); //$NON-NLS-1$
		if (gtk4 != null && !"0".equals(gtk4) && !"false".equalsIgnoreCase(gtk4)) { //$NON-NLS-1$ //$NON-NLS-2$
			return "SWT is running on GTK4, where capturing a window produces a blank image rather than an error."; //$NON-NLS-1$
		}
		// Deliberately no Wayland check. WAYLAND_DISPLAY and XDG_SESSION_TYPE stay set
		// even when GDK_BACKEND=x11 binds the X11 backend through XWayland, where
		// capture works, so the environment cannot answer the question and sniffing it
		// refused on machines that were fine. The uniform-image check after the capture
		// is the real backstop, so this can afford to be permissive.
		return null;
	}

	private static <T> McpToolResult onUi(Supplier<T> work, Function<T, String> render) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return McpToolResult.error("There is no running workbench."); //$NON-NLS-1$
		}
		CompletableFuture<T> pending = new CompletableFuture<>();
		// asyncExec, never syncExec: a busy UI must not block the HTTP worker
		UiThread.exec(() -> {
			try {
				pending.complete(work.get());
			} catch (RuntimeException e) {
				pending.completeExceptionally(e);
			}
		});
		try {
			return McpToolResult.of(render.apply(pending.get(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS)));
		} catch (TimeoutException e) {
			pending.cancel(false);
			return McpToolResult.error("The Eclipse UI is busy, try again."); //$NON-NLS-1$
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return McpToolResult.error("The request was interrupted."); //$NON-NLS-1$
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			return McpToolResult.error("The capture failed: " + cause); //$NON-NLS-1$
		}
	}

	/** Lists shells and parts, so that a caller does not have to guess ids. */
	public static final class ListTargets implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_list_ui_targets"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Lists what the UI tools can address: every open shell with its title, whether it is modal and its bounds, and every workbench part with its id, title and whether it is currently visible. A PART ID IS NOT UNIQUE: every Java editor carries the same one, so the title is what tells two parts under one id apart, and it is what the other tools take to name a particular one. With includeAvailableViews it also lists the views that are registered but not open, which is where eclipse_show_view gets its ids. Also the answer to 'which dialog is open right now', which nothing else here can tell you."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "includeAvailableViews": {"type":"boolean","default":false,"description":"Also list every view registered in this IDE, open or not. There are hundreds, so pass a filter with it."},
					    "filter":                {"type":"string","description":"Substring of the id or the label, case insensitive, applied to availableViews only."},
					    "maxResults":            {"type":"integer","default":100,"minimum":1,"maximum":1000}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			boolean includeAvailableViews = args.getBoolean("includeAvailableViews", false); //$NON-NLS-1$
			String filter = args.getString("filter"); //$NON-NLS-1$
			int maxResults = args.getInt("maxResults", 100, 1, 1000); //$NON-NLS-1$
			return onUi(() -> collect(includeAvailableViews, filter, maxResults), JsonObject::toString);
		}

		private static JsonObject collect(boolean includeAvailableViews, String filter, int maxResults) {
			Display display = PlatformUI.getWorkbench().getDisplay();
			JsonArray shells = new JsonArray();
			Shell[] ordered = display.getShells();
			for (int i = 0; i < ordered.length; i++) {
				Shell shell = ordered[i];
				Rectangle bounds = shell.getBounds();
				shells.add(new JsonObject().put("index", Integer.valueOf(i)) //$NON-NLS-1$
						.put("title", shell.getText()) //$NON-NLS-1$
						.put("kind", Shells.kind(shell)) //$NON-NLS-1$
						.put("firstControl", Shells.firstControlName(shell)) //$NON-NLS-1$
						.put("modal", Shells.isModal(shell)) //$NON-NLS-1$
						.put("visible", shell.isVisible()) //$NON-NLS-1$
						.put("bounds", bounds.x + "," + bounds.y + " " + bounds.width + "x" + bounds.height)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			}
			JsonArray parts = new JsonArray();
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			IWorkbenchPage page = window == null ? null : window.getActivePage();
			if (page != null) {
				for (IWorkbenchPartReference reference : allReferences(page)) {
					parts.add(new JsonObject().put("id", reference.getId()) //$NON-NLS-1$
							.put("title", reference.getTitle()) //$NON-NLS-1$
							.put("kind", reference instanceof IViewReference ? "view" : "editor") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							.put("visible", page.isPartVisible(reference.getPart(false)))); //$NON-NLS-1$
				}
			}
			JsonObject result = new JsonObject().put("shells", shells).put("parts", parts); //$NON-NLS-1$ //$NON-NLS-2$
			if (includeAvailableViews) {
				addAvailableViews(result, filter, maxResults);
			}
			String unsupported = unsupportedReason();
			if (unsupported != null) {
				result.put("captureUnsupported", unsupported); //$NON-NLS-1$
			}
			return result;
		}

		/** Every registered view, which is the set eclipse_show_view can open. */
		private static void addAvailableViews(JsonObject result, String filter, int maxResults) {
			String needle = filter == null ? null : filter.toLowerCase(Locale.ROOT);
			List<IViewDescriptor> matching = new ArrayList<>();
			for (IViewDescriptor descriptor : PlatformUI.getWorkbench().getViewRegistry().getViews()) {
				if (needle == null || descriptor.getId().toLowerCase(Locale.ROOT).contains(needle)
						|| descriptor.getLabel().toLowerCase(Locale.ROOT).contains(needle)) {
					matching.add(descriptor);
				}
			}
			matching.sort((a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));
			result.put("availableViews", ViewTools.describe(matching, maxResults)) //$NON-NLS-1$
					.put("availableViewsTotal", Integer.valueOf(matching.size())) //$NON-NLS-1$
					.put("availableViewsTruncated", Boolean.valueOf(matching.size() > maxResults)); //$NON-NLS-1$
		}

		static List<IWorkbenchPartReference> allReferences(IWorkbenchPage page) {
			List<IWorkbenchPartReference> references = new ArrayList<>();
			references.addAll(Arrays.asList(page.getViewReferences()));
			references.addAll(Arrays.asList(page.getEditorReferences()));
			return references;
		}
	}

	/** Captures the display, a shell or a part. */
	public static final class Capture implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_screenshot"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Captures the IDE as a PNG and writes it to a file, returning the path. Targets are a workbench part by id, a shell by title, or the whole display; passing part or shellTitle selects the target on its own. Omitting both shellTitle and part captures the active workbench window's shell, which is also what a target of shell without a title does; the display target must be asked for explicitly. The answer reports which method worked: rootCapture reads the real screen pixels, widgetPrint paints the widget hierarchy instead, which is the fallback on a compositing window manager where reading the X11 root yields nothing, and also what is used inside an atomic eclipse_run_script, where no paint has happened yet and the screen would show what was there before. ROOT CAPTURE READS WHATEVER IS IN FRONT: on a desktop this IDE shares with a person, a window over the shell is photographed instead of the shell, and it is not a failure any other field can show, since a window sitting still is settled, converged and the right size. So a part or shell capture falls back to widgetPrint when this IDE is not the foreground application, and every answer carries 'foreground'; a display capture cannot fall back and says so in foregroundNote. Assert on 'foreground' rather than on eclipse_set_ide_visibility having been called, because that asks the window system for focus and the window system can refuse. A screen read also has to tell cairo that the screen has moved on since it last read it, which every capture does and reports as screenSourceRefreshed; where that fails the image can be an EARLIER FRAME at the right size, the right zoom and the right area, and screenSourceNote says so. A capture taken inside such a batch reports sameTurnCapture. A shell capture is sized to the shell's client area and composes every visible child of the shell into one image, so the trim bars are in it; the window decorations, meaning the title bar and the frame, are drawn by the window manager and are present only when rootCapture succeeded. requestedArea names the bounds of what was asked for, and when the capture covers less than that, requestedAreaNote says what was excluded and why. For method widgetPrint the areas between parts that no print paints are replaced with the widget background colour before the image is written, and the answer counts them in unpaintedPixels, so a whole shell capture does not arrive outlined in filler colour. coverage says in words how much of the requested area the print actually covered, and says outright when a capture holds too much filler to be evidence about the UI, rather than leaving that judgement to be computed from the fraction. On a HiDPI or scaled display both methods capture the pixels the screen really holds rather than the points a widget is measured in, so a 200% display yields an image twice the widget's size in each direction; zoom is that ratio, read off the pixels that came back, deviceZoom is what the display paints at, and belowDeviceZoom says so when the two differ, which is the one thing a softer-than-expected picture cannot tell you by itself. Use it for UI work such as layout, theming and dialog rendering; for anything textual the other tools answer better and shorter. A part that is not visible is refused rather than captured blank, unless activate is set. capturedArea is the pixels returned, areaInPoints is the widget's own size, and zoom is the percentage between them. Set includeToolbar to capture a part together with its surrounding stack, but note that the stack's topRight children, the view toolbar among them, are not painted by any widget print rooted inside the window; capture the shell and crop to the bounds from eclipse_get_widget_tree for those."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "target":     {"type":"string","enum":["part","shell","display"],"default":"part"},
					    "part":       {"type":"string","description":"Part id, e.g. org.eclipse.ui.views.ProblemView. Use eclipse_list_ui_targets to find it. Several open parts share one id, every Java editor above all; the active or a visible one is chosen, and a part's TITLE from eclipse_list_ui_targets names a particular one."},
					    "shellTitle": {"type":"string","description":"Title of the shell to capture, or a substring. Ambiguous when several shells share a title, e.g. the empty title of the workbench and the content assist popup; use 'shell' then."},
				    "shell":      {"type":"string","description":"Shell to capture, independent of title: 'popup' for the topmost untitled non-workbench shell (the content assist proposals), an index from eclipse_list_ui_targets ('1' or '#1'), or its bounds as printed ('151,334 402x255'). Wins over shellTitle."},
					    "activate":   {"type":"boolean","default":false,"description":"Bring the part to the front first. This visibly rearranges the user's IDE, so it is off by default."},
					    "maxWidth":   {"type":"integer","default":1200,"minimum":100,"maximum":4000,"description":"Downscale to this width. A full HD PNG is over a megabyte once base64 encoded."},
					    "outputPath": {"type":"string","description":"Absolute file to write. Defaults to a temporary file."},
					    "includeBase64": {"type":"boolean","default":false,"description":"Also return the image inline. Large; prefer reading the file."},
				    "highlights": {"type":"array","description":"Rectangles to draw onto the image after the capture, each {path?, bounds?, color?, label?, style?, padding?, lineWidth?, labelPosition?}: path is a widget path from eclipse_get_widget_tree relative to the capture target, bounds is 'x,y wxh' in points relative to the capture target, color is #rrggbb (default #ff0066, which reads on light and dark themes), label is drawn in a filled box beside the rectangle, style is outline (default) or fill (translucent). padding adds air around the rectangle IN POINTS, one number for every side or 'top,right,bottom,left', applied after the rectangle is resolved and before it is scaled, which is the only way to frame a widget loosely when the rectangle came from a path. lineWidth is the outline in pixels, 3 by default. labelPosition is above (default), below, left, right or inside, for keeping a label off a neighbouring element. The answer reports the padded rectangle under pointsInTarget and the pixels actually drawn under pixels.","items":{"type":"object","properties":{"path":{"type":"string"},"bounds":{"type":"string"},"color":{"type":"string"},"label":{"type":"string"},"style":{"type":"string","enum":["outline","fill"]},"padding":{"type":["integer","string"]},"lineWidth":{"type":"integer","minimum":1,"maximum":40},"labelPosition":{"type":"string","enum":["above","below","left","right","inside"]}},"additionalProperties":false}},
				    "settle": {"type":"boolean","default":false,"description":"Wait for the UI to look idle before capturing, the same heuristic eclipse_wait_until_settled runs, and report it under 'settle'. IT IS A HEURISTIC: it drains the display queue and waits for the job manager, and it cannot see work on a plain background thread, so JDT's semantic highlighting can still land after the capture. Assert what you need rather than trusting it."},
				    "settleTimeoutSeconds": {"type":"integer","default":10,"minimum":1,"maximum":120,"description":"Budget for 'settle'. Running out still captures, and the report says it did not settle."},
				    "settlePixels": {"type":"boolean","default":false,"description":"Capture repeatedly until two consecutive images are byte identical, and report it under 'pixelSettle'. THIS IS THE ONLY CHECK THAT CATCHES A REPAINT IN FLIGHT: 'settle' asks the display queue, the job manager and the reconcilers, and a paint already under way is none of those, so a whole-shell capture can come back with a few thousand scattered pixels wrong while settle honestly reports settled true. Comparing two captures does not need to know why. It costs one extra capture at least, and answers converged false rather than hanging when the screen genuinely never stops changing, which is what an animation or a progress bar does. suppressCaret is on by default for the same reason and is what stops a blinking caret making this never converge. READ THE TWO OUTCOMES AS A DIAGNOSIS, because they point at different causes: converged false means the screen never stopped, so look for something still animating; converged TRUE while the image still differs from an earlier one means the screen stopped at a DIFFERENT LAYOUT, not mid-paint, so look at placement rather than painting. A whole-window comparison that differs by a few thousand scattered pixels with converged true is the second case and is usually one part laid out at a different offset, which drags everything below it and looks like noise spread over the window; no amount of waiting fixes it, because the waiting already succeeded."},
				    "settlePixelAttempts": {"type":"integer","default":4,"minimum":2,"maximum":10,"description":"How many captures 'settlePixels' may take before giving up and answering with the last one."},
				    "suppressCaret": {"type":"boolean","default":true,"description":"Take the text caret out of every StyledText under the target for the duration of the capture, and put it back afterwards. SWT draws and blinks the caret itself, so gtk-cursor-blink=false does not reach it, and two captures of a focused editor otherwise differ by the caret alone, which is enough to make a pixel comparison never agree. Reported under caretsSuppressed. ON by default: the two failures are not symmetric. Leaving it off makes two captures differ by a caret and look like a real change, which is silent and misleading; leaving it on costs a caret in a capture taken to look at one, and the answer says caretsSuppressed so that explains itself. Pass false when the caret is the subject."},
				    "includeToolbar": {"type":"boolean","default":false,"description":"For a part, capture the whole part stack instead: the tabs and any sibling view sharing the stack. KNOWN GAP: it does NOT paint the CTabFolder's topRight children, which are the view toolbar, the view menu and the min and max buttons, and nothing in the answer says they are missing. To see those, capture target=shell and crop to the bounds eclipse_get_widget_tree reports for them."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			String unsupported = unsupportedReason();
			if (unsupported != null) {
				return McpToolResult.error("Cannot capture on this platform. " + unsupported); //$NON-NLS-1$
			}
			ToolArguments args = ToolArguments.of(arguments);
			String part = args.getString("part"); //$NON-NLS-1$
			String explicitTarget = args.getString("target"); //$NON-NLS-1$
			// infer from whichever selector was given, and with none at all capture the
			// active shell, which is what the description promises
			String shellSpec = args.getString("shell") != null ? args.getString("shell") : args.getString("shellTitle"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			String target = explicitTarget != null ? explicitTarget
					: part != null ? "part" : "shell"; //$NON-NLS-1$ //$NON-NLS-2$
			if ("part".equals(target) && part == null) { //$NON-NLS-1$
				return McpToolResult.error("The target 'part' needs a 'part' id. Use eclipse_list_ui_targets."); //$NON-NLS-1$
			}
			String shellTitle = shellSpec;
			boolean activate = args.getBoolean("activate", false); //$NON-NLS-1$
			int maxWidth = args.getInt("maxWidth", 1200, 100, 4000); //$NON-NLS-1$
			String outputPath = args.getString("outputPath"); //$NON-NLS-1$
			boolean includeBase64 = args.getBoolean("includeBase64", false); //$NON-NLS-1$

			Object highlights = arguments.get("highlights"); //$NON-NLS-1$
			// Whether this call is already ON the UI thread, which has to be asked here
			// rather than inside capture, where the answer is always yes. It is true
			// when eclipse_run_script with atomic runs the batch inside one Display
			// runnable, and it means no paint has been dispatched since the widgets
			// this batch created came into existence.
			boolean sameTurn = UiThread.onUiThread();
			// before the UI hop, because the fence it posts has to be waited for from
			// off the UI thread; inside an atomic batch it refuses and says why
			JsonObject settled = args.getBoolean("settle", false) //$NON-NLS-1$
					? UiSettle.settle(3, args.getInt("settleTimeoutSeconds", 10, 1, 120) * 1000L, 120, monitor) //$NON-NLS-1$
					: null;
			boolean suppressCaret = args.getBoolean("suppressCaret", true); //$NON-NLS-1$
			boolean settlePixels = args.getBoolean("settlePixels", false) && !sameTurn; //$NON-NLS-1$
			int attempts = args.getInt("settlePixelAttempts", 4, 2, 10); //$NON-NLS-1$
			// a known path for every attempt, because two captures are compared on the
			// file they wrote and a temporary name chosen inside the capture would not
			// be knowable here
			String path = outputPath;
			if (settlePixels && path == null) {
				try {
					path = Files.createTempFile("mcp-capture", ".png").toString(); //$NON-NLS-1$ //$NON-NLS-2$
				} catch (IOException e) {
					return McpToolResult.error("Could not create a file for the capture: " + e.getMessage()); //$NON-NLS-1$
				}
			}
			String target0 = path;
			Supplier<JsonObject> once = () -> capture(target, part, shellTitle, activate, maxWidth, target0,
					includeBase64, args.getBoolean("includeToolbar", false), highlights, sameTurn, suppressCaret); //$NON-NLS-1$
			if (!settlePixels) {
				return onUi(once, answer -> (settled == null ? answer : answer.put("settle", settled)).toString()); //$NON-NLS-1$
			}
			JsonObject answer = null;
			byte[] previous = null;
			int taken = 0;
			boolean converged = false;
			for (int i = 0; i < attempts && !converged; i++) {
				// each capture is its own UI hop on purpose: the point is to let
				// whatever is painting get on with it between them, which cannot happen
				// inside one
				answer = onUiValue(once);
				if (answer == null) {
					return McpToolResult.error("The Eclipse UI is busy, try again."); //$NON-NLS-1$
				}
				taken++;
				byte[] bytes = bytesOf(target0);
				if (bytes != null && previous != null && java.util.Arrays.equals(bytes, previous)) {
					converged = true;
				}
				previous = bytes;
				if (!converged && i + 1 < attempts) {
					pause();
				}
			}
			JsonObject pixelSettle = new JsonObject().put("converged", Boolean.valueOf(converged)) //$NON-NLS-1$
					.put("captures", Integer.valueOf(taken)); //$NON-NLS-1$
			if (!converged) {
				pixelSettle.put("note", //$NON-NLS-1$
						"No two consecutive captures were identical within %d attempts, so the image returned is the last one and something on screen is still changing. An animation, a progress bar or a caret does this; suppressCaret is on by default, so a caret is the least likely of the three." //$NON-NLS-1$
								.formatted(Integer.valueOf(attempts)));
			}
			answer.put("pixelSettle", pixelSettle); //$NON-NLS-1$
			return McpToolResult.of((settled == null ? answer : answer.put("settle", settled)).toString()); //$NON-NLS-1$
		}

		/** Leaves the UI alone between two captures of a pixel settle. */
		private static void pause() {
			try {
				Thread.sleep(120);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		/**
		 * The written PNG, which is what two captures are compared on.
		 * <p>
		 * The file rather than the ImageData: the encoder is deterministic for
		 * identical pixels, and the file is what the caller compares too.
		 */
		private static byte[] bytesOf(String path) {
			try {
				return Files.readAllBytes(Path.of(path));
			} catch (IOException | RuntimeException e) {
				return null;
			}
		}

		/** One capture on the UI thread, as the object rather than as a result. */
		private static JsonObject onUiValue(Supplier<JsonObject> work) {
			CompletableFuture<JsonObject> pending = new CompletableFuture<>();
			UiThread.exec(() -> {
				try {
					pending.complete(work.get());
				} catch (RuntimeException e) {
					pending.completeExceptionally(e);
				}
			});
			try {
				return pending.get(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			} catch (Exception e) {
				return null;
			}
		}

		private static JsonObject capture(String target, String partId, String shellTitle, boolean activate,
				int maxWidth, String outputPath, boolean includeBase64, boolean includeToolbar, Object highlights,
				boolean sameTurn, boolean suppressCaret) {
			Display display = PlatformUI.getWorkbench().getDisplay();
			Rectangle area;
			// the bounds of what the caller named, which the answer reports even when
			// the capture covers less
			Rectangle requested;
			// what is outside the capture although the caller named it, if anything
			String exclusion = null;
			Rectangle clientArea = null;
			List<Paintable> pieces = null;
			// what the pieces report about themselves, which the scan of the finished
			// canvas can no longer see: they are handed over already repainted
			int[] insidePieces = { 0 };
			// the control to paint if reading the root drawable comes back empty
			Control printable = null;
			if ("display".equals(target)) { //$NON-NLS-1$
				area = display.getBounds();
				requested = new Rectangle(area.x, area.y, area.width, area.height);
			} else if ("shell".equals(target)) { //$NON-NLS-1$
				Shell shell = findShell(display, shellTitle);
				if (shell == null) {
					return failure(shellTitle == null ? "This IDE has no window to capture." //$NON-NLS-1$
							: "No shell matching '%s'.".formatted(shellTitle)); //$NON-NLS-1$
				}
				area = shell.getBounds();
				requested = new Rectangle(area.x, area.y, area.width, area.height);
				exclusion = DECORATIONS_EXCLUDED;
				printable = shell;
			} else {
				Control control = findPart(partId, activate);
				if (control == null) {
					return failure(noPartReason(partId));
				}
				if (includeToolbar) {
					control = stackOf(control);
				}
				// map to display coordinates and capture the real pixels, rather than
				// Control.print(), which has GTK gaps the widget on screen does not
				area = display.map(control.getParent(), null, control.getBounds());
				requested = new Rectangle(area.x, area.y, area.width, area.height);
				printable = control;
			}
			if (area.width <= 0 || area.height <= 0) {
				// zero bounds have two quite different causes and the caller can only
				// act on one of them, so name both rather than saying "empty"
				return failure(
						"The capture area is empty: the target reports %dx%d. Either the widget has never been laid out, which is what a shell that has not been shown yet reports, or the part is behind another one and is therefore not rendered at all. A part behind another tab has no area until it is brought forward; pass activate for that. Use eclipse_list_ui_targets to see which shells and parts are visible." //$NON-NLS-1$
								.formatted(Integer.valueOf(area.width), Integer.valueOf(area.height)));
			}

			List<Overlays.Highlight> overlays = Overlays.resolve(display, printable, highlights);
			// Inside one turn of the UI thread the screen cannot have caught up with
			// the widgets: a shell this batch created has correct bounds and has never
			// painted, so reading the root drawable at those bounds photographs
			// whatever was underneath and returns it as a successful capture. This
			// shipped that way and produced a picture of the Welcome page for a content
			// assist popup, reported as captured true with no warning, which a
			// regression suite then recorded as its baseline. Flush what is pending and
			// then paint the widget itself, whose state IS current, rather than trusting
			// the screen.
			// Reading the screen photographs whatever is in FRONT of the target, and a
			// window from another application is not uniform, so every check this class
			// has passes on an image of somebody's browser. That is the one failure a
			// caller cannot detect: settled, converged, plausible area, right zoom. A
			// display with no active shell is one where this IDE is not the foreground
			// application, which is as much as SWT will say portably.
			boolean foreground = display.getActiveShell() != null;
			boolean occluded = !foreground && !sameTurn;
			boolean screenUnreliable = (sameTurn || occluded) && printable != null;
			if (screenUnreliable) {
				printable.update();
			}
			// SWT draws and blinks the caret itself, so gtk-cursor-blink=false never
			// reaches it and two captures of a focused editor differ by the caret
			// alone. Taken out for the duration and put back in the finally below,
			// because leaving an editor with no caret would be a far worse bug than
			// the one this avoids
			List<SuppressedCaret> carets = suppressCaret ? suppressCarets(printable) : List.of();
			try {
			// the screen holds device pixels, and a destination sized in points takes
			// them downsampled with nothing in the answer to say so
			int zoom = DeviceScale.screenZoom();
			Image image = DeviceScale.screenTarget(display, area.width, area.height, zoom);
			String method = "rootCapture"; //$NON-NLS-1$
			// cairo answers from the copy of the screen it read last unless it is
			// told the screen has moved on, and every capture after the first was
			// then the first one again
			boolean screenRead = DisplayScaling.refreshScreenSource();
			try {
				GC gc = new GC(display);
				try {
					gc.copyArea(image, area.x, area.y);
				} finally {
					gc.dispose();
				}
				if ((screenUnreliable || isBlank(DeviceScale.screenData(image, zoom))) && printable instanceof Shell shell
						&& shell.getChildren().length > 0) {
					// Shell.print returns blank under a compositing window manager while
					// Composite.print does not, so paint the shell's content instead:
					// every visible child at its own bounds, trim bars included, rather
					// than one of them alone. The window decorations are no child of
					// anything SWT can print; requestedArea and its note tell the caller.
					clientArea = shell.getClientArea();
					pieces = paintablesOf(shell);
				}
				if ((screenUnreliable || isBlank(DeviceScale.screenData(image, zoom))) && printable != null) {
					final Control painted = printable;
					final List<Paintable> composed = pieces;
					// A compositing window manager redirects window contents into an
					// offscreen pixmap, so reading the X11 root drawable yields nothing.
					// Painting the widget hierarchy ourselves does work there. It has
					// known GTK gaps, which is why it is the fallback and not the
					// primary path, but a slightly wrong image beats no image at all.
					image.dispose();
					Rectangle own = clientArea != null ? clientArea : painted.getBounds();
					Size canvas = compositionSize(own.width, own.height);
					// the print itself lands in points whatever surface it is given, so
					// the canvas is sized in device pixels and the GC carries the scale
					// that makes GTK rasterise at that resolution, glyphs included
					zoom = DeviceScale.zoomOf(painted);
					final int pieceZoom = zoom;
					image = DeviceScale.paint(display, canvas.width(), canvas.height(), zoom,
							(drawer, drawnWidth, drawnHeight) -> {
						// anything print leaves untouched stays this colour. White would
						// be indistinguishable from the unstyled widgets a dark theme bug
						// produces, which is most of what these captures are used for
						drawer.setBackground(display.getSystemColor(SWT.COLOR_MAGENTA));
						drawer.fillRectangle(0, 0, drawnWidth, drawnHeight);
						if (composed == null) {
							painted.print(drawer);
							if (Screencast.GTK) {
								Rectangle own2 = painted.getBounds();
								painted.redraw(0, 0, own2.width, own2.height, true);
							}
						} else {
							for (Paintable piece : composed) {
								insidePieces[0] += piece.print(drawer, pieceZoom);
							}
						}
					});
					area = new Rectangle(area.x, area.y, canvas.width(), canvas.height());
					method = "widgetPrint"; //$NON-NLS-1$
				}
				ImageData data = "rootCapture".equals(method) ? DeviceScale.screenData(image, zoom) //$NON-NLS-1$
						: DeviceScale.paintedData(image, zoom);
				if (isBlank(data)) {
					return failure(printable == null
							? "The capture came back uniform, so this display cannot be captured through the X11 root drawable. A compositing window manager redirects window contents into an offscreen pixmap, so reading the root yields nothing. There is no fallback for the whole display; capture a part or a shell instead, which can be painted directly." //$NON-NLS-1$
							: "The capture came back uniform through both the X11 root drawable and by painting the widget, so this display cannot be captured at all. Nothing was written; do not trust screenshots here."); //$NON-NLS-1$
				}
				// before the filler is replaced, because replacing it is what makes this
				// invisible: a paint that landed at half scale fills the top left quarter
				// and leaves the rest filler, which afterwards reads as a plain
				// background and counts as zero unpainted pixels
				String scaleWarning = "widgetPrint".equals(method) ? paintCoverageWarning(data) : null; //$NON-NLS-1$
				// after the blank check: a fully unpainted capture must still read as
				// uniform here, and swapping the filler first would hide exactly that
				Unpainted unpainted = "widgetPrint".equals(method) && printable != null && scaleWarning == null //$NON-NLS-1$
						? replaceFiller(data, backgroundSource(printable, pieces))
						: null;
				if (unpainted != null && insidePieces[0] > 0) {
					unpainted = unpainted.plus(insidePieces[0]);
				}
				// read off the pixels that came back rather than off what was asked
				// for. A capture that lost the device scale reported zoom 100 with
				// every other field agreeing, which is the one failure this answer
				// cannot afford: the picture is soft and nothing says why
				int captured = area.width <= 0 ? zoom : Math.round(data.width * 100f / area.width);
				JsonObject written = write(display, image, data, area, maxWidth, outputPath, includeBase64, overlays,
						captured).put("method", method) //$NON-NLS-1$
						.put("zoom", Integer.valueOf(captured)) //$NON-NLS-1$
						.put("deviceZoom", Integer.valueOf(zoom)) //$NON-NLS-1$
						.put("foreground", Boolean.valueOf(foreground)) //$NON-NLS-1$
						.put("requestedArea", describe(requested)); //$NON-NLS-1$
				if ("rootCapture".equals(method) && DeviceScale.GTK) { //$NON-NLS-1$
					written.put("screenSourceRefreshed", Boolean.valueOf(screenRead)); //$NON-NLS-1$
					if (!screenRead) {
						written.put("screenSourceNote", //$NON-NLS-1$
								"Cairo's copy of the screen could not be invalidated on this IDE, so this image may be an earlier frame rather than the screen as it is now. Nothing else in this answer can tell the two apart: a repeated frame has the right size, the right zoom and the right area. Capture a shell or a part with activate, which is painted directly, if the picture does not show what you just changed."); //$NON-NLS-1$
					}
				}
				if (captured < zoom) {
					written.put("belowDeviceZoom", //$NON-NLS-1$
							"This display paints at %d%% and the capture came back at %d%%, so the image holds fewer pixels than the screen does and its text is softer than what is on it. Use eclipse_get_display_info to see the scaling in force." //$NON-NLS-1$
									.formatted(Integer.valueOf(zoom), Integer.valueOf(captured)));
				}
				// reported whatever happened, because a caller that only reads 'method'
				// must still be able to tell a capture of the IDE from a capture of
				// whatever was on top of it
				if (!foreground) {
					written.put("foregroundNote", "rootCapture".equals(method) //$NON-NLS-1$ //$NON-NLS-2$
							? "THIS IDE IS NOT THE FOREGROUND APPLICATION AND THE WHOLE DISPLAY CANNOT BE PAINTED WIDGET BY WIDGET, so this image is of the screen as it is, including any window in front of the IDE. Nothing else in this answer can tell you that: a window sitting still is settled, converged and the right size. Capture a shell or a part instead, which is painted directly and cannot be occluded, or bring the IDE forward first and check 'foreground' here rather than trusting eclipse_set_ide_visibility, which asks the window system for focus and can be refused." //$NON-NLS-1$
							: "This IDE is not the foreground application, so reading the screen would have photographed whatever is in front of it. The widget was painted directly instead, so the image is of the right thing; expect the GTK gaps of widgetPrint rather than the fidelity of a screen read."); //$NON-NLS-1$
				}
				if (sameTurn) {
					written.put("sameTurnCapture", Boolean.TRUE); //$NON-NLS-1$
					written.put("sameTurnNote", screenUnreliable //$NON-NLS-1$
							? "This capture ran inside one turn of the UI thread, which is what eclipse_run_script with atomic does. No paint has been dispatched since this batch started, so the screen does not show what the widgets hold and reading it would have photographed whatever was underneath. The widget was painted directly instead, so the image is of the right thing; expect the GTK gaps of widgetPrint rather than the fidelity of a screen read."
							: "This capture ran inside one turn of the UI thread, which is what eclipse_run_script with atomic does, and the target is the whole display, which cannot be painted widget by widget. No paint has been dispatched since this batch started, so ANYTHING THIS BATCH CHANGED IS PROBABLY NOT IN THE IMAGE. Capture a shell or a part instead, or take the screenshot outside the atomic batch."); //$NON-NLS-1$
				}
				if (!requested.equals(area)) {
					written.put("requestedAreaNote", exclusion != null ? exclusion //$NON-NLS-1$
							: "The capture covers %dx%d points while requestedArea names %dx%d." //$NON-NLS-1$
									.formatted(Integer.valueOf(area.width), Integer.valueOf(area.height),
											Integer.valueOf(requested.width), Integer.valueOf(requested.height)));
				}
				if (scaleWarning != null) {
					written.put("paintScaleMismatch", scaleWarning); //$NON-NLS-1$
				}
				if (unpainted != null) {
					written.put("unpaintedPixels", Integer.valueOf(unpainted.pixels())) //$NON-NLS-1$
							.put("unpaintedFraction", Double.valueOf(unpainted.fraction())) //$NON-NLS-1$
							.put("unpaintedFilledWith", unpainted.fillDescription()) //$NON-NLS-1$
							.put("coverage", unpainted.coverage()); //$NON-NLS-1$
				}
				if ("widgetPrint".equals(method)) { //$NON-NLS-1$
					written.put("printNote", "The widget print does not paint the sash and margin areas between parts. Those carry the background colour of the part they sit in, and unpaintedPixels counts them; a composed shell capture measures each piece against a filler colour and then repaints it against its own background, so no text in the image is blended against the filler."); //$NON-NLS-1$ //$NON-NLS-2$
				}
				// the two axes have to agree on one scale, and when they do not the
				// paint landed at the wrong one and part of the image is whatever the
				// canvas was filled with. Saying so beats returning a picture that is
				// three quarters filler and looks like a rendering bug in the IDE
				if (data.height != Math.round(area.height * captured / 100f)) {
					written.put("scaleMismatch", //$NON-NLS-1$
							"The capture is %dx%d pixels for a widget of %dx%d points, which is not one scale in both directions. Part of the image is the magenta fill rather than the widget, so treat it as unreliable." //$NON-NLS-1$
									.formatted(Integer.valueOf(data.width), Integer.valueOf(data.height),
											Integer.valueOf(area.width), Integer.valueOf(area.height)));
				}
				if (!carets.isEmpty()) {
					written.put("caretsSuppressed", Integer.valueOf(carets.size())) //$NON-NLS-1$
							.put("caretNote", //$NON-NLS-1$
									"The text caret was taken out of %d StyledText widgets for this capture and put back afterwards, so the image has no caret in it and two captures of the same state can be identical. SWT blinks the caret itself, which is why no window system setting stops it." //$NON-NLS-1$
											.formatted(Integer.valueOf(carets.size())));
				}
				return written;
			} finally {
				image.dispose();
			}
			} finally {
				restoreCarets(carets);
			}
		}

		/** A StyledText and the caret taken off it, so it can be put back. */
		private record SuppressedCaret(org.eclipse.swt.custom.StyledText text, org.eclipse.swt.widgets.Caret caret) {
		}

		/**
		 * Takes the caret off every StyledText under {@code root}.
		 * <p>
		 * {@code setCaret(null)} rather than {@code Caret.setVisible(false)}: StyledText
		 * manages caret visibility from setCaretLocations as the offset changes, so a
		 * hidden caret comes back the moment anything moves it, while a null caret
		 * clears the Canvas caret and StyledText's own carets array together and stays
		 * gone until it is put back.
		 */
		private static List<SuppressedCaret> suppressCarets(Control root) {
			if (root == null) {
				return List.of();
			}
			List<SuppressedCaret> taken = new ArrayList<>();
			collectCarets(root, taken);
			return taken;
		}

		private static void collectCarets(Control control, List<SuppressedCaret> into) {
			if (control instanceof org.eclipse.swt.custom.StyledText text && !text.isDisposed()) {
				org.eclipse.swt.widgets.Caret caret = text.getCaret();
				if (caret != null) {
					into.add(new SuppressedCaret(text, caret));
					text.setCaret(null);
				}
			}
			if (control instanceof Composite composite && !composite.isDisposed()) {
				for (Control child : composite.getChildren()) {
					collectCarets(child, into);
				}
			}
		}

		/** Puts every caret back, whatever the capture did. */
		private static void restoreCarets(List<SuppressedCaret> taken) {
			for (SuppressedCaret entry : taken) {
				try {
					if (!entry.text().isDisposed() && !entry.caret().isDisposed()) {
						entry.text().setCaret(entry.caret());
					}
				} catch (RuntimeException e) {
					// an editor closed mid capture is not a reason to fail the answer,
					// and a disposed StyledText has no caret to lose
				}
			}
		}

		/** What a shell capture leaves out, and why nothing can paint it. */
		private static final String DECORATIONS_EXCLUDED = "The window decorations, meaning the title bar and the frame around the shell, are drawn by the window manager and are not part of the client area this capture paints, so they are not in the image."; //$NON-NLS-1$

		/** The size of a composed shell capture's canvas, as plain values. */
		public record Size(int width, int height) {
		}

		/**
		 * The canvas a composed shell capture paints into: the shell's client area,
		 * never empty, so that a degenerate shell still yields an image the
		 * uniform-pixel check can judge.
		 */
		public static Size compositionSize(int clientWidth, int clientHeight) {
			return new Size(Math.max(1, clientWidth), Math.max(1, clientHeight));
		}

		/** Where a child goes in a composed shell capture, as plain values. */
		public record Placement(int x, int y, int width, int height) {
		}

		/**
		 * Where a child is painted in a composed shell capture, or {@code null} when
		 * it is left out because it is invisible or has an empty bounds.
		 */
		public static Placement placementOf(int x, int y, int width, int height, boolean visible) {
			if (!visible || width <= 0 || height <= 0) {
				return null;
			}
			return new Placement(x, y, width, height);
		}

		/** One control of a composed shell capture, with its placement. */
		record Paintable(Control control, Rectangle at) {

			/**
			 * Prints the control into an image of its own and draws that image at its
			 * place, so every piece goes through the same print a single child would.
			 * <p>
			 * It prints twice, because the two things wanted of the fill colour cannot
			 * both come from one pass. Finding what no print touched needs a colour no
			 * widget uses; delivering pixels a human reads needs the colour the widget
			 * would have had, since a label draws its glyphs blended against whatever
			 * lies under them and a magenta ground turns the text magenta.
			 *
			 * @return how many pixels of this piece no print touched
			 */
			int print(GC target, int zoom) {
				int unpainted = unpaintedPixels(target, zoom);
				Image piece = paint(background(), zoom);
				try {
					// the piece already holds device pixels, so it goes onto the canvas
					// one for one rather than through the canvas' own scale
					DeviceScale.drawPixels(target, piece, at.x, at.y, zoom);
				} finally {
					piece.dispose();
				}
				return unpainted;
			}

			/** The same print onto filler, counted and thrown away. */
			private int unpaintedPixels(GC target, int zoom) {
				Image probe = paint(control.getDisplay().getSystemColor(SWT.COLOR_MAGENTA), zoom);
				try {
					// at the canvas' own resolution, because the count is added to what
					// the finished canvas reports and the two have to be the same pixels
					return countFiller(DeviceScale.paintedData(probe, zoom));
				} catch (RuntimeException e) {
					return 0;
				} finally {
					probe.dispose();
				}
			}

			private Image paint(org.eclipse.swt.graphics.Color fill, int zoom) {
				return DeviceScale.paint(control.getDisplay(), at.width, at.height, zoom, (gc, w, h) -> {
					gc.setBackground(fill);
					gc.fillRectangle(0, 0, w, h);
					control.print(gc);
					if (Screencast.GTK) {
						control.redraw(0, 0, at.width, at.height, true);
					}
				});
			}

			private org.eclipse.swt.graphics.Color background() {
				org.eclipse.swt.graphics.Color own = control.getBackground();
				return own == null ? control.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND) : own;
			}
		}

		/** Pixels still carrying the fill colour, which is what no print touched. */
		private static int countFiller(ImageData data) {
			int fillerPixel = pixelOf(data.palette, FILLER);
			if (fillerPixel < 0) {
				return 0;
			}
			int count = 0;
			int[] row = new int[data.width];
			for (int y = 0; y < data.height; y++) {
				data.getPixels(0, y, data.width, row, 0);
				for (int x = 0; x < data.width; x++) {
					if (row[x] == fillerPixel) {
						count++;
					}
				}
			}
			return count;
		}

		static List<Paintable> paintablesOf(Shell shell) {
			List<Paintable> paintables = new ArrayList<>();
			for (Control child : shell.getChildren()) {
				Rectangle bounds = child.getBounds();
				Placement at = placementOf(bounds.x, bounds.y, bounds.width, bounds.height, child.isVisible());
				if (at != null) {
					paintables.add(new Paintable(child, new Rectangle(at.x(), at.y(), at.width(), at.height())));
				}
			}
			return paintables;
		}

		/**
		 * The widget whose background replaces unpainted pixels: the first child for a
		 * composed shell, which is what the one-child path used before composing.
		 */
		private static Control backgroundSource(Control printable, List<Paintable> pieces) {
			return pieces != null && !pieces.isEmpty() ? pieces.get(0).control() : printable;
		}

		/** Bounds in the {@code x,y widthxheight} form the other tools report. */
		private static String describe(Rectangle rectangle) {
			return rectangle.x + "," + rectangle.y + " " + rectangle.width + "x" + rectangle.height; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}

		/**
		 * The width to scale to, snapped to a whole number ratio when that is close to
		 * what was asked for.
		 * <p>
		 * Resampling already rendered text by a fraction makes the glyphs mushy, and a
		 * caller asking for 1920 from a 5370 wide capture wants a picture that reads
		 * rather than exactly 1920 pixels. Only snaps within a fifth of the request, so
		 * a deliberate width is still honoured. A tenth was too tight to be useful: a
		 * 5370 wide capture asked for 2000 lands on 1790, which missed by half a
		 * percent and left the picture mushy for nothing.
		 */
		static int crispWidth(int actual, int maxWidth) {
			if (actual <= maxWidth) {
				return maxWidth;
			}
			int divisor = Math.max(1, Math.round(actual / (float) maxWidth));
			int candidate = actual / divisor;
			return candidate <= maxWidth && candidate >= maxWidth * 4 / 5 ? candidate : maxWidth;
		}

		private static JsonObject write(Display display, Image image, ImageData data, Rectangle area, int maxWidth,
				String outputPath, boolean includeBase64, List<Overlays.Highlight> overlays, int zoom) {
			ImageData scaled = data;
			int snapped = crispWidth(data.width, maxWidth);
			if (data.width > snapped) {
				int height = Math.max(1, data.height * snapped / data.width);
				scaled = data.scaledTo(snapped, height);
			}
			JsonArray highlighted = null;
			if (!overlays.isEmpty()) {
				// points to pixels: the zoom the widget painted at, then the downscale
				double scale = zoom / 100.0 * scaled.width / data.width;
				highlighted = Overlays.draw(display, scaled, overlays, scale);
			}
			ImageLoader loader = new ImageLoader();
			loader.data = new ImageData[] { scaled };
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			loader.save(bytes, SWT.IMAGE_PNG);
			try {
				Path file = outputPath != null ? Path.of(outputPath)
						: Files.createTempFile("eclipse-screenshot-", ".png"); //$NON-NLS-1$ //$NON-NLS-2$
				Files.write(file, bytes.toByteArray());
				JsonObject result = new JsonObject().put("captured", true) //$NON-NLS-1$
						.put("path", file.toAbsolutePath().toString()) //$NON-NLS-1$
						.put("width", scaled.width) //$NON-NLS-1$
						.put("height", scaled.height) //$NON-NLS-1$
						// the pixels that were actually captured, not the widget's size in
						// points. Reporting the two as if they were the same is what let a
						// capture that kept a quarter of the window look complete
						.put("capturedArea", data.width + "x" + data.height) //$NON-NLS-1$ //$NON-NLS-2$
						.put("areaInPoints", area.width + "x" + area.height) //$NON-NLS-1$ //$NON-NLS-2$
						// every number the scaling depends on, from its own source: the
						// image as SWT sizes it, the data as it came back, and the factor
						// actually applied. A capture that looks right in every derived
						// field and wrong on screen is a disagreement between these, and
						// naming them is cheaper than inferring them from the picture
						.put("imageBounds", image.getBounds().width + "x" + image.getBounds().height) //$NON-NLS-1$ //$NON-NLS-2$
						.put("scaleFactor", Math.round(scaled.width * 1000.0 / data.width) / 1000.0) //$NON-NLS-1$
						.put("maxWidthSnappedTo", snapped == maxWidth ? null : Integer.valueOf(snapped)) //$NON-NLS-1$
						.put("bytes", bytes.size()); //$NON-NLS-1$
				if (highlighted != null) {
					result.put("highlights", highlighted); //$NON-NLS-1$
				}
				if (includeBase64) {
					result.put("base64", Base64.getEncoder().encodeToString(bytes.toByteArray())); //$NON-NLS-1$
				}
				return result;
			} catch (IOException e) {
				return failure("Could not write the image: " + e.getMessage()); //$NON-NLS-1$
			}
		}

		/** A uniform image is what a silently failing capture produces. */
		private static boolean isBlank(ImageData data) {
			int first = data.getPixel(0, 0);
			for (int y = 0; y < data.height; y += Math.max(1, data.height / 50)) {
				for (int x = 0; x < data.width; x += Math.max(1, data.width / 50)) {
					if (data.getPixel(x, y) != first) {
						return false;
					}
				}
			}
			return true;
		}

		/** The pre-print fill and what it was replaced with. */
		private record Unpainted(int pixels, int width, int height, org.eclipse.swt.graphics.RGB fill) {

			/** Adds what a piece counted in itself before it was drawn onto the canvas. */
			Unpainted plus(int more) {
				return new Unpainted(pixels + more, width, height, fill);
			}

			double fraction() {
				return width * height == 0 ? 0.0 : Math.round(pixels * 1000.0 / (width * height)) / 10.0;
			}

			/**
			 * How much of the requested area the print actually covered, in words.
			 * <p>
			 * The fraction alone leaves the caller to decide what counts as too much
			 * filler, and a capture that is mostly filler is not evidence about the UI
			 * however honestly it is measured. Saying so is what stops a visual check
			 * from being run against a picture of nothing. The sash and margin areas
			 * between parts account for a few percent on any healthy shell capture,
			 * which is why the quiet case says nothing at all.
			 */
			JsonObject coverage() {
				double painted = Math.round((100.0 - fraction()) * 10.0) / 10.0;
				JsonObject json = new JsonObject().put("paintedFraction", Double.valueOf(painted)); //$NON-NLS-1$
				if (fraction() >= 50.0) {
					return json.put("note", //$NON-NLS-1$
							"The print covered only %s%% of the requested area; the rest was never painted. THIS IMAGE IS NOT EVIDENCE ABOUT THE UI: most of it is filler, so do not read a layout or a theme off it. A widget print leaves this much unpainted when the target was not laid out, was not visible, or was printed at a scale the canvas does not match." //$NON-NLS-1$
									.formatted(Double.valueOf(painted)));
				}
				if (fraction() >= 10.0) {
					return json.put("note", //$NON-NLS-1$
							"The print covered %s%% of the requested area. That is more filler than the sashes and margins between parts account for, so treat anything in the unpainted regions as absent from the capture rather than absent from the UI." //$NON-NLS-1$
									.formatted(Double.valueOf(painted)));
				}
				return json;
			}

			String fillDescription() {
				return "%d,%d,%d (the widget's background colour)".formatted(Integer.valueOf(fill().red), //$NON-NLS-1$
						Integer.valueOf(fill().green), Integer.valueOf(fill().blue));
			}
		}

		/**
		 * Whether the paint covered the image it was given, or only a corner of it.
		 * <p>
		 * A print that lands at half the scale of its buffer fills the top left
		 * quarter and leaves the rest as it was filled, which the metadata cannot
		 * show: the sizes agree with each other and only the pixels disagree. Sampling
		 * the far corner catches it, and it has to happen before the filler is
		 * replaced, or the evidence is gone.
		 *
		 * @return the warning, or {@code null} when the far corner holds real content
		 */
		private static String paintCoverageWarning(ImageData data) {
			int fillerPixel = pixelOf(data.palette, FILLER);
			if (fillerPixel < 0 || data.width < 8 || data.height < 8) {
				return null;
			}
			int sampled = 0;
			int filler = 0;
			// the bottom right eighth: far enough from the middle that a real widget
			// tree covers it, small enough to stay cheap on a 5000 pixel wide capture
			for (int y = data.height * 7 / 8; y < data.height; y += 4) {
				for (int x = data.width * 7 / 8; x < data.width; x += 4) {
					sampled++;
					if (data.getPixel(x, y) == fillerPixel) {
						filler++;
					}
				}
			}
			if (sampled == 0 || filler < sampled) {
				return null;
			}
			return "The far corner of this capture is entirely unpainted while the answer claims the whole area, which means the widget print landed at a different scale than the image it was given: the picture holds the top left part of the widget, enlarged, and the rest is filler. The filler was NOT replaced here, so the image shows the problem rather than hiding it. Known to happen after the window has been resized; a restart of the IDE clears it."; //$NON-NLS-1$
		}

		private static final org.eclipse.swt.graphics.RGB FILLER = new org.eclipse.swt.graphics.RGB(255, 0, 255);

		/**
		 * Replaces the pixels the print left untouched with the widget's background
		 * colour, in the data already fetched at the capture zoom.
		 * <p>
		 * The magenta stays in the image while it is being judged: a uniform one means
		 * nothing painted at all. Only afterwards is it swapped out, so the picture a
		 * human reads is not screaming pink between the parts while the count still
		 * says exactly how much of it was never painted.
		 *
		 * @return {@code null} when either colour cannot be named in this palette,
		 *         which leaves the image as printed
		 */
		private static Unpainted replaceFiller(ImageData data, Control painted) {
			org.eclipse.swt.graphics.PaletteData palette = data.palette;
			int fillerPixel = pixelOf(palette, FILLER);
			if (fillerPixel < 0) {
				return null;
			}
			org.eclipse.swt.graphics.RGB background = painted.getBackground() == null ? null
					: painted.getBackground().getRGB();
			if (background == null) {
				background = painted.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND).getRGB();
			}
			int backgroundPixel = pixelOf(palette, background);
			if (backgroundPixel < 0 || backgroundPixel == fillerPixel) {
				return null;
			}
			int count = 0;
			int[] row = new int[data.width];
			for (int y = 0; y < data.height; y++) {
				data.getPixels(0, y, data.width, row, 0);
				boolean touched = false;
				for (int x = 0; x < data.width; x++) {
					if (row[x] == fillerPixel) {
						row[x] = backgroundPixel;
						count++;
						touched = true;
					}
				}
				if (touched) {
					data.setPixels(0, y, data.width, row, 0);
				}
			}
			return new Unpainted(count, data.width, data.height, background);
		}

		/** The palette value for a colour, or -1 when the palette cannot name it. */
		private static int pixelOf(org.eclipse.swt.graphics.PaletteData palette, org.eclipse.swt.graphics.RGB rgb) {
			if (palette.isDirect) {
				return palette.getPixel(rgb);
			}
			org.eclipse.swt.graphics.RGB[] colors = palette.colors;
			if (colors == null) {
				return -1;
			}
			for (int i = 0; i < colors.length; i++) {
				if (rgb.equals(colors[i])) {
					return i;
				}
			}
			return -1;
		}

		static Shell findShell(Display display, String spec) {
			return Shells.select(display, spec);
		}

		/** The monitor's zoom in percent, which is the resolution a widget paints at. */
		static int zoomOf(Control control) {
			return DeviceScale.zoomOf(control);
		}

		/**
		 * The part stack a control sits in, or the control itself when it is not in one.
		 * <p>
		 * A view's toolbar is rendered by the surrounding {@code CTabFolder} rather than
		 * by the part, so it appears in no part capture at all. Walking up to the folder
		 * is the only way to ask for a view together with its toolbar.
		 */
		static Control stackOf(Control control) {
			for (Control candidate = control; candidate != null; candidate = candidate.getParent()) {
				if (candidate instanceof org.eclipse.swt.custom.CTabFolder) {
					return candidate;
				}
			}
			return control;
		}

		/** The composite an e4 part renders into, or {@code null} when it has none yet. */
		static Control controlOf(IWorkbenchPart part) {
			Object widget = part.getSite().getService(org.eclipse.e4.ui.model.application.ui.basic.MPart.class);
			if (widget instanceof org.eclipse.e4.ui.model.application.ui.basic.MPart model
					&& model.getWidget() instanceof Composite composite) {
				return composite;
			}
			return null;
		}

		/**
		 * Why a part could not be captured, naming the parts that share the id when
		 * several do: the one that is on screen is then addressable by its title.
		 */
		static String noPartReason(String partId) {
			String reason = "No part '%s', or it is not visible. A part behind another tab is not rendered at all; pass activate to bring it forward." //$NON-NLS-1$
					.formatted(partId);
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			IWorkbenchPage page = window == null ? null : window.getActivePage();
			String ambiguity = Parts.ambiguityNote(Parts.find(page, partId));
			return ambiguity == null ? reason : reason + " " + ambiguity; //$NON-NLS-1$
		}

		static Control findPart(String partId, boolean activate) {
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			IWorkbenchPage page = window == null ? null : window.getActivePage();
			if (page == null) {
				return null;
			}
			IWorkbenchPartReference reference = Parts.reference(page, partId);
			if (reference == null) {
				return null;
			}
			IWorkbenchPart part = reference.getPart(true);
			if (part == null) {
				return null;
			}
			if (!page.isPartVisible(part)) {
				if (!activate) {
					return null;
				}
				page.activate(part);
			}
			return controlOf(part);
		}

		private static JsonObject failure(String reason) {
			return new JsonObject().put("captured", false).put("reason", reason); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}
}
