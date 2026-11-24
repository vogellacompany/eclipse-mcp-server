package com.vogella.eclipse.mcp.web.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.json.Json;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The one browser page the web tools drive: a Chromium the IDE started, or a tab opened in a browser that already
 * listens for DevTools connections.
 * <p>
 * There is one page per IDE; the tools run one at a time through {@link WebPage#exclusive}, so two clients take turns
 * on it rather than acting on each other's element.
 */
final class WebBrowser {

	/** Network requests that never finish by design, so a page that holds one open can still settle. */
	private static final Set<String> STREAMING_TYPES = Set.of("WebSocket", "EventSource"); //$NON-NLS-1$ //$NON-NLS-2$

	private static final long COMMAND_MILLIS = 15_000;

	/** The most JavaScript dialogs, and downloads, kept for one answer. */
	private static final int MAX_REPORTED = 20;

	private static WebBrowser instance;

	/** Starts Chromium without its sandbox, for containers where it cannot set one up even for a normal user. */
	static final String NO_SANDBOX_PROPERTY = "com.vogella.eclipse.mcp.web.noSandbox"; //$NON-NLS-1$

	/** Survives a navigation and a restart of the browser, because a ref a client still holds must never name another element. */
	private static long nextRef = 1;

	private final Process process;
	private final Path executable;
	private final Path profile;
	private final String endpoint;
	private final String targetId;
	private final boolean headless;
	private final CdpConnection page;

	private final Object state = new Object();
	/** Requests in flight, by request id, with the loader id of the document that made them. */
	private final Map<String, String> inflight = new HashMap<>();
	private final List<JsonObject> dialogs = new ArrayList<>();
	private final List<JsonObject> downloads = new ArrayList<>();
	/** Dialogs answered beyond {@link #MAX_REPORTED} since the last report, guarded by {@code state}. */
	private int droppedDialogs;
	/** Downloads started beyond {@link #MAX_REPORTED} since the last report, guarded by {@code state}. */
	private int droppedDownloads;
	private String mainFrameId;
	private boolean loading;
	private Integer documentStatus;
	private boolean acceptConfirm;

	private Thread shutdownHook;

	private WebBrowser(Process process, Path executable, Path profile, String endpoint, String targetId,
			boolean headless, CdpConnection page) {
		this.process = process;
		this.executable = executable;
		this.profile = profile;
		this.endpoint = endpoint;
		this.targetId = targetId;
		this.headless = headless;
		this.page = page;
	}

	/** The page that is open, or {@code null} when none is or its browser went away. */
	static synchronized WebBrowser current() {
		if (instance != null && !instance.isAlive()) {
			instance.dispose();
			instance = null;
		}
		return instance;
	}

	/**
	 * The open page, or a new one: in a browser this IDE starts, or, with {@code connectUrl}, in a new tab of a browser
	 * already listening there. A different request than the open page was made with closes it first.
	 */
	static synchronized WebBrowser start(String browserPath, Boolean requestedHeadless, String connect)
			throws IOException {
		boolean headless = requestedHeadless == null || requestedHeadless.booleanValue();
		String connectUrl = connect != null && connect.endsWith("/") ? connect.substring(0, connect.length() - 1) //$NON-NLS-1$
				: connect;
		WebBrowser open = current();
		if (open != null) {
			boolean sameKind;
			if (connectUrl != null) {
				// localhost and 127.0.0.1 name the same browser; another spelling must not dispose the one just used
				sameKind = sameEndpoint(connectUrl, open.endpoint);
			} else if (open.process == null) {
				// a tab in the user's browser stays until a call names a browser of this IDE's own; headless does not
				// apply to the user's browser, so passing it, even as the default true, does not close their tab
				sameKind = browserPath == null;
			} else {
				sameKind = (requestedHeadless == null || open.headless == headless)
						&& (browserPath == null || BrowserLocator.locate(browserPath).equals(open.executable));
			}
			if (sameKind) {
				return open;
			}
		}
		// the new page first, so a request that fails leaves the page that worked open
		WebBrowser started = connectUrl == null ? launch(browserPath, headless) : connect(connectUrl);
		if (open != null) {
			open.dispose();
		}
		instance = started;
		return instance;
	}

	/** Closes the page and, when this IDE started it, the browser. Answers whether anything was open. */
	static synchronized boolean shutdown() {
		// through current(), which disposes a browser that already died, so that one does not count as closed here
		WebBrowser open = current();
		instance = null;
		if (open == null) {
			return false;
		}
		open.dispose();
		return true;
	}

	private static boolean sameEndpoint(String one, String other) {
		return other != null && canonical(one).equals(canonical(other));
	}

	private static String canonical(String endpoint) {
		return endpoint.toLowerCase(Locale.ROOT).replace("://localhost:", "://127.0.0.1:").replace("://[::1]:", "://127.0.0.1:"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	private static WebBrowser launch(String browserPath, boolean headless) throws IOException {
		Path executable = BrowserLocator.locate(browserPath);
		Path profile = Files.createTempDirectory("mcp-web-"); //$NON-NLS-1$
		try {
			return launch(executable, profile, headless);
		} catch (IOException | RuntimeException e) {
			// also when the process could not even be started
			deleteQuietly(profile);
			throw e;
		}
	}

	private static WebBrowser launch(Path executable, Path profile, boolean headless) throws IOException {
		List<String> command = new ArrayList<>(List.of(executable.toString(), "--remote-debugging-port=0", //$NON-NLS-1$
				"--user-data-dir=" + profile, "--no-first-run", "--no-default-browser-check", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"--disable-background-networking", "--disable-sync", "--disable-features=Translate", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"--window-size=1280,800")); //$NON-NLS-1$
		if (headless) {
			command.add("--headless=new"); //$NON-NLS-1$
			command.add("--hide-scrollbars"); //$NON-NLS-1$
		}
		if (isLinux()) {
			command.add("--disable-dev-shm-usage"); //$NON-NLS-1$
			if ("root".equals(System.getProperty("user.name")) || Boolean.getBoolean(NO_SANDBOX_PROPERTY)) { //$NON-NLS-1$
				// Chromium refuses to start its sandbox as root, which is how containers and CI agents often run
				command.add("--no-sandbox"); //$NON-NLS-1$
			}
		}
		command.add("about:blank"); //$NON-NLS-1$
		Path log = profile.resolve("browser.log"); //$NON-NLS-1$
		Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
		CdpConnection connection = null;
		try {
			String endpoint = "http://127.0.0.1:" + awaitPort(process, profile, log); //$NON-NLS-1$
			Map<String, Object> target = pageTarget(endpoint, false);
			connection = CdpConnection.open(URI.create(String.valueOf(target.get("webSocketDebuggerUrl"))), //$NON-NLS-1$
					launchBudgetMillis(10_000));
			WebBrowser browser = new WebBrowser(process, executable, profile, endpoint,
					String.valueOf(target.get("id")), headless, connection); //$NON-NLS-1$
			browser.attach();
			// a fixed wait: at JVM exit the preferences the call budget reads may already be gone
			browser.shutdownHook = new Thread(() -> browser.killProcess(3_000), "mcp-web-browser-shutdown"); //$NON-NLS-1$
			Runtime.getRuntime().addShutdownHook(browser.shutdownHook);
			return browser;
		} catch (IOException | RuntimeException e) {
			if (connection != null) {
				connection.close();
			}
			process.destroyForcibly();
			throw e;
		}
	}

	private static WebBrowser connect(String endpoint) throws IOException {
		Map<String, Object> target = pageTarget(endpoint, true);
		CdpConnection connection = null;
		try {
			connection = CdpConnection.open(URI.create(String.valueOf(target.get("webSocketDebuggerUrl"))), //$NON-NLS-1$
					launchBudgetMillis(10_000));
			WebBrowser browser = new WebBrowser(null, null, null, endpoint, String.valueOf(target.get("id")), false, //$NON-NLS-1$
					connection);
			browser.attach();
			return browser;
		} catch (IOException | RuntimeException e) {
			if (connection != null) {
				connection.close();
			}
			try {
				http(endpoint + "/json/close/" + target.get("id"), "GET"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			} catch (IOException closing) {
				e.addSuppressed(closing);
			}
			throw e;
		}
	}

	/** Chromium writes the port it picked into the profile, which is the only race-free way to learn it. */
	private static int awaitPort(Process process, Path profile, Path log) throws IOException {
		Path portFile = profile.resolve("DevToolsActivePort"); //$NON-NLS-1$
		long budget = launchBudgetMillis(20_000);
		boolean limitedByCall = budget < 20_000;
		long deadline = System.nanoTime() + Duration.ofMillis(budget).toNanos();
		while (System.nanoTime() < deadline) {
			if (!process.isAlive()) {
				String output = tail(log);
				// in a container that runs as an ordinary user the sandbox is often what fails
				String hint = isLinux() && !Boolean.getBoolean(NO_SANDBOX_PROPERTY) && output.toLowerCase(Locale.ROOT).contains("sandbox") //$NON-NLS-1$
						? " The sandbox could not start; in a container, start the IDE with -D" + NO_SANDBOX_PROPERTY + "=true." //$NON-NLS-1$ //$NON-NLS-2$
						: ""; //$NON-NLS-1$
				throw new IOException("The browser exited with code " + process.exitValue() + " before it was ready." + hint //$NON-NLS-1$ //$NON-NLS-2$
						+ " Output: " + output); //$NON-NLS-1$
			}
			if (Files.isRegularFile(portFile)) {
				List<String> lines = Files.readAllLines(portFile, StandardCharsets.UTF_8);
				// the browser writes the port and then its own path; with the path there, the port is complete
				if (lines.size() >= 2 && !lines.get(0).isBlank() && !lines.get(1).isBlank()) {
					try {
						return Integer.parseInt(lines.get(0).trim());
					} catch (NumberFormatException e) {
						// not a port yet; read again
					}
				}
			}
			sleep(50);
		}
		throw new IOException("The browser did not open its DevTools port within " + budget / 1000 //$NON-NLS-1$
				+ (limitedByCall
						? " seconds, which is what was left of this call. A first start on a busy machine can take that long; raise the tool call timeout in Preferences > General > MCP Server if it keeps happening. Output: " //$NON-NLS-1$
						: " seconds, the limit for starting a browser. Output: ") //$NON-NLS-1$
				+ tail(log));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> pageTarget(String endpoint, boolean newTab) throws IOException {
		if (newTab) {
			Object created = Json.parse(http(endpoint + "/json/new?about:blank", "PUT")); //$NON-NLS-1$ //$NON-NLS-2$
			if (created instanceof Map<?, ?> map && map.get("webSocketDebuggerUrl") != null) { //$NON-NLS-1$
				return (Map<String, Object>) map;
			}
			throw new IOException("The browser at " + endpoint + " did not open a new tab."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		long deadline = System.nanoTime() + Duration.ofMillis(launchBudgetMillis(10_000)).toNanos();
		while (true) {
			IOException refused = null;
			try {
				if (Json.parse(http(endpoint + "/json/list", "GET")) instanceof List<?> targets) { //$NON-NLS-1$ //$NON-NLS-2$
					for (Object target : targets) {
						if (target instanceof Map<?, ?> map && "page".equals(map.get("type")) //$NON-NLS-1$ //$NON-NLS-2$
								&& map.get("webSocketDebuggerUrl") != null) { //$NON-NLS-1$
							return (Map<String, Object>) map;
						}
					}
				}
			} catch (IOException e) {
				// a browser that just wrote its port can still refuse the first request
				refused = e;
			}
			if (System.nanoTime() > deadline) {
				if (refused != null) {
					throw refused;
				}
				throw new IOException("The browser at " + endpoint + " has no page to drive."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			sleep(100);
		}
	}

	/** At most {@code millis}, and never longer than what is left of the tool call, so a slow start answers in time. */
	private static long launchBudgetMillis(long millis) {
		// no floor: once the call has nothing left, a launch step fails at once and the start is undone
		return Math.max(1, Math.min(millis, CallBudget.leftMillis()));
	}

	private static String http(String url, String method) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(launchBudgetMillis(5_000)))
				.method(method, HttpRequest.BodyPublishers.noBody()).build();
		try {
			HttpResponse<String> response = CdpConnection.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IOException(method + " " + url + " answered " + response.statusCode() + ": " + response.body()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return response.body();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while asking " + url, e); //$NON-NLS-1$
		} catch (IOException e) {
			throw new IOException("Cannot reach the browser's DevTools endpoint " + url + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	@SuppressWarnings("unchecked")
	private void attach() throws IOException {
		page.addListener(this::onEvent);
		send("Page.enable", null); //$NON-NLS-1$
		send("Network.enable", null); //$NON-NLS-1$
		send("Runtime.enable", null); //$NON-NLS-1$
		if (process != null) {
			try {
				// a download in this IDE's own browser would land in the user's Downloads folder unasked
				send("Page.setDownloadBehavior", new JsonObject().put("behavior", "deny")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			} catch (IOException e) {
				// a browser without the command keeps its default
			}
		}
		Map<String, Object> tree = send("Page.getFrameTree", null); //$NON-NLS-1$
		if (tree.get("frameTree") instanceof Map<?, ?> frameTree && frameTree.get("frame") instanceof Map<?, ?> frame) { //$NON-NLS-1$ //$NON-NLS-2$
			synchronized (state) {
				mainFrameId = String.valueOf(((Map<String, Object>) frame).get("id")); //$NON-NLS-1$
			}
		}
	}

	private void onEvent(String method, Map<String, Object> params) {
		synchronized (state) {
			try {
				onEventLocked(method, params);
			} finally {
				// also after an odd event that broke the handler, so a settle wait never sleeps through a change
				state.notifyAll();
			}
		}
	}

	private void onEventLocked(String method, Map<String, Object> params) {
		switch (method) {
		case "Page.frameStartedLoading" -> { //$NON-NLS-1$
			if (isMainFrame(params.get("frameId"))) { //$NON-NLS-1$
				loading = true;
			}
		}
		case "Page.frameStoppedLoading" -> { //$NON-NLS-1$
			if (isMainFrame(params.get("frameId"))) { //$NON-NLS-1$
				loading = false;
			}
		}
		case "Network.requestWillBeSent" -> { //$NON-NLS-1$
			if (!STREAMING_TYPES.contains(params.get("type"))) { //$NON-NLS-1$
				inflight.put(String.valueOf(params.get("requestId")), String.valueOf(params.get("loaderId"))); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		case "Network.responseReceived" -> { //$NON-NLS-1$
			if ("Document".equals(params.get("type")) && isMainFrame(params.get("frameId")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					&& params.get("response") instanceof Map<?, ?> response //$NON-NLS-1$
					&& response.get("status") instanceof Number status) { //$NON-NLS-1$
				documentStatus = Integer.valueOf(status.intValue());
			}
		}
		case "Page.frameNavigated" -> { //$NON-NLS-1$
			// a navigation another one overtook gets neither loadingFinished nor loadingFailed, so a new document
			// drops what the documents before it left in flight
			if (params.get("frame") instanceof Map<?, ?> frame && frame.get("parentId") == null //$NON-NLS-1$ //$NON-NLS-2$
					&& isMainFrame(frame.get("id")) && frame.get("loaderId") instanceof String loader) { //$NON-NLS-1$ //$NON-NLS-2$
				inflight.values().removeIf(other -> !other.equals(loader));
			}
		}
		case "Network.loadingFinished", "Network.loadingFailed" -> //$NON-NLS-1$ //$NON-NLS-2$
			inflight.remove(String.valueOf(params.get("requestId"))); //$NON-NLS-1$
		case "Page.javascriptDialogOpening" -> answerDialog(params); //$NON-NLS-1$
		case "Page.downloadWillBegin" -> { //$NON-NLS-1$
			if (downloads.size() >= MAX_REPORTED) {
				droppedDownloads++;
			} else {
				downloads.add(new JsonObject().put("url", params.get("url")) //$NON-NLS-1$ //$NON-NLS-2$
						.put("file", params.get("suggestedFilename")) //$NON-NLS-1$ //$NON-NLS-2$
						.put("saved", process != null ? "no, downloads are denied in this IDE's own browser" //$NON-NLS-1$ //$NON-NLS-2$
								: "by the user's browser, where it saves downloads")); //$NON-NLS-1$
			}
		}
		default -> {
			// not needed to decide whether the page settled
		}
		}
	}

	private boolean isMainFrame(Object frameId) {
		return mainFrameId == null || mainFrameId.equals(frameId);
	}

	/**
	 * A JavaScript dialog blocks the page until it is answered, so every one is answered at once: alerts and leave-page
	 * prompts are accepted, a confirm or prompt is dismissed unless the call asked to accept it.
	 */
	private void answerDialog(Map<String, Object> params) {
		String type = String.valueOf(params.get("type")); //$NON-NLS-1$
		boolean accept = !"confirm".equals(type) && !"prompt".equals(type) || acceptConfirm; //$NON-NLS-1$ //$NON-NLS-2$
		if (dialogs.size() >= MAX_REPORTED) {
			droppedDialogs++;
		} else {
			// a page in a dialog loop is still answered every time, but only the first ones are kept for the report
			dialogs.add(new JsonObject().put("type", type).put("message", params.get("message")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.put("answered", accept ? "accepted" : "dismissed")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		JsonObject answer = new JsonObject().put("accept", Boolean.valueOf(accept)); //$NON-NLS-1$
		if (accept && params.get("defaultPrompt") instanceof String prompt) { //$NON-NLS-1$
			answer.put("promptText", prompt); //$NON-NLS-1$
		}
		// answered from another thread, because this one delivers the events the answer waits for
		Thread.ofVirtual().start(() -> {
			try {
				send("Page.handleJavaScriptDialog", answer); //$NON-NLS-1$
			} catch (IOException e) {
				// the page went away with its dialog
			}
		});
	}

	/** Whether a confirm or prompt raised by the following calls is accepted rather than dismissed. */
	void acceptConfirmations(boolean accept) {
		synchronized (state) {
			acceptConfirm = accept;
		}
	}

	/** The downloads the page started since the last call, which a click answers with. */
	List<JsonObject> takeDownloads() {
		synchronized (state) {
			List<JsonObject> taken = List.copyOf(downloads);
			downloads.clear();
			return taken;
		}
	}

	/** How many downloads beyond {@link #MAX_REPORTED} were started since the last call and not listed. */
	int takeUnlistedDownloads() {
		synchronized (state) {
			int dropped = droppedDownloads;
			droppedDownloads = 0;
			return dropped;
		}
	}

	/** The dialogs answered since the last call, for the answer of the tool that raised them. */
	List<JsonObject> takeDialogs() {
		synchronized (state) {
			List<JsonObject> taken = new ArrayList<>(dialogs);
			if (droppedDialogs > 0) {
				taken.add(new JsonObject().put("more", Integer.valueOf(droppedDialogs)).put("note", //$NON-NLS-1$ //$NON-NLS-2$
						"The page raised this many more dialogs, all answered the same way; it is probably raising them in a loop.")); //$NON-NLS-1$
			}
			dialogs.clear();
			droppedDialogs = 0;
			return taken;
		}
	}

	/** Sends a command, waiting at most what is left of the tool call, so a wedged page still gets an answer in time. */
	Map<String, Object> send(String method, JsonObject params) throws IOException {
		return page.send(method, params, Math.max(1, Math.min(COMMAND_MILLIS, CallBudget.leftMillis())));
	}

	/** What {@link #navigate} answers while the server has not yet sent the response the navigation waits for. */
	static final String NOT_ANSWERED = "not answered"; //$NON-NLS-1$

	/**
	 * Starts loading {@code url} in the page, waiting at most {@code timeoutMillis} for the browser to commit it.
	 *
	 * @return the navigation error Chromium reported, such as {@code net::ERR_NAME_NOT_RESOLVED},
	 *         {@link #NOT_ANSWERED} when the load is still waiting for the server, or {@code null}
	 */
	String navigate(String url, long timeoutMillis) throws IOException {
		synchronized (state) {
			documentStatus = null;
			loading = true;
		}
		Map<String, Object> result;
		try {
			result = page.send("Page.navigate", new JsonObject().put("url", url), //$NON-NLS-1$ //$NON-NLS-2$
					Math.max(1, Math.min(Math.max(1_000, timeoutMillis), Math.min(COMMAND_MILLIS, CallBudget.leftMillis()))));
		} catch (IOException e) {
			if (e.getCause() instanceof java.util.concurrent.TimeoutException && page.isOpen()) {
				// the load goes on in the browser, so the page stays loading and a later settle or snapshot sees it
				return NOT_ANSWERED;
			}
			synchronized (state) {
				loading = false;
			}
			throw e;
		}
		Object error = result.get("errorText"); //$NON-NLS-1$
		if (error instanceof String text && !text.isBlank()) {
			synchronized (state) {
				loading = false;
			}
			return text;
		}
		if (!(result.get("loaderId") instanceof String)) { //$NON-NLS-1$
			// a same-document navigation, such as a change of the fragment, starts no load
			synchronized (state) {
				loading = false;
			}
		}
		return null;
	}

	/**
	 * Waits until the main frame finished loading and no request was in flight for {@code quietMillis}, or the time
	 * ran out.
	 *
	 * @return whether the page settled
	 */
	boolean settle(long timeoutMillis, long quietMillis) {
		long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
		long quietSince = 0;
		synchronized (state) {
			while (true) {
				if (!isAlive()) {
					// a browser or connection that is gone never finishes loading; the next step reports the page as gone
					return false;
				}
				long now = System.nanoTime();
				if (!loading && inflight.isEmpty()) {
					if (quietSince == 0) {
						quietSince = now;
					}
					if (now - quietSince >= Duration.ofMillis(quietMillis).toNanos()) {
						return true;
					}
				} else {
					quietSince = 0;
				}
				if (now >= deadline) {
					return false;
				}
				try {
					state.wait(25);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
		}
	}

	/** Records that the page handed out refs below {@code next}, in case it went past its reserved block. */
	static synchronized void refsUsedBelow(long next) {
		nextRef = Math.max(nextRef, next);
	}

	/**
	 * Sets aside {@code count} ref numbers and answers the first, before a snapshot that may hand out that many runs, so
	 * the numbers stay taken even when the snapshot never answers.
	 */
	static synchronized long reserveRefs(long count) {
		long first = nextRef;
		nextRef += Math.max(0, count) + 1;
		return first;
	}

	int pendingRequests() {
		synchronized (state) {
			return inflight.size();
		}
	}

	Integer documentStatus() {
		synchronized (state) {
			return documentStatus;
		}
	}

	/**
	 * Evaluates a JavaScript expression in the page and answers its value, awaiting a promise.
	 * <p>
	 * Not marked as a user gesture: the tools act through real input events, and a page must not gain gesture-gated
	 * powers, such as opening a popup, from a snapshot or a locate.
	 *
	 * @throws IOException when the script threw, with the page's message
	 */
	Object evaluate(String expression) throws IOException {
		Map<String, Object> result = send("Runtime.evaluate", new JsonObject().put("expression", expression) //$NON-NLS-1$ //$NON-NLS-2$
				.put("returnByValue", Boolean.TRUE).put("awaitPromise", Boolean.TRUE)); //$NON-NLS-1$ //$NON-NLS-2$
		if (result.get("exceptionDetails") instanceof Map<?, ?> details) { //$NON-NLS-1$
			Object message = details.get("exception") instanceof Map<?, ?> exception ? exception.get("description") //$NON-NLS-1$ //$NON-NLS-2$
					: details.get("text"); //$NON-NLS-1$
			throw new IOException("The page script failed: " + message); //$NON-NLS-1$
		}
		return result.get("result") instanceof Map<?, ?> value ? value.get("value") : null; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** The page's address and title, which every answer starts with. */
	JsonObject describe() throws IOException {
		JsonObject answer = new JsonObject();
		location(answer);
		return answer;
	}

	/** Puts the page's url and title into {@code answer} and answers the url. */
	String location(JsonObject answer) throws IOException {
		Object location = evaluate("JSON.stringify({url: location.href, title: document.title})"); //$NON-NLS-1$
		if (location instanceof String text && Json.parse(text) instanceof Map<?, ?> map) {
			answer.put("url", map.get("url")).put("title", map.get("title")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			return String.valueOf(map.get("url")); //$NON-NLS-1$
		}
		return null;
	}

	boolean launchedHere() {
		return process != null;
	}

	boolean isHeadless() {
		return headless;
	}

	String endpoint() {
		return endpoint;
	}

	private boolean isAlive() {
		return page.isOpen() && (process == null || process.isAlive());
	}

	private void dispose() {
		page.close();
		if (process == null) {
			try {
				http(endpoint + "/json/close/" + targetId, "GET"); //$NON-NLS-1$ //$NON-NLS-2$
			} catch (IOException e) {
				// the tab or the browser is already gone
			}
			return;
		}
		// each wait within what is left of the call, so closing a hung browser never costs the call its answer
		killProcess(Math.max(100, Math.min(3_000, CallBudget.leftMillis() / 2)));
		if (shutdownHook != null) {
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException e) {
				// the JVM is already shutting down and runs the hook itself
			}
		}
	}

	private void killProcess(long wait) {
		if (process == null) {
			return;
		}
		process.destroy();
		try {
			if (!process.waitFor(wait, java.util.concurrent.TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				process.waitFor(wait, java.util.concurrent.TimeUnit.MILLISECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		deleteQuietly(profile);
	}

	private static void deleteQuietly(Path directory) {
		if (directory == null || !Files.exists(directory)) {
			return;
		}
		try (Stream<Path> files = Files.walk(directory)) {
			files.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					// a file the browser still holds; the temporary directory is cleaned up by the OS
				}
			});
		} catch (IOException | UncheckedIOException e) {
			// as above; a directory the browser removes while this walks it is reported as unchecked
		}
	}

	private static String tail(Path log) {
		try {
			String text = Files.readString(log, StandardCharsets.UTF_8).strip();
			return text.isEmpty() ? "(no output)" : text.substring(Math.max(0, text.length() - 1500)); //$NON-NLS-1$
		} catch (IOException e) {
			return "(no output)"; //$NON-NLS-1$
		}
	}

	private static boolean isLinux() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private static void sleep(long millis) throws IOException {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the browser", e); //$NON-NLS-1$
		}
	}
}
