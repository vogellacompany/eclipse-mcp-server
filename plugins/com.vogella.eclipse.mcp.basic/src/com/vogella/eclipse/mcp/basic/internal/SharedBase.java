package com.vogella.eclipse.mcp.basic.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.eclipse.core.runtime.Platform;

import com.vogella.eclipse.mcp.core.FileLocations;

/**
 * The shared installation behind a user configuration area, and whether simpleconfigurator reads the user's
 * bundles.info at the next start: once the shared file's time differs from the one the user area recorded, every
 * line of the user's is ignored.
 */
public final class SharedBase {

	public static final String TIMESTAMP_FILE = "org.eclipse.equinox.simpleconfigurator/.baseBundlesInfoTimestamp"; //$NON-NLS-1$

	static final String TIMESTAMP_KEY = "bundlesInfoTimestamp"; //$NON-NLS-1$

	static final String EXT_KEY = "extTimestamp"; //$NON-NLS-1$

	/** Set when simpleconfigurator also reads fragment info files, whose newest time it records as extTimestamp. */
	private static final String FRAGMENTS = "p2.fragments"; //$NON-NLS-1$

	private static final long NO_TIMESTAMP = -1;

	/**
	 * What the timestamps say; {@code stale} means the user's bundles.info is ignored at the next start. Without
	 * fragment info files the current extTimestamp is -1; with them it is not computed here, and {@code extChecked}
	 * says so.
	 */
	public record State(Path baseBundlesInfo, long recorded, long current, long recordedExt, boolean extChecked) {

		public boolean stale() {
			return recorded != NO_TIMESTAMP && (recorded != current || extChecked && recordedExt != NO_TIMESTAMP);
		}
	}

	private SharedBase() {
	}

	/** The shared installation's bundles.info, or {@code null} when this configuration has no shared parent. */
	public static Path baseBundlesInfo(String bundlesInfo) {
		var location = Platform.getConfigurationLocation();
		var parent = location == null ? null : location.getParentLocation();
		if (parent == null || parent.getURL() == null) {
			return null;
		}
		Path base = FileLocations.pathOf(parent.getURL());
		Path file = base == null ? null : base.resolve(bundlesInfo);
		return file != null && Files.isRegularFile(file) ? file : null;
	}

	/** The state for a user area and its shared bundles.info. */
	public static State state(Path configuration, Path baseBundlesInfo) throws IOException {
		Properties recorded = recorded(configuration);
		return new State(baseBundlesInfo, number(recorded, TIMESTAMP_KEY),
				Files.getLastModifiedTime(baseBundlesInfo).toMillis(), number(recorded, EXT_KEY),
				System.getProperty(FRAGMENTS) == null);
	}

	private static Properties recorded(Path configuration) throws IOException {
		Properties properties = new Properties();
		Path file = configuration.resolve(TIMESTAMP_FILE);
		if (Files.isRegularFile(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				properties.load(in);
			}
		}
		return properties;
	}

	private static long number(Properties properties, String key) {
		String value = properties.getProperty(key);
		try {
			return value == null ? NO_TIMESTAMP : Long.parseLong(value.strip());
		} catch (NumberFormatException e) {
			return NO_TIMESTAMP;
		}
	}

	/**
	 * The lines of {@code from} that {@code other} does not hold, leaving out the named bundles and comments. Lines
	 * are compared whole, since a changed path or start level is as much a change as a new version.
	 */
	public static List<String> linesNotIn(List<String> from, List<String> other, Set<String> ignored) {
		Set<String> held = new HashSet<>();
		for (String line : other) {
			held.add(line.strip());
		}
		List<String> result = new ArrayList<>();
		for (String line : from) {
			String stripped = line.strip();
			if (stripped.isEmpty() || stripped.startsWith("#") || held.contains(stripped)) { //$NON-NLS-1$
				continue;
			}
			int comma = stripped.indexOf(',');
			if (comma < 0 || !ignored.contains(stripped.substring(0, comma))) {
				result.add(stripped);
			}
		}
		return result;
	}

	/** Records the shared file's time as it is now in the user area, keeping every other entry of the file. */
	public static void markCurrent(Path configuration, Path baseBundlesInfo) throws IOException {
		long current = Files.getLastModifiedTime(baseBundlesInfo).toMillis();
		Path file = configuration.resolve(TIMESTAMP_FILE);
		Properties properties = new Properties();
		if (Files.isRegularFile(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				properties.load(in);
			}
		}
		properties.setProperty(TIMESTAMP_KEY, Long.toString(current));
		if (System.getProperty(FRAGMENTS) == null) {
			properties.setProperty(EXT_KEY, Long.toString(NO_TIMESTAMP));
		}
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp"); //$NON-NLS-1$
		try (OutputStream out = Files.newOutputStream(temporary)) {
			properties.store(out, "Written by eclipse_substitute_bundle"); //$NON-NLS-1$
		}
		try {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

}
