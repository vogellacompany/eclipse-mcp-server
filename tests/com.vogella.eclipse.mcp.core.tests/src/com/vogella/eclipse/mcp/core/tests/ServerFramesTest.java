package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.ServerFrames;
import com.vogella.eclipse.mcp.core.ServerFrames.Verdict;

class ServerFramesTest {

	@Test
	void anApplicationStackIsKept() {
		assertEquals(Verdict.KEEP, ServerFrames.classify("main",
				List.of("java.lang.String", "org.example.Handler", "org.eclipse.swt.widgets.Display", "java.lang.Thread")));
	}

	@Test
	void workDispatchedThroughTheServerKeepsTheApplicationsFrames() {
		// a tool running a menu entry on the UI thread: the handler is the application's work
		assertEquals(Verdict.ELIDE, ServerFrames.classify("main",
				List.of("org.example.ThemeSwitchHandler", "com.vogella.eclipse.mcp.ui.internal.SelectMenuItemTool$$Lambda",
						"org.eclipse.swt.widgets.Synchronizer", "org.eclipse.swt.widgets.Display")));
	}

	@Test
	void theServersCodeRunningOnAForeignThreadIsDropped() {
		assertEquals(Verdict.DROP, ServerFrames.classify("main",
				List.of("java.lang.StringBuilder", "com.vogella.eclipse.mcp.ui.internal.SamplingRegistry",
						"org.eclipse.swt.widgets.Display")));
	}

	@Test
	void aThreadTheServerStartedIsDropped() {
		assertEquals(Verdict.DROP, ServerFrames.classify("MCP stack sampler sampling-1", List.of("java.lang.Thread")));
		assertEquals(Verdict.DROP, ServerFrames.classify("mcp-jetty-66", List.of("sun.nio.ch.Net")));
		assertEquals(Verdict.DROP, ServerFrames.classify("pool-1", List.of("org.example.Work",
				"com.vogella.eclipse.mcp.server.internal.McpToolAdapter", "java.util.concurrent.ThreadPoolExecutor")));
	}

	@Test
	void theMcpSdkServingARequestIsDroppedWithoutAFrameOfTheServer() {
		assertEquals(Verdict.DROP, ServerFrames.classify("boundedElastic-3",
				List.of("tools.jackson.databind.ObjectMapper",
						"io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider",
						"reactor.core.publisher.MonoRunnable", "java.lang.Thread")));
	}

	@Test
	void theServersTestsAreNotTheServer() {
		assertEquals(Verdict.KEEP, ServerFrames.classify("main",
				List.of("com.vogella.eclipse.mcp.core.tests.FlightRecordingToolsTest", "java.lang.Thread")));
	}

	@Test
	void anApplicationThatUsesJacksonItselfKeepsItsWorkOnTheUiThread() {
		// a tool pressing a button whose handler parses JSON, which is nothing of the server's
		assertEquals(Verdict.ELIDE, ServerFrames.classify("main",
				List.of("com.fasterxml.jackson.core.JsonParser", "org.jkiss.dbeaver.Handler",
						"com.vogella.eclipse.mcp.ui.internal.PressWidgetTool$$Lambda", "org.eclipse.swt.widgets.Synchronizer",
						"org.eclipse.swt.widgets.Display", "org.eclipse.equinox.launcher.Main")));
	}

	@Test
	void theServerInOneOfItsLibrariesIsStillTheServer() {
		assertEquals(Verdict.DROP, ServerFrames.classify("main",
				List.of("java.lang.String", "tools.jackson.databind.ObjectMapper",
						"com.vogella.eclipse.mcp.server.internal.McpToolAdapter", "org.eclipse.swt.widgets.Display")));
	}

	@Test
	void aStackCutAtAServerFrameIsNotTakenForAThreadOfTheServer() {
		List<String> cut = List.of("org.example.Deep", "org.example.Recursion",
				"com.vogella.eclipse.mcp.ui.internal.PressWidgetTool$$Lambda");
		assertEquals(Verdict.ELIDE, ServerFrames.classify("main", cut, true));
		assertEquals(Verdict.DROP, ServerFrames.classify("main", cut, false));
	}

	@Test
	void theEventLoopOfTheUiThreadIsNotTheServers() {
		assertEquals(Verdict.KEEP, ServerFrames.classify("main",
				List.of("org.eclipse.swt.internal.win32.OS", "org.eclipse.swt.widgets.Display",
						"org.jkiss.dbeaver.ui.app.standalone.DBeaverApplication", "org.eclipse.equinox.launcher.Main")));
	}

	@Test
	void theRequestMachineryCountsOnlyBesideTheServer() {
		assertEquals(Verdict.KEEP, ServerFrames.classify("qtp-1",
				List.of("org.example.Servlet", "org.eclipse.jetty.server.Server", "java.lang.Thread")));
		assertEquals(Verdict.DROP, ServerFrames.classify("boundedElastic-1",
				List.of("org.example.Work", "com.vogella.eclipse.mcp.ui.internal.UiThread", "org.example.Outer",
						"io.modelcontextprotocol.server.McpServer", "reactor.core.Scheduler")));
	}
}
