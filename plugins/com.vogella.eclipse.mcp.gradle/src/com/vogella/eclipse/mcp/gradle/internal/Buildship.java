package com.vogella.eclipse.mcp.gradle.internal;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;

/**
 * Whether Buildship is usable, and which project a request is about.
 * <p>
 * The Buildship and Tooling API bundles are optional: the tools ask {@link #isAvailable()}
 * first, and only {@link GradleOperations} touches their types.
 */
final class Buildship {

	static final String NATURE = "org.eclipse.buildship.core.gradleprojectnature"; //$NON-NLS-1$

	private static final String NOT_INSTALLED = "Buildship is not installed in this IDE, so the Gradle tools cannot work. Install 'Buildship Gradle Integration', or use eclipse_run_command to run ./gradlew, which leaves the workspace unaware of the change until it is refreshed."; //$NON-NLS-1$

	private Buildship() {
	}

	/** Why the Gradle tools cannot work: Buildship is missing, or installed in a version this bundle is not wired to. */
	static String unavailable() {
		var buildship = Platform.getBundle("org.eclipse.buildship.core"); //$NON-NLS-1$
		return buildship == null ? NOT_INSTALLED
				: "Buildship %s is installed in this IDE, but the Gradle tools are not wired to it, so they cannot work. They need Buildship 3.1 or newer. Use eclipse_run_command to run ./gradlew, which leaves the workspace unaware of the change until it is refreshed." //$NON-NLS-1$
						.formatted(buildship.getVersion());
	}

	/** Whether the optional Buildship and Tooling API bundles resolved. */
	static boolean isAvailable() {
		try {
			Class.forName("org.eclipse.buildship.core.GradleCore"); //$NON-NLS-1$
			Class.forName("org.gradle.tooling.ProjectConnection"); //$NON-NLS-1$
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	/** The open Gradle project called {@code name}, or the reason there is none. */
	static Lookup find(String name) {
		if (name == null || name.isBlank()) {
			return new Lookup(null, "Give the 'project' to use, as listed by eclipse_list_projects."); //$NON-NLS-1$
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name.strip());
		if (!project.exists()) {
			return new Lookup(null, "There is no project '%s' in the workspace.".formatted(name.strip())); //$NON-NLS-1$
		}
		if (!project.isOpen()) {
			return new Lookup(null, "Project '%s' is closed.".formatted(project.getName())); //$NON-NLS-1$
		}
		try {
			if (!project.hasNature(NATURE)) {
				return new Lookup(null,
						"Project '%s' is not a Gradle project: it has no Buildship nature. Import it through Buildship first." //$NON-NLS-1$
								.formatted(project.getName()));
			}
		} catch (CoreException e) {
			return new Lookup(null, "Could not read the natures of '%s': %s".formatted(project.getName(), e.getMessage())); //$NON-NLS-1$
		}
		return new Lookup(project, null);
	}

	/** A found project, or the message to refuse with. */
	record Lookup(IProject project, String error) {

		boolean failed() {
			return project == null;
		}
	}
}
