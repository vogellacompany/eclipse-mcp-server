package com.vogella.eclipse.mcp.server.internal;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.server.McpPreferences;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Presents an {@link IMcpTool} as an MCP tool, under a hard call timeout.
 */
public final class McpToolAdapter {

	/** Set once the tool body has returned, which {@code Future.isDone} cannot tell after a cancel. */
	private static final class Running {
		static final int QUEUED = 0, RUNNING = 1, DONE = 2;

		/** Claimed by the worker on start and by {@link #abandon} for a call that never started, never both. */
		final AtomicInteger state = new AtomicInteger(QUEUED);
	}

	/** Calls that outlived their timeout and whose tool body has not returned yet. */
	private static final Map<String, Running> ABANDONED = new LinkedHashMap<>();

	private McpToolAdapter() {
	}

	public static SyncToolSpecification toSpecification(IMcpTool tool, McpJsonMapper jsonMapper,
			ExecutorService executor) {
		Tool descriptor = Tool.builder().name(tool.getName()).description(tool.getDescription())
				.inputSchema(jsonMapper, tool.getInputSchema()).build();
		return SyncToolSpecification.builder().tool(descriptor)
				.callHandler((exchange, request) -> call(tool, request.arguments(), executor)).build();
	}

	private static CallToolResult call(IMcpTool tool, Map<String, Object> arguments, ExecutorService executor) {
		// read per call, so that changing the preference takes effect without a restart
		Duration timeout = McpPreferences.getCallTimeout();
		NullProgressMonitor monitor = new NullProgressMonitor();
		Running running = new Running();
		Future<McpToolResult> pending = executor.submit(() -> {
			if (!running.state.compareAndSet(Running.QUEUED, Running.RUNNING)) {
				return null;
			}
			try {
				return tool.call(arguments == null ? Map.of() : arguments, monitor);
			} finally {
				running.state.set(Running.DONE);
			}
		});
		try {
			McpToolResult result = pending.get(timeout.toSeconds(), TimeUnit.SECONDS);
			CallToolResult.Builder answer = CallToolResult.builder().addTextContent(result.text())
					.isError(Boolean.valueOf(result.isError()));
			for (McpToolResult.Image image : result.images()) {
				answer.addContent(
						new ImageContent(null, Base64.getEncoder().encodeToString(image.data()), image.mimeType()));
			}
			return answer.build();
		} catch (TimeoutException e) {
			// the monitor stops a cooperative tool; cancel(true) only interrupts, so a blocked
			// tool keeps its thread and must be reported through abandon
			monitor.setCanceled(true);
			pending.cancel(true);
			int abandoned = abandon(tool.getName(), running);
			return error(
					"The tool '%s' did not finish within %d seconds. Raise the timeout in Preferences > General > MCP Server if the operation is expected to take longer.%s" //$NON-NLS-1$
							.formatted(tool.getName(), timeout.toSeconds(), abandoned <= 1 ? "" //$NON-NLS-1$
									: " %d calls are now abandoned and still running in this IDE; they hold locks that block builds and refreshes, so a restart is worth considering." //$NON-NLS-1$
											.formatted(Integer.valueOf(abandoned))));
		} catch (InterruptedException e) {
			pending.cancel(true);
			Thread.currentThread().interrupt();
			return error("The call to '%s' was interrupted.".formatted(tool.getName())); //$NON-NLS-1$
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			ILog.get().error("The MCP tool '%s' failed".formatted(tool.getName()), cause); //$NON-NLS-1$
			return error("The tool '%s' failed: %s".formatted(tool.getName(), describe(cause))); //$NON-NLS-1$
		}
	}

	/**
	 * Some exceptions carry no message, and "failed: null" tells a caller nothing.
	 * Name the type and where it came from instead, and chase the cause chain.
	 */
	private static String describe(Throwable throwable) {
		StringBuilder text = new StringBuilder();
		for (Throwable current = throwable; current != null && text.length() < 500; current = current.getCause()) {
			if (text.length() > 0) {
				text.append(" caused by "); //$NON-NLS-1$
			}
			text.append(current.getMessage() == null || current.getMessage().isBlank()
					? current.getClass().getName()
							+ (current.getStackTrace().length == 0 ? "" : " at " + current.getStackTrace()[0]) //$NON-NLS-1$ //$NON-NLS-2$
					: current.getMessage());
			if (current.getCause() == current) {
				break;
			}
		}
		return text.toString();
	}

	/**
	 * Remembers a call that outlived its timeout, and reports how many are still
	 * running. Nothing can kill a thread stuck in native code, so the containment
	 * available is to stop the leak being invisible.
	 */
	private static synchronized int abandon(String name, Running running) {
		ABANDONED.entrySet().removeIf(entry -> entry.getValue().state.get() == Running.DONE);
		// claimed before the worker started, so it never will, or finished just after the timeout
		if (running.state.compareAndSet(Running.QUEUED, Running.DONE) || running.state.get() == Running.DONE) {
			return ABANDONED.size();
		}
		ABANDONED.put(name + "@" + System.nanoTime(), running); //$NON-NLS-1$
		if (ABANDONED.size() > 1) {
			ILog.get().warn("%d MCP tool calls are abandoned and still running: %s" //$NON-NLS-1$
					.formatted(Integer.valueOf(ABANDONED.size()), ABANDONED.keySet()));
		}
		return ABANDONED.size();
	}

	private static CallToolResult error(String message) {
		return CallToolResult.builder().addTextContent(message).isError(Boolean.TRUE).build();
	}
}
