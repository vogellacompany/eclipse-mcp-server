package com.vogella.eclipse.mcp.web.internal;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Captures the open web page, the whole page or one element of it as a PNG.
 */
public final class WebScreenshotTool implements IMcpTool {

	/** Chromium refuses captures much taller than this, and a model gains nothing from them. */
	private static final double MAX_FULL_PAGE_HEIGHT = 16_384;

	/** The widest capture. */
	private static final double MAX_WIDTH = 8_192;

	/** The most pixels of one capture, so a page both wide and long cannot fill the IDE's heap with one image. */
	private static final double MAX_PIXELS = 8_192 * 2_048;

	/** The tallest capture at that width: the height limit, lowered for a wide capture to stay within the pixels. */
	private static double maxHeight(double width) {
		return Math.min(MAX_FULL_PAGE_HEIGHT, Math.floor(MAX_PIXELS / Math.max(1, width)));
	}

	@Override
	public String getName() {
		return "eclipse_web_screenshot"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Captures the web page eclipse_web_open opened as a PNG image: the visible viewport by default, the whole scrollable page with fullPage true, or one element when ref or selector names it (it is scrolled into view first, and fixed or sticky elements outside it, such as a site header, are hidden while it is captured so they cannot paint over it; hiddenOverlays says how many). Answers the url, title, and the size in CSS pixels, with the picture attached. Changes nothing but the scroll position for an element capture. Use eclipse_web_snapshot to find what to act on; use this to check how the page looks."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				%s,
				    "fullPage": {"type":"boolean","default":false,"description":"Capture the whole page rather than the viewport, up to 8192 pixels wide, 16384 high and 16.7 megapixels in all (about 13100 high at 1280 wide); cutAtHeight or cutAtWidth says where a larger page was cut."}
				  },
				  "additionalProperties": false
				}""".formatted(WebPage.TARGET_PROPERTIES.indent(4).stripTrailing()); //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		return WebPage.exclusive(() -> run(arguments));
	}

	private static McpToolResult run(Map<String, Object> arguments) {
		ToolArguments args = ToolArguments.of(arguments);
		WebBrowser browser = WebBrowser.current();
		if (browser == null) {
			return McpToolResult.error(WebPage.NO_PAGE);
		}
		try {
			JsonObject params = new JsonObject().put("format", "png"); //$NON-NLS-1$ //$NON-NLS-2$
			JsonObject answer = browser.describe();
			boolean element = args.has("ref") || args.has("selector"); //$NON-NLS-1$ //$NON-NLS-2$
			if (element) {
				WebPage.Target target = WebPage.locate(browser, args, true);
				if (target.error() != null) {
					return McpToolResult.error(target.error());
				}
				double left = target.x() - target.width() / 2 + target.scrollX();
				double top = target.y() - target.height() / 2 + target.scrollY();
				double wide = Math.min(target.width(), MAX_WIDTH);
				double captured = Math.min(target.height(), maxHeight(wide));
				params.put("clip", clip(left, top, wide, captured)); //$NON-NLS-1$
				params.put("captureBeyondViewport", Boolean.TRUE); //$NON-NLS-1$
				answer.put("element", target.tag()); //$NON-NLS-1$
				if (target.matches() > 1) {
					answer.put("matches", Integer.valueOf(target.matches())); //$NON-NLS-1$
				}
				size(answer, wide, captured);
				if (captured < target.height()) {
					answer.put("cutAtHeight", Double.valueOf(captured)).put("elementHeight", Double.valueOf(target.height())); //$NON-NLS-1$ //$NON-NLS-2$
				}
				if (wide < target.width()) {
					answer.put("cutAtWidth", Double.valueOf(wide)).put("elementWidth", Double.valueOf(target.width())); //$NON-NLS-1$ //$NON-NLS-2$
				}
			} else if (args.getBoolean("fullPage", false)) { //$NON-NLS-1$
				Map<String, Object> metrics = browser.send("Page.getLayoutMetrics", null); //$NON-NLS-1$
				// contentSize is the older name of the same value
				Map<?, ?> content = metrics.get("cssContentSize") instanceof Map<?, ?> map ? map //$NON-NLS-1$
						: metrics.get("contentSize") instanceof Map<?, ?> old ? old : Map.of(); //$NON-NLS-1$
				if (content.isEmpty()) {
					return McpToolResult.error("The browser did not report the size of the page, so a full-page capture is not possible; capture the viewport instead."); //$NON-NLS-1$
				}
				double width = WebPage.number(content.get("width")); //$NON-NLS-1$
				double height = WebPage.number(content.get("height")); //$NON-NLS-1$
				double wide = Math.min(width, MAX_WIDTH);
				double captured = Math.min(height, maxHeight(wide));
				params.put("clip", clip(0, 0, wide, captured)); //$NON-NLS-1$
				params.put("captureBeyondViewport", Boolean.TRUE); //$NON-NLS-1$
				size(answer, wide, captured);
				if (captured < height) {
					answer.put("cutAtHeight", Double.valueOf(captured)).put("pageHeight", Double.valueOf(height)); //$NON-NLS-1$ //$NON-NLS-2$
				}
				if (wide < width) {
					answer.put("cutAtWidth", Double.valueOf(wide)).put("pageWidth", Double.valueOf(width)); //$NON-NLS-1$ //$NON-NLS-2$
				}
			} else {
				Object viewport = browser.evaluate(
						"JSON.stringify({width: innerWidth, height: innerHeight, x: scrollX, y: scrollY})"); //$NON-NLS-1$
				Map<String, Object> view = WebPage.parse(viewport);
				double width = WebPage.number(view.get("width")); //$NON-NLS-1$
				double height = WebPage.number(view.get("height")); //$NON-NLS-1$
				// a clip at scale 1, so a window on a HiDPI display answers the CSS pixels the answer reports
				params.put("clip", clip(WebPage.number(view.get("x")), WebPage.number(view.get("y")), width, height)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				size(answer, width, height);
			}
			if (element) {
				answer.put("hiddenOverlays", Long.valueOf(Math.round(WebPage.number(browser.evaluate(PageScripts.HIDE_OVERLAYS))))); //$NON-NLS-1$
			}
			Map<String, Object> shot;
			try {
				shot = browser.send("Page.captureScreenshot", params); //$NON-NLS-1$
			} finally {
				if (element) {
					try {
						browser.evaluate(PageScripts.RESTORE_OVERLAYS);
					} catch (IOException e) {
						// the capture is still good; say the page may keep its overlays hidden rather than fail
						answer.put("overlaysRestored", Boolean.FALSE); //$NON-NLS-1$
					}
				}
			}
			if (!(shot.get("data") instanceof String data)) { //$NON-NLS-1$
				return McpToolResult.error("The browser answered no image."); //$NON-NLS-1$
			}
			WebPage.addDialogs(browser, answer);
			return McpToolResult.of(answer.toString()).withImage(Base64.getDecoder().decode(data), "image/png"); //$NON-NLS-1$
		} catch (IOException e) {
			return WebPage.failure(e);
		}
	}

	private static JsonObject clip(double x, double y, double width, double height) {
		return new JsonObject().put("x", Double.valueOf(x)).put("y", Double.valueOf(y)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("width", Double.valueOf(Math.max(1, width))).put("height", Double.valueOf(Math.max(1, height))) //$NON-NLS-1$ //$NON-NLS-2$
				.put("scale", Integer.valueOf(1)); //$NON-NLS-1$
	}

	private static void size(JsonObject answer, double width, double height) {
		answer.put("width", Long.valueOf(Math.round(width))).put("height", Long.valueOf(Math.round(height))); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
