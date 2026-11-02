package com.vogella.eclipse.mcp.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the early listeners observed while the application started. The framework has no record of these moments,
 * so a phase nobody was listening for stays unknown rather than being reconstructed.
 * <p>
 * The first observation of a phase wins, a phase is either measured or missed and never both, and everything here is
 * safe to call from any thread.
 */
public final class StartupTimeline {

	/** The phases only a listener can observe. */
	public enum Phase {
		FRAMEWORK_READY, WORKSPACE_LOCATION_SET, APPLICATION_START, FIRST_SHELL, MAIN_WINDOW_VISIBLE,
		MAIN_WINDOW_FIRST_PAINT, APPLICATION_RUNNING, WORKBENCH_STARTUP_COMPLETE
	}

	/** An observed moment, and how it was observed. */
	public record Mark(long epochMillis, String obtainedBy) {
	}

	/** A bundle activation seen from its STARTING event to its STARTED event. */
	public record BundleActivation(String symbolicName, String version, long startedMillis, long durationMillis) {
	}

	private record Pending(String symbolicName, String version, long startedMillis, long startedNanos) {
	}

	private static final StartupTimeline DEFAULT = new StartupTimeline();

	private final Map<Phase, Mark> marks = new ConcurrentHashMap<>();

	private final Map<Phase, String> missed = new ConcurrentHashMap<>();

	private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

	private final List<BundleActivation> activations = new ArrayList<>();

	private final Set<String> activeBeforeCapture = new LinkedHashSet<>();

	private Long frameworkCaptureStarted;

	private Long uiCaptureStarted;

	private boolean uiCaptureLate;

	/** The timeline of this process. */
	public static StartupTimeline getDefault() {
		return DEFAULT;
	}

	/** Records the phase now, and answers whether this was its first observation. */
	public boolean mark(Phase phase, String obtainedBy) {
		return mark(phase, System.currentTimeMillis(), obtainedBy);
	}

	/**
	 * Records the phase at the given time, and answers whether this was its first observation. A phase already recorded
	 * as missed stays missed: what fires now is a later occurrence, such as a second application.
	 */
	public synchronized boolean mark(Phase phase, long epochMillis, String obtainedBy) {
		return !missed.containsKey(phase) && marks.putIfAbsent(phase, new Mark(epochMillis, obtainedBy)) == null;
	}

	/** The observation of a phase, or null. */
	public Mark get(Phase phase) {
		return marks.get(phase);
	}

	/** Records that a phase had already passed when its listener started, unless it has been observed by now. */
	public synchronized void missed(Phase phase, String reason) {
		if (!marks.containsKey(phase)) {
			missed.putIfAbsent(phase, reason);
		}
	}

	/** Why a phase cannot be observed any more, or null. */
	public String missedReason(Phase phase) {
		return missed.get(phase);
	}

	/** Records when the framework listeners started and which bundles were active by then, unless they are timed anyway. */
	public synchronized void frameworkCaptureStarted(List<String> alreadyActive) {
		if (frameworkCaptureStarted == null) {
			frameworkCaptureStarted = Long.valueOf(System.currentTimeMillis());
			for (String name : alreadyActive) {
				if (!isTimed(name)) {
					activeBeforeCapture.add(name);
				}
			}
		}
	}

	/** When the framework listeners started, or null when they never did. */
	public synchronized Long frameworkCaptureStarted() {
		return frameworkCaptureStarted;
	}

	private boolean isTimed(String symbolicName) {
		return pending.values().stream().anyMatch(started -> started.symbolicName().equals(symbolicName))
				|| activations.stream().anyMatch(activation -> activation.symbolicName().equals(symbolicName));
	}

	/**
	 * The bundles that were active before the framework listeners started, or in their activator by then and have since
	 * finished, so their activation has no timing.
	 */
	public synchronized List<String> activeBeforeCapture() {
		return List.copyOf(activeBeforeCapture);
	}

	/** Records when the UI listeners started, and whether a workbench already existed by then. */
	public synchronized void uiCaptureStarted(boolean workbenchAlreadyThere) {
		if (uiCaptureStarted == null) {
			uiCaptureStarted = Long.valueOf(System.currentTimeMillis());
			uiCaptureLate = workbenchAlreadyThere;
		}
	}

	/** When the UI listeners started, or null when they never did. */
	public synchronized Long uiCaptureStarted() {
		return uiCaptureStarted;
	}

	/** Whether the UI listeners started after a workbench already existed. */
	public synchronized boolean uiCaptureLate() {
		return uiCaptureLate;
	}

	public void bundleStarting(long bundleId, String symbolicName, String version) {
		pending.put(Long.valueOf(bundleId),
				new Pending(symbolicName, version, System.currentTimeMillis(), System.nanoTime()));
	}

	/** Completes a timing; without a STARTING event the bundle was already in its activator when listening began. */
	public synchronized void bundleStarted(long bundleId, String symbolicName) {
		long now = System.nanoTime();
		Pending started = pending.remove(Long.valueOf(bundleId));
		if (started == null) {
			activeBeforeCapture.add(symbolicName);
		} else {
			activations.add(new BundleActivation(started.symbolicName(), started.version(), started.startedMillis(),
					(now - started.startedNanos()) / 1_000_000));
		}
	}

	/** Forgets an activation that never completed. */
	public void bundleStopped(long bundleId) {
		pending.remove(Long.valueOf(bundleId));
	}

	/** The completed activations, in the order they finished. */
	public synchronized List<BundleActivation> activations() {
		return List.copyOf(activations);
	}

	/** The bundles whose activator is running right now. */
	public List<String> stillStarting() {
		return pending.values().stream().map(Pending::symbolicName).sorted().toList();
	}
}
