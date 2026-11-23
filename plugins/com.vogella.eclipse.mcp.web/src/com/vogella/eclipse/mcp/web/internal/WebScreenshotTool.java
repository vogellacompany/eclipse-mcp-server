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

	@Override
	public String getName() {
		return "eclipse_web_screenshot"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Captures the web page eclipse_web_open opened as a PNG image: the visible viewport by default, the whole scrollable page with fullPage true, or one element when ref or selector names it (it is scrolled into view first). Answers the url, title, and the size in CSS pixels, with the picture attached. Changes nothing but the scroll position for an element capture. Use eclipse_web_snapshot to find what to act on; use this to check how the page looks."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				%s,
				    "fullPage": {"type":"boolean","default":false,"description":"Capture the whole page rather than the viewport, up to 16384 pixels high."}
				  },
				  "additionalProperties": false
				}""".formatted(WebPage.TARGET_PROPERTIES.indent(4).stripTrailing()); //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		WebBrowser browser = WebBrowser.current();
		if (browser == null) {
			return McpToolResult.error(WebPage.NO_PAGE);
		}
		try {
			JsonObject params = new JsonObject().put("format", "png"); //$NON-NLS-1$ //$NON-NLS-2$
			JsonObject answer = browser.describe();
			if (args.has("ref") || args.has("selector")) { //$NON-NLS-1$ //$NON-NLS-2$
				WebPage.Target target = WebPage.locate(browser, args, true);
				if (target.error() != null) {
					return McpToolResult.error(target.error());
				}
				double left = target.x() - target.width() / 2 + target.scrollX();
				double top = target.y() - target.height() / 2 + target.scrollY();
				params.put("clip", clip(left, top, target.width(), target.height())); //$NON-NLS-1$
				params.put("captureBeyondViewport", Boolean.TRUE); //$NON-NLS-1$
				answer.put("element", target.tag()); //$NON-NLS-1$
				size(answer, target.width(), target.height());
			} else if (args.getBoolean("fullPage", false)) { //$NON-NLS-1$
				Map<String, Object> metrics = browser.send("Page.getLayoutMetrics", null); //$NON-NLS-1$
				Map<?, ?> content = metrics.get("cssContentSize") instanceof Map<?, ?> map ? map : Map.of(); //$NON-NLS-1$
				double width = WebPage.number(content.get("width")); //$NON-NLS-1$
				double height = WebPage.number(content.get("height")); //$NON-NLS-1$
				double captured = Math.min(height, MAX_FULL_PAGE_HEIGHT);
				params.put("clip", clip(0, 0, width, captured)); //$NON-NLS-1$
				params.put("captureBeyondViewport", Boolean.TRUE); //$NON-NLS-1$
				size(answer, width, captured);
				if (captured < height) {
					answer.put("cutAtHeight", Double.valueOf(captured)).put("pageHeight", Double.valueOf(height)); //$NON-NLS-1$ //$NON-NLS-2$
				}
			} else {
				Object viewport = browser.evaluate("JSON.stringify({width: innerWidth, height: innerHeight})"); //$NON-NLS-1$
				Map<String, Object> view = WebPage.parse(viewport);
				size(answer, WebPage.number(view.get("width")), WebPage.number(view.get("height"))); //$NON-NLS-1$ //$NON-NLS-2$
			}
			Map<String, Object> shot = browser.send("Page.captureScreenshot", params); //$NON-NLS-1$
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
