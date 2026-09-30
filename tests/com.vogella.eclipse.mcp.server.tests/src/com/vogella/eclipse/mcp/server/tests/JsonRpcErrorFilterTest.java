package com.vogella.eclipse.mcp.server.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.json.Json;
import com.vogella.eclipse.mcp.server.internal.JsonRpcErrorFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * What a client reads when the SDK's transport answers with a serialized exception.
 */
class JsonRpcErrorFilterTest {

	private static final String UNKNOWN_SESSION = "{\"jsonRpcError\":{\"code\":-32001,\"message\":\"Session not found: abc\"},\"stackTrace\":[]}";

	private static final String NO_SESSION = "{\"jsonRpcError\":{\"code\":-32601,\"message\":\"Session ID required in mcp-session-id header\"}}";

	private int status;

	private String written;

	private String send(int answerStatus, String body) throws Exception {
		status = answerStatus;
		StringWriter out = new StringWriter();
		HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { HttpServletResponse.class }, (proxy, m, args) -> switch (m.getName()) {
				case "getStatus" -> Integer.valueOf(status);
				case "getWriter" -> new PrintWriter(out);
				default -> null;
				});
		FilterChain chain = (ServletRequest req, ServletResponse res) -> {
			res.getWriter().write(body);
			res.getWriter().flush();
		};
		new JsonRpcErrorFilter().doFilter(null, response, chain);
		written = out.toString();
		return written;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> error(String json) {
		return (Map<String, Object>) ((Map<String, Object>) Json.parse(json)).get("error");
	}

	@Test
	void anUnknownSessionBecomesAJsonRpcErrorWithoutTheStackTrace() throws Exception {
		String answer = send(404, UNKNOWN_SESSION);

		assertFalse(answer.contains("stackTrace"), answer);
		Map<String, Object> error = error(answer);
		assertTrue(String.valueOf(error.get("message")).contains("initialize a new one"), answer);
		assertEquals(-32001L, ((Number) error.get("code")).longValue());
	}

	@Test
	void aMissingSessionIdIsAnInvalidRequestRatherThanAMissingMethod() throws Exception {
		Map<String, Object> error = error(send(400, NO_SESSION));

		assertEquals(-32600L, ((Number) error.get("code")).longValue());
		assertTrue(String.valueOf(error.get("message")).contains("Mcp-Session-Id"));
	}

	@Test
	void anErrorBodyThatIsNotASerializedMcpErrorIsPassedThrough() throws Exception {
		assertEquals("plain text failure", send(500, "plain text failure"));
		assertEquals("{\"other\":1}", send(500, "{\"other\":1}"));
	}

	@Test
	void aSuccessfulAnswerIsNotTouched() throws Exception {
		assertEquals(UNKNOWN_SESSION, send(200, UNKNOWN_SESSION));
	}
}
