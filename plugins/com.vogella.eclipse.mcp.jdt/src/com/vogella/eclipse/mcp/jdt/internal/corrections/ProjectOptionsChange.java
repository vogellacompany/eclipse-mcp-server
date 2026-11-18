package com.vogella.eclipse.mcp.jdt.internal.corrections;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;

/**
 * Replaces a Java project's compiler options, the headless stand-in for JDT UI's "Change project compliance" fix,
 * which writes the same options from inside a dialog.
 */
public final class ProjectOptionsChange extends Change {

	private final String name;
	private final IJavaProject project;
	/** The options to set, applied to the project's options as they are when the change runs. */
	private final Map<String, String> updates;

	/** Whether {@link #updates} replace all project options, as the undo of a change does. */
	private final boolean replace;

	private ProjectOptionsChange(String name, IJavaProject project, Map<String, String> updates, boolean replace) {
		this.name = name;
		this.project = project;
		this.updates = updates;
		this.replace = replace;
	}

	/** Raises the project's compliance, source and target level to {@code version}, optionally with preview features. */
	static ProjectOptionsChange compliance(String name, IJavaProject project, String version, boolean enablePreviews) {
		// only the keys the compliance level decides, so options changed after the proposal are kept
		Map<String, String> updates = new HashMap<>();
		JavaCore.setComplianceOptions(version, updates);
		if (enablePreviews) {
			updates.put(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, JavaCore.ENABLED);
			updates.put(JavaCore.COMPILER_PB_REPORT_PREVIEW_FEATURES, JavaCore.IGNORE);
		}
		return new ProjectOptionsChange(name, project, updates, false);
	}

	/** The project options this change sets to a value they do not have now, by key. */
	public Map<String, String> changedOptions() {
		Map<String, String> current = project.getOptions(false);
		Map<String, String> changed = new TreeMap<>();
		effectiveUpdates().forEach((key, value) -> {
			if (!Objects.equals(current.get(key), value)) {
				changed.put(key, value);
			}
		});
		return changed;
	}

	/** The name of the project whose options change. */
	public String projectName() {
		return project.getElementName();
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void initializeValidationData(IProgressMonitor monitor) {
		// the project's options are read when the change runs
	}

	@Override
	public RefactoringStatus isValid(IProgressMonitor monitor) {
		// a closed project still exists, but its options cannot be written
		if (!project.getProject().isAccessible()) {
			return RefactoringStatus.createFatalErrorStatus("The project " + project.getElementName() + " is closed or no longer exists."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		String target = updates.get(JavaCore.COMPILER_COMPLIANCE);
		String current = project.getOption(JavaCore.COMPILER_COMPLIANCE, true);
		if (!replace && higher(current, target)) {
			// raised since the problem was found: writing the fix's level now would lower it
			return RefactoringStatus.createFatalErrorStatus("The project " + project.getElementName() //$NON-NLS-1$
					+ " is already at compliance " + current + ", above the fix's " + target + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		return new RefactoringStatus();
	}

	/**
	 * The updates as they will be written: a source or target level the project already has above the fix's stays,
	 * since a project may legally build with a target above its compliance.
	 */
	private Map<String, String> effectiveUpdates() {
		if (replace) {
			return updates;
		}
		Map<String, String> effective = new HashMap<>(updates);
		for (String key : List.of(JavaCore.COMPILER_SOURCE, JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM)) {
			if (higher(project.getOption(key, true), updates.get(key))) {
				effective.remove(key);
			}
		}
		return effective;
	}

	private static boolean higher(String current, String target) {
		return current != null && target != null && JavaCore.compareJavaVersions(current, target) > 0;
	}

	@Override
	public Change perform(IProgressMonitor monitor) {
		if (monitor != null && monitor.isCanceled()) {
			// a change outside the source must not land after the caller gave up
			throw new OperationCanceledException();
		}
		Map<String, String> before = new HashMap<>(project.getOptions(false));
		Map<String, String> after = replace ? new HashMap<>(updates) : new HashMap<>(before);
		if (!replace) {
			after.putAll(effectiveUpdates());
		}
		project.setOptions(after);
		return new ProjectOptionsChange(name, project, before, true);
	}

	@Override
	public Object getModifiedElement() {
		return project;
	}
}
