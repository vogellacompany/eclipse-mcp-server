package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

/**
 * What a bundle's {@code Export-Package} header says about who may use a package.
 * <p>
 * For an OSGi bundle this decides what a workspace search can prove. In a plainly
 * exported package, no references in this workspace proves nothing: consumers may
 * exist anywhere. In a package that is not exported, or is exported
 * {@code x-internal}, the same search very nearly settles it. With
 * {@code x-friends} it can settle it exactly, because the friend list names every
 * bundle allowed to reference the package, and if all of them are in this
 * workspace there is nowhere else to look.
 * <p>
 * Read from the manifest rather than from PDE's resolved state, so that this
 * bundle keeps its dependencies and the header's directives survive: PDE's own
 * model reports the package names with the directives stripped, which is exactly
 * the part the question needs.
 */
final class PackageExports {

	/** The tiers, from the one a search can settle to the one it cannot. */
	static final String NOT_EXPORTED = "not-exported"; //$NON-NLS-1$

	static final String INTERNAL = "internal-api"; //$NON-NLS-1$

	static final String FRIENDS = "internal-api-friends"; //$NON-NLS-1$

	static final String PUBLIC = "public-api"; //$NON-NLS-1$

	static final String NOT_A_BUNDLE = "not-a-bundle"; //$NON-NLS-1$

	record Export(String tier, List<String> friends) {
	}

	private final Map<String, Map<String, Export>> byProject = new HashMap<>();

	private final Map<String, Boolean> isBundle = new HashMap<>();

	Export of(IProject project, String packageName) {
		String name = project.getName();
		if (!byProject.containsKey(name)) {
			byProject.put(name, read(project));
		}
		if (!Boolean.TRUE.equals(isBundle.get(name))) {
			return new Export(NOT_A_BUNDLE, List.of());
		}
		return byProject.get(name).getOrDefault(packageName, new Export(NOT_EXPORTED, List.of()));
	}

	private Map<String, Export> read(IProject project) {
		IFile manifest = project.getFile("META-INF/MANIFEST.MF"); //$NON-NLS-1$
		isBundle.put(project.getName(), Boolean.valueOf(manifest.exists()));
		Map<String, Export> exports = new HashMap<>();
		if (!manifest.exists()) {
			return exports;
		}
		String header = FileText.manifestHeader(manifest, "Export-Package"); //$NON-NLS-1$
		if (header == null) {
			return exports;
		}
		for (String clause : split(header, ',')) {
			List<String> parts = split(clause, ';');
			if (parts.isEmpty()) {
				continue;
			}
			List<String> packages = new ArrayList<>();
			String tier = PUBLIC;
			List<String> friends = List.of();
			for (String part : parts) {
				String trimmed = part.trim();
				int assignment = trimmed.indexOf('=');
				if (assignment < 0) {
					packages.add(trimmed);
					continue;
				}
				String key = trimmed.substring(0, assignment).replace(":", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
				String value = unquote(trimmed.substring(assignment + 1).trim());
				if ("x-internal".equals(key) && Boolean.parseBoolean(value)) { //$NON-NLS-1$
					tier = INTERNAL;
				} else if ("x-friends".equals(key)) { //$NON-NLS-1$
					tier = FRIENDS;
					friends = new ArrayList<>();
					for (String friend : value.split(",")) { //$NON-NLS-1$
						if (!friend.isBlank()) {
							friends.add(friend.trim());
						}
					}
				}
			}
			for (String name : packages) {
				exports.put(name, new Export(tier, friends));
			}
		}
		return exports;
	}

	/** Splits on a separator that is not inside quotes, which x-friends lists are. */
	private static List<String> split(String value, char separator) {
		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '"') {
				quoted = !quoted;
				current.append(c);
			} else if (c == separator && !quoted) {
				parts.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		if (!current.isEmpty()) {
			parts.add(current.toString());
		}
		return parts;
	}

	private static String unquote(String value) {
		return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") //$NON-NLS-1$ //$NON-NLS-2$
				? value.substring(1, value.length() - 1)
				: value;
	}
}
