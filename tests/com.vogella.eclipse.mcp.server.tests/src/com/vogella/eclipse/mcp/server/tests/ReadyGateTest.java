package com.vogella.eclipse.mcp.server.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.server.internal.ReadyGate;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The gate that holds requests while the tools load, against hand made servlet objects.
 */
class ReadyGateTest {

	private final List<Integer> statuses = new ArrayList<>();

	private final List<String> messages = new ArrayList<>();

	private final List<String> headers = new ArrayList<>();

	private final AtomicInteger chained = new AtomicInteger();

	private void run(ReadyGate gate) throws Exception {
		HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { HttpServletRequest.class }, (proxy, m, args) -> null);
		HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { HttpServletResponse.class }, (proxy, m, args) -> {
					switch (m.getName()) {
					case "sendError" -> {
						statuses.add((Integer) args[0]);
						messages.add(String.valueOf(args[1]));
					}
					case "setHeader" -> headers.add(args[0] + ": " + args[1]);
					default -> {
					}
					}
					return null;
				});
		FilterChain chain = (ServletRequest req, ServletResponse res) -> chained.incrementAndGet();
		gate.doFilter(request, response, chain);
	}

	@Test
	void aHeldRequestPassesOnceTheGateOpens() throws Exception {
		ReadyGate gate = new ReadyGate(10_000);
		CompletableFuture<Void> held = CompletableFuture.runAsync(() -> {
			try {
				run(gate);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		Thread.sleep(200);
		assertEquals(0, chained.get(), "passed before the gate opened");

		gate.open();
		held.get(5, TimeUnit.SECONDS);

		assertEquals(1, chained.get());
		assertTrue(statuses.isEmpty());
	}

	@Test
	void aGateThatNeverOpensAnswersUnavailable() throws Exception {
		run(new ReadyGate(50));

		assertEquals(0, chained.get());
		assertEquals(List.of(Integer.valueOf(503)), statuses);
		assertTrue(headers.contains("Retry-After: 5"), headers::toString);
		assertTrue(messages.get(0).contains("still loading"), messages::toString);
	}

	@Test
	void aClosedGateAnswersUnavailableAtOnce() throws Exception {
		ReadyGate gate = new ReadyGate(60_000);
		gate.close();
		long start = System.nanoTime();

		run(gate);

		assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000, "waited for a closed gate");
		assertEquals(List.of(Integer.valueOf(503)), statuses);
		assertTrue(messages.get(0).contains("failed to start or was stopped"), messages::toString);
	}
}
