package com.vogella.eclipse.mcp.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers that this IDE session changed what the framework runs, so the next
 * restart knows to discard the registry and resolver caches.
 * <p>
 * A hot install refreshes bundles whose registry contributions the cache written
 * at shutdown then describes wrongly, and the editors the workbench restores at
 * the next start fail with {@code InvalidRegistryObjectException}; a substitution
 * changes the jar behind a bundles.info line under the same caches. Both cost a
 * few seconds of {@code -clean} and nothing else.
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
