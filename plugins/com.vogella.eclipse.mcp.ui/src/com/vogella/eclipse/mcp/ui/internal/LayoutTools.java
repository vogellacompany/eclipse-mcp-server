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
			return "Moves, resizes, maximizes or restores a window. CHANGES WHAT THE USER SEES, in the same way eclipse_set_ide_visibility does. Its purpose is the states that only exist at a particular size: tab overflow and its chevron, text truncation and ellipsis, scrollbars, sash and border rendering at the edges between stacks, and reflowing form layouts. None of those can be reached by any other tool here, and each is drawn by a different set of CSS selectors. The answer reports previousBounds and previousMaximized, so a caller can put the window back exactly as it was, which is what makes this safe to use on somebody's running IDE. Give x, y, width and height in points, any subset, or maximized on its own."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "shellTitle": {"type":"string","description":"Title of the shell, or a substring. Omit for the active one."},
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
			Boolean maximized = arguments != null && arguments.get("maximized") instanceof Boolean value ? value : null; //$NON-NLS-1$
			if (x == null && y == null && width == null && height == null && maximized == null) {
				return McpToolResult.error("Give at least one of x, y, width, height or maximized."); //$NON-NLS-1$
			}
			return UiThread.call(15, () -> apply(shellTitle, x, y, width, height, maximized));
		}

		private static JsonObject apply(String shellTitle, Integer x, Integer y, Integer width, Integer height,
				Boolean maximized) {
			Display display = PlatformUI.getWorkbench().getDisplay();
			Shell shell = ScreenshotTools.Capture.findShell(display, shellTitle);
			if (shell == null) {
				return new JsonObject().put("changed", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", shellTitle == null //$NON-NLS-1$
								? "This IDE has no window to resize." //$NON-NLS-1$
								: "No shell matching '%s'.".formatted(shellTitle)); //$NON-NLS-1$
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
					.put("previousBounds", describe(before)) //$NON-NLS-1$
					.put("previousMaximized", Boolean.valueOf(wasMaximized)) //$NON-NLS-1$
					.put("bounds", describe(after)) //$NON-NLS-1$
					.put("maximized", Boolean.valueOf(shell.getMaximized())) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"Pass previousBounds and previousMaximized back to put the window as it was."); //$NON-NLS-1$
		}

		private static String describe(Rectangle bounds) {
			return bounds.x + "," + bounds.y + " " + bounds.width + "x" + bounds.height; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
					    "part":  {"type":"string","description":"Part id, from eclipse_list_ui_targets. Several open parts share one id, every Java editor above all; the active or a visible one is chosen, and a part's TITLE from eclipse_list_ui_targets names a particular one."},
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
			Parts.Match match = Parts.find(page, partId);
			IWorkbenchPartReference reference = match == null ? null : match.reference();
			if (reference == null) {
				return new JsonObject().put("changed", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "No open part '%s'. Use eclipse_list_ui_targets.".formatted(partId)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			String previous = nameOf(page.getPartState(reference));
			JsonObject result = new JsonObject().put("changed", Boolean.TRUE) //$NON-NLS-1$
					.put("part", partId) //$NON-NLS-1$
					// which of the parts under that id this acted on: it used to take
					// the first and report the id back, so acting on the wrong editor
					// was indistinguishable from acting on the right one
					.put("partTitle", reference.getTitle()) //$NON-NLS-1$
					.put("requested", state) //$NON-NLS-1$
					.put("previousState", previous); //$NON-NLS-1$
			String ambiguity = Parts.ambiguityNote(match);
			if (ambiguity != null) {
				result.put("sharedId", ambiguity); //$NON-NLS-1$
			}
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
