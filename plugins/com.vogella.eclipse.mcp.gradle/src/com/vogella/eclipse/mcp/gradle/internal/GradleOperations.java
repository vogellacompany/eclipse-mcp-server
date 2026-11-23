package com.vogella.eclipse.mcp.gradle.internal;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.buildship.core.GradleBuild;
import org.eclipse.buildship.core.GradleCore;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.gradle.tooling.CancellationTokenSource;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.GradleTask;

/**
 * Everything that touches Buildship or the Tooling API; callers check {@link Buildship#isAvailable()}
 * first, and no signature here carries a type from either, so loading a tool never needs them.
 */
final class GradleOperations {

	private GradleOperations() {
	}

	private static GradleBuild build(IProject project) {
		return GradleCore.getWorkspace().getBuild(project).orElseThrow(() -> new IllegalStateException(
				"Buildship has no Gradle build for project '%s'.".formatted(project.getName()))); //$NON-NLS-1$
	}

	/** Synchronizes the project's build with Buildship, writing what it reports to {@code run}; true unless it failed. */
	static boolean synchronize(IProject project, GradleRuns.Run run, IProgressMonitor monitor) {
		IStatus status = build(project).synchronize(monitor).getStatus();
		report(status, run, 0);
		return status.getSeverity() < IStatus.ERROR;
	}

	private static void report(IStatus status, GradleRuns.Run run, int depth) {
		if (depth == 0 && status.isOK() && status.getMessage().isBlank()) {
			run.append("Synchronized."); //$NON-NLS-1$
			return;
		}
		String level = switch (status.getSeverity()) {
		case IStatus.ERROR -> "ERROR"; //$NON-NLS-1$
		case IStatus.WARNING -> "WARNING"; //$NON-NLS-1$
		default -> "INFO"; //$NON-NLS-1$
		};
		if (!status.getMessage().isBlank()) {
			run.append("  ".repeat(depth) + level + ": " + status.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (status.getException() != null) {
			run.append("  ".repeat(depth + 1) + GradleRuns.describe(status.getException()).replace("\n", " ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		for (IStatus child : status.getChildren()) {
			report(child, run, depth + 1);
		}
	}

	/** Runs the tasks through Buildship's connection, streaming Gradle's output to {@code run}; true unless the build failed. */
	static boolean runTasks(IProject project, List<String> tasks, List<String> arguments, GradleRuns.Run run,
			IProgressMonitor monitor) {
		CancellationTokenSource cancellation = GradleConnector.newCancellationTokenSource();
		run.onCancel(cancellation::cancel);
		try (OutputStream output = run.output()) {
			build(project).withConnection(connection -> {
				connection.newBuild().forTasks(tasks.toArray(String[]::new)).withArguments(arguments)
						.setStandardOutput(output).setStandardError(output).setColorOutput(false)
						.withCancellationToken(cancellation.token()).run();
				return null;
			}, monitor);
			return true;
		} catch (Exception e) {
			// a failed build is a result, with Gradle's reason as the last lines of the output
			run.append(GradleRuns.describe(e));
			return false;
		} finally {
			refresh(project);
		}
	}

	/** The tasks of the project's build, the root first, each as Gradle names it. */
	static List<GradleTaskInfo> listTasks(IProject project, IProgressMonitor monitor) throws Exception {
		return build(project).withConnection(connection -> {
			List<GradleTaskInfo> found = new ArrayList<>();
			collect(connection.getModel(GradleProject.class), found);
			return found;
		}, monitor);
	}

	private static void collect(GradleProject gradleProject, List<GradleTaskInfo> found) {
		for (GradleTask task : gradleProject.getTasks()) {
			found.add(new GradleTaskInfo(gradleProject.getPath(), task.getName(), task.getPath(), task.getGroup(),
					task.getDescription(), task.isPublic()));
		}
		for (GradleProject child : gradleProject.getChildren()) {
			collect(child, found);
		}
	}

	/** A build writes files the workspace has not seen, so look at them before the next read. */
	private static void refresh(IProject project) {
		try {
			project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		} catch (CoreException e) {
			// the next tool that reads files refreshes again
		}
	}
}
