package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.Launch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * A debug launch the server did not start, as Debug As in the UI makes, is a session the tools report.
 */
class ForeignDebugLaunchTest {

	@TempDir
	Path directory;

	@Test
	void aLaunchStartedOutsideTheServerIsReported() throws Exception {
		Path source = Files.writeString(directory.resolve("Sleeper.java"),
				"class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep(60_000); } }");
		String java = ProcessHandle.current().info().command().orElse("java");
		Process running = new ProcessBuilder(java, source.toString()).start();
		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
		Launch launch = new Launch(null, ILaunchManager.DEBUG_MODE, null);
		try {
			DebugPlugin.newProcess(launch, running, "sleeper");
			manager.addLaunch(launch);
			McpToolResult status = TestFixture.call("eclipse_debug_status", Map.of());
			assertTrue(status.text().contains("debug-"), "the launch started outside the server is missing: " + status.text());
		} finally {
			running.destroyForcibly();
			manager.removeLaunch(launch);
		}
	}
}
