package com.vogella.eclipse.mcp.server.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts the MCP clients that have been heard from recently.
 * <p>
 * A session-based client is counted by its {@code Mcp-Session-Id} header on the way
 * through the token filter, since the SDK's transport does not expose its sessions. A
 * stateless client has no session and is counted by its clientInfo, so two copies of
 * the same client count as one. A client that has gone away stops refreshing its entry
 * and drops out of the count, which keeps a stale disconnect from making the defaults
 * refuse forever.
 */
public final class ActiveSessions {

	// short, because a client that reconnects looks like a second client until its
	// old session ages out, and reconnecting is exactly what a client does after
	// eclipse_restart. Ending a session removes it at once; this only bounds the
	// case where a client goes away without saying so
	private static final long WINDOW_MILLIS = 60 * 1000L;

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
		// prefixed so that no session id can collide with it
		seen("stateless:" + identity); //$NON-NLS-1$
	}

	/** A client ending its session stops being a client immediately. */
	static void ended(String sessionId) {
		if (sessionId != null) {
			lastSeen.remove(sessionId);
		}
	}

	/** How many distinct clients have been heard from inside the window. */
	public static int count() {
		long cutoff = System.currentTimeMillis() - WINDOW_MILLIS;
		lastSeen.values().removeIf(seen -> seen.longValue() < cutoff);
		return lastSeen.size();
	}

	public static void clear() {
		lastSeen.clear();
	}
}
