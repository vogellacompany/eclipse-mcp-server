package com.vogella.eclipse.mcp.web.internal;

import java.io.IOException;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.Json;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Types text into a field of the open web page, or picks an option of a select.
 */
public final class WebTypeTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_web_type"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "TYPES text into a field of the web page eclipse_web_open opened: an input, a textarea or a contenteditable element, addressed by ref from eclipse_web_snapshot or by a CSS selector. Focuses it, replaces what it holds unless clear is false, and enters the text through the browser's input pipeline, so the page's input and change handlers and frameworks such as React or Angular see it as typed. For a select (a combobox in the snapshot) it picks the option whose label or value is the text and fires input and change. submit true presses Enter afterwards, which submits most forms. Then waits for the page to settle and answers the url and title, navigated, the field's value as it now stands (masked for a password) and any JavaScript dialog raised."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				%s,
				    "text":           {"type":"string","description":"What to type, or the label or value of the option to pick. Empty with clear true empties the field."},
				    "clear":          {"type":"boolean","default":true,"description":"Replace the field's content. false appends to it."},
				    "submit":         {"type":"boolean","default":false,"description":"Press Enter after typing."},
				    "acceptDialogs":  {"type":"boolean","default":false,"description":"Accept a confirm or prompt the submit raises instead of dismissing it."},
				    "timeoutSeconds": {"type":"integer","default":10,"minimum":0,"maximum":120,"description":"How long to wait for the page to settle afterwards."}
				  },
				  "required": ["text"],
				  "additionalProperties": false
				}""".formatted(WebPage.TARGET_PROPERTIES.indent(4).stripTrailing()); //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String text = args.getText("text"); //$NON-NLS-1$
		if (text == null) {
			return McpToolResult.error("text is required; pass an empty string with clear true to empty the field."); //$NON-NLS-1$
		}
		WebBrowser browser = WebBrowser.current();
		if (browser == null) {
			return McpToolResult.error(WebPage.NO_PAGE);
		}
		boolean clear = args.getBoolean("clear", true); //$NON-NLS-1$
		try {
			WebPage.Target target = WebPage.locate(browser, args, true);
			if (target.error() != null) {
				return McpToolResult.error(target.error());
			}
			String before = String.valueOf(browser.evaluate("location.href")); //$NON-NLS-1$
			Map<String, Object> focus = WebPage
					.parse(browser.evaluate(PageScripts.call(PageScripts.FOCUS_TARGET, String.valueOf(clear))));
			if (focus.get("error") instanceof String error) { //$NON-NLS-1$
				return McpToolResult.error(error);
			}
			browser.acceptConfirmations(args.getBoolean("acceptDialogs", false)); //$NON-NLS-1$
			try {
				JsonObject typed = new JsonObject();
				if (Boolean.TRUE.equals(focus.get("select"))) { //$NON-NLS-1$
					Map<String, Object> chosen = WebPage
							.parse(browser.evaluate(PageScripts.call(PageScripts.CHOOSE_OPTION, Json.write(text))));
					if (chosen.get("error") instanceof String error) { //$NON-NLS-1$
						return McpToolResult.error(error);
					}
					typed.put("chosen", chosen.get("chosen")).put("value", chosen.get("value")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				} else {
					if (text.isEmpty()) {
						if (clear && Boolean.TRUE.equals(focus.get("hadValue"))) { //$NON-NLS-1$
							UserInput.press(browser, "Backspace", 8, null); //$NON-NLS-1$
						}
					} else {
						UserInput.insertText(browser, text);
					}
				}
				if (args.getBoolean("submit", false)) { //$NON-NLS-1$
					UserInput.press(browser, "Enter", 13, "\r"); //$NON-NLS-1$ //$NON-NLS-2$
				}
				JsonObject answer = WebPage.settleAndDescribe(browser, WebPage.settleMillis(args, 10), before);
				answer.put("typedInto", target.tag()); //$NON-NLS-1$
				if (target.matches() > 1) {
					answer.put("matches", Integer.valueOf(target.matches())); //$NON-NLS-1$
				}
				if (typed.isEmpty()) {
					putValue(browser, answer);
				} else {
					answer.put("chosen", typed.remove("chosen")).put("value", typed.remove("value")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				}
				return McpToolResult.of(answer.toString());
			} finally {
				browser.acceptConfirmations(false);
			}
		} catch (IOException e) {
			return WebPage.failure(e);
		}
	}

	/** The field's value after typing, unless a submit took the page away from it. */
	private static void putValue(WebBrowser browser, JsonObject answer) {
		try {
			Object masked = browser.evaluate(
					"(() => { const el = window.__mcpTarget; if (!el || !el.isConnected) return null; const v = el.isContentEditable ? el.innerText : el.value; return el.type === 'password' ? '*'.repeat(v.length) : v; })()"); //$NON-NLS-1$
			if (masked != null) {
				answer.put("value", masked); //$NON-NLS-1$
			}
		} catch (IOException e) {
			// the field went away with a navigation, which the answer already reports
		}
	}
}
