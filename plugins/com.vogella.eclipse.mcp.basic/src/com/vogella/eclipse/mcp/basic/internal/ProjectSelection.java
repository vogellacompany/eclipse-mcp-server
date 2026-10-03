package com.vogella.eclipse.mcp.basic.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;

/**
 * Helpers shared by the tools that select projects by name.
 */
final class ProjectSelection {

	private ProjectSelection() {
	}

	/** The trimmed, non-blank entries of the {@code projects} argument, in order and without duplicates. */
	static Set<String> names(Map<String, Object> arguments) {
		Set<String> names = new LinkedHashSet<>();
		if (arguments != null && arguments.get("projects") instanceof List<?> list) { //$NON-NLS-1$
			for (Object entry : list) {
				if (entry != null && !String.valueOf(entry).isBlank()) {
					names.add(String.valueOf(entry).trim());
				}
			}
		}
		return names;
	}

	/** Open projects that reference this one and are not in {@code leaving}. */
	static List<String> blockingDependents(IProject project, Set<String> leaving) {
		List<String> blocking = new ArrayList<>();
		for (IProject referencing : project.getReferencingProjects()) {
			if (!leaving.contains(referencing.getName())) {
				blocking.add(referencing.getName());
			}
		}
		return blocking;
	}
}
