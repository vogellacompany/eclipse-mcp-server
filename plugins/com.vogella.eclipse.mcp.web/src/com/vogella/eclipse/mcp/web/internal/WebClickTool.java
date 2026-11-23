package com.vogella.eclipse.mcp.web.internal;

import java.io.IOException;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Clicks an element of the open web page with real mouse events.
 */
public final class WebClickTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_web_click"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "CLICKS an element of the web page eclipse_web_open opened, as a user would: scrolls it into view and sends real mouse events to its centre, so the page's own handlers, links and form submits run. Address it by ref from eclipse_web_snapshot (preferred) or by a CSS selector. Refuses an element that another one covers at that point, such as a cookie banner or a modal, which would get the click instead; force sends it there anyway. Then waits for the page to settle and answers the url and title afterwards, navigated true when the click changed the address, and any JavaScript dialog the click raised: alerts and 'Leave site?' prompts are accepted, so a click that leaves a page with unsaved input discards it, and a confirm or prompt is dismissed unless acceptDialogs is true."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				%s,
				    "doubleClick":    {"type":"boolean","default":false},
				    "force":          {"type":"boolean","default":false,"description":"Send the click to the element's centre even when another element covers it there; the covering element then receives it, as it would for a user."},
				    "acceptDialogs":  {"type":"boolean","default":false,"description":"Accept a confirm or prompt the click raises, e.g. 'Really delete?', instead of dismissing it."},
				    "timeoutSeconds": {"type":"integer","default":10,"minimum":0,"maximum":120,"description":"How long to wait for the page to settle after the click."}
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
			WebPage.Target target = WebPage.locate(browser, args, args.getBoolean("force", false)); //$NON-NLS-1$
			if (target.error() != null) {
				return McpToolResult.error(target.error());
			}
			if (target.disabled()) {
				return McpToolResult.error("The " + target.tag() //$NON-NLS-1$
						+ " is disabled, so the browser would not deliver a click to it; the page usually enables it once a form is complete."); //$NON-NLS-1$
			}
			String before = String.valueOf(browser.evaluate("location.href")); //$NON-NLS-1$
			browser.acceptConfirmations(args.getBoolean("acceptDialogs", false)); //$NON-NLS-1$
			try {
				UserInput.click(browser, target.x(), target.y(), args.getBoolean("doubleClick", false) ? 2 : 1); //$NON-NLS-1$
				JsonObject answer = WebPage.settleAndDescribe(browser, args, 10, before);
				answer.put("clicked", target.tag()); //$NON-NLS-1$
				if (target.matches() > 1) {
					answer.put("matches", Integer.valueOf(target.matches())); //$NON-NLS-1$
				}
				return McpToolResult.of(answer.toString());
			} finally {
				browser.acceptConfirmations(false);
			}
		} catch (IOException e) {
			return WebPage.failure(e);
		}
	}
}
