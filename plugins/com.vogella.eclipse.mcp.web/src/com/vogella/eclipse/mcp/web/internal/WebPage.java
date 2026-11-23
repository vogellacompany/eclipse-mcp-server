package com.vogella.eclipse.mcp.web.internal;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.Json;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * What the web tools share: finding the open page, addressing an element, waiting for the page to settle and the
 * common part of every answer.
 */
final class WebPage {

	/** How long nothing may be loading before the page counts as settled. */
	static final long QUIET_MILLIS = 500;

	static final String NO_PAGE = "No web page is open. Call eclipse_web_open with a url first."; //$NON-NLS-1$

	/** The schema members for addressing one element, shared by every tool that acts on one. */
	static final String TARGET_PROPERTIES = """
			"ref":      {"type":"string","description":"The element's ref from the last eclipse_web_snapshot, e.g. 'e12'. Preferred: it names exactly what the snapshot showed."},
			"selector": {"type":"string","description":"A CSS selector instead of ref, e.g. '#login' or 'form button[type=submit]'. The first match is used and 'matches' reports how many there were."}"""; //$NON-NLS-1$

	private WebPage() {
	}

	/** The location of an element, or the error to answer with. */
	record Target(double x, double y, double width, double height, double scrollX, double scrollY, String tag,
			int matches, String error) {
	}

	/** Parses the JSON a page script answered with. */
	@SuppressWarnings("unchecked")
	static Map<String, Object> parse(Object scriptResult) throws IOException {
		if (scriptResult instanceof String text && Json.parse(text) instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		throw new IOException("The page script answered " + scriptResult + " instead of JSON."); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Locates the element named by {@code ref} or {@code selector} and scrolls it into view. Refuses an element that is
	 * covered by another one, which a click would hit instead, unless {@code force} is set.
	 */
	static Target locate(WebBrowser browser, ToolArguments args, boolean force) throws IOException {
		String ref = args.getString("ref"); //$NON-NLS-1$
		String selector = args.getString("selector"); //$NON-NLS-1$
		if (ref == null && selector == null) {
			return failed("Pass ref, from eclipse_web_snapshot, or a CSS selector."); //$NON-NLS-1$
		}
		Map<String, Object> found = parse(browser.evaluate(PageScripts.locate(ref, ref == null ? selector : null)));
		if (found.get("error") instanceof String error) { //$NON-NLS-1$
			return failed(error);
		}
		double width = number(found.get("width")); //$NON-NLS-1$
		double height = number(found.get("height")); //$NON-NLS-1$
		String tag = String.valueOf(found.get("tag")); //$NON-NLS-1$
		if (width == 0 && height == 0) {
			return failed("The " + tag + " has no size on the page, so it cannot be clicked or seen; it may be collapsed or off screen."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (!force && found.get("coveredBy") instanceof String covered) { //$NON-NLS-1$
			return failed("The " + tag + " is covered by " + covered //$NON-NLS-1$ //$NON-NLS-2$
					+ ", which would receive the click instead, often a dialog, banner or overlay. Deal with that first, or pass force to click through to it anyway."); //$NON-NLS-1$
		}
		return new Target(number(found.get("x")), number(found.get("y")), width, height, number(found.get("scrollX")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				number(found.get("scrollY")), tag, (int) number(found.get("matches")), null); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static Target failed(String error) {
		return new Target(0, 0, 0, 0, 0, 0, null, 0, error);
	}

	static double number(Object value) {
		return value instanceof Number number ? number.doubleValue() : 0;
	}

	/** The settle timeout in milliseconds: the requested seconds capped by what is left of the call. */
	static long settleMillis(ToolArguments args, int defaultSeconds) {
		int requested = args.getInt("timeoutSeconds", defaultSeconds, 0, 120); //$NON-NLS-1$
		return TimeUnit.SECONDS.toMillis(CallBudget.boundedWaitSeconds(requested));
	}

	/**
	 * Waits for the page to settle and answers the page's address and title, whether it settled, and the dialogs it
	 * raised, which every acting tool reports.
	 */
	static JsonObject settleAndDescribe(WebBrowser browser, long timeoutMillis) throws IOException {
		return settleAndDescribe(browser, timeoutMillis, null);
	}

	/** As {@link #settleAndDescribe(WebBrowser, long)}, and whether the address changed from {@code urlBefore}. */
	static JsonObject settleAndDescribe(WebBrowser browser, long timeoutMillis, String urlBefore) throws IOException {
		boolean settled = browser.settle(timeoutMillis, QUIET_MILLIS);
		JsonObject answer = new JsonObject();
		String url = browser.location(answer);
		if (urlBefore != null) {
			answer.put("navigated", Boolean.valueOf(!urlBefore.equals(url))); //$NON-NLS-1$
		}
		answer.put("settled", Boolean.valueOf(settled)); //$NON-NLS-1$
		if (!settled) {
			answer.put("pendingRequests", Integer.valueOf(browser.pendingRequests())); //$NON-NLS-1$
			answer.put("settleNote", //$NON-NLS-1$
					"The page was still loading or had requests in flight when the wait ended. A page that polls never settles; take a snapshot to see where it is."); //$NON-NLS-1$
		}
		addDialogs(browser, answer);
		return answer;
	}

	static void addDialogs(WebBrowser browser, JsonObject answer) {
		List<JsonObject> dialogs = browser.takeDialogs();
		if (!dialogs.isEmpty()) {
			JsonArray list = new JsonArray();
			dialogs.forEach(list::add);
			answer.put("dialogs", list); //$NON-NLS-1$
		}
	}

	static McpToolResult failure(IOException e) {
		return McpToolResult.error(e.getMessage());
	}
}
