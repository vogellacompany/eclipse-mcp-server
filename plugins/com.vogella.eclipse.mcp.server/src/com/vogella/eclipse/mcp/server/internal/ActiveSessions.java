package com.vogella.eclipse.mcp.server.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts the MCP clients that have been heard from recently.
 * <p>
 * A session-based client is counted by its {@code Mcp-Session-Id} header on the way
 * through the token filter, since the SDK's transport does not expose its sessions. A
 * stateless client has no session and cannot be told from a second copy of itself, so
 * it counts as two and never gets an implicit default. A client that has gone away
 * stops refreshing its entry and drops out of the count, which keeps a stale
 * disconnect from making the defaults refuse forever.
 */
public final class ActiveSessions {

	// short, because a client that reconnects looks like a second client until its
	// old session ages out, and reconnecting is exactly what a client does after
	// eclipse_restart. Ending a session removes it at once; this only bounds the
	// case where a client goes away without saying so
	private static final long WINDOW_MILLIS = 60 * 1000L;

	// prefixed so that no session id can collide with it
	private static final String STATELESS_PREFIX = "stateless:"; //$NON-NLS-1$

	private static final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

	private ActiveSessions() {
	}

	static void seen(String sessionId) {
		if (sessionId != null && !sessionId.isBlank()) {
			lastSeen.put(sessionId, Long.valueOf(System.currentTimeMillis()));
		}
	}

	/** A request of a stateless client, named by {@link ProtocolRouter#clientIdentity}. */
	static void seenStateless(String identity) {
			seen(STATELESS_PREFIX + identity);
	}

	/** A client ending its session stops being a client immediately. */
	static void ended(String sessionId) {
		if (sessionId != null) {
			lastSeen.remove(sessionId);
		}
	}

	/** How many clients may have been heard from inside the window, a stateless one counting twice. */
	public static int count() {
		long cutoff = System.currentTimeMillis() - WINDOW_MILLIS;
		lastSeen.values().removeIf(seen -> seen.longValue() < cutoff);
		int stateless = (int) lastSeen.keySet().stream().filter(key -> key.startsWith(STATELESS_PREFIX)).count();
		return lastSeen.size() + stateless;
	}

	public static void clear() {
		lastSeen.clear();
	}
}
