package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Changes the size of what is on screen, so that the states only a size change
 * produces can be reached.
 */
public final class LayoutTools {

	private LayoutTools() {
	}

	/** Moves and resizes a shell, or maximizes it. */
	public static final class SetShellBounds implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_set_shell_bounds"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Moves, resizes, maximizes or restores a window. CHANGES WHAT THE USER SEES, in the same way eclipse_set_ide_visibility does. Its purpose is the states that only exist at a particular size: tab overflow and its chevron, text truncation and ellipsis, scrollbars, sash and border rendering at the edges between stacks, and reflowing form layouts. None of those can be reached by any other tool here, and each is drawn by a different set of CSS selectors. The answer reports previousBounds and previousMaximized, so a caller can put the window back exactly as it was, which is what makes this safe to use on somebody's running IDE. Give x, y, width and height in points, any subset, or maximized on its own. monitor moves the window onto another monitor, by its index in eclipse_get_display_info, which is how a scale factor change is reached on a mixed-DPI setup; on Wayland, where a client cannot position its window, it goes through a fullscreen on that monitor and back, and the answer reports placement and onRequestedMonitor, read there from the window's scale factor since a Wayland client cannot see its own position."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "shellTitle": {"type":"string","description":"Title of the shell, or a substring. Omit for the active one."},
					    "monitor":    {"type":"integer","minimum":0,"description":"Index of the monitor to move the window onto, as eclipse_get_display_info lists them. x and y then count from that monitor's client area, and the window is centred there when they are omitted. A maximized window stays maximized unless maximized is given."},
					    "x":          {"type":"integer","description":"Left edge in points, not pixels, on the display the shell is on."},
					    "y":          {"type":"integer","description":"Top edge in points."},
					    "width":      {"type":"integer","minimum":100,"description":"Width in points, not pixels: on a scaled display the widget covers more pixels than this."},
					    "height":     {"type":"integer","minimum":100,"description":"Height in points."},
					    "maximized":  {"type":"boolean","description":"Maximize, or restore when false. Applied after any bounds, so passing both restores to the bounds given."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String shellTitle = args.getString("shellTitle"); //$NON-NLS-1$
			Integer x = optional(arguments, "x"); //$NON-NLS-1$
			Integer y = optional(arguments, "y"); //$NON-NLS-1$
			Integer width = optional(arguments, "width"); //$NON-NLS-1$
			Integer height = optional(arguments, "height"); //$NON-NLS-1$
			Integer monitorIndex = optional(arguments, "monitor"); //$NON-NLS-1$
			Boolean maximized = arguments != null && arguments.get("maximized") instanceof Boolean value ? value : null; //$NON-NLS-1$
			if (x == null && y == null && width == null && height == null && maximized == null
					&& monitorIndex == null) {
				return McpToolResult.error("Give at least one of monitor, x, y, width, height or maximized."); //$NON-NLS-1$
			}
			if (monitorIndex != null) {
				return moveToMonitor(shellTitle, monitorIndex.intValue(), x, y, width, height, maximized, monitor);
			}
			return UiThread.call(15, () -> apply(shellTitle, x, y, width, height, maximized));
		}

		private static McpToolResult moveToMonitor(String shellTitle, int index, Integer x, Integer y,
				Integer width, Integer height, Boolean maximized, IProgressMonitor monitor) {
			MonitorPlacement.Ui ui = step -> {
				UiThread.Outcome outcome = UiThread.run(5, () -> {
					step.run();
					return new JsonObject();
				});
				if (outcome.error() != null) {
					throw new IllegalStateException(outcome.error());
				}
			};
			try {
				Shell[] shell = new Shell[1];
				ui.run(() -> shell[0] = ScreenshotTools.Capture.findShell(Workbenches.display(), shellTitle));
				JsonObject result = shell[0] == null ? noShell(shellTitle)
						: MonitorPlacement.move(ui, shell[0], index, x, y, width, height, maximized, monitor);
				return McpToolResult.of(result.toString());
			} catch (IllegalStateException e) {
				return McpToolResult.error(e.getMessage());
			}
		}

		private static JsonObject noShell(String shellTitle) {
			return new JsonObject().put("changed", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", shellTitle == null //$NON-NLS-1$
							? "This IDE has no window to resize." //$NON-NLS-1$
							: "No shell matching '%s'.".formatted(shellTitle)); //$NON-NLS-1$
		}

