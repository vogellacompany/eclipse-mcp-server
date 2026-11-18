package com.vogella.eclipse.mcp.jdt.internal.corrections;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.eclipse.core.runtime.IProgressMonitor;
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
		updates.forEach((key, value) -> {
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
		return project.getProject().isAccessible() ? new RefactoringStatus()
				: RefactoringStatus.createFatalErrorStatus("The project " + project.getElementName() + " is closed or no longer exists."); //$NON-NLS-1$ //$NON-NLS-2$
	}

	@Override
	public Change perform(IProgressMonitor monitor) {
		Map<String, String> before = new HashMap<>(project.getOptions(false));
		Map<String, String> after = replace ? new HashMap<>(updates) : new HashMap<>(before);
		if (!replace) {
			after.putAll(updates);
		}
		project.setOptions(after);
		return new ProjectOptionsChange(name, project, before, true);
	}

	@Override
	public Object getModifiedElement() {
		return project;
	}
}
