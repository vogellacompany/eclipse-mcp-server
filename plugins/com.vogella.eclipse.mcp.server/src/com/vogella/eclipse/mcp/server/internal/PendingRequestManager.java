package com.vogella.eclipse.mcp.server.internal;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.server.McpRequestManager;
import io.modelcontextprotocol.modern.server.McpTransportResponse;
import reactor.core.publisher.Mono;

/**
 * Stands in for the stateless server while the tools load, since the transport is
 * built before the socket binds and the server only once the tools exist. The
 * {@link ReadyGate} keeps requests out until {@link #set} has run.
 */
public final class PendingRequestManager implements McpRequestManager {

	private volatile McpRequestManager delegate;

	public void set(McpRequestManager manager) {
		delegate = manager;
	}

	private McpRequestManager delegate() {
		McpRequestManager current = delegate;
		if (current == null) {
			throw new IllegalStateException("The MCP server has not loaded its tools yet"); //$NON-NLS-1$
		}
		return current;
	}

	@Override
	public Mono<McpTransportResponse> handle(McpTransportContext context, JSONRPCRequest request) {
		return Mono.defer(() -> delegate().handle(context, request));
	}

	@Override
	public Mono<McpTransportResponse> handleBlocking(McpTransportContext context, JSONRPCRequest request) {
		return Mono.defer(() -> delegate().handleBlocking(context, request));
	}

	@Override
	public Mono<Void> handleNotification(McpTransportContext context, JSONRPCNotification notification) {
		return Mono.defer(() -> delegate().handleNotification(context, notification));
	}
}
