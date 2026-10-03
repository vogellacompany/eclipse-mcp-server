package com.vogella.eclipse.mcp.core;

import java.io.File;

/**
 * Whether a JRE install can actually compile.
 * <p>
 * A JDK can exist and still lack {@code lib/ct.sym}, which makes JDT fail every project bound to it.
 */
public final class JreUsability {

	private JreUsability() {
	}

	/** Why this install cannot compile, or {@code null} when nothing is wrong with it. */
	public static String reason(File location) {
		if (location == null) {
			return "The VM reports no install location, so what it contains cannot be checked."; //$NON-NLS-1$
		}
		if (!location.isDirectory()) {
			return "The install location '%s' is not a directory on this machine.".formatted(location); //$NON-NLS-1$
		}
		File lib = new File(location, "lib"); //$NON-NLS-1$
		if (new File(lib, "jrt-fs.jar").isFile() && !new File(lib, "ct.sym").isFile()) { //$NON-NLS-1$ //$NON-NLS-2$
			return "'%s' has lib/jrt-fs.jar but no lib/ct.sym, so JDT cannot initialise the release signatures. Every project bound to it fails to build with \"Failed to init ct.sym\", which mentions no target and looks nothing like a target platform problem. Activating anyway is a decision rather than an accident: give this target another JRE, or install the full JDK." //$NON-NLS-1$
					.formatted(location);
		}
		return null;
	}
}
