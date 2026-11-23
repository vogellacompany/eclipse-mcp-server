package com.vogella.eclipse.mcp.web.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.vogella.eclipse.mcp.core.json.Json;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The one browser page the web tools drive: a Chromium the IDE started, or a tab opened in a browser that already
 * listens for DevTools connections.
 * <p>
 * All access goes through {@link #current()} and {@link #start}, which synchronize on the class, so two clients see
 * the same page one call after the other.
 */
final class WebBrowser {

	/** Network requests that never finish by design, so a page that holds one open can still settle. */
	private static final Set<String> STREAMING_TYPES = Set.of("WebSocket", "EventSource"); //$NON-NLS-1$ //$NON-NLS-2$

	private static final long COMMAND_MILLIS = 15_000;

	private static WebBrowser instance;

	private final Process process;
	private final Path executable;
	private final Path profile;
	private final String endpoint;
	private final String targetId;
	private final boolean headless;
	private final CdpConnection page;

	private final Object state = new Object();
	private final Set<String> inflight = new HashSet<>();
	private final List<JsonObject> dialogs = new ArrayList<>();
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
	static synchronized WebBrowser start(String browserPath, boolean headless, String connect) throws IOException {
		String connectUrl = connect != null && connect.endsWith("/") ? connect.substring(0, connect.length() - 1) //$NON-NLS-1$
				: connect;
		WebBrowser open = current();
		if (open != null) {
			boolean sameKind = connectUrl == null
					? open.process != null && open.headless == headless
							&& (browserPath == null || BrowserLocator.locate(browserPath).equals(open.executable))
					: connectUrl.equals(open.endpoint);
			if (sameKind) {
				return open;
			}
			open.dispose();
			instance = null;
		}
		instance = connectUrl == null ? launch(browserPath, headless) : connect(connectUrl);
		return instance;
	}

	/** Closes the page and, when this IDE started it, the browser. Answers whether anything was open. */
	static synchronized boolean shutdown() {
		WebBrowser open = instance;
		instance = null;
		if (open == null) {
			return false;
		}
		open.dispose();
		return true;
	}

	private static WebBrowser launch(String browserPath, boolean headless) throws IOException {
		Path executable = BrowserLocator.locate(browserPath);
		Path profile = Files.createTempDirectory("mcp-web-"); //$NON-NLS-1$
		List<String> command = new ArrayList<>(List.of(executable.toString(), "--remote-debugging-port=0", //$NON-NLS-1$
				"--remote-allow-origins=*", //$NON-NLS-1$
				"--user-data-dir=" + profile, "--no-first-run", "--no-default-browser-check", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"--disable-background-networking", "--disable-sync", "--disable-features=Translate", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"--window-size=1280,800")); //$NON-NLS-1$
		if (headless) {
			command.add("--headless=new"); //$NON-NLS-1$
			command.add("--hide-scrollbars"); //$NON-NLS-1$
		}
		if (isLinux()) {
			command.add("--disable-dev-shm-usage"); //$NON-NLS-1$
			if ("root".equals(System.getProperty("user.name"))) { //$NON-NLS-1$ //$NON-NLS-2$
				// Chromium refuses to start its sandbox as root, which is how containers and CI agents often run
				command.add("--no-sandbox"); //$NON-NLS-1$
			}
		}
		command.add("about:blank"); //$NON-NLS-1$
		Path log = profile.resolve("browser.log"); //$NON-NLS-1$
		Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			String endpoint = "http://127.0.0.1:" + awaitPort(process, profile, log); //$NON-NLS-1$
			Map<String, Object> target = pageTarget(endpoint, false);
			WebBrowser browser = new WebBrowser(process, executable, profile, endpoint,
					String.valueOf(target.get("id")), headless, //$NON-NLS-1$
					CdpConnection.open(URI.create(String.valueOf(target.get("webSocketDebuggerUrl"))))); //$NON-NLS-1$
			browser.attach();
			browser.shutdownHook = new Thread(browser::killProcess, "mcp-web-browser-shutdown"); //$NON-NLS-1$
			Runtime.getRuntime().addShutdownHook(browser.shutdownHook);
			return browser;
		} catch (IOException | RuntimeException e) {
			process.destroyForcibly();
			deleteQuietly(profile);
			throw e;
		}
	}

	private static WebBrowser connect(String endpoint) throws IOException {
		Map<String, Object> target = pageTarget(endpoint, true);
		WebBrowser browser = new WebBrowser(null, null, null, endpoint, String.valueOf(target.get("id")), false, //$NON-NLS-1$
				CdpConnection.open(URI.create(String.valueOf(target.get("webSocketDebuggerUrl"))))); //$NON-NLS-1$
		browser.attach();
		return browser;
	}

	/** Chromium writes the port it picked into the profile, which is the only race-free way to learn it. */
	private static int awaitPort(Process process, Path profile, Path log) throws IOException {
		Path portFile = profile.resolve("DevToolsActivePort"); //$NON-NLS-1$
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (System.nanoTime() < deadline) {
			if (!process.isAlive()) {
				throw new IOException("The browser exited with code " + process.exitValue() + " before it was ready: " //$NON-NLS-1$ //$NON-NLS-2$
						+ tail(log));
			}
			if (Files.isRegularFile(portFile)) {
				List<String> lines = Files.readAllLines(portFile, StandardCharsets.UTF_8);
				if (!lines.isEmpty() && !lines.get(0).isBlank()) {
					return Integer.parseInt(lines.get(0).trim());
				}
			}
			sleep(50);
		}
		throw new IOException("The browser did not open its DevTools port within 20 seconds: " + tail(log)); //$NON-NLS-1$
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
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (true) {
			if (Json.parse(http(endpoint + "/json/list", "GET")) instanceof List<?> targets) { //$NON-NLS-1$ //$NON-NLS-2$
				for (Object target : targets) {
					if (target instanceof Map<?, ?> map && "page".equals(map.get("type")) //$NON-NLS-1$ //$NON-NLS-2$
							&& map.get("webSocketDebuggerUrl") != null) { //$NON-NLS-1$
						return (Map<String, Object>) map;
					}
				}
			}
			if (System.nanoTime() > deadline) {
				throw new IOException("The browser at " + endpoint + " has no page to drive."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			sleep(100);
		}
	}

	private static String http(String url, String method) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
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
		Map<String, Object> tree = send("Page.getFrameTree", null); //$NON-NLS-1$
		if (tree.get("frameTree") instanceof Map<?, ?> frameTree && frameTree.get("frame") instanceof Map<?, ?> frame) { //$NON-NLS-1$ //$NON-NLS-2$
			synchronized (state) {
				mainFrameId = String.valueOf(((Map<String, Object>) frame).get("id")); //$NON-NLS-1$
			}
		}
	}

	private void onEvent(String method, Map<String, Object> params) {
		synchronized (state) {
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
					inflight.add(String.valueOf(params.get("requestId"))); //$NON-NLS-1$
				}
			}
			case "Network.responseReceived" -> { //$NON-NLS-1$
				if ("Document".equals(params.get("type")) && isMainFrame(params.get("frameId")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						&& params.get("response") instanceof Map<?, ?> response //$NON-NLS-1$
						&& response.get("status") instanceof Number status) { //$NON-NLS-1$
					documentStatus = Integer.valueOf(status.intValue());
				}
			}
			case "Network.loadingFinished", "Network.loadingFailed" -> //$NON-NLS-1$ //$NON-NLS-2$
				inflight.remove(String.valueOf(params.get("requestId"))); //$NON-NLS-1$
			case "Page.javascriptDialogOpening" -> answerDialog(params); //$NON-NLS-1$
			default -> {
				// not needed to decide whether the page settled
			}
			}
			state.notifyAll();
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
		dialogs.add(new JsonObject().put("type", type).put("message", params.get("message")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("answered", accept ? "accepted" : "dismissed")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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

	/** The dialogs answered since the last call, for the answer of the tool that raised them. */
	List<JsonObject> takeDialogs() {
		synchronized (state) {
			List<JsonObject> taken = List.copyOf(dialogs);
			dialogs.clear();
			return taken;
		}
	}

	Map<String, Object> send(String method, JsonObject params) throws IOException {
		return page.send(method, params, COMMAND_MILLIS);
	}

	/**
	 * Loads {@code url} in the page and waits until it settled.
	 *
	 * @return the navigation error Chromium reported, such as {@code net::ERR_NAME_NOT_RESOLVED}, or {@code null}
	 */
	String navigate(String url) throws IOException {
		synchronized (state) {
			documentStatus = null;
			loading = true;
		}
		Map<String, Object> result = send("Page.navigate", new JsonObject().put("url", url)); //$NON-NLS-1$ //$NON-NLS-2$
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
	 *
	 * @throws IOException when the script threw, with the page's message
	 */
	Object evaluate(String expression) throws IOException {
		Map<String, Object> result = send("Runtime.evaluate", new JsonObject().put("expression", expression) //$NON-NLS-1$ //$NON-NLS-2$
				.put("returnByValue", Boolean.TRUE).put("awaitPromise", Boolean.TRUE).put("userGesture", Boolean.TRUE)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
		killProcess();
		if (shutdownHook != null) {
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException e) {
				// the JVM is already shutting down and runs the hook itself
			}
		}
	}

	private void killProcess() {
		if (process == null) {
			return;
		}
		process.destroy();
		try {
			if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
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
		} catch (IOException e) {
			// as above
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
