package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import com.vogella.eclipse.mcp.core.json.Json;
import com.vogella.eclipse.mcp.core.json.JsonObject;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Turns the transport's error bodies into JSON-RPC errors.
 * <p>
 * The SDK's servlet serializes its {@code McpError} with the JSON mapper, so a request with a missing or unknown
 * session id is answered with the whole Java exception, stack trace included, instead of a JSON-RPC error a client
 * can read.
 */
public final class JsonRpcErrorFilter implements Filter {

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!(response instanceof HttpServletResponse httpResponse)) {
			chain.doFilter(request, response);
			return;
		}
		Buffering buffering = new Buffering(httpResponse);
		chain.doFilter(request, buffering);
		if (buffering.buffer != null) {
			String body = rewrite(buffering.buffer.toString());
			httpResponse.setContentType("application/json"); //$NON-NLS-1$
			httpResponse.setCharacterEncoding("UTF-8"); //$NON-NLS-1$
			httpResponse.getWriter().write(body);
			httpResponse.getWriter().flush();
		}
	}

	/** The body as a JSON-RPC error, or unchanged when it is not a serialized McpError. */
	static String rewrite(String body) {
		Object parsed;
		try {
			parsed = Json.parse(body);
		} catch (RuntimeException e) {
			return body;
		}
		if (!(parsed instanceof Map<?, ?> map) || !(map.get("jsonRpcError") instanceof Map<?, ?> error)) { //$NON-NLS-1$
			return body;
		}
		String message = String.valueOf(error.get("message")); //$NON-NLS-1$
		Object code = error.get("code"); //$NON-NLS-1$
		if (message.startsWith("Session not found")) { //$NON-NLS-1$
			message += ". The server has restarted or ended that session; initialize a new one."; //$NON-NLS-1$
		} else if (message.startsWith("Session ID required")) { //$NON-NLS-1$
			message += ". Send the Mcp-Session-Id the initialize response returned, or, on protocol 2026-07-28, name the version in _meta['io.modelcontextprotocol/protocolVersion'] instead of initializing."; //$NON-NLS-1$
			// the SDK says method not found, but the method exists and the request is what is wrong
			code = Integer.valueOf(-32600);
		}
		JsonObject rpcError = new JsonObject().put("code", code).put("message", message); //$NON-NLS-1$ //$NON-NLS-2$
		return new JsonObject().put("jsonrpc", "2.0").put("id", null).put("error", rpcError).toString(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	/** Holds back what is written after an error status, and passes everything else through. */
	private static final class Buffering extends HttpServletResponseWrapper {

		StringWriter buffer;

		Buffering(HttpServletResponse response) {
			super(response);
		}

		@Override
		public PrintWriter getWriter() throws IOException {
			if (getStatus() < 400) {
				return super.getWriter();
			}
			if (buffer == null) {
				buffer = new StringWriter();
			}
			return new PrintWriter(buffer);
		}
	}
}