		private static JsonObject apply(String shellTitle, Integer x, Integer y, Integer width, Integer height,
				Boolean maximized) {
			Display display = Workbenches.display();
			Shell shell = ScreenshotTools.Capture.findShell(display, shellTitle);
			if (shell == null) {
				return noShell(shellTitle);
			}
			Rectangle before = shell.getBounds();
			boolean wasMaximized = shell.getMaximized();
			// bounds first, then the maximized flag, so that passing both means "restore
			// to this size" rather than the two fighting
			if (x != null || y != null || width != null || height != null) {
				if (wasMaximized) {
					shell.setMaximized(false);
				}
				shell.setBounds(x == null ? before.x : x.intValue(), y == null ? before.y : y.intValue(),
						width == null ? before.width : width.intValue(),
						height == null ? before.height : height.intValue());
			}
			if (maximized != null) {
				shell.setMaximized(maximized.booleanValue());
			}
			shell.layout(true, true);
			Rectangle after = shell.getBounds();
			return new JsonObject().put("changed", Boolean.TRUE) //$NON-NLS-1$
					.put("title", shell.getText()) //$NON-NLS-1$
					.put("previousBounds", Overlays.describe(before)) //$NON-NLS-1$
					.put("previousMaximized", Boolean.valueOf(wasMaximized)) //$NON-NLS-1$
					.put("bounds", Overlays.describe(after)) //$NON-NLS-1$
					.put("maximized", Boolean.valueOf(shell.getMaximized())) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"Pass previousBounds and previousMaximized back to put the window as it was."); //$NON-NLS-1$
		}

		private static Integer optional(Map<String, Object> arguments, String name) {
			if (arguments == null || !(arguments.get(name) instanceof Number number)) {
				return null;
			}
			return Integer.valueOf(number.intValue());
		}
	}

	/** Maximizes, minimizes, restores or activates a part. */
	public static final class SetPartState implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_set_part_state"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Maximizes, minimizes, restores or activates a part, by id, editors included. CHANGES WHAT THE USER SEES. Maximizing is the Ctrl+M behaviour and puts a part in a known large state, which also makes captures of it comparable between runs; minimizing moves it to the trim stack, which is rendered by a different set of CSS selectors again and is otherwise unreachable. Activating works for an editor without knowing its file path, which eclipse_open needs; note that it is not one of the three window states, so it answers with focusGiven and leaves state at whatever window state the part kept. The answer reports the previous state so it can be put back."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "part":  {"type":"string","description":"Part id, from eclipse_list_ui_targets. Several parts with one id: id@editor input path or title."},
					    "state": {"type":"string","enum":["maximized","minimized","restored","activated"],"description":"Omit to only activate. activated gives focus and brings the part forward; it is not a window state, so the answer reports focusGiven and 'state' stays the window state the part had."}
					  },
					  "required": ["part"],
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String partId = args.getString("part"); //$NON-NLS-1$
			if (partId == null || partId.isBlank()) {
				return McpToolResult.error("Give the 'part' id. Use eclipse_list_ui_targets."); //$NON-NLS-1$
			}
			String state = args.getString("state", "activated"); //$NON-NLS-1$ //$NON-NLS-2$
			return UiThread.call(15, () -> apply(partId, state));
		}

		private static JsonObject apply(String partId, String state) {
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			IWorkbenchPage page = window == null ? null : window.getActivePage();
			if (page == null) {
				return new JsonObject().put("changed", Boolean.FALSE).put("reason", "There is no active page."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			IWorkbenchPartReference reference;
			try {
				reference = PartAddress.find(page, partId);
			} catch (IllegalStateException e) {
				return new JsonObject().put("changed", Boolean.FALSE).put("reason", e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (reference == null) {
				return new JsonObject().put("changed", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "No open part '%s'. Use eclipse_list_ui_targets.".formatted(partId)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			String previous = nameOf(page.getPartState(reference));
			JsonObject result = new JsonObject().put("changed", Boolean.TRUE) //$NON-NLS-1$
					.put("part", partId) //$NON-NLS-1$
					.put("requested", state) //$NON-NLS-1$
					.put("previousState", previous); //$NON-NLS-1$
			if ("activated".equals(state)) { //$NON-NLS-1$
				// activating is not one of the three window states, so reporting only
				// 'state' back reads as if the request had been ignored
				IWorkbenchPart part = reference.getPart(true);
				if (part != null) {
					page.activate(part);
				}
				result.put("changed", Boolean.valueOf(part != null)) //$NON-NLS-1$
						.put("focusGiven", Boolean.valueOf(part != null)) //$NON-NLS-1$
						.put("state", nameOf(page.getPartState(reference))) //$NON-NLS-1$
						.put("note", part == null //$NON-NLS-1$
								? "The part could not be created, so nothing was focused. Its window state is unchanged." //$NON-NLS-1$
								: "activated is not a window state: the part was brought to the front and given focus, and 'state' is the window state it kept. Only maximized, minimized and restored are window states."); //$NON-NLS-1$
				return result;
			}
			page.setPartState(reference, stateOf(state));
			return result.put("state", nameOf(page.getPartState(reference))) //$NON-NLS-1$
					.put("note", "Pass previousState back to put it as it was."); //$NON-NLS-1$ //$NON-NLS-2$
		}

		private static int stateOf(String state) {
			return switch (state) {
			case "maximized" -> IWorkbenchPage.STATE_MAXIMIZED; //$NON-NLS-1$
			case "minimized" -> IWorkbenchPage.STATE_MINIMIZED; //$NON-NLS-1$
			default -> IWorkbenchPage.STATE_RESTORED;
			};
		}

		private static String nameOf(int state) {
			return switch (state) {
			case IWorkbenchPage.STATE_MAXIMIZED -> "maximized"; //$NON-NLS-1$
			case IWorkbenchPage.STATE_MINIMIZED -> "minimized"; //$NON-NLS-1$
			default -> "restored"; //$NON-NLS-1$
			};
		}
	}
}
