package com.vogella.eclipse.mcp.core;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Turns the {@code file:} URLs the framework hands out into filesystem paths.
 * <p>
 * Every {@code Location} in Equinox answers with a {@code URL}, and the obvious
 * ways of getting a path back out of one are both wrong off Linux.
 * {@code url.getPath()} on Windows yields {@code /C:/eclipse}, which
 * {@link Path#of(String, String...)} rejects outright, and it leaves {@code %20}
 * in place wherever the URL was encoded, so an installation under
 * {@code Program Files} misses on any window system. Going through
 * {@link Path#of(URI)} is the only form that decodes the escapes and knows about
 * drive letters and UNC shares.
 * <p>
 * That alone is not enough either: Equinox does not always encode what it puts
 * in a Location URL, so a path with a space in it is not a valid URI and
 * {@link URI#create(String)} throws. The raw form is therefore decoded by hand
 * as the fallback, which is what keeps this from failing on exactly the
 * installations it is meant to describe.
 */
public final class FileLocations {

	private static final String FILE_SCHEME = "file:"; //$NON-NLS-1$

	private FileLocations() {
	}

	/** Whether this JVM runs on a window system with drive letters and backslashes. */
	public static boolean isWindows() {
		// the separator rather than os.name: it is what the path parser itself uses,
		// so it cannot disagree with the thing this class has to get right
		return java.io.File.separatorChar == '\\';
	}

	/** The local path a {@code file:} URL names, or {@code null} when it names none. */
	public static Path pathOf(URL url) {
		return url == null ? null : pathOf(url.toString());
	}

	/**
	 * The local path for a {@code file:} URL or for a plain filesystem path,
	 * whichever was given.
	 *
	 * @return {@code null} when it is neither
	 */
	public static Path pathOf(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String text = value.strip();
		if (!isFileUrl(text)) {
			try {
				return Path.of(text);
			} catch (InvalidPathException e) {
				return null;
			}
		}
		try {
			return Path.of(URI.create(text));
		} catch (RuntimeException e) {
			return fromUnencodedUrl(text);
		}
	}

	private static boolean isFileUrl(String text) {
		return text.regionMatches(true, 0, FILE_SCHEME, 0, FILE_SCHEME.length());
	}

	/**
	 * The path of a {@code file:} URL that is not a well formed URI, which is what
	 * an unencoded space produces.
	 */
	private static Path fromUnencodedUrl(String url) {
		String path = decode(url.substring(FILE_SCHEME.length()));
		if (isWindows()) {
			path = path.replace('/', '\\');
			// "/C:/eclipse" is what a URL carries and "C:\eclipse" is what the path
			// parser accepts; a UNC "\\host\share" keeps both of its leading slashes
			if (path.length() > 2 && path.charAt(0) == '\\' && path.charAt(2) == ':') {
				path = path.substring(1);
			}
		}
		try {
			return Path.of(path);
		} catch (InvalidPathException e) {
			return null;
		}
	}

	/** Percent decoding only, never the {@code +} of a query string, which a path may contain. */
	private static String decode(String value) {
		if (value.indexOf('%') < 0) {
			return value;
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			int high = i + 2 < value.length() ? Character.digit(value.charAt(i + 1), 16) : -1;
			int low = i + 2 < value.length() ? Character.digit(value.charAt(i + 2), 16) : -1;
			if (c == '%' && high >= 0 && low >= 0) {
				bytes.write(high << 4 | low);
				i += 2;
			} else {
				bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
			}
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}
}
