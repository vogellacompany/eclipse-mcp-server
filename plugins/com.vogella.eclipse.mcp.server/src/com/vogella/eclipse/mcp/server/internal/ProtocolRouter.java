package com.vogella.eclipse.mcp.server.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.vogella.eclipse.mcp.core.json.Json;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves one endpoint for both protocol generations: requests of a stateless revision
 * (2026-07-28 and later) go to the stateless server, everything else to the session-based
 * one, so clients that still open with {@code initialize} keep working.
 */
public final class ProtocolRouter extends HttpServlet {

	private static final long serialVersionUID = 1L;

	static final String PROTOCOL_VERSION_KEY = "io.modelcontextprotocol/protocolVersion"; //$NON-NLS-1$

	static final String CLIENT_INFO_KEY = "io.modelcontextprotocol/clientInfo"; //$NON-NLS-1$

	private static final String SESSION_HEADER = "Mcp-Session-Id"; //$NON-NLS-1$

	private static final String VERSION_HEADER = "MCP-Protocol-Version"; //$NON-NLS-1$

	/** The SDK transports' own default limit. */
	private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

	private final HttpServlet sessions;

	private final HttpServlet stateless;

	private final List<String> statelessVersions;

	public ProtocolRouter(HttpServlet sessions, HttpServlet stateless, List<String> statelessVersions) {
		this.sessions = sessions;
		this.stateless = stateless;
		this.statelessVersions = List.copyOf(statelessVersions);
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		sessions.init(config);
		stateless.init(config);
	}

	@Override
	public void destroy() {
		sessions.destroy();
		stateless.destroy();
		super.destroy();
	}

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		// GET and DELETE exist only for sessions: the stream a session listens on, and ending it
		if (!"POST".equals(request.getMethod()) || request.getHeader(SESSION_HEADER) != null) { //$NON-NLS-1$
			sessions.service(request, response);
			return;
		}
		byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
			return;
		}
		Replayed replayed = new Replayed(request, body);
		Object message = parse(body, request.getCharacterEncoding());
		if (!isStateless(message, request.getHeader(VERSION_HEADER))) {
			sessions.service(replayed, response);
			return;
		}
		stateless.service(replayed, response);
		// only once the request was accepted, as for sessions
		if (response.getStatus() < 400) {
			ActiveSessions.seenStateless(clientIdentity(message));
		}
	}

	/**
	 * Whether a request without a session id belongs to a stateless revision: it says so in
	 * {@code _meta}, or its version header names one when the body cannot, as for a notification.
	 */
	boolean isStateless(Object message, String versionHeader) {
		if (versionOf(message) instanceof String version && !version.isBlank()) {
			return true;
		}
		return versionHeader != null && statelessVersions.contains(versionHeader.strip());
	}

	/** Names a stateless client by its clientInfo, which is all such a request says about who sent it. */
	static String clientIdentity(Object message) {
		if (meta(message) instanceof Map<?, ?> meta && meta.get(CLIENT_INFO_KEY) instanceof Map<?, ?> info) {
			return info.get("name") + "/" + info.get("version"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		return "anonymous"; //$NON-NLS-1$
	}

	private static Object versionOf(Object message) {
		return meta(message) instanceof Map<?, ?> meta ? meta.get(PROTOCOL_VERSION_KEY) : null;
	}

	private static Object meta(Object message) {
		if (message instanceof Map<?, ?> map && map.get("params") instanceof Map<?, ?> params) { //$NON-NLS-1$
			return params.get("_meta"); //$NON-NLS-1$
		}
		return null;
	}

	private static Object parse(byte[] body, String encoding) {
		try {
			String text = new String(body,
					encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding));
			return Json.parse(text);
		} catch (RuntimeException e) {
			// malformed JSON is the session server's to answer, as before
			return null;
		}
	}

	/** The request with its body read once and served again to whichever server takes it. */
	private static final class Replayed extends HttpServletRequestWrapper {

		private final byte[] body;

		Replayed(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream in = new ByteArrayInputStream(body);
			return new ServletInputStream() {
				@Override
				public int read() throws IOException {
					return in.read();
				}

				@Override
				public int read(byte[] buffer, int offset, int length) throws IOException {
					return in.read(buffer, offset, length);
				}

				@Override
				public boolean isFinished() {
					return in.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new IllegalStateException("The body has already been read"); //$NON-NLS-1$
				}
			};
		}
	}
}
