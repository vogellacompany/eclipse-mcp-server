package com.vogella.eclipse.mcp.web.internal;

import java.io.IOException;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Describes the open web page as an outline of roles, names and states, with a ref for every element.
 */
public final class WebSnapshotTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 500;

	@Override
	public String getName() {
		return "eclipse_web_snapshot"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reads the web page eclipse_web_open opened as an indented outline of what a user sees: one line per element with its role (button, link, textbox, heading, checkbox, combobox, list, ...), its accessible name in quotes, its state ([checked], [disabled], [expanded=...], [level=2]), a field's current value after a colon, a link's href after ->, and the visible text between them. Every element line carries [ref=eN]; pass that ref to eclipse_web_click, eclipse_web_type or eclipse_web_screenshot. An element keeps its ref across snapshots of the same document, a snapshot of part of the page keeps the refs of the rest, and a ref is never reused for another element, not even after a navigation, so an action on an element that went away is refused rather than landing elsewhere. Take a snapshot again after the page changed. Hidden elements are left out, the contents of iframes too. Changes nothing. Cheaper and more exact than a screenshot for finding what to act on; use the screenshot to check layout."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "selector":   {"type":"string","description":"A CSS selector of the part of the page to describe, e.g. 'main' or '#results'. Default: the whole body."},
				    "maxResults": {"type":"integer","default":500,"minimum":1,"maximum":5000,"description":"The most lines to answer with; total and truncated say how many there were."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	/** How many elements the page has, which bounds the refs one snapshot can hand out, shadow roots included. */
	private static long elementCount(WebBrowser browser) throws IOException {
		Object count = browser.evaluate(
				"(() => { let n = 0; const walk = root => { for (const el of root.querySelectorAll('*')) { n++; if (el.shadowRoot) walk(el.shadowRoot); } }; walk(document); return n; })()"); //$NON-NLS-1$
		// some to spare for what the page renders between this count and the snapshot; a snapshot that answers
		// reports how far it really went, so the margin only matters for one that never does
		if (count instanceof Number number) {
			return number.longValue() + 200;
		}
		throw new IOException("The page did not count its elements, so no refs were handed out."); //$NON-NLS-1$
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
		int maxResults = args.getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 5000); //$NON-NLS-1$
		try {
			Map<String, Object> result = WebPage
					.parse(browser.evaluate(PageScripts.snapshot(args.getString("selector"), maxResults, WebBrowser.reserveRefs(elementCount(browser))))); //$NON-NLS-1$
			if (result.get("nextRef") instanceof Number next) { //$NON-NLS-1$
				WebBrowser.refsUsedBelow(next.longValue());
			}
			JsonObject answer = browser.describe();
			answer.put("total", result.get("total")); //$NON-NLS-1$ //$NON-NLS-2$
			answer.put("truncated", result.get("truncated")); //$NON-NLS-1$ //$NON-NLS-2$
			WebPage.addDialogs(browser, answer);
			answer.put("snapshot", result.get("snapshot")); //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.of(answer.toString());
		} catch (IOException e) {
			return WebPage.failure(e);
		}
	}
}
