package com.vogella.eclipse.mcp.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers that this IDE session changed what the framework runs, so the next restart discards the registry
 * and resolver caches.
 */
public final class FrameworkChanges {

	private static final List<String> CHANGES = new ArrayList<>();

	private FrameworkChanges() {
	}

	/** Records a change that the next start should not trust the caches after. */
	public static synchronized void markLiveChange(String what) {
		CHANGES.add(what);
	}

	/** The changes recorded since the IDE started, oldest first. */
	public static synchronized List<String> since() {
		return List.copyOf(CHANGES);
	}

	public static synchronized boolean any() {
		return !CHANGES.isEmpty();
	}
}
