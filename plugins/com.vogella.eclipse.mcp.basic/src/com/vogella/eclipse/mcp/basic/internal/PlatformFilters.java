package com.vogella.eclipse.mcp.basic.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;
import java.util.Locale;
import java.util.jar.Manifest;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;

/**
 * Decides whether a project's bundle can run on the platform this IDE runs on.
 * <p>
 * The authoritative signal is the {@code Eclipse-PlatformFilter} header of the
 * bundle manifest, evaluated against the running window system, operating system
 * and architecture. Name tokens such as {@code .win32.} are a convention of the
 * Eclipse projects themselves and are only used when there is no header.
 */
public final class PlatformFilters {

	private static final String HEADER = "Eclipse-PlatformFilter"; //$NON-NLS-1$

	/** The platform tokens the Eclipse projects put into bundle names. */
	private static final String[] KNOWN_WS = { "win32", "cocoa", "gtk", "carbon", "motif", "wpf" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

	private static final String[] KNOWN_OS = { "win32", "macosx", "linux", "aix", "hpux", "solaris" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

	/** Why a project was judged to run, or not run, on this platform. */
	public record Verdict(boolean mismatch, String reason) {
	}

	private PlatformFilters() {
	}

	public static Verdict evaluate(IProject project) {
		String filter = readHeader(project);
		if (filter != null) {
			try {
				Filter parsed = FrameworkUtil.createFilter(filter);
				boolean matches = parsed.match(currentPlatform());
				return new Verdict(!matches, "%s %s: %s".formatted(HEADER, matches ? "matches" : "does not match", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						filter));
			} catch (InvalidSyntaxException e) {
				return new Verdict(false, "%s could not be parsed, so the project was left alone: %s".formatted(HEADER, //$NON-NLS-1$
						filter));
			}
		}
		return fromName(project.getName());
	}

	private static String readHeader(IProject project) {
		IFile manifest = project.getFile("META-INF/MANIFEST.MF"); //$NON-NLS-1$
		if (!manifest.exists()) {
			return null;
		}
		try (InputStream in = manifest.getContents(true)) {
			String value = new Manifest(in).getMainAttributes().getValue(HEADER);
			return value == null || value.isBlank() ? null : value.trim();
		} catch (IOException | CoreException e) {
			return null;
		}
	}

	private static Hashtable<String, String> currentPlatform() {
		Hashtable<String, String> properties = new Hashtable<>();
		properties.put("osgi.ws", Platform.getWS()); //$NON-NLS-1$
		properties.put("osgi.os", Platform.getOS()); //$NON-NLS-1$
		properties.put("osgi.arch", Platform.getOSArch()); //$NON-NLS-1$
		properties.put("osgi.nl", Platform.getNL()); //$NON-NLS-1$
		return properties;
	}

	/**
	 * The fallback: a name that carries a foreign platform token and none of the
	 * tokens of this platform. It is a heuristic, and the reason says so.
	 */
	private static Verdict fromName(String name) {
		String lower = "." + name.toLowerCase(Locale.ROOT) + "."; //$NON-NLS-1$ //$NON-NLS-2$
		String foreign = null;
		for (String token : KNOWN_WS) {
			foreign = firstForeign(lower, token, Platform.getWS(), foreign);
		}
		for (String token : KNOWN_OS) {
			foreign = firstForeign(lower, token, Platform.getOS(), foreign);
		}
		if (foreign == null) {
			return new Verdict(false, "No " + HEADER + " and no foreign platform token in the name."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (lower.contains("." + Platform.getWS() + ".") || lower.contains("." + Platform.getOS() + ".")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			return new Verdict(false, "The name carries this platform's token as well, so it was left alone."); //$NON-NLS-1$
		}
		return new Verdict(true,
				"No %s; the name carries the foreign token '%s' while this IDE runs %s/%s/%s. This is a naming heuristic, not a declaration." //$NON-NLS-1$
						.formatted(HEADER, foreign, Platform.getWS(), Platform.getOS(), Platform.getOSArch()));
	}

	private static String firstForeign(String dottedName, String token, String current, String found) {
		if (found != null || token.equals(current)) {
			return found;
		}
		return dottedName.contains("." + token + ".") ? token : null; //$NON-NLS-1$ //$NON-NLS-2$
	}
}
