package com.vogella.eclipse.mcp.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Silences the questions and invisible breakpoints that turn an unattended launch into a gamble.
 * <p>
 * Three preferences stop a launch on handled exceptions or raise a modal perspective dialog.
 * They are restored when the last launch that asked for quiet has ended, counted so overlapping launches
 * cannot restore each other's settings.
 */
public final class LaunchPrompts {

	private static final String DEBUG_UI = "org.eclipse.debug.ui"; //$NON-NLS-1$

	private static final String JDT_DEBUG_UI = "org.eclipse.jdt.debug.ui"; //$NON-NLS-1$

	/**
	 * Asking to switch perspective on suspend. The value is a
	 * MessageDialogWithToggle constant, and "never" is the one that decides without
	 * asking.
	 */
	private static final String SWITCH_PERSPECTIVE = "org.eclipse.debug.ui.switch_perspective_on_suspend"; //$NON-NLS-1$

	private static final String NEVER = "never"; //$NON-NLS-1$

	/** JDT's two suspend-anyway settings, which are breakpoints in all but name. */
	private static final String SUSPEND_ON_UNCAUGHT = "org.eclipse.jdt.debug.ui.javaDebug.SuspendOnUncaughtExceptions"; //$NON-NLS-1$

	private static final String SUSPEND_ON_COMPILATION_ERRORS = "org.eclipse.jdt.debug.ui.javaDebug.SuspendOnCompilationErrors"; //$NON-NLS-1$

	private static final AtomicInteger QUIET = new AtomicInteger();

	private static volatile Map<String, String> previous;

	private LaunchPrompts() {
	}

	/**
	 * Silences the three settings for the duration of one launch. Every call has to
	 * be paired with {@link #release()}.
	 */
	public static synchronized void quiet() {
		if (QUIET.getAndIncrement() > 0) {
			return;
		}
		Map<String, String> saved = new LinkedHashMap<>();
		saved.put(key(DEBUG_UI, SWITCH_PERSPECTIVE), read(DEBUG_UI, SWITCH_PERSPECTIVE));
		saved.put(key(JDT_DEBUG_UI, SUSPEND_ON_UNCAUGHT), read(JDT_DEBUG_UI, SUSPEND_ON_UNCAUGHT));
		saved.put(key(JDT_DEBUG_UI, SUSPEND_ON_COMPILATION_ERRORS),
				read(JDT_DEBUG_UI, SUSPEND_ON_COMPILATION_ERRORS));
		previous = saved;
		write(DEBUG_UI, SWITCH_PERSPECTIVE, NEVER);
		write(JDT_DEBUG_UI, SUSPEND_ON_UNCAUGHT, "false"); //$NON-NLS-1$
		write(JDT_DEBUG_UI, SUSPEND_ON_COMPILATION_ERRORS, "false"); //$NON-NLS-1$
	}

	/**
	 * Puts the settings back once the last quiet launch has ended. A key that was
	 * absent is removed rather than written, or this would pin a setting the user
	 * never had.
	 */
	public static synchronized void release() {
		if (QUIET.get() == 0 || QUIET.decrementAndGet() > 0) {
			return;
		}
		Map<String, String> saved = previous;
		previous = null;
		if (saved == null) {
			return;
		}
		for (Map.Entry<String, String> entry : saved.entrySet()) {
			String[] parts = entry.getKey().split("/", 2); //$NON-NLS-1$
			if (entry.getValue() == null) {
				InstanceScope.INSTANCE.getNode(parts[0]).remove(parts[1]);
			} else {
				InstanceScope.INSTANCE.getNode(parts[0]).put(parts[1], entry.getValue());
			}
		}
	}

	/** How many launches currently hold the quiet, which is what a status can report. */
	public static int held() {
		return QUIET.get();
	}

	private static String key(String node, String name) {
		return node + "/" + name; //$NON-NLS-1$
	}

	private static String read(String node, String name) {
		return InstanceScope.INSTANCE.getNode(node).get(name, null);
	}

	private static void write(String node, String name, String value) {
		InstanceScope.INSTANCE.getNode(node).put(name, value);
	}
}
