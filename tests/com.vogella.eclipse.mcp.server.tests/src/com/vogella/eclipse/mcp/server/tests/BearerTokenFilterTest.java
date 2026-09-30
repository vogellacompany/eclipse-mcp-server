package com.vogella.eclipse.mcp.server.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.server.internal.ActiveSessions;
import com.vogella.eclipse.mcp.server.internal.BearerTokenFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The bearer check and the session counting that rides on it, against hand made servlet objects.
 */
class BearerTokenFilterTest {

	private static final String TOKEN = "secret-token";

	private final List<String> errors = new ArrayList<>();

	private final List<String> headers = new ArrayList<>();

	private int status = 200;

	private int chained;

	private HttpServletRequest request(String authorization, String method, String session) {
		return (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { HttpServletRequest.class }, (proxy, m, args) -> switch (m.getName()) {
				case "getHeader" -> "Authorization".equals(args[0]) ? authorization
						: "Mcp-Session-Id".equals(args[0]) ? session : null;
				case "getMethod" -> method;
				default -> null;
				});
	}

	private HttpServletResponse response() {
		return (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { HttpServletResponse.class }, (proxy, m, args) -> {
					switch (m.getName()) {
					case "sendError" -> {
						status = ((Integer) args[0]).intValue();
						errors.add(String.valueOf(args[1]));
					}
					case "setHeader" -> headers.add(args[0] + ": " + args[1]);
					case "getStatus" -> {
						return Integer.valueOf(status);
					}
					default -> {
					}
					}
					return null;
				});
	}

	private FilterChain chain() {
		return (ServletRequest req, ServletResponse res) -> chained++;
	}

	private void run(String authorization, String method, String session) throws Exception {
		new BearerTokenFilter(TOKEN).doFilter(request(authorization, method, session), response(), chain());
	}

	@Test
	void theRightTokenPassesOn() throws Exception {
		run("Bearer " + TOKEN, "POST", null);

		assertEquals(1, chained);
		assertTrue(errors.isEmpty());
	}

	@Test
	void aMissingHeaderIsAnUnauthorizedAnswerWithAChallenge() throws Exception {
		run(null, "POST", null);

		assertEquals(0, chained);
		assertEquals(401, status);
		assertEquals(1, errors.size());
		assertTrue(headers.stream().anyMatch(h -> h.startsWith("WWW-Authenticate: Bearer error=\"invalid_token\"")),
				headers.toString());
	}

	@Test
	void aWrongOrDifferentlyCasedTokenIsRefused() throws Exception {
		for (String authorization : new String[] { "Bearer other", "bearer " + TOKEN, TOKEN, "Bearer " + TOKEN + " " }) {
			chained = 0;
			errors.clear();
			run(authorization, "POST", null);
			assertEquals(0, chained, authorization);
			assertEquals(1, errors.size(), authorization);
		}
	}

	@Test
	void theRefusalNamesWhereTheCurrentTokenIs() throws Exception {
		run("Bearer nope", "POST", null);

		assertNotNull(errors.get(0));
		assertTrue(errors.get(0).contains("token"), errors.get(0));
	}

	@Test
	void anAcceptedSessionIsCountedAndADeleteEndsIt() throws Exception {
		String session = "bearer-filter-test-" + System.nanoTime();
		int before = ActiveSessions.count();

		run("Bearer " + TOKEN, "POST", session);
		assertEquals(before + 1, ActiveSessions.count());

		run("Bearer " + TOKEN, "DELETE", session);
		assertEquals(before, ActiveSessions.count());
	}

	@Test
	void aRefusedRequestDoesNotCountAsASession() throws Exception {
		int before = ActiveSessions.count();

		run("Bearer nope", "POST", "refused-session-" + System.nanoTime());

		assertEquals(before, ActiveSessions.count());
		assertFalse(errors.isEmpty());
	}

	@Test
	void aSessionTheTransportRejectedIsNotCounted() throws Exception {
		int before = ActiveSessions.count();
		FilterChain rejecting = (ServletRequest req, ServletResponse res) -> ((HttpServletResponse) res)
				.sendError(404, "Session not found");

		new BearerTokenFilter(TOKEN).doFilter(request("Bearer " + TOKEN, "POST", "stale-" + System.nanoTime()),
				response(), rejecting);

		assertEquals(before, ActiveSessions.count());
	}
}
