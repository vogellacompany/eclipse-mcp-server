package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Holds requests back until the server has loaded its tools.
 * <p>
 * The socket is bound before the tools load, because a client reconnecting after a
 * restart gives up after a few refused connections, while a request that waits a few
 * seconds simply succeeds.
 */
public final class ReadyGate implements Filter {

	private final CompletableFuture<Void> ready = new CompletableFuture<>();

	private final long waitMillis;

	public ReadyGate(long waitMillis) {
		this.waitMillis = waitMillis;
	}

	/** Lets every held and future request through. */
	public void open() {
		ready.complete(null);
	}

	/** Answers every held and future request as unavailable, for a server that stops before it was ready. */
	public void close() {
		ready.completeExceptionally(new IllegalStateException("The MCP server stopped")); //$NON-NLS-1$
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		try {
			ready.get(waitMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			unavailable(response, "The MCP server is shutting down."); //$NON-NLS-1$
			return;
		} catch (ExecutionException e) {
			unavailable(response, "The MCP server failed to start or was stopped; the Error Log of the IDE says why."); //$NON-NLS-1$
			return;
		} catch (TimeoutException e) {
			unavailable(response, "The MCP server is still loading its tools. Retry in a few seconds."); //$NON-NLS-1$
			return;
		}
		chain.doFilter(request, response);
	}

	private static void unavailable(ServletResponse response, String message) throws IOException {
		if (response instanceof HttpServletResponse httpResponse) {
			httpResponse.setHeader("Retry-After", "5"); //$NON-NLS-1$ //$NON-NLS-2$
			httpResponse.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, message);
		}
	}
}
