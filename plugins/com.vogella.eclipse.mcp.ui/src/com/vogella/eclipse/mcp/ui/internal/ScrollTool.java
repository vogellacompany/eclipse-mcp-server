package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Scrollable;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** Turns the mouse wheel over a widget and reports its scroll bars before and after. */
public final class ScrollTool implements IMcpTool {

	private static final long SETTLE_MILLIS = 1500;

	private static final long POLL_MILLIS = 50;

	private static final int MOVED_STABLE_READS = 3;

	// a wheel turn the toolkit ignores is given this many unchanged reads before giving up
	private static final int UNMOVED_STABLE_READS = 10;

	@Override
	public String getName() {
		return "eclipse_scroll"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Scrolls a widget with the real mouse wheel: moves the pointer onto it and turns the wheel by 'notches' (negative scrolls up, or left with horizontal) through the X server's XTest extension on GTK, or Display.post elsewhere, so the toolkit scrolls by its own amount, which for a GTK Tree or Table is not a whole number of rows. The selection and focus stay as they are, unlike eclipse_set_selection with reveal or eclipse_press_key. CHANGES THE SCROLL POSITION and MOVES THE MOUSE POINTER, which stays where it was put. Address the widget the way eclipse_get_widget_tree reports it, part or shell plus path; the wheel turns over its centre, or at x and y inside it. The answer reports the vertical and horizontal scroll bar of the nearest scrollable widget, plus topIndex or topItem for a Table or Tree, before and after the scroll, and 'changed' says whether they moved; 'changed' compares the scroll bar selections only, and notchesSent says how many notches the window system took; after waits up to 1.5 seconds for an animated scroll to stop. The scroll bars are those of the control under the pointer or its nearest parent with a bar in that direction, and nothing is scrolled when there is none. Like eclipse_click it reads the pointer back first and scrolls nothing when the pointer did not arrive or another control covers the point, and it needs an X11 display without a compositor, such as Xvfb."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "part":           {"type":"string","description":"Part id the path is rooted in. Use eclipse_list_ui_targets."},
				    "shellTitle":     {"type":"string","description":"Shell to root the path in, by title substring; omit both for the active shell."},
				    "shell":          {"type":"string","description":"Shell independent of title: 'popup', an index from eclipse_list_ui_targets, or its bounds. Wins over shellTitle."},
				    "includeToolbar": {"type":"boolean","default":false,"description":"Root the path in the surrounding part stack."},
				    "path":           {"type":"string","description":"Widget path from eclipse_get_widget_tree, such as 0/1. Omit for the root itself."},
				    "notches":        {"type":"integer","minimum":-50,"maximum":50,"description":"Wheel notches; positive scrolls down (right), negative up (left)."},
				    "horizontal":     {"type":"boolean","default":false,"description":"Turn the horizontal wheel instead."},
				    "x":              {"type":"integer","minimum":0,"description":"Horizontal offset inside the widget. Defaults to its centre."},
				    "y":              {"type":"integer","minimum":0,"description":"Vertical offset inside the widget. Defaults to its centre."}
				  },
				  "required": ["notches"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		int notches = args.getInt("notches", 0, -50, 50); //$NON-NLS-1$
		if (notches == 0) {
			return McpToolResult.error("'notches' must not be 0."); //$NON-NLS-1$
		}
		boolean horizontal = args.getBoolean("horizontal", false); //$NON-NLS-1$
		String partId = args.getString("part"); //$NON-NLS-1$
		String shellSpec = Shells.spec(args);
		String path = args.getString("path"); //$NON-NLS-1$
		Integer offsetX = args.has("x") ? Integer.valueOf(args.getInt("x", 0, 0, 100_000)) : null; //$NON-NLS-1$ //$NON-NLS-2$
		Integer offsetY = args.has("y") ? Integer.valueOf(args.getInt("y", 0, 0, 100_000)) : null; //$NON-NLS-1$ //$NON-NLS-2$
		boolean includeToolbar = args.getBoolean("includeToolbar", false); //$NON-NLS-1$
		// set only once the wheel was turned
		AtomicReference<Scrollable> scrolled = new AtomicReference<>();
		AtomicReference<Position> before = new AtomicReference<>();
		UiThread.Outcome posted = UiThread.run(10, () -> {
			Control root = WidgetTools.rootOf(partId, shellSpec, includeToolbar);
			if (root == null) {
				return refusal("No such part or shell, or the part is not open. Use eclipse_list_ui_targets."); //$NON-NLS-1$
			}
			Widget target = WidgetTools.resolve(root, path);
			if (target == null) {
				return refusal("The path '%s' does not resolve under this root.".formatted(path)); //$NON-NLS-1$
			}
			Control owner = target instanceof Control control ? control : WidgetTools.parentOf(target);
			Rectangle own = target instanceof Control control
					? new Rectangle(0, 0, control.getSize().x, control.getSize().y)
					: WidgetTools.rectangleOf(target);
			if (owner == null || own == null) {
				return refusal("A %s has no bounds to scroll over.".formatted(target.getClass().getSimpleName())); //$NON-NLS-1$
			}
			if (!owner.isVisible() || own.width <= 0 || own.height <= 0) {
				return refusal("The %s is not showing, so there is nothing on screen to scroll." //$NON-NLS-1$
						.formatted(target.getClass().getSimpleName()));
			}
			int dx = offsetX == null ? own.width / 2 : offsetX.intValue();
			int dy = offsetY == null ? own.height / 2 : offsetY.intValue();
			if (dx >= own.width || dy >= own.height) {
				return refusal("The offset %d,%d lies outside the widget, which is %dx%d.".formatted( //$NON-NLS-1$
						Integer.valueOf(dx), Integer.valueOf(dy), Integer.valueOf(own.width), Integer.valueOf(own.height)));
			}
			Display display = owner.getDisplay();
			Rectangle onScreen = display.map(owner, null, own);
			Point point = new Point(onScreen.x + dx, onScreen.y + dy);
			JsonObject result = new JsonObject().put("target", target.getClass().getSimpleName()) //$NON-NLS-1$
					.put("boundsInDisplay", Overlays.describe(onScreen)); //$NON-NLS-1$
			WidgetTools.Click.Warp warp = WidgetTools.Click.warp(display, point, result, "nothing was scrolled", //$NON-NLS-1$
					"scrolling"); //$NON-NLS-1$
			if (warp.failure() != null) {
				return result.put("scrolled", Boolean.FALSE).put("reason", warp.failure()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			Control under = warp.under();
			if (under == null || !WidgetTools.Click.isInside(under, owner)) {
				return result.put("scrolled", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", under == null //$NON-NLS-1$
								? "The point is not over an SWT control of this IDE, so nothing was scrolled." //$NON-NLS-1$
								: "A %s covers the widget at that point, so nothing was scrolled." //$NON-NLS-1$
										.formatted(under.getClass().getSimpleName()));
			}
			// the wheel goes to the control under the pointer, which may be a child of the target
			Scrollable scrollable = scrollableOf(under, horizontal);
			if (scrollable == null) {
				return result.put("scrolled", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "Neither the %s under the pointer nor any of its parents has a %s scroll bar, so nothing was scrolled." //$NON-NLS-1$
								.formatted(under.getClass().getSimpleName(), horizontal ? "horizontal" : "vertical")); //$NON-NLS-1$ //$NON-NLS-2$
			}
			before.set(position(scrollable));
			result.put("scrolledWidget", scrollable.getClass().getSimpleName()) //$NON-NLS-1$
					.put("before", state(scrollable)); //$NON-NLS-1$
			String failure;
			int sent;
			if (XTestInput.unavailableReason() == null) {
				result.put("method", "xtest"); //$NON-NLS-1$ //$NON-NLS-2$
				// X11 wheel buttons: 4 up, 5 down, 6 left, 7 right
				int button = horizontal ? (notches < 0 ? 6 : 7) : (notches < 0 ? 4 : 5);
				XTestInput.Pressed pressed = XTestInput.press(button, Math.abs(notches));
				failure = pressed.failure();
				sent = pressed.sent();
			} else {
				result.put("method", "displayPost"); //$NON-NLS-1$ //$NON-NLS-2$
				Event wheel = new Event();
				wheel.type = horizontal ? SWT.MouseHorizontalWheel : SWT.MouseWheel;
				wheel.detail = SWT.SCROLL_LINE;
				// SWT counts upwards as positive
				wheel.count = -notches;
				boolean accepted = display.post(wheel);
				failure = accepted ? null : "Display.post refused the wheel event."; //$NON-NLS-1$
				sent = accepted ? Math.abs(notches) : 0;
			}
			result.put("scrolled", Boolean.valueOf(sent > 0)) //$NON-NLS-1$
					.put("notchesSent", Integer.valueOf(sent)); //$NON-NLS-1$
			if (failure != null) {
				result.put("reason", failure); //$NON-NLS-1$
			}
			if (sent > 0) {
				scrolled.set(scrollable);
			}
			return result;
		});
		if (posted.error() != null) {
			return McpToolResult.error(posted.error());
		}
		JsonObject result = posted.value();
		if (scrolled.get() == null) {
			return McpToolResult.of(result.toString());
		}
		if (UiThread.onUiThread()) {
			// waiting here would block the very dispatch that scrolls, as in an atomic eclipse_run_script
			return McpToolResult.of(result.put("afterNote", //$NON-NLS-1$
					"Called on the UI thread, so the scroll is dispatched after this call returns and 'after' is not read. Read it with a following call.") //$NON-NLS-1$
					.toString());
		}
		return McpToolResult.of(settle(result, scrolled.get(), before.get()).toString());
	}

	/** Polls the scroll bars until they moved and stopped, or gives up once they stayed put. */
	private static JsonObject settle(JsonObject result, Scrollable scrollable, Position before) {
		Position last = before;
		int stableReads = 0;
		long deadline = System.currentTimeMillis() + SETTLE_MILLIS;
		JsonObject after = null;
		while (System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			AtomicReference<Position> position = new AtomicReference<>();
			UiThread.Outcome read = UiThread.run(1, () -> {
				position.set(position(scrollable));
				return state(scrollable);
			});
			if (read.error() != null) {
				result.put("afterError", read.error()); //$NON-NLS-1$
				break;
			}
			if (position.get() == null) {
				result.put("widgetDisposed", Boolean.TRUE); //$NON-NLS-1$
				break;
			}
			after = read.value();
			stableReads = position.get().equals(last) ? stableReads + 1 : 0;
			last = position.get();
			boolean moved = !last.equals(before);
			if (moved ? stableReads >= MOVED_STABLE_READS : stableReads >= UNMOVED_STABLE_READS) {
				break;
			}
		}
		// after is the last successful read, null when there was none
		return result.put("after", after) //$NON-NLS-1$
				.put("changed", Boolean.valueOf(!last.equals(before))); //$NON-NLS-1$
	}

	/** The control or its nearest ancestor with a scroll bar in the asked direction, {@code null} when there is none. */
	public static Scrollable scrollableOf(Control control, boolean horizontal) {
		for (Control current = control; current != null; current = current.getParent()) {
			if (current instanceof Scrollable scrollable
					&& (horizontal ? scrollable.getHorizontalBar() : scrollable.getVerticalBar()) != null) {
				return scrollable;
			}
		}
		return null;
	}

	/** Both scroll bar selections, which is what {@code changed} compares; a missing bar is -1. */
	private record Position(int vertical, int horizontal) {
	}

	/** The current position, {@code null} once disposed. */
	private static Position position(Scrollable scrollable) {
		if (scrollable.isDisposed()) {
			return null;
		}
		ScrollBar vertical = scrollable.getVerticalBar();
		ScrollBar horizontal = scrollable.getHorizontalBar();
		return new Position(vertical == null ? -1 : vertical.getSelection(),
				horizontal == null ? -1 : horizontal.getSelection());
	}

	public static JsonObject state(Scrollable scrollable) {
		if (scrollable == null || scrollable.isDisposed()) {
			return null;
		}
		JsonObject state = new JsonObject().put("vertical", bar(scrollable.getVerticalBar())) //$NON-NLS-1$
				.put("horizontal", bar(scrollable.getHorizontalBar())); //$NON-NLS-1$
		if (scrollable instanceof Table table) {
			state.put("topIndex", Integer.valueOf(table.getTopIndex())) //$NON-NLS-1$
					.put("selectionCount", Integer.valueOf(table.getSelectionCount())); //$NON-NLS-1$
		} else if (scrollable instanceof Tree tree) {
			TreeItem top = tree.getTopItem();
			state.put("topItem", top == null ? null : top.getText()) //$NON-NLS-1$
					.put("selectionCount", Integer.valueOf(tree.getSelectionCount())); //$NON-NLS-1$
		}
		return state;
	}

	private static JsonObject bar(ScrollBar bar) {
		if (bar == null) {
			return null;
		}
		return new JsonObject().put("selection", Integer.valueOf(bar.getSelection())) //$NON-NLS-1$
				.put("maximum", Integer.valueOf(bar.getMaximum())) //$NON-NLS-1$
				.put("thumb", Integer.valueOf(bar.getThumb())) //$NON-NLS-1$
				.put("increment", Integer.valueOf(bar.getIncrement())) //$NON-NLS-1$
				.put("visible", Boolean.valueOf(bar.isVisible())); //$NON-NLS-1$
	}

	private static JsonObject refusal(String reason) {
		return new JsonObject().put("scrolled", Boolean.FALSE).put("reason", reason); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
