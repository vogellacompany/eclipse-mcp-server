package com.vogella.eclipse.mcp.server.internal;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds tools that descriptions name but this installation does not have, since a product may install only some bundles. */
public final class ToolReferences {

	private static final Pattern TOOL_NAME = Pattern.compile("\\beclipse_[a-z]+(?:_[a-z]+)*\\b"); //$NON-NLS-1$

	private ToolReferences() {
	}

	/** The tool names in {@code texts} that are not among {@code installed}, sorted. */
	public static Set<String> missing(Collection<String> installed, Collection<String> texts) {
		Set<String> missing = new TreeSet<>();
		for (String text : texts) {
			Matcher matcher = TOOL_NAME.matcher(text);
			while (matcher.find()) {
				if (!installed.contains(matcher.group())) {
					missing.add(matcher.group());
				}
			}
		}
		return missing;
	}
}
