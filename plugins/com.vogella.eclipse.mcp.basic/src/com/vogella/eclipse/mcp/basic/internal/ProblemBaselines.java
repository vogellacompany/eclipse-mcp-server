package com.vogella.eclipse.mcp.basic.internal;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Snapshots of the problem markers, so that "what did my change break" is a
 * question the IDE answers rather than one the client computes.
 * <p>
 * The alternative is reading every problem before and after and diffing the two
 * sets, which on a large project is a hundred kilobytes per call for an answer
 * that is usually a handful of lines.
 */
final class ProblemBaselines {

	static final String MARKER_PREFIX = "mcpproblems"; //$NON-NLS-1$

	private static final int KEEP = 10;

	private static final AtomicLong IDS = new AtomicLong();

	private static final Map<String, Set<String>> BASELINES = new LinkedHashMap<>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
			return size() > KEEP;
		}
	};

	private ProblemBaselines() {
	}

	static synchronized String take(Set<String> problems) {
		String id = "%s-%d".formatted(MARKER_PREFIX, Long.valueOf(IDS.incrementAndGet())); //$NON-NLS-1$
		BASELINES.put(id, new LinkedHashSet<>(problems));
		return id;
	}

	/** The snapshot, or {@code null} when the marker is unknown or has aged out. */
	static synchronized Set<String> of(String marker) {
		return BASELINES.get(marker);
	}

	static synchronized List<String> ids() {
		return List.copyOf(BASELINES.keySet());
	}
}
