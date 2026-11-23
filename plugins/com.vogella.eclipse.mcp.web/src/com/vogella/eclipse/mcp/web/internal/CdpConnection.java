package com.vogella.eclipse.mcp.web.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import com.vogella.eclipse.mcp.core.json.Json;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * One WebSocket to a Chrome DevTools Protocol target, with commands answered by id and events handed to listeners.
 * <p>
 * Uses the JDK's own HTTP client, so the bundle needs no third party library and no browser driver.
 */
final class CdpConnection implements AutoCloseable {

	/** Never through a proxy: the browser listens on the loopback interface and a system proxy would not reach it. */
	static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
			.connectTimeout(Duration.ofSeconds(5)).build();

	private final WebSocket socket;
	private final AtomicInteger nextId = new AtomicInteger();
	private final Map<Integer, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
	private final CopyOnWriteArrayList<BiConsumer<String, Map<String, Object>>> listeners = new CopyOnWriteArrayList<>();
	private final CompletableFuture<Void> closed = new CompletableFuture<>();

	private CdpConnection(URI webSocketUrl, long timeoutMillis) throws IOException {
		try {
			socket = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
					.buildAsync(webSocketUrl, new Listener()).get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while connecting to " + webSocketUrl, e); //$NON-NLS-1$
		} catch (ExecutionException | TimeoutException e) {
			throw new IOException("Cannot connect to the browser at " + webSocketUrl + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	static CdpConnection open(URI webSocketUrl, long timeoutMillis) throws IOException {
		return new CdpConnection(webSocketUrl, timeoutMillis);
	}

	void addListener(BiConsumer<String, Map<String, Object>> listener) {
		listeners.add(listener);
	}

	boolean isOpen() {
		return !closed.isDone() && !socket.isOutputClosed();
	}

	/**
	 * Sends a command and waits for its result.
	 *
	 * @throws IOException when the browser answers with an error, the connection is gone or the wait runs out
	 */
	Map<String, Object> send(String method, JsonObject params, long timeoutMillis) throws IOException {
		if (!isOpen()) {
			throw new IOException("The browser connection is closed."); //$NON-NLS-1$
		}
		int id = nextId.incrementAndGet();
		CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
		pending.put(Integer.valueOf(id), answer);
		JsonObject message = new JsonObject().put("id", Integer.valueOf(id)).put("method", method) //$NON-NLS-1$ //$NON-NLS-2$
				.put("params", params == null ? new JsonObject() : params); //$NON-NLS-1$
		try {
			synchronized (socket) {
				socket.sendText(Json.write(message), true).get(timeoutMillis, TimeUnit.MILLISECONDS);
			}
			return answer.get(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(method + " was interrupted", e); //$NON-NLS-1$
		} catch (TimeoutException e) {
			throw new IOException(method + " got no answer within " + timeoutMillis + " ms", e); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (ExecutionException e) {
			throw new IOException(e.getCause() == null ? e.getMessage() : e.getCause().getMessage(), e);
		} finally {
			pending.remove(Integer.valueOf(id));
		}
	}

	@SuppressWarnings("unchecked")
	private void dispatch(String text) {
		Object parsed;
		try {
			parsed = Json.parse(text);
		} catch (IllegalArgumentException e) {
			return;
		}
		if (!(parsed instanceof Map<?, ?> map)) {
			return;
		}
		Map<String, Object> message = (Map<String, Object>) map;
		Object id = message.get("id"); //$NON-NLS-1$
		if (id instanceof Number number) {
			CompletableFuture<Map<String, Object>> answer = pending.get(Integer.valueOf(number.intValue()));
			if (answer == null) {
				return;
			}
			if (message.get("error") instanceof Map<?, ?> error) { //$NON-NLS-1$
				answer.completeExceptionally(new IOException(String.valueOf(error.get("message")))); //$NON-NLS-1$
			} else {
				Object result = message.get("result"); //$NON-NLS-1$
				answer.complete(result instanceof Map<?, ?> ? (Map<String, Object>) result : Map.of());
			}
			return;
		}
		Object method = message.get("method"); //$NON-NLS-1$
		if (method instanceof String name) {
			Object params = message.get("params"); //$NON-NLS-1$
			Map<String, Object> values = params instanceof Map<?, ?> ? (Map<String, Object>) params : Map.of();
			for (BiConsumer<String, Map<String, Object>> listener : listeners) {
				try {
					listener.accept(name, values);
				} catch (RuntimeException e) {
					// a broken listener must not stop the answers to commands
				}
			}
		}
	}

	private void failPending(String reason) {
		closed.complete(null);
		IOException failure = new IOException(reason);
		pending.values().forEach(answer -> answer.completeExceptionally(failure));
	}

	@Override
	public void close() {
		if (!closed.isDone()) {
			try {
				socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS); //$NON-NLS-1$
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (ExecutionException | TimeoutException e) {
				socket.abort();
			}
		}
		failPending("The browser connection was closed."); //$NON-NLS-1$
	}

	private final class Listener implements WebSocket.Listener {

		private final StringBuilder partial = new StringBuilder();

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			partial.append(data);
			if (last) {
				String text = partial.toString();
				partial.setLength(0);
				dispatch(text);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			failPending("The browser closed the connection."); //$NON-NLS-1$
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			failPending("The browser connection failed: " + error.getMessage()); //$NON-NLS-1$
		}
	}
}
