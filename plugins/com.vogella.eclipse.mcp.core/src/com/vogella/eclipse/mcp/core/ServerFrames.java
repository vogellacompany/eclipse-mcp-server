package com.vogella.eclipse.mcp.core;

import java.util.List;

/**
 * Tells a profiled stack that belongs to this server apart from the application's own work.
 * <p>
 * Profiling through the server measures the server too: the sampler, the tool that was called and the HTTP request
 * that carried it. A stack is the server's cost when it runs on a thread the server owns or when the code executing
 * is the server's; it is the application's when the server only passed work through, as a tool does that dispatches
 * a menu entry onto the UI thread, and then only those frames are left out.
 */
public final class ServerFrames {

	/** What to do with one stack. */
	public enum Verdict {
		/** No frame of the server: the application's own. */
		KEEP,
		/** The application's work reached through the server: drop the server's frames, keep the rest. */
		ELIDE,
		/** The server's own cost: leave the whole stack out. */
		DROP
	}

	private static final String OWN = "com.vogella.eclipse.mcp."; //$NON-NLS-1$

	private static final String MCP_SDK = "io.modelcontextprotocol."; //$NON-NLS-1$

	/** The request machinery, which counts as the server's only beside a frame of the server, since an application may use it too. */
	private static final List<String> SERVING = List.of("org.eclipse.jetty.", "io.modelcontextprotocol.", //$NON-NLS-1$ //$NON-NLS-2$
			"reactor.", "tools.jackson.", "com.fasterxml.jackson.", "com.networknt."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	private static final List<String> JDK = List.of("java.", "javax.", "jdk.", "sun.", "com.sun."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

	private ServerFrames() {
	}

	/** Whether the class belongs to the server; its test bundles share the prefix and are not the server. */
	public static boolean isOwn(String className) {
		return className != null && className.startsWith(OWN) && !className.contains(".tests."); //$NON-NLS-1$
	}

	/** Whether the thread is one the server started, all of which are named "MCP ..." or "mcp-...". */
	public static boolean isServerThread(String threadName) {
		if (threadName == null) {
			return false;
		}
		String name = threadName.toLowerCase(java.util.Locale.ROOT);
		return name.startsWith("mcp-") || name.startsWith("mcp "); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Classifies a stack given as the class name of each frame, innermost first. */
	public static Verdict classify(String threadName, List<String> classNamesInnermostFirst) {
		if (isServerThread(threadName)) {
			return Verdict.DROP;
		}
		boolean own = false;
		boolean serving = false;
		for (String name : classNamesInnermostFirst) {
			if (name != null && name.startsWith(MCP_SDK)) {
				// the SDK is only ever on a stack because this server serves a request
				return Verdict.DROP;
			}
			own |= isOwn(name);
			serving |= startsWithAny(name, SERVING);
		}
		if (!own) {
			return Verdict.KEEP;
		}
		if (serving) {
			return Verdict.DROP;
		}
		String innermost = firstNonJdk(classNamesInnermostFirst);
		String outermost = firstNonJdk(classNamesInnermostFirst.reversed());
		// a thread the server started, such as the sampler or a tool call, or the server's code executing
		return isOwn(outermost) || isOwn(innermost) ? Verdict.DROP : Verdict.ELIDE;
	}

	/** Whether the class belongs to the JDK. */
	public static boolean isJdk(String className) {
		return startsWithAny(className, JDK);
	}

	private static String firstNonJdk(List<String> names) {
		for (String name : names) {
			if (!startsWithAny(name, JDK)) {
				return name;
			}
		}
		return null;
	}

	private static boolean startsWithAny(String name, List<String> prefixes) {
		if (name == null) {
			return false;
		}
		for (String prefix : prefixes) {
			if (name.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}
}
