package com.vogella.eclipse.mcp.ui.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.ConfigurationScope;
import org.eclipse.osgi.service.datalocation.Location;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.server.McpPreferences;

/**
 * Points the launcher at this plug-in's splash image, or puts back what it found.
 * <p>
 * Nothing here can affect the splash of the start that runs it. The launcher paints the
 * splash before OSGi exists, so the only thing a running plug-in can do is write the
 * configuration the NEXT start reads, which is the same shape
 * {@code eclipse_substitute_bundle} uses for bundles.info.
 * <p>
 * Through {@code osgi.splashLocation} rather than {@code osgi.splashPath}: the path form
 * names bundle directories the launcher reads from disk before OSGi, and this bundle
 * ships as a jar, so its image is unreadable that way. The image is copied out to the
 * configuration area instead and named absolutely.
 */
final class SplashBranding {

	/** Where the previous value is kept, so turning the preference off restores it exactly. */
	private static final String KEY_PREVIOUS = "splashLocationBefore"; //$NON-NLS-1$

	/** Written when the file had no such line, so restoring means removing it again. */
	private static final String NONE = "<none>"; //$NON-NLS-1$

	private SplashBranding() {
	}

	/**
	 * Brings config.ini into line with the preference, at every startup rather than
	 * only when the preference changes, so that a p2 update rewriting config.ini does
	 * not silently drop the setting.
	 */
	static void reconcile() {
		try {
			apply(McpPreferences.isReplaceSplash());
		} catch (IOException | RuntimeException e) {
			// never fatal: a splash is cosmetic and the IDE has already started
			ILog.get().warn("Could not reconcile the splash screen configuration", e); //$NON-NLS-1$
		}
	}

	/** @return what happened, for a tool or a preference page to report */
	static String apply(boolean replace) throws IOException {
		Path config = configFile();
		if (config == null || !Files.isReadable(config)) {
			return "There is no readable config.ini for this installation, so the splash cannot be changed."; //$NON-NLS-1$
		}
		List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
		String current = SplashConfig.read(lines, SplashConfig.KEY);
		var node = ConfigurationScope.INSTANCE.getNode(McpPreferences.QUALIFIER);

		if (!replace) {
			String previous = node.get(KEY_PREVIOUS, null);
			if (previous == null) {
				return null;
			}
			// only undo our own line: somebody else's later edit is theirs to keep
			String ours = splashFile().toString();
			List<String> restored = NONE.equals(previous) ? SplashConfig.remove(lines, SplashConfig.KEY)
					: SplashConfig.set(lines, SplashConfig.KEY, previous);
			if (current != null && !current.equals(ours)) {
				forget(node);
				return "config.ini names a splash this plug-in did not set, so it was left alone."; //$NON-NLS-1$
			}
			write(config, restored);
			forget(node);
			return "The original splash screen is back at the next restart."; //$NON-NLS-1$
		}

		Path image = extractSplash();
		if (image == null) {
			return "This plug-in ships no splash image, so there was nothing to point the launcher at."; //$NON-NLS-1$
		}
		if (image.toString().equals(current)) {
			return null;
		}
		if (node.get(KEY_PREVIOUS, null) == null) {
			node.put(KEY_PREVIOUS, current == null ? NONE : current);
			flush(node);
		}
		write(config, SplashConfig.set(lines, SplashConfig.KEY, image.toString()));
		return "The splash screen changes at the next restart. The previous setting is recorded, so switching the preference off puts it back."; //$NON-NLS-1$
	}

	/**
	 * Copies the image out of the bundle into the configuration area.
	 * <p>
	 * The launcher reads it from disk before any bundle is open, so it cannot stay
	 * inside a jar, and the configuration area is where per installation state of this
	 * kind belongs.
	 */
	private static Path extractSplash() throws IOException {
		Bundle bundle = FrameworkUtil.getBundle(SplashBranding.class);
		URL entry = bundle == null ? null : bundle.getEntry("splash.png"); //$NON-NLS-1$
		if (entry == null) {
			return null;
		}
		Path target = splashFile();
		Files.createDirectories(target.getParent());
		try (InputStream in = entry.openStream()) {
			Files.write(target, in.readAllBytes());
		}
		return target;
	}

	private static Path splashFile() {
		return configurationArea().resolve(McpPreferences.QUALIFIER).resolve("splash.png"); //$NON-NLS-1$
	}

	private static Path configurationArea() {
		Location location = Platform.getConfigurationLocation();
		Path area = location == null ? null : FileLocations.pathOf(location.getURL());
		return area == null ? Path.of(System.getProperty("user.home"), ".eclipse") //$NON-NLS-1$ //$NON-NLS-2$
				: area;
	}

	private static Path configFile() {
		Path area = configurationArea();
		return area == null ? null : area.resolve("config.ini"); //$NON-NLS-1$
	}

	/** Through a temporary file, because a half written config.ini does not start. */
	private static void write(Path config, List<String> lines) throws IOException {
		Path temporary = config.resolveSibling("config.ini.mcp-tmp"); //$NON-NLS-1$
		Files.write(temporary, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
		try {
			Files.move(temporary, config, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporary, config, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void forget(Preferences node) {
		node.remove(KEY_PREVIOUS);
		flush(node);
	}

	private static void flush(Preferences node) {
		try {
			node.flush();
		} catch (BackingStoreException e) {
			ILog.get().warn("Could not store the previous splash setting", e); //$NON-NLS-1$
		}
	}
}
