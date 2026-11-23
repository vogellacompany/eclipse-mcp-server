package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Drives a real headless Chromium against a page served from the test. Skipped where no Chrome, Chromium or Edge is
 * installed, because the bundle never downloads one.
 */
class WebToolsTest {

	private static final String FORM = """
			<!doctype html>
			<html><head><title>Greeter</title></head><body>
			<h1>Greeter</h1>
			<form id="form" onsubmit="event.preventDefault(); document.getElementById('out').textContent = 'Submitted ' + document.getElementById('name').value;">
			  <label for="name">Name</label> <input id="name" value="old">
			  <label for="color">Color</label>
			  <select id="color" onchange="document.getElementById('out').textContent = 'Color ' + this.value;">
			    <option value="r">Red</option><option value="g">Green</option>
			  </select>
			  <button type="button" id="greet" onclick="document.getElementById('out').textContent = 'Hello ' + document.getElementById('name').value;">Greet</button>
			  <button type="button" id="ask" onclick="document.getElementById('out').textContent = confirm('Sure?') ? 'yes' : 'no';">Ask</button>
			</form>
			<p id="out">nothing yet</p>
			<div hidden><button>Invisible</button></div>
			<button type="button" onclick="this.remove()">Remove me</button>
			<a href="/second">Next page</a>
			<div id="overlay-host"></div>
			<shadow-greeter></shadow-greeter>
			<script>
			customElements.define('shadow-greeter', class extends HTMLElement {
			  connectedCallback() {
			    const root = this.attachShadow({mode: 'open'});
			    root.innerHTML = '<button>Shadow greet</button>';
			    root.querySelector('button').onclick = () => document.getElementById('out').textContent = 'From the shadow';
			  }
			});
			</script>
			</body></html>""";

	private static final String SECOND = """
			<!doctype html><html><head><title>Second</title></head><body><p>Arrived</p>
			<button id="covered" style="position:relative">Under</button>
			<div style="position:fixed;inset:0;background:rgba(0,0,0,.3)" id="modal"></div>
			</body></html>""";

	private static HttpServer server;
	private static String base;

