package com.vogella.eclipse.mcp.web.internal;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Opens a web page in a browser this IDE drives, starting the browser on first use.
 */
public final class WebOpenTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_web_open"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Loads a URL in the web page this IDE drives and waits until it settled: loaded, and no request in flight for half a second. The first call STARTS A BROWSER: a Chrome, Chromium or Edge already installed on this machine (none is downloaded), headless unless headless is false, with a fresh temporary profile that is deleted when it closes, so no cookies or logins of the user's own browser are used. Later calls reuse that page, including a tab opened through connectUrl; passing a different headless or browserPath closes this IDE's own browser and starts another, and a connected tab is replaced only when browserPath is passed (or after eclipse_web_close). Pass connectUrl, e.g. http://localhost:9222, to open a new tab in a browser the user started with --remote-debugging-port instead, which then does use that browser's profile and is left running on close. Then use eclipse_web_snapshot to see the page, eclipse_web_click and eclipse_web_type to act on it, eclipse_web_screenshot to look at it and eclipse_web_close to end it. A 'Leave site?' prompt of the page being left is accepted. Answers the final url (after redirects), title, the HTTP status of the document and whether it settled. Works for any web app, for example one launched from this workspace at http://localhost:8080."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "url":            {"type":"string","description":"The address to load, e.g. 'http://localhost:8080/login'. http, https, file and about URLs; a bare host such as 'localhost:3000' gets http://."},
				    "headless":       {"type":"boolean","description":"false shows the browser window, so the user can watch. Applies when a browser is started, where it defaults to true; left out, a browser that is already open is kept as it is."},
				    "browserPath":    {"type":"string","description":"The browser executable to start, when the one found is not the one wanted. The system property com.vogella.eclipse.mcp.web.browser sets it for every call."},
				    "connectUrl":     {"type":"string","description":"The DevTools endpoint of a browser that is already running, e.g. 'http://localhost:9222'. A new tab is opened there instead of starting a browser."},
				    "timeoutSeconds": {"type":"integer","default":15,"minimum":0,"maximum":120,"description":"How long to wait for the page to settle. The answer comes back when the time is up either way, with settled false."}
				  },
				  "required": ["url"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public String getOutputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "url":                {"type":["string","null"]},
				    "title":              {"type":["string","null"]},
				    "animating":          {"type":"boolean"},
				    "pageGone":           {"type":["string","null"]},
				    "settled":            {"type":"boolean"},
				    "settleNote":         {"type":"string"},
				    "pendingRequests":    {"type":"integer"},
				    "waitNote":           {"type":"string"},
				    "dialogs":            {"type":"array","items":{"type":"object"}},
				    "downloads":          {"type":"array","items":{"type":"object"}},
				    "downloadsTotal":     {"type":"integer"},
				    "downloadsTruncated": {"type":"boolean"},
				    "navigationPending":  {"type":"boolean"},
				    "navigationNote":     {"type":"string"},
				    "status":             {"type":"integer"},
				    "browser":            {"type":"object","properties":{"mode":{"type":"string"},"headless":{"type":"boolean"},"endpoint":{"type":["string","null"]}}}
				  },
				  "required": ["settled"]
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		return WebPage.exclusive(() -> run(arguments));
	}

	private static McpToolResult run(Map<String, Object> arguments) {
		ToolArguments args = ToolArguments.of(arguments);
		String url = normalize(args.getString("url")); //$NON-NLS-1$
		if (url == null) {
			return McpToolResult.error("url must be an absolute http, https, file or about address, e.g. http://localhost:8080/."); //$NON-NLS-1$
		}
		Boolean headless = args.has("headless") ? Boolean.valueOf(args.getBoolean("headless", true)) : null; //$NON-NLS-1$ //$NON-NLS-2$
		try {
			String connect = args.getString("connectUrl"); //$NON-NLS-1$
			String connectUrl = connect == null ? null : endpoint(connect);
			if (connect != null && connectUrl == null) {
				return McpToolResult.error("connectUrl must be the http address of a DevTools endpoint, e.g. http://localhost:9222, not " //$NON-NLS-1$
						+ connect + "."); //$NON-NLS-1$
			}
			WebBrowser browser = WebBrowser.start(args.getString("browserPath"), headless, connectUrl); //$NON-NLS-1$
			long navigationStarted = System.nanoTime();
			String error = browser.navigate(url, WebPage.waitMillis(args, 15));
			if (WebBrowser.NOT_ANSWERED.equals(error)) {
				// no page script here: while a navigation waits for the server, the page answers none
				JsonObject answer = new JsonObject().put("url", url).put("settled", Boolean.FALSE) //$NON-NLS-1$ //$NON-NLS-2$
						.put("navigationPending", Boolean.TRUE); //$NON-NLS-1$
				WebPage.addDialogs(browser, answer);
				answer.put("navigationNote", //$NON-NLS-1$
						"The server has not answered " + url + " yet, so the browser still shows the previous page; a server stopped at a breakpoint looks like this. The page answers no other web tool until the server does, so open it again once the server is back."); //$NON-NLS-1$ //$NON-NLS-2$
				return McpToolResult.of(answer.toString());
			}
			if (error != null) {
				JsonObject answer = browser.describe();
				answer.put("error", error); //$NON-NLS-1$
				WebPage.addDialogs(browser, answer);
				return McpToolResult.error("The browser could not load " + url + ": " + error //$NON-NLS-1$ //$NON-NLS-2$
						+ ". Check that the server is running and the address is right. " + answer); //$NON-NLS-1$
			}
			// one timeoutSeconds for loading and settling, so a slow server does not get it twice
			int asked = args.getInt("timeoutSeconds", 15, 0, 120); //$NON-NLS-1$
			int spent = (int) java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - navigationStarted);
			Map<String, Object> rest = new java.util.HashMap<>(arguments);
			rest.put("timeoutSeconds", Integer.valueOf(asked == 0 ? 0 : Math.max(1, asked - spent))); //$NON-NLS-1$
			JsonObject answer = WebPage.settleAndDescribe(browser, ToolArguments.of(rest), 15, null);
			Integer status = browser.documentStatus();
			if (status != null) {
				answer.put("status", status); //$NON-NLS-1$
			}
			answer.put("browser", new JsonObject() //$NON-NLS-1$
					.put("mode", browser.launchedHere() ? "started" : "connected") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.put("headless", Boolean.valueOf(browser.isHeadless())) //$NON-NLS-1$
					.put("endpoint", browser.endpoint())); //$NON-NLS-1$
			return McpToolResult.of(answer.toString());
		} catch (IOException e) {
			return WebPage.failure(e);
		}
	}

	/** A DevTools endpoint as http://host:port, with http:// added to a bare host:port, or null when it is not one. */
	static String endpoint(String connect) {
		String candidate = connect.contains("://") ? connect : "http://" + connect; //$NON-NLS-1$ //$NON-NLS-2$
		try {
			URI uri = new URI(candidate);
			if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) { //$NON-NLS-1$
				return null;
			}
			return "http://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		} catch (URISyntaxException e) {
			return null;
		}
	}

	/** The address as given, with http:// added to a bare host, or null when it is not one the browser should load. */
	static String normalize(String url) {
		if (url == null) {
			return null;
		}
		if (!url.contains("://") && !url.startsWith("about:") && !url.startsWith("file:") && url.matches("[A-Za-z][A-Za-z0-9+.-]*:(?!\\d).*")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			// another scheme, such as mailto: or javascript:, rather than a host with a port such as localhost:8080
			return null;
		}
		String candidate = url.contains("://") || url.startsWith("about:") || url.startsWith("file:") ? url : "http://" + url; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		try {
			URI uri = new URI(candidate);
			String scheme = uri.getScheme();
			if (scheme == null) {
				return null;
			}
			return switch (scheme.toLowerCase(java.util.Locale.ROOT)) {
			case "http", "https" -> uri.getHost() == null ? null : candidate; //$NON-NLS-1$ //$NON-NLS-2$
			case "file", "about" -> candidate; //$NON-NLS-1$ //$NON-NLS-2$
			default -> null;
			};
		} catch (URISyntaxException e) {
			return null;
		}
	}
}
