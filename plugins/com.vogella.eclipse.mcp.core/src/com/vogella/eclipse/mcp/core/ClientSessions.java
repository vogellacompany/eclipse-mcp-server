package com.vogella.eclipse.mcp.core;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * How many MCP clients are talking to this IDE right now.
 * <p>
 * Tools consult this to refuse the implicit "most recent" default when a second client could own it.
 * The server bundle sets the provider; without one the answer is one, which keeps headless runs and tests working.
 */
public final class ClientSessions {

	private static volatile IntSupplier provider;

	private ClientSessions() {
	}

	/** Installs the counter, or removes it when {@code null}. */
	public static void setProvider(IntSupplier supplier) {
		provider = supplier;
	}

	/** The number of clients seen recently, at least one. */
	public static int count() {
		IntSupplier current = provider;
		if (current == null) {
			return 1;
		}
		return Math.max(1, current.getAsInt());
	}

	/** Whether a tool may answer about "the most recent" anything without being told which. */
	public static boolean canAssumeASingleClient() {
		return count() <= 1;
	}

	/**
	 * The refusal to use when it cannot. Names the ids so the caller can pick one
	 * rather than being told only that it guessed wrong.
	 */
	public static String ambiguousDefault(String what, String argument, List<String> available) {
		return "%d MCP sessions or clients have made a request in the last minute, so 'the most recent %s' is not necessarily yours. Pass '%s' explicitly. Known ids, newest last: %s. A session-based client is counted per session, so one that opens a new session per call and never ends one looks like many; a session is dropped when it ends with an HTTP DELETE, or after 60 seconds of silence. A client on protocol 2026-07-28 has no session and cannot be told from a second copy of itself, so it counts as two for 60 seconds after its last request and always has to pass the id." //$NON-NLS-1$
				.formatted(Integer.valueOf(count()), what, argument,
						available.isEmpty() ? "none yet" : String.join(", ", available)); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
