package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the DPI and scaling state of the running IDE.
 */
public final class DisplayInfoTool implements IMcpTool {

	private static final long UI_TIMEOUT_SECONDS = 10;

	/** The environment variables that decide GTK's own scaling, before SWT sees it. */
	private static final String[] SCALING_ENVIRONMENT = { "GDK_SCALE", "GDK_DPI_SCALE", "GDK_BACKEND", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"WAYLAND_DISPLAY", "XDG_SESSION_TYPE", "DISPLAY" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	/** The properties that override what the window system reported. */
	private static final String[] SCALING_PROPERTIES = { "swt.autoScale", "swt.autoScale.method", //$NON-NLS-1$ //$NON-NLS-2$
			"swt.autoScale.updateOnRuntime" }; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_get_display_info"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports the DPI and scaling state of the running IDE: the process-wide device zoom, the zoom the window system reported before any override, whether swt.autoScale was set at all and to what, the display DPI, every monitor with its bounds, client area, zoom and whether it is primary, the SWT platform and version, the GTK version, and the values of GDK_SCALE, GDK_DPI_SCALE, GDK_BACKEND, WAYLAND_DISPLAY, XDG_SESSION_TYPE and DISPLAY as this process actually sees them. Read-only. THIS IS THE ONLY WAY TO ASSERT THAT A SCALING VARIANT TOOK EFFECT: the zoom field of eclipse_screenshot is capturedPixels divided by areaInPoints, which on GTK stays 100 even when the device zoom is 200, because SWT points map to device pixels there and what changes is how much logical content fits rather than the size of the capture. A visual regression run that checks the capture's zoom therefore cannot tell a variant that applied from one that was silently ignored, and compares against the right baseline while proving nothing. deviceZoom is the number that moves. customAutoScale separates 'the flag was never set' from 'the flag was set and had no effect', which the effective value alone cannot say, since an explicit setting and a default of the same number read identically. The monitor list matters as much as the zoom: on a headless Xvfb run it is the only way to tell a virtual screen that came up at the wrong geometry from a scaling flag that was ignored. Note that GDK_SCALE is what actually scales the layout on GTK, while swt.autoScale alone rescales images and pins deviceZoom to its value, which then hides whatever the display underneath is doing."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {},
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (!Workbenches.running()) {
			return McpToolResult.error("There is no running workbench."); //$NON-NLS-1$
		}
		Display display = Workbenches.display();
		return UiThread.call(UI_TIMEOUT_SECONDS, () -> describe(display));
	}

	private static JsonObject describe(Display display) {
		JsonObject json = new JsonObject();
		json.put("deviceZoom", DisplayScaling.deviceZoom()) //$NON-NLS-1$
				.put("nativeDeviceZoom", DisplayScaling.nativeDeviceZoom()) //$NON-NLS-1$
				.put("effectiveAutoScaleValue", DisplayScaling.effectiveAutoScaleValue()) //$NON-NLS-1$
				.put("customAutoScale", DisplayScaling.customAutoScale()); //$NON-NLS-1$
		Point dpi = display.getDPI();
		json.put("dpi", new JsonObject().put("x", Integer.valueOf(dpi.x)).put("y", Integer.valueOf(dpi.y))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		json.put("monitors", monitors(display)); //$NON-NLS-1$
		json.put("platform", SWT.getPlatform()) //$NON-NLS-1$
				.put("swtVersion", Integer.valueOf(SWT.getVersion())) //$NON-NLS-1$
				.put("gtkVersion", DisplayScaling.gtkVersion()); //$NON-NLS-1$
		JsonObject environment = new JsonObject();
		for (String name : SCALING_ENVIRONMENT) {
			environment.put(name, System.getenv(name));
		}
		json.put("environment", environment); //$NON-NLS-1$
		JsonObject properties = new JsonObject();
		for (String name : SCALING_PROPERTIES) {
			properties.put(name, System.getProperty(name));
		}
		json.put("systemProperties", properties); //$NON-NLS-1$
		addNotes(json);
		return json;
	}

	private static JsonArray monitors(Display display) {
		JsonArray array = new JsonArray();
		Monitor primary = display.getPrimaryMonitor();
		Monitor[] all = display.getMonitors();
		for (int i = 0; i < all.length; i++) {
			Monitor candidate = all[i];
			array.add(new JsonObject().put("index", Integer.valueOf(i)) //$NON-NLS-1$
					.put("bounds", Overlays.describe(candidate.getBounds())) //$NON-NLS-1$
					.put("clientArea", Overlays.describe(candidate.getClientArea())) //$NON-NLS-1$
					.put("zoom", Integer.valueOf(candidate.getZoom())) //$NON-NLS-1$
					.put("primary", Boolean.valueOf(candidate.equals(primary)))); //$NON-NLS-1$
		}
		return array;
	}

	/**
	 * Says what the numbers mean where reading them wrongly is the usual mistake.
	 * <p>
	 * A scaling override that was set and had no effect looks exactly like one
	 * that worked, unless something points at the pair of numbers that disagree.
	 */
	private static void addNotes(JsonObject json) {
		Integer device = DisplayScaling.deviceZoom();
		Integer nativeZoom = DisplayScaling.nativeDeviceZoom();
		Boolean custom = DisplayScaling.customAutoScale();
		if (device == null) {
			json.put("scalingNote", //$NON-NLS-1$
					"SWT's internal DPIUtil could not be reached on this IDE, so deviceZoom is unknown and only the monitor zooms and the environment below are evidence about scaling."); //$NON-NLS-1$
			return;
		}
		if (Boolean.TRUE.equals(custom) && nativeZoom != null && !device.equals(nativeZoom)) {
			json.put("scalingNote", //$NON-NLS-1$
					"swt.autoScale is set and deviceZoom (%d) differs from what the window system reported (%d), so the override is in force and is pinning the zoom. Anything the display underneath does is hidden while it is set." //$NON-NLS-1$
							.formatted(device, nativeZoom));
			return;
		}
		if (Boolean.FALSE.equals(custom)) {
			json.put("scalingNote", //$NON-NLS-1$
					"swt.autoScale was not set, so deviceZoom (%d) is what the window system reported. On GTK this is what GDK_SCALE moves; swt.autoScale on its own rescales images without changing the layout." //$NON-NLS-1$
							.formatted(device));
		}
	}
}