	@BeforeAll
	static void serve() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> respond(exchange,
				exchange.getRequestURI().getPath().equals("/second") ? SECOND : FORM));
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterAll
	static void stop() throws Exception {
		TestFixture.call("eclipse_web_close", Map.of());
		server.stop(0);
	}

	private static void respond(HttpExchange exchange, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static Map<String, Object> open(String path) throws Exception {
		McpToolResult result = TestFixture.call("eclipse_web_open", Map.of("url", base + path));
		Assumptions.assumeFalse(result.isError() && result.text().contains("No Chrome, Chromium or Edge"),
				"No browser installed: " + result.text());
		assertFalse(result.isError(), result.text());
		return TestFixture.parse(result.text());
	}

	private static String snapshot() throws Exception {
		return String.valueOf(TestFixture.callAndParse("eclipse_web_snapshot", Map.of()).get("snapshot"));
	}

	private static String ref(String snapshot, String line) {
		Matcher matcher = Pattern.compile(Pattern.quote(line) + ".*?\\[ref=(e\\d+)\\]").matcher(snapshot);
		assertTrue(matcher.find(), "No '" + line + "' in\n" + snapshot);
		return matcher.group(1);
	}

	@Test
	void opensTypesClicksAndFollowsALink() throws Exception {
		Map<String, Object> opened = open("/");
		assertEquals("Greeter", opened.get("title"));
		assertEquals(Integer.valueOf(200), opened.get("status"));
		assertEquals(Boolean.TRUE, opened.get("settled"));

		String snapshot = snapshot();
		assertTrue(snapshot.contains("- heading \"Greeter\" [level=1]"), snapshot);
		assertTrue(snapshot.contains("- textbox \"Name\""), snapshot);
		assertTrue(snapshot.contains(": \"old\""), snapshot);
		assertTrue(snapshot.contains("- combobox \"Color\""), snapshot);
		assertTrue(snapshot.contains("- text: \"nothing yet\""), snapshot);
		assertFalse(snapshot.contains("Invisible"), snapshot);

		Map<String, Object> typed = TestFixture.callAndParse("eclipse_web_type",
				Map.of("ref", ref(snapshot, "textbox \"Name\""), "text", "Lars"));
		assertEquals("Lars", typed.get("value"));
		assertEquals(Boolean.FALSE, typed.get("navigated"));

		Map<String, Object> clicked = TestFixture.callAndParse("eclipse_web_click",
				Map.of("ref", ref(snapshot, "button \"Greet\"")));
		assertEquals("button", clicked.get("clicked"));
		assertTrue(snapshot().contains("Hello Lars"), snapshot());

		TestFixture.callAndParse("eclipse_web_type", Map.of("selector", "#name", "text", "Eclipse", "submit", Boolean.TRUE));
		assertTrue(snapshot().contains("Submitted Eclipse"), snapshot());

		Map<String, Object> chosen = TestFixture.callAndParse("eclipse_web_type",
				Map.of("selector", "#color", "text", "Green"));
		assertEquals("g", chosen.get("value"));
		assertTrue(snapshot().contains("Color g"), snapshot());

		TestFixture.callAndParse("eclipse_web_click", Map.of("ref", ref(snapshot(), "button \"Shadow greet\"")));
		assertTrue(snapshot().contains("From the shadow"), snapshot());

		Map<String, Object> followed = TestFixture.callAndParse("eclipse_web_click",
				Map.of("ref", ref(snapshot(), "link \"Next page\"")));
		assertEquals(Boolean.TRUE, followed.get("navigated"));
		assertEquals("Second", followed.get("title"));
		assertEquals(base + "/second", followed.get("url"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void answersConfirmDialogsAndReportsThem() throws Exception {
		open("/");
		String ask = ref(snapshot(), "button \"Ask\"");
		Map<String, Object> dismissed = TestFixture.callAndParse("eclipse_web_click", Map.of("ref", ask));
		Map<String, Object> dialog = ((List<Map<String, Object>>) dismissed.get("dialogs")).get(0);
		assertEquals("confirm", dialog.get("type"));
		assertEquals("Sure?", dialog.get("message"));
		assertEquals("dismissed", dialog.get("answered"));
		assertTrue(snapshot().contains("text: \"no\""), snapshot());

		Map<String, Object> accepted = TestFixture.callAndParse("eclipse_web_click",
				Map.of("ref", ref(snapshot(), "button \"Ask\""), "acceptDialogs", Boolean.TRUE));
		assertEquals("accepted", ((List<Map<String, Object>>) accepted.get("dialogs")).get(0).get("answered"));
		assertTrue(snapshot().contains("text: \"yes\""), snapshot());
	}

	@Test
	void keepsRefsAcrossSnapshotsAndRefusesOnesWhoseElementWentAway() throws Exception {
		open("/");
		String first = snapshot();
		String greet = ref(first, "button \"Greet\"");
		String ask = ref(first, "button \"Ask\"");
		String second = snapshot();
		assertEquals(greet, ref(second, "button \"Greet\""));
		TestFixture.callAndParse("eclipse_web_snapshot", Map.of("selector", "#out"));
		assertFalse(TestFixture.call("eclipse_web_click", Map.of("ref", ask)).isError(),
				"a snapshot of part of the page keeps the other refs");
		String remove = ref(snapshot(), "button \"Remove me\"");
		TestFixture.callAndParse("eclipse_web_click", Map.of("ref", remove));
		TestFixture.assertRefused(TestFixture.call("eclipse_web_click", Map.of("ref", remove)), "removed");
		TestFixture.assertRefused(TestFixture.call("eclipse_web_type", Map.of("selector", "#color", "text", "")),
				"cannot be emptied");

		open("/second");
		String covered = ref(snapshot(), "button \"Under\"");
		assertFalse(List.of(greet, ask, remove).contains(covered), "a new document must not reuse refs: " + covered);
		TestFixture.assertRefused(TestFixture.call("eclipse_web_click", Map.of("ref", greet)), "take a new snapshot");
		TestFixture.assertRefused(TestFixture.call("eclipse_web_type", Map.of("selector", "#covered", "text", "", "force", Boolean.TRUE)),
				"does not take text");
	}

	@Test
	void refusesACoveredElementAndStaleRefs() throws Exception {
		open("/");
		String greet = ref(snapshot(), "button \"Greet\"");
		open("/second");
		TestFixture.assertRefused(TestFixture.call("eclipse_web_click", Map.of("ref", greet)), "take a new snapshot");
		TestFixture.assertRefused(TestFixture.call("eclipse_web_click", Map.of("selector", "#covered")), "covered by div#modal");
		TestFixture.assertRefused(TestFixture.call("eclipse_web_type", Map.of("selector", "#covered", "text", "x")),
				"covered by div#modal");
		TestFixture.assertRefused(TestFixture.call("eclipse_web_click", Map.of("selector", "#missing")), "No element matches");
	}

	@Test
	void capturesTheViewportTheFullPageAndAnElement() throws Exception {
		open("/");
		McpToolResult viewport = TestFixture.call("eclipse_web_screenshot", Map.of());
		assertFalse(viewport.isError(), viewport.text());
		assertEquals(1, viewport.images().size());
		byte[] png = viewport.images().get(0).data();
		assertEquals((byte) 0x89, png[0]);
		assertEquals('P', png[1]);
		assertEquals(Long.valueOf(1280), Long.valueOf(((Number) TestFixture.parse(viewport.text()).get("width")).longValue()));

		McpToolResult element = TestFixture.call("eclipse_web_screenshot", Map.of("selector", "#greet"));
		assertFalse(element.isError(), element.text());
		Map<String, Object> answer = TestFixture.parse(element.text());
		assertEquals("button", answer.get("element"));
		assertTrue(((Number) answer.get("width")).intValue() < 200, element.text());

		McpToolResult full = TestFixture.call("eclipse_web_screenshot", Map.of("fullPage", Boolean.TRUE));
		assertFalse(full.isError(), full.text());
		assertEquals(1, full.images().size());
	}

	@Test
	void refusesWithoutAPageAndRejectsOddUrls() throws Exception {
		TestFixture.call("eclipse_web_close", Map.of());
		TestFixture.assertRefused(TestFixture.call("eclipse_web_snapshot", Map.of()), "eclipse_web_open");
		TestFixture.assertRefused(TestFixture.call("eclipse_web_click", Map.of("selector", "a")), "eclipse_web_open");
		TestFixture.assertRefused(TestFixture.call("eclipse_web_open", Map.of("url", "javascript:alert(1)")), "absolute");
		TestFixture.assertRefused(
				TestFixture.call("eclipse_web_open", Map.of("url", base + "/", "connectUrl", "ftp://localhost:9222")),
				"DevTools endpoint");
		assertEquals(Boolean.FALSE, TestFixture.callAndParse("eclipse_web_close", Map.of()).get("closed"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void opensATabInABrowserThatIsAlreadyRunningAndLeavesItRunning() throws Exception {
		Path executable = Stream.of(System.getenv("PATH").split(File.pathSeparator))
				.flatMap(dir -> Stream.of("google-chrome", "chromium", "chromium-browser").map(name -> Path.of(dir, name)))
				.filter(Files::isExecutable).findFirst().orElse(null);
		Assumptions.assumeTrue(executable != null, "No Chrome or Chromium on PATH");
		TestFixture.call("eclipse_web_close", Map.of());
		Path profile = Files.createTempDirectory("mcp-web-test-");
		Process browser = new ProcessBuilder(executable.toString(), "--headless=new", "--no-sandbox",
				"--remote-debugging-port=0", "--user-data-dir=" + profile, "about:blank").redirectErrorStream(true)
				.redirectOutput(profile.resolve("log").toFile()).start();
		try {
			Path portFile = profile.resolve("DevToolsActivePort");
			for (int i = 0; i < 200 && !(Files.isRegularFile(portFile) && Files.size(portFile) > 0); i++) {
				Thread.sleep(50);
			}
			String endpoint = "http://127.0.0.1:" + Files.readAllLines(portFile).get(0).trim();
			Map<String, Object> opened = TestFixture.callAndParse("eclipse_web_open",
					Map.of("url", base + "/", "connectUrl", endpoint + "/"));
			assertEquals("Greeter", opened.get("title"));
			assertEquals("connected", ((Map<String, Object>) opened.get("browser")).get("mode"));
			assertTrue(snapshot().contains("button \"Greet\""));
			Map<String, Object> again = TestFixture.callAndParse("eclipse_web_open", Map.of("url", base + "/second"));
			assertEquals("connected", ((Map<String, Object>) again.get("browser")).get("mode"),
					"a later open without connectUrl keeps the connected tab");
			assertEquals(Boolean.TRUE, TestFixture.callAndParse("eclipse_web_close", Map.of()).get("closed"));
			assertTrue(browser.isAlive(), "closing the tab must leave the user's browser running");
		} finally {
			browser.destroyForcibly().waitFor();
		}
	}
}
