package com.vogella.eclipse.mcp.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.Version;

/**
 * The server name, version and instructions a client receives on {@code initialize}, as
 * contributed through the {@code com.vogella.eclipse.mcp.core.server} extension point.
 */
public record McpServerDescription(String name, String version, String instructions) {

	public static final String EXTENSION_POINT_ID = "com.vogella.eclipse.mcp.core.server"; //$NON-NLS-1$

	/** An {@code instructions} contribution; {@code replace} drops the server's default text. */
	public record Instructions(String contributor, String text, boolean replace) {
	}

	/** A {@code serverInfo} contribution; a {@code null} version keeps the default. */
	public record Info(String contributor, String name, String version) {
	}

	/** The defaults with the contributions from the extension registry applied. */
	public static McpServerDescription read(McpServerDescription defaults) {
		IExtensionRegistry registry = Platform.getExtensionRegistry();
		if (registry == null) {
			return defaults;
		}
		List<Instructions> texts = new ArrayList<>();
		List<Info> infos = new ArrayList<>();
		for (IConfigurationElement element : registry.getConfigurationElementsFor(EXTENSION_POINT_ID)) {
			String contributor = element.getContributor().getName();
			switch (element.getName()) {
				case "instructions" -> texts.add(new Instructions(contributor, element.getValue(), //$NON-NLS-1$
						Boolean.parseBoolean(element.getAttribute("replace")))); //$NON-NLS-1$
				case "serverInfo" -> { //$NON-NLS-1$
					String version = element.getAttribute("version"); //$NON-NLS-1$
					infos.add(new Info(contributor, element.getAttribute("name"), //$NON-NLS-1$
							version == null || version.isBlank() ? bundleVersion(contributor) : version));
				}
				default -> ILog.get().warn("Unknown element <%s> contributed by %s to %s".formatted(element.getName(), //$NON-NLS-1$
						contributor, EXTENSION_POINT_ID));
			}
		}
		if (infos.size() > 1) {
			ILog.get().warn("Several bundles contribute serverInfo to %s, using the one from %s".formatted( //$NON-NLS-1$
					EXTENSION_POINT_ID, infos.stream().map(Info::contributor).sorted().findFirst().orElseThrow()));
		}
		return compose(defaults, texts, infos);
	}

	/**
	 * Appends the contributed texts to the default instructions in contributor order, without
	 * the default when one of them replaces it, and takes the first contributed server info.
	 */
	public static McpServerDescription compose(McpServerDescription defaults, List<Instructions> texts,
			List<Info> infos) {
		List<Instructions> contributed = texts.stream().filter(text -> text.text() != null && !text.text().isBlank())
				.sorted(Comparator.comparing(Instructions::contributor)).toList();
		List<String> parts = new ArrayList<>();
		if (contributed.stream().noneMatch(Instructions::replace)) {
			parts.add(defaults.instructions());
		}
		contributed.forEach(text -> parts.add(text.text().strip()));
		Info info = infos.stream().filter(candidate -> candidate.name() != null && !candidate.name().isBlank())
				.min(Comparator.comparing(Info::contributor)).orElse(null);
		String name = info == null ? defaults.name() : info.name().strip();
		String version = info == null || info.version() == null ? defaults.version() : info.version().strip();
		return new McpServerDescription(name, version, String.join("\n\n", parts)); //$NON-NLS-1$
	}

	private static String bundleVersion(String symbolicName) {
		Bundle bundle = Platform.getBundle(symbolicName);
		if (bundle == null) {
			return null;
		}
		Version version = bundle.getVersion();
		return "%d.%d.%d".formatted(version.getMajor(), version.getMinor(), version.getMicro()); //$NON-NLS-1$
	}
}
