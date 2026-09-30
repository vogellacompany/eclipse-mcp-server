package com.vogella.eclipse.mcp.server.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.server.McpPreferences;
import com.vogella.eclipse.mcp.server.internal.McpToolAdapter;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * The call timeout of the adapter and its account of calls that outlive it.
 */
class McpToolAdapterTest {

	/** Ignores cancellation until released, like a tool blocked on a lock. */
	private static final class StuckTool implements IMcpTool {

		final CountDownLatch release = new CountDownLatch(1);

		@Override
		public String getName() {
			return "stuck_tool";
		}

		@Override
		public String getDescription() {
			return "Blocks until released.";
		}

		@Override
		public String getInputSchema() {
			return "{\"type\":\"object\",\"properties\":{}}";
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			while (true) {
				try {
					release.await();
					return McpToolResult.of("{}");
				} catch (InterruptedException e) {
					// keeps running, which is what an abandoned call does
				}
			}
		}
	}

	@Test
	void countsEveryCallThatKeepsRunningAfterItsTimeout() throws Exception {
		Preferences node = InstanceScope.INSTANCE.getNode(McpPreferences.QUALIFIER);
		String previous = node.get(McpPreferences.KEY_CALL_TIMEOUT_SECONDS, null);
		StuckTool tool = new StuckTool();
		ExecutorService tools = Executors.newCachedThreadPool();
		ExecutorService callers = Executors.newFixedThreadPool(2);
		try {
			node.putInt(McpPreferences.KEY_CALL_TIMEOUT_SECONDS, McpPreferences.MIN_CALL_TIMEOUT_SECONDS);
			var specification = McpToolAdapter.toSpecification(tool, new JacksonMcpJsonMapperSupplier().get(), tools);
			var request = new CallToolRequest("stuck_tool", Map.of());
			Future<CallToolResult> first = callers.submit(() -> specification.callHandler().apply(null, request));
			Future<CallToolResult> second = callers.submit(() -> specification.callHandler().apply(null, request));
			String one = ((TextContent) first.get().content().get(0)).text();
			String two = ((TextContent) second.get().content().get(0)).text();
			assertTrue(one.contains("did not finish") && two.contains("did not finish"), one + " / " + two);
			assertTrue(one.contains(" calls are now abandoned") || two.contains(" calls are now abandoned"),
					"the second abandoned call should be counted with the first: " + one + " / " + two);
			assertEquals(Boolean.TRUE, first.get().isError());
		} finally {
			tool.release.countDown();
			restore(node, previous);
			callers.shutdownNow();
			tools.shutdownNow();
		}
	}

	private static void restore(Preferences node, String previous) throws BackingStoreException {
		if (previous == null) {
			node.remove(McpPreferences.KEY_CALL_TIMEOUT_SECONDS);
		} else {
			node.put(McpPreferences.KEY_CALL_TIMEOUT_SECONDS, previous);
		}
		node.flush();
	}
}
