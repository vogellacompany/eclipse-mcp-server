package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * The edits this plug-in makes to {@code config.ini}, as pure text.
 * <p>
 * Line based rather than through {@link java.util.Properties}: storing a Properties
 * object rewrites the whole file, dropping its comments and reordering every key, and
 * that file is p2's. Touching one line and leaving the rest byte for byte alone is what
 * keeps the change reviewable and reversible.
 * <p>
 * Values in this file are escaped the way a properties file escapes them, so a colon in
 * {@code platform:/base/...} is written {@code platform\:/base/...}. Anything written
 * here has to be escaped the same way or the launcher reads a truncated path.
 */
public final class SplashConfig {

	static final String KEY = "osgi.splashLocation"; //$NON-NLS-1$

	private SplashConfig() {
	}

	/** The raw value of a key, unescaped, or {@code null} when the file does not set it. */
	public static String read(List<String> lines, String key) {
		for (String line : lines) {
			String trimmed = line.strip();
			if (!trimmed.startsWith(key + "=")) { //$NON-NLS-1$
				continue;
			}
			return unescape(trimmed.substring(key.length() + 1));
		}
		return null;
	}

	/**
	 * The file with {@code key} set to {@code value}, replacing the line that set it or
	 * appending one when it did not.
	 */
	public static List<String> set(List<String> lines, String key, String value) {
		List<String> result = new ArrayList<>(lines.size() + 1);
		boolean replaced = false;
		for (String line : lines) {
			if (!replaced && line.strip().startsWith(key + "=")) { //$NON-NLS-1$
				result.add(key + "=" + escape(value)); //$NON-NLS-1$
				replaced = true;
			} else {
				result.add(line);
			}
		}
		if (!replaced) {
			result.add(key + "=" + escape(value)); //$NON-NLS-1$
		}
		return result;
	}

	/** The file without any line setting {@code key}. */
	public static List<String> remove(List<String> lines, String key) {
		List<String> result = new ArrayList<>(lines.size());
		for (String line : lines) {
			if (!line.strip().startsWith(key + "=")) { //$NON-NLS-1$
				result.add(line);
			}
		}
		return result;
	}

	/** Escapes the characters a properties file gives meaning to, which is what p2 writes. */
	public static String escape(String value) {
		StringBuilder out = new StringBuilder(value.length() + 8);
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == ':' || c == '=' || c == '\\' || c == '!' || c == '#') {
				out.append('\\');
			}
			out.append(c);
		}
		return out.toString();
	}

	public static String unescape(String value) {
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '\\' && i + 1 < value.length()) {
				out.append(value.charAt(++i));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}
}
