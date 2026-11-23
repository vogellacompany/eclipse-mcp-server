package com.vogella.eclipse.mcp.core;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Pre-answers the platform's "Errors in Workspace" launch prompt for the
 * duration of one launch.
 * <p>
 * Launching with compile errors otherwise raises a modal dialog, which blocks a
 * call nobody is watching and reports as a launch that never started. The value
 * is set around the launch and put back afterwards, so a person's own Run As
 * keeps prompting. Suppressions are counted so overlapping launches cannot
 * restore each other's setting.
 */
public final class CompileErrorPrompt {

	private static final String DEBUG_UI = "org.eclipse.debug.ui"; //$NON-NLS-1$

	/** The constant is named PREF_CONTINUE_WITH_COMPILE_ERROR; its value is not. */
	private static final String KEY = "org.eclipse.debug.ui.cancel_launch_with_compile_errors"; //$NON-NLS-1$

	/**
	 * The only value that lets a launch through. CompileErrorPromptStatusHandler
	 * proceeds on "always" and opens the dialog for everything else, "never"
	 * included, which is the opposite of what that word suggests.
	 */
	private static final String PROCEED = "always"; //$NON-NLS-1$

	private static final AtomicInteger SUPPRESSED = new AtomicInteger();

	private static volatile String previous;

	private CompileErrorPrompt() {
	}

	/** The value the preference holds right now, or {@code null}. */
	public static String effectiveValue() {
		return InstanceScope.INSTANCE.getNode(DEBUG_UI).get(KEY, null);
	}

	/** The user's own value, the one saved while a launch holds the suppression, or {@code null}. */
	public static synchronized String originalValue() {
		return SUPPRESSED.get() > 0 ? previous : effectiveValue();
	}

	/**
	 * Answers the prompt for the next launch, returning the user's original value
	 * to report. Only the first caller writes "always"; later ones just count.
	 */
	public static synchronized String suppress() {
		if (SUPPRESSED.getAndIncrement() > 0) {
			return previous;
		}
		previous = effectiveValue();
		InstanceScope.INSTANCE.getNode(DEBUG_UI).put(KEY, PROCEED);
		return previous;
	}

	/**
	 * Puts the preference back once the last suppressed launch has ended. A key
	 * that was absent is removed rather than written back as a value, or this
	 * would pin a setting the user never had.
	 */
	public static synchronized void restore() {
		if (SUPPRESSED.get() == 0 || SUPPRESSED.decrementAndGet() > 0) {
			return;
		}
		var node = InstanceScope.INSTANCE.getNode(DEBUG_UI);
		if (previous == null) {
			node.remove(KEY);
		} else {
			node.put(KEY, previous);
		}
		previous = null;
	}

	/** How many launches currently hold the suppression. */
	public static int held() {
		return SUPPRESSED.get();
	}
}
