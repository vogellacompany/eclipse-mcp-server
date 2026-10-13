package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Widget;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** Drags with a real mouse button from one widget to another, through optional waypoints. */
public final class DragTool implements IMcpTool {

	private static final int MAX_WAYPOINTS = 20;

	private static final int MAX_DWELL_MILLIS = 10_000;

	/** The first steps after the press, small enough that GTK and SWT see a gradual move past their thresholds. */
	private static final int[] FIRST_STEPS = { 1, 2, 3 };

	private static final long STEP_MILLIS = 15;

	/** How long the pointer rests on 'from' before the press. */
	private static final long PRESS_DELAY_MILLIS = 300;

	/** Small moves around each point once it is reached, ending on it. */
	private static final int[] WIGGLE = { 2, -2, 2, 0 };

	/** How long after the release the drop is given to run before the answer is read. */
	private static final long AFTER_RELEASE_MILLIS = 300;

	private static final ReentrantLock GESTURE = new ReentrantLock();

	/** When this tool last released, so the next press is not taken for the second half of a double click. */
	private static volatile long lastRelease;

	@Override
	public String getName() {
		return "eclipse_drag"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Drags with a real mouse button: moves the pointer onto 'from', presses, moves in small interpolated steps through the optional 'via' waypoints to 'to', and releases there, through the X server's XTest extension on GTK, or Display.post elsewhere, so the toolkit's own drag detection and drag and drop run exactly as for a person. CHANGES WHAT THE IDE DOES, which is whatever the drop does, and MOVES THE MOUSE POINTER, which stays where it was released. Each point is addressed like eclipse_click: part or shell plus a path from eclipse_get_widget_tree, including row paths such as 0/r3 and 0/r1/r0, with optional x and y inside the widget, or displayX and displayY. On arriving at each point the pointer wiggles by two pixels, since GTK starts the drag partway along the line and a drop target only reacts to motion after that. 'to', or the first waypoint, is resolved before the press, and every later one only when the pointer heads for it, because hovering over a collapsed tree row during a drag expands it after about a second and moves every row below; dwellMillis on a waypoint pauses there so drop feedback shows in a screencast. Before pressing, the pointer is read back over 'from' and nothing is pressed when it did not arrive or something covers the widget; a button the X server still holds down from an earlier gesture is released first, and a press is delayed until the double click time has passed since this tool's last release. Once pressed, the button is always released, also when a later waypoint fails to resolve, in which case it is released where the pointer is and the answer says so. dragDetected reports whether SWT raised DragDetect, which only a control with a DragSource or a DragDetect listener does; onTarget says whether the pointer was inside the bounds of 'to', the row itself for a row path, at release. A gesture that would outlast the call is cut short and released where it got to, and a second drag is refused while one runs, since there is one pointer. The drop is dispatched after the release, so verify it with eclipse_screenshot or eclipse_get_widget_tree. Like eclipse_click it needs an X11 display without a compositor, such as Xvfb, and it refuses on the UI thread, as inside an atomic eclipse_run_script, because the drag needs the event loop running between its events."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		String point = """
				{"type":"object","properties":{
				  "part":           {"type":"string","description":"Part id the path is rooted in. Use eclipse_list_ui_targets."},
				  "shellTitle":     {"type":"string","description":"Shell to root the path in, by title substring; omit both for the active shell."},
				  "shell":          {"type":"string","description":"Shell independent of title: 'popup', an index from eclipse_list_ui_targets, or its bounds. Wins over shellTitle."},
				  "includeToolbar": {"type":"boolean","default":false,"description":"Root the path in the surrounding part stack."},
				  "path":           {"type":"string","description":"Widget path from eclipse_get_widget_tree, such as 0/1 or 0/r2/r0. Omit for the root itself."},
				  "x":              {"type":"integer","minimum":0,"description":"Horizontal offset inside the widget. Defaults to its centre."},
				  "y":              {"type":"integer","minimum":0,"description":"Vertical offset inside the widget. Defaults to its centre."},
				  "displayX":       {"type":"integer","description":"Absolute screen x, as boundsInDisplay reports it. Use with displayY instead of a widget."},
				  "displayY":       {"type":"integer","description":"Absolute screen y. Use with displayX instead of a widget."},
				  "dwellMillis":    {"type":"integer","minimum":0,"maximum":10000,"description":"Pause after arriving here, with the button still held. Ignored on 'from'."}
				},"additionalProperties":false}"""; //$NON-NLS-1$
		return """
				{
				  "type": "object",
				  "properties": {
				    "from":         %s,
				    "to":           %s,
				    "via":          {"type":"array","maxItems":20,"items":%s,"description":"Waypoints between from and to, in order."},
				    "button":       {"type":"string","enum":["left","middle","right"],"default":"left"},
				    "holdMillis":   {"type":"integer","minimum":0,"maximum":400,"default":200,"description":"Pause between the press and the first move. SWT on X11 gives up detecting a drag 500 ms after the press."},
				    "stepPixels":   {"type":"integer","minimum":1,"maximum":100,"default":8,"description":"Distance of each interpolated move after the first few small ones."}
				  },
				  "required": ["from", "to"],
				  "additionalProperties": false
				}""".formatted(point, point, point); //$NON-NLS-1$
	}

	/** One point of the gesture as the caller addressed it. */
	record Target(String part, String shell, boolean includeToolbar, String path, Integer x, Integer y, Point display,
			int dwellMillis) {

		/** Parses one point, or throws with the reason it is not one. */
		static Target of(String name, Object value) {
			if (!(value instanceof Map<?, ?> map)) {
				throw new IllegalArgumentException("'%s' must be an object.".formatted(name)); //$NON-NLS-1$
			}
			@SuppressWarnings("unchecked")
			ToolArguments args = ToolArguments.of((Map<String, Object>) map);
			String part = args.getString("part"); //$NON-NLS-1$
			String shell = Shells.spec(args);
			String path = args.getString("path"); //$NON-NLS-1$
			boolean absolute = args.has("displayX") || args.has("displayY"); //$NON-NLS-1$ //$NON-NLS-2$
			if (absolute && (!args.has("displayX") || !args.has("displayY"))) { //$NON-NLS-1$ //$NON-NLS-2$
				throw new IllegalArgumentException("Give both 'displayX' and 'displayY' in '%s'.".formatted(name)); //$NON-NLS-1$
			}
			if (absolute && (part != null || shell != null || path != null || args.has("x") || args.has("y"))) { //$NON-NLS-1$ //$NON-NLS-2$
				throw new IllegalArgumentException(
						"Give '%s' either a widget (part, shell, path, x, y) or displayX and displayY, not both." //$NON-NLS-1$
								.formatted(name));
			}
			return new Target(part, shell, args.getBoolean("includeToolbar", false), path, //$NON-NLS-1$
					args.has("x") ? Integer.valueOf(args.getInt("x", 0, 0, 100_000)) : null, //$NON-NLS-1$ //$NON-NLS-2$
					args.has("y") ? Integer.valueOf(args.getInt("y", 0, 0, 100_000)) : null, //$NON-NLS-1$ //$NON-NLS-2$
					absolute ? new Point(args.getInt("displayX", 0, -100_000, 100_000), //$NON-NLS-1$
							args.getInt("displayY", 0, -100_000, 100_000)) : null, //$NON-NLS-1$
					args.getInt("dwellMillis", 0, 0, MAX_DWELL_MILLIS)); //$NON-NLS-1$
		}
	}

	/** Where a target is on screen and the control that owns it, or why it is nowhere. */
	record Resolved(Point point, Control owner, Rectangle bounds, String failure) {
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		int button = switch (args.getString("button", "left").toLowerCase(Locale.ROOT)) { //$NON-NLS-1$ //$NON-NLS-2$
		case "left" -> 1; //$NON-NLS-1$
		case "middle" -> 2; //$NON-NLS-1$
		case "right" -> 3; //$NON-NLS-1$
		default -> -1;
		};
		if (button < 0) {
			return McpToolResult.error("Unknown button '%s'; use left, middle or right.".formatted(args.getString("button"))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		List<Target> targets = new ArrayList<>();
		try {
			targets.add(Target.of("from", arguments == null ? null : arguments.get("from"))); //$NON-NLS-1$ //$NON-NLS-2$
			Object viaArgument = arguments.get("via"); //$NON-NLS-1$
			if (viaArgument != null && !(viaArgument instanceof List)) {
				return McpToolResult.error("'via' must be an array of points."); //$NON-NLS-1$
			}
			if (viaArgument instanceof List<?> via) {
				if (via.size() > MAX_WAYPOINTS) {
					return McpToolResult.error("At most %d waypoints in 'via'.".formatted(Integer.valueOf(MAX_WAYPOINTS))); //$NON-NLS-1$
				}
				for (int i = 0; i < via.size(); i++) {
					targets.add(Target.of("via[" + i + "]", via.get(i))); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
			targets.add(Target.of("to", arguments.get("to"))); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (IllegalArgumentException e) {
			return McpToolResult.error(e.getMessage());
		}
		int holdMillis = args.getInt("holdMillis", 200, 0, 400); //$NON-NLS-1$
		int stepPixels = args.getInt("stepPixels", 8, 1, 100); //$NON-NLS-1$
		long dwells = targets.stream().skip(1).mapToLong(Target::dwellMillis).sum();
		long budget = CallBudget.maxWaitSeconds() * 800L;
		long pauses = holdMillis + dwells + PRESS_DELAY_MILLIS + AFTER_RELEASE_MILLIS;
		if (pauses > budget) {
			return McpToolResult.error("The pauses add up to %d ms, more than the %d ms this call can take; shorten dwellMillis." //$NON-NLS-1$
					.formatted(Long.valueOf(pauses), Long.valueOf(budget)));
		}
		if (UiThread.onUiThread()) {
			return McpToolResult.error("eclipse_drag cannot run on the UI thread, as inside an atomic eclipse_run_script: the drag needs the event loop running between its events."); //$NON-NLS-1$
		}
		if (!GESTURE.tryLock()) {
			return McpToolResult.error("Another eclipse_drag is still running, and there is only one pointer; retry once it has returned."); //$NON-NLS-1$
		}
		try {
			return drag(targets, button, holdMillis, stepPixels, System.currentTimeMillis() + budget, monitor);
		} finally {
			GESTURE.unlock();
		}
	}

	private static McpToolResult drag(List<Target> targets, int button, int holdMillis, int stepPixels, long deadline,
			IProgressMonitor monitor) {
		JsonObject result = new JsonObject();
		boolean xtest = XTestInput.unavailableReason() == null;
		result.put("method", xtest ? "xtest" : "displayPost"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (xtest && Boolean.TRUE.equals(XTestInput.isDown(button))) {
			result.put("releasedStuckButton", Boolean.valueOf(XTestInput.button(button, false) == null)); //$NON-NLS-1$
		}
		AtomicInteger detected = new AtomicInteger();
		AtomicReference<String> detectedOn = new AtomicReference<>();
		Listener filter = event -> {
			detected.incrementAndGet();
			detectedOn.compareAndSet(null, event.widget == null ? null : event.widget.getClass().getSimpleName());
		};
		AtomicReference<Point> pressAt = new AtomicReference<>();
		JsonObject from = new JsonObject();
		UiThread.Outcome start = UiThread.run(10, () -> {
			Resolved resolved = resolve(targets.get(0));
			if (resolved.failure() != null) {
				return from.put("reason", "'from': " + resolved.failure()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			Display display = Workbenches.display();
			WidgetTools.Click.Warp warp = WidgetTools.Click.warp(display, resolved.point(), from, "nothing was pressed", //$NON-NLS-1$
					"dragging"); //$NON-NLS-1$
			if (warp.failure() != null) {
				return from.put("reason", warp.failure()); //$NON-NLS-1$
			}
			Control under = warp.under();
			if (under == null || resolved.owner() != null && !WidgetTools.Click.isInside(under, resolved.owner())) {
				return from.put("reason", under == null //$NON-NLS-1$
						? "'from' is not over an SWT control of this IDE, so nothing was pressed." //$NON-NLS-1$
						: "A %s covers 'from', so nothing was pressed.".formatted(under.getClass().getSimpleName())); //$NON-NLS-1$
			}
			display.addFilter(SWT.DragDetect, filter);
			pressAt.set(resolved.point());
			return from;
		});
		result.put("from", from); //$NON-NLS-1$
		if (start.error() != null) {
			return start.errorResult();
		}
		if (pressAt.get() == null) {
			return McpToolResult.of(result.put("dragged", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "Nothing was pressed, see 'from'.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		int zoom = DeviceScale.screenZoom();
		JsonArray waypoints = new JsonArray();
		Point current = pressAt.get();
		String failure = null;
		try {
			if (xtest) {
				// GTK has seen no motion from a warp, and a drag source needs some before the press
				String moved = XTestInput.move(DeviceScale.pixels(current.x, zoom), DeviceScale.pixels(current.y, zoom));
				if (moved != null) {
					return McpToolResult.of(result.put("dragged", Boolean.FALSE) //$NON-NLS-1$
							.put("reason", "The pointer could not be moved: " + moved).toString()); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
			// SWT's X11 drag detection holds the UI thread for up to 500 ms after the press, so the first
			// leg must not need it
			Resolved firstLeg = resolveOnUiThread(targets.get(1));
			if (firstLeg.failure() != null) {
				return McpToolResult.of(result.put("dragged", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "Nothing was pressed, since %s: %s".formatted( //$NON-NLS-1$ //$NON-NLS-2$
								targets.size() == 2 ? "'to'" : "'via[0]'", firstLeg.failure())) //$NON-NLS-1$ //$NON-NLS-2$
						.toString());
			}
			pause(Math.max(PRESS_DELAY_MILLIS, doubleClickMillis() + 50 - (System.currentTimeMillis() - lastRelease)), monitor);
			String pressFailure = xtest ? XTestInput.button(button, true) : postButton(SWT.MouseDown, button);
			if (pressFailure != null) {
				return McpToolResult.of(result.put("dragged", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "The press failed: " + pressFailure).toString()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			try {
				pause(holdMillis, monitor);
				for (int i = 1; i < targets.size(); i++) {
					Target target = targets.get(i);
					boolean last = i == targets.size() - 1;
					String name = last ? "to" : "via[" + (i - 1) + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					// later points are resolved only now, since hovering may have expanded a row and moved them
					Resolved where = i == 1 ? firstLeg : resolveOnUiThread(target);
					if (where.failure() != null) {
						failure = name + ": " + where.failure(); //$NON-NLS-1$
						break;
					}
					failure = moveTo(current, where.point(), stepPixels, i == 1, xtest, zoom, deadline, monitor);
					if (failure != null) {
						failure = name + ": " + failure; //$NON-NLS-1$
						break;
					}
					current = where.point();
					waypoints.add(new JsonObject().put("name", name).put("point", current.x + "," + current.y)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					// a drop target needs a dragOver round trip before the release
					pause(Math.min(last ? Math.max(150, target.dwellMillis()) : target.dwellMillis(),
							deadline - System.currentTimeMillis()), monitor);
					if (last) {
						Rectangle bounds = where.bounds();
						UiThread.run(5, () -> {
							// the cursor control reads null while GTK holds the drag grab, so compare bounds
							Display display = Workbenches.display();
							Point pointer = display.getCursorLocation();
							result.put("pointer", pointer.x + "," + pointer.y); //$NON-NLS-1$ //$NON-NLS-2$
							if (bounds != null) {
								result.put("onTarget", Boolean.valueOf(bounds.contains(pointer))); //$NON-NLS-1$
							}
							return result;
						});
					}
				}
			} finally {
				String releaseFailure = xtest ? XTestInput.button(button, false) : postButton(SWT.MouseUp, button);
				lastRelease = System.currentTimeMillis();
				result.put("releasedAt", current.x + "," + current.y); //$NON-NLS-1$ //$NON-NLS-2$
				if (releaseFailure != null) {
					result.put("releaseFailure", releaseFailure); //$NON-NLS-1$
				}
			}
			pause(AFTER_RELEASE_MILLIS);
		} finally {
			UiThread.run(5, () -> {
				Workbenches.display().removeFilter(SWT.DragDetect, filter);
				return new JsonObject();
			});
		}
		result.put("dragged", Boolean.valueOf(failure == null)) //$NON-NLS-1$
				.put("button", button == 1 ? "left" : button == 2 ? "middle" : "right") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				.put("waypoints", waypoints) //$NON-NLS-1$
				.put("dragDetected", Boolean.valueOf(detected.get() > 0)) //$NON-NLS-1$
				.put("dragDetectedOn", detectedOn.get()); //$NON-NLS-1$
		if (failure != null) {
			result.put("reason", "The drag stopped at " + failure //$NON-NLS-1$
					+ ". The button was released where the pointer was, which may have dropped there."); //$NON-NLS-1$
		} else {
			result.put("note", "The drop is dispatched after the release. Verify it with eclipse_screenshot or eclipse_get_widget_tree."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return McpToolResult.of(result.toString());
	}

	private static Resolved resolveOnUiThread(Target target) {
		AtomicReference<Resolved> resolved = new AtomicReference<>();
		UiThread.Outcome outcome = UiThread.run(10, () -> {
			resolved.set(resolve(target));
			return new JsonObject();
		});
		if (outcome.error() != null) {
			return new Resolved(null, null, null, outcome.error());
		}
		return resolved.get() == null ? new Resolved(null, null, null, "not resolved") : resolved.get(); //$NON-NLS-1$
	}

	/** Resolves a target to a display point, on the UI thread. */
	static Resolved resolve(Target target) {
		if (target.display() != null) {
			return new Resolved(target.display(), null, null, null);
		}
		Control root = WidgetTools.rootOf(target.part(), target.shell(), target.includeToolbar());
		if (root == null) {
			return new Resolved(null, null, null, "No such part or shell, or the part is not open. Use eclipse_list_ui_targets."); //$NON-NLS-1$
		}
		Widget widget = WidgetTools.resolve(root, target.path());
		if (widget == null) {
			return new Resolved(null, null, null, "The path '%s' does not resolve under this root.".formatted(target.path())); //$NON-NLS-1$
		}
		Control owner = widget instanceof Control control ? control : WidgetTools.parentOf(widget);
		Rectangle own = widget instanceof Control control ? new Rectangle(0, 0, control.getSize().x, control.getSize().y)
				: WidgetTools.rectangleOf(widget);
		if (owner == null || own == null) {
			return new Resolved(null, null, null, "A %s has no bounds.".formatted(widget.getClass().getSimpleName())); //$NON-NLS-1$
		}
		if (!owner.isVisible() || own.width <= 0 || own.height <= 0) {
			return new Resolved(null, null, null, "The %s is not showing.".formatted(widget.getClass().getSimpleName())); //$NON-NLS-1$
		}
		int dx = target.x() == null ? own.width / 2 : target.x().intValue();
		int dy = target.y() == null ? own.height / 2 : target.y().intValue();
		if (dx >= own.width || dy >= own.height) {
			return new Resolved(null, null, null, "The offset %d,%d lies outside the widget, which is %dx%d." //$NON-NLS-1$
					.formatted(Integer.valueOf(dx), Integer.valueOf(dy), Integer.valueOf(own.width), Integer.valueOf(own.height)));
		}
		if (!(widget instanceof Control) && !new Rectangle(0, 0, owner.getSize().x, owner.getSize().y).contains(own.x + dx, own.y + dy)) {
			return new Resolved(null, null, null, "The %s is scrolled out of view; bring it in with eclipse_scroll or eclipse_set_selection first." //$NON-NLS-1$
					.formatted(widget.getClass().getSimpleName()));
		}
		Rectangle onScreen = owner.getDisplay().map(owner, null, own);
		return new Resolved(new Point(onScreen.x + dx, onScreen.y + dy), owner, onScreen, null);
	}

	/** The points between two, a few small steps first when {@code gentleStart}, then {@code step} apart, ending on {@code to}. */
	public static List<Point> interpolate(Point from, Point to, int step, boolean gentleStart) {
		List<Point> points = new ArrayList<>();
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double length = Math.hypot(dx, dy);
		double travelled = 0;
		if (gentleStart) {
			for (int small : FIRST_STEPS) {
				if (travelled + small >= length) {
					break;
				}
				travelled += small;
				points.add(at(from, dx, dy, travelled / length));
			}
		}
		while (travelled + step < length) {
			travelled += step;
			points.add(at(from, dx, dy, travelled / length));
		}
		points.add(new Point(to.x, to.y));
		return points;
	}

	private static Point at(Point from, double dx, double dy, double fraction) {
		return new Point((int) Math.round(from.x + dx * fraction), (int) Math.round(from.y + dy * fraction));
	}

	/**
	 * Moves the pointer along an interpolated line. On X11 the moves go through XTest, since GTK starts no drag
	 * from the pointer warps SWT posts.
	 */
	private static String moveTo(Point from, Point to, int step, boolean gentleStart, boolean xtest, int zoom,
			long deadline, IProgressMonitor monitor) {
		for (Point point : interpolate(from, to, step, gentleStart)) {
			if (System.currentTimeMillis() > deadline || monitor != null && monitor.isCanceled()) {
				return "the call ran out of time before reaching %d,%d; raise stepPixels or shorten dwellMillis" //$NON-NLS-1$
						.formatted(Integer.valueOf(point.x), Integer.valueOf(point.y));
			}
			String failure = xtest ? XTestInput.move(DeviceScale.pixels(point.x, zoom), DeviceScale.pixels(point.y, zoom))
					: post(SWT.MouseMove, 0, point);
			if (failure != null) {
				return failure;
			}
			pause(STEP_MILLIS);
		}
		// GTK starts its drag partway along the line, and a drop target only answers motion after that
		for (int dx : WIGGLE) {
			String failure = xtest ? XTestInput.move(DeviceScale.pixels(to.x + dx, zoom), DeviceScale.pixels(to.y, zoom))
					: post(SWT.MouseMove, 0, new Point(to.x + dx, to.y));
			if (failure != null) {
				return failure;
			}
			pause(50);
		}
		return null;
	}

	private static String postButton(int type, int button) {
		return post(type, button, null);
	}

	/** Posts one mouse event on the UI thread, {@code null} when the display took it. */
	private static String post(int type, int button, Point point) {
		AtomicReference<Boolean> posted = new AtomicReference<>();
		UiThread.Outcome outcome = UiThread.run(5, () -> {
			Event event = new Event();
			event.type = type;
			event.button = button;
			if (point != null) {
				event.x = point.x;
				event.y = point.y;
			}
			posted.set(Boolean.valueOf(Workbenches.display().post(event)));
			return new JsonObject();
		});
		if (outcome.error() != null) {
			return outcome.error();
		}
		return Boolean.TRUE.equals(posted.get()) ? null : "Display.post refused the %s event".formatted( //$NON-NLS-1$
				type == SWT.MouseMove ? "move" : "button"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static long doubleClickMillis() {
		AtomicInteger millis = new AtomicInteger(500);
		UiThread.run(5, () -> {
			millis.set(Workbenches.display().getDoubleClickTime());
			return new JsonObject();
		});
		return millis.get();
	}

	/** Sleeps, returning early once the call is cancelled. */
	private static void pause(long millis, IProgressMonitor monitor) {
		long end = System.currentTimeMillis() + millis;
		for (long left = millis; left > 0 && (monitor == null || !monitor.isCanceled()); left = end - System.currentTimeMillis()) {
			pause(Math.min(left, 50));
		}
	}

	private static void pause(long millis) {
		if (millis <= 0) {
			return;
		}
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
