package com.vogella.eclipse.mcp.web.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Finds a Chromium based browser that is already installed, because this bundle never downloads one.
 */
final class BrowserLocator {

	/** System property naming the browser executable, which wins over everything found on the machine. */
	static final String BROWSER_PROPERTY = "com.vogella.eclipse.mcp.web.browser"; //$NON-NLS-1$

	private static final List<String> PATH_NAMES = List.of("google-chrome", "google-chrome-stable", "chromium", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"chromium-browser", "microsoft-edge", "microsoft-edge-stable", "chrome"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	private BrowserLocator() {
	}

	/** The browser to start: {@code requested} when given, else the system property, else the first one found. */
	static Path locate(String requested) throws IOException {
		if (requested != null) {
			return executable(Path.of(requested), "browserPath"); //$NON-NLS-1$
		}
		String configured = System.getProperty(BROWSER_PROPERTY);
		if (configured != null && !configured.isBlank()) {
			return executable(Path.of(configured.trim()), "-D" + BROWSER_PROPERTY); //$NON-NLS-1$
		}
		List<Path> candidates = candidates();
		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
				return candidate;
			}
		}
		throw new IOException(
				"No Chrome, Chromium or Edge was found. Install one, pass browserPath, or start the IDE with -D" //$NON-NLS-1$
						+ BROWSER_PROPERTY + "=<path to the browser executable>. Looked in: " + candidates); //$NON-NLS-1$
	}

	private static Path executable(Path path, String source) throws IOException {
		Path resolved = path;
		if (Files.isDirectory(path) && path.toString().endsWith(".app")) { //$NON-NLS-1$
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(path.resolve("Contents/MacOS"))) { //$NON-NLS-1$
				for (Path inner : stream) {
					resolved = inner;
					break;
				}
			}
		}
		if (!Files.isRegularFile(resolved) || !Files.isExecutable(resolved)) {
			throw new IOException(source + " names " + path + ", which is not an executable file."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return resolved;
	}

	/** Every place looked at, in order, for the error message and the tests. */
	static List<Path> candidates() {
		List<Path> result = new ArrayList<>();
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
		if (os.contains("mac")) { //$NON-NLS-1$
			for (String app : List.of("Google Chrome.app/Contents/MacOS/Google Chrome", //$NON-NLS-1$
					"Chromium.app/Contents/MacOS/Chromium", "Microsoft Edge.app/Contents/MacOS/Microsoft Edge", //$NON-NLS-1$ //$NON-NLS-2$
					"Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")) { //$NON-NLS-1$
				result.add(Path.of("/Applications", app)); //$NON-NLS-1$
				result.add(Path.of(System.getProperty("user.home"), "Applications", app)); //$NON-NLS-1$ //$NON-NLS-2$
			}
		} else if (os.contains("win")) { //$NON-NLS-1$
			for (String base : new String[] { System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), //$NON-NLS-1$ //$NON-NLS-2$
					System.getenv("LOCALAPPDATA") }) { //$NON-NLS-1$
				if (base != null) {
					result.add(Path.of(base, "Google", "Chrome", "Application", "chrome.exe")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					result.add(Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					result.add(Path.of(base, "Chromium", "Application", "chrome.exe")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
			}
		} else {
			result.add(Path.of("/opt/google/chrome/chrome")); //$NON-NLS-1$
			result.add(Path.of("/opt/microsoft/msedge/msedge")); //$NON-NLS-1$
		}
		String path = System.getenv("PATH"); //$NON-NLS-1$
		if (path != null) {
			for (String directory : path.split(File.pathSeparator)) {
				if (directory.isBlank()) {
					continue;
				}
				for (String name : PATH_NAMES) {
					result.add(Path.of(directory, os.contains("win") ? name + ".exe" : name)); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
		}
		result.addAll(playwrightBrowsers(os));
		return result;
	}

	/** The number in a Playwright directory such as {@code chromium-1243}, compared as a number so 1243 beats 999. */
	private static long buildNumber(Path build) {
		String name = build.getFileName().toString();
		try {
			return Long.parseLong(name.substring(name.lastIndexOf('-') + 1));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** Chromium builds a Playwright install already downloaded, newest first, so a machine set up for Playwright works too. */
	private static List<Path> playwrightBrowsers(String os) {
		List<Path> roots = new ArrayList<>();
		String configured = System.getenv("PLAYWRIGHT_BROWSERS_PATH"); //$NON-NLS-1$
		if (configured != null && !configured.isBlank() && !"0".equals(configured)) { //$NON-NLS-1$
			roots.add(Path.of(configured));
		}
		String home = System.getProperty("user.home"); //$NON-NLS-1$
		if (os.contains("mac")) { //$NON-NLS-1$
			roots.add(Path.of(home, "Library", "Caches", "ms-playwright")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		} else if (os.contains("win")) { //$NON-NLS-1$
			String local = System.getenv("LOCALAPPDATA"); //$NON-NLS-1$
			if (local != null) {
				roots.add(Path.of(local, "ms-playwright")); //$NON-NLS-1$
			}
		} else {
			roots.add(Path.of(home, ".cache", "ms-playwright")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		List<Path> result = new ArrayList<>();
		for (Path root : roots) {
			if (!Files.isDirectory(root)) {
				continue;
			}
			List<Path> builds = new ArrayList<>();
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "chromium-*")) { //$NON-NLS-1$
				stream.forEach(builds::add);
			} catch (IOException e) {
				continue;
			}
			builds.sort(Comparator.comparingLong(BrowserLocator::buildNumber).reversed());
			for (Path build : builds) {
				if (os.contains("mac")) { //$NON-NLS-1$
					result.add(build.resolve("chrome-mac/Chromium.app/Contents/MacOS/Chromium")); //$NON-NLS-1$
					result.add(build.resolve(
							"chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")); //$NON-NLS-1$
				} else if (os.contains("win")) { //$NON-NLS-1$
					result.add(build.resolve("chrome-win/chrome.exe")); //$NON-NLS-1$
					result.add(build.resolve("chrome-win64/chrome.exe")); //$NON-NLS-1$
				} else {
					result.add(build.resolve("chrome-linux64/chrome")); //$NON-NLS-1$
					result.add(build.resolve("chrome-linux/chrome")); //$NON-NLS-1$
				}
			}
		}
		return result;
	}
}
