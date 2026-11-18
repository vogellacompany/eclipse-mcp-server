package com.vogella.eclipse.mcp.jdt.internal.corrections;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;

/**
 * Replaces a Java project's compiler options, the headless stand-in for JDT UI's "Change project compliance" fix,
 * which writes the same options from inside a dialog.
 */
final class ProjectOptionsChange extends Change {

	private final String name;
	private final IJavaProject project;
	private final Map<String, String> options;

	private ProjectOptionsChange(String name, IJavaProject project, Map<String, String> options) {
		this.name = name;
		this.project = project;
		this.options = options;
	}

	/** Raises the project's compliance, source and target level to {@code version}, optionally with preview features. */
	static ProjectOptionsChange compliance(String name, IJavaProject project, String version, boolean enablePreviews) {
		Map<String, String> options = new HashMap<>(project.getOptions(false));
		JavaCore.setComplianceOptions(version, options);
		if (enablePreviews) {
			options.put(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, JavaCore.ENABLED);
			options.put(JavaCore.COMPILER_PB_REPORT_PREVIEW_FEATURES, JavaCore.IGNORE);
		}
		return new ProjectOptionsChange(name, project, options);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void initializeValidationData(IProgressMonitor monitor) {
		// the options are read when the change runs
	}

	@Override
	public RefactoringStatus isValid(IProgressMonitor monitor) {
		return project.exists() ? new RefactoringStatus()
				: RefactoringStatus.createFatalErrorStatus("The project " + project.getElementName() + " no longer exists."); //$NON-NLS-1$ //$NON-NLS-2$
	}

	@Override
	public Change perform(IProgressMonitor monitor) {
		ProjectOptionsChange undo = new ProjectOptionsChange(name, project, new HashMap<>(project.getOptions(false)));
		project.setOptions(options);
		return undo;
	}

	@Override
	public Object getModifiedElement() {
		return project;
	}
}
