package com.vogella.eclipse.mcp.core.internal;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.vogella.eclipse.mcp.core.WorkspaceSync;

/**
 * Runs builds as jobs and keeps their outcome so that a client can poll instead of
 * holding an HTTP request open for the length of a build.
 */
public final class BuildRegistry {

	/** How many finished builds stay queryable. */
	private static final int HISTORY = 20;

	public static final String CLEAN = "clean"; //$NON-NLS-1$

	public static final String REFRESH = "refresh"; //$NON-NLS-1$

	private static final BuildRegistry INSTANCE = new BuildRegistry();

	private final AtomicLong ids = new AtomicLong();

	private final Map<String, Build> builds = new LinkedHashMap<>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Build> eldest) {
			return size() > HISTORY && !"running".equals(eldest.getValue().state()); //$NON-NLS-1$
		}
	};

	private String lastId;

	/**
	 * The job family of the builds this server starts, so they can be found and
	 * waited for the way the platform's own build jobs can. Without one a caller
	 * can watch a build only through its id.
	 */
	public static final Object FAMILY = "com.vogella.eclipse.mcp.build"; //$NON-NLS-1$

	public static BuildRegistry getInstance() {
		return INSTANCE;
	}

	private BuildRegistry() {
	}

	/** One build, its outcome, and the problems the workspace had once it ended. */
	public static final class Build {

		private final String id;
		private final String kind;
		private final List<String> projects;
		private final long startedAt = System.currentTimeMillis();
		private final CountDownLatch finished = new CountDownLatch(1);

		private volatile String state = "running"; //$NON-NLS-1$
		private volatile long endedAt;
		private volatile long refreshMillis = -1;
		private volatile long buildMillis = -1;
		private volatile int[] refreshedFiles;
		private volatile int[] builtFiles;
		private volatile List<String> builtProjects = List.of();
		private volatile String note;
		private volatile Job job;
		private volatile List<String> builderFailures = List.of();
		private volatile int errors = -1;
		private volatile int warnings = -1;

		Build(String id, String kind, List<String> projects) {
			this.id = id;
			this.kind = kind;
			this.projects = List.copyOf(projects);
		}

		public String id() {
			return id;
		}

		public String kind() {
			return kind;
		}

		public List<String> projects() {
			return projects;
		}

		public String state() {
			return state;
		}

		public long elapsedMillis() {
			return (endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt;
		}

		/** Time spent refreshing from disk, {@code -1} when no refresh was asked for. */
		public long refreshMillis() {
			return refreshMillis;
		}

		/** Time spent building, {@code -1} for a refresh that never built. */
		public long buildMillis() {
			return buildMillis;
		}

		/**
		 * Files the refresh read in as {added, changed, removed}, or {@code null}
		 * when no refresh ran.
		 * <p>
		 * A duration alone cannot tell a refresh that picked up a whole branch
		 * switch from one that found nothing, and both look like a fast build.
		 */
		public int[] refreshedFiles() {
			return refreshedFiles;
		}

		/** Files the builders wrote as {added, changed, removed}, {@code null} when nothing was built. */
		public int[] builtFiles() {
			return builtFiles;
		}

		/** The projects a build delta actually arrived for. */
		public List<String> builtProjects() {
			return builtProjects;
		}

		/** Set when the outcome needs a caveat, such as a clean that rebuilt nothing. */
		public String note() {
			return note;
		}

		/**
		 * Builder exceptions that never became markers. A build that reports no
		 * problems while its builder threw is the misleading case worth avoiding.
		 */
		public List<String> builderFailures() {
			return builderFailures;
		}

		/** Error count once the build ended, {@code -1} while it is still running. */
		public int errors() {
			return errors;
		}

		public int warnings() {
			return warnings;
		}

		/** Ends a build whose job threw, so that nobody waits for an outcome that will never be set. */
		void abort(RuntimeException failure) {
			if (!"running".equals(state)) { //$NON-NLS-1$
				return;
			}
			builderFailures = List.of(String.valueOf(failure));
			endedAt = System.currentTimeMillis();
			state = "failed"; //$NON-NLS-1$
			finished.countDown();
		}

		boolean await(long timeout, TimeUnit unit) throws InterruptedException {
			return finished.await(timeout, unit);
		}

		/**
		 * Asks the job to stop. Cancellation is cooperative: a builder that never
		 * looks at its monitor runs to the end of whatever it is doing.
		 * <p>
		 * A job cancelled before it ever started is a different case, and
		 * {@code Job.cancel} reporting true is how it is told apart. Nothing will run
		 * for it, so nothing would ever set its outcome, and it would be reported as
		 * running for the rest of the session. It is ended here instead.
		 *
		 * @return whether there was a job left to ask
		 */
		public boolean cancel() {
			Job running = job;
			if (running == null || !"running".equals(state)) { //$NON-NLS-1$
				return false;
			}
			if (running.cancel()) {
				endedAt = System.currentTimeMillis();
				state = "cancelled"; //$NON-NLS-1$
				note = "Cancelled before it started, so nothing was built and no builder ran."; //$NON-NLS-1$
				finished.countDown();
			}
			return true;
		}
	}

	/**
	 * What one call asked for. {@code projectNames} empty means the whole
	 * workspace; {@code kind} is {@code refresh} for a refresh that never builds.
	 */
	public record Request(String kind, List<String> projectNames, boolean countProblems, boolean refresh,
			boolean buildAfterClean) {
	}

	/**
	 * Starts the work and returns immediately.
	 * <p>
	 * Everything slow happens inside the job, the refresh included. A refresh done
	 * before scheduling would make a call block for its whole duration even when
	 * the caller asked not to wait, which defeats the point of handing back an id.
	 */
	public synchronized Build start(Request request) {
		String id = "build-" + ids.incrementAndGet(); //$NON-NLS-1$
		Build build = new Build(id, request.kind(), request.projectNames());
		builds.put(id, build);
		lastId = id;

		Job job = new Job("MCP " + request.kind()) { //$NON-NLS-1$

			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					BuildRegistry.run(build, request, monitor);
				} catch (RuntimeException e) {
					build.abort(e);
				}
				return Status.OK_STATUS;
			}

			@Override
			public boolean belongsTo(Object family) {
				return FAMILY.equals(family);
			}
		};
		job.setRule(ResourcesPlugin.getWorkspace().getRuleFactory().buildRule());
		build.job = job;
		job.schedule();
		return build;
	}

	/** The builds this server started that have not ended, newest first. */
	public synchronized List<Build> running() {
		List<Build> found = new ArrayList<>();
		for (Build build : builds.values()) {
			if ("running".equals(build.state())) { //$NON-NLS-1$
				found.add(build);
			}
		}
		Collections.reverse(found);
		return found;
	}

	private static void run(Build build, Request request, IProgressMonitor monitor) {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		String kind = request.kind();
		List<String> projectNames = request.projectNames();
		List<String> failures = new ArrayList<>();
		String state = "done"; //$NON-NLS-1$

		if (request.refresh()) {
			long startedRefresh = System.currentTimeMillis();
			ResourceCounter counter = ResourceCounter.forRefresh();
			counter.start();
			try {
				for (IResource scope : scopes(projectNames)) {
					WorkspaceSync.refresh(scope, monitor);
				}
			} catch (CoreException e) {
				state = "failed"; //$NON-NLS-1$
				collect(e.getStatus(), failures);
			} catch (OperationCanceledException e) {
				state = "cancelled"; //$NON-NLS-1$
			} finally {
				counter.stop();
			}
			build.refreshedFiles = new int[] { counter.added(), counter.changed(), counter.removed() };
			build.refreshMillis = System.currentTimeMillis() - startedRefresh;
		}

		if (!REFRESH.equals(kind) && "done".equals(state)) { //$NON-NLS-1$
			long startedBuild = System.currentTimeMillis();
			ResourceCounter counter = ResourceCounter.forBuild();
			counter.start();
			try {
				build(workspace, kindOf(kind), projectNames, failures, monitor);
				if (CLEAN.equals(kind) && request.buildAfterClean()) {
					build(workspace, IncrementalProjectBuilder.FULL_BUILD, projectNames, failures, monitor);
				}
			} catch (CoreException e) {
				state = "failed"; //$NON-NLS-1$
				collect(e.getStatus(), failures);
			} catch (OperationCanceledException e) {
				state = "cancelled"; //$NON-NLS-1$
			} finally {
				counter.stop();
			}
			build.builtFiles = new int[] { counter.added(), counter.changed(), counter.removed() };
			build.builtProjects = List.copyOf(counter.projects());
			build.buildMillis = System.currentTimeMillis() - startedBuild;
		}

		if (CLEAN.equals(kind) && !request.buildAfterClean()) {
			// a clean deletes the markers, so the counts below describe an unbuilt
			// workspace and say nothing about whether it compiles
			build.note = "A clean deletes build state without rebuilding, so the error and warning counts below only mean that nothing is built. Pass buildAfterClean to get a verdict."; //$NON-NLS-1$
		}
		collectLogged(build, failures);
		build.builderFailures = List.copyOf(failures);
		if (request.countProblems()) {
			countProblems(build, projectNames);
		}
		build.endedAt = System.currentTimeMillis();
		build.state = state;
		build.finished.countDown();
	}

	private static void build(IWorkspace workspace, int buildKind, List<String> projectNames, List<String> failures,
			IProgressMonitor monitor) throws CoreException {
		if (projectNames.isEmpty()) {
			workspace.build(buildKind, monitor);
			return;
		}
		for (String name : projectNames) {
			IProject project = workspace.getRoot().getProject(name);
			if (project.isAccessible()) {
				project.build(buildKind, monitor);
			} else {
				failures.add("Project '%s' is not open, so it was not built.".formatted(name)); //$NON-NLS-1$
			}
		}
	}

	/** The resources to refresh: the named projects, or the whole workspace. */
	private static List<IResource> scopes(List<String> projectNames) {
		if (projectNames.isEmpty()) {
			return List.of(ResourcesPlugin.getWorkspace().getRoot());
		}
		List<IResource> scopes = new ArrayList<>();
		for (String name : projectNames) {
			scopes.add(ResourcesPlugin.getWorkspace().getRoot().getProject(name));
		}
		return scopes;
	}

	/**
	 * Adds the errors and warnings the platform logged while the build ran.
	 * <p>
	 * A builder that throws does not fail the build: {@code BuildManager} runs
	 * builders inside a {@code SafeRunner}, which catches the exception and logs it,
	 * so {@code IProject.build} returns normally and there is nothing to catch. The
	 * failure only exists in the log, and without this a project whose
	 * {@code JavaBuilder} threw reports a clean build.
	 * <p>
	 * The entries are correlated by time, not by causation, so anything else logged
	 * during the same window is included too. That is the honest trade: over-report
	 * rather than call a broken build clean.
	 */
	private static void collectLogged(Build build, List<String> into) {
		var location = Platform.getLogFileLocation();
		if (location == null) {
			return;
		}
		LocalDateTime since = Instant.ofEpochMilli(build.startedAt)
				.atZone(ZoneId.systemDefault()).toLocalDateTime();
		try {
			for (PlatformLogFile.Entry entry : PlatformLogFile.read(location.toFile().toPath())) {
				if (entry.time() == null || entry.time().isBefore(since)) {
					continue;
				}
				if (entry.severity() == IStatus.ERROR || entry.severity() == IStatus.WARNING) {
					into.add("%s logged: %s".formatted(entry.plugin(), entry.message())); //$NON-NLS-1$
				}
			}
		} catch (IOException e) {
			// the log is a diagnostic aid here, not the result; a build that ran still ran
		}
	}

	/** Flattens a build's status tree, because builder failures arrive as a multi status. */
	private static void collect(IStatus status, List<String> into) {
		if (status == null) {
			return;
		}
		if (status.getMessage() != null && !status.getMessage().isBlank() && !status.isMultiStatus()) {
			into.add(status.getMessage());
		}
		for (IStatus child : status.getChildren()) {
			collect(child, into);
		}
	}

	private static void countProblems(Build build, List<String> projectNames) {
		int errors = 0;
		int warnings = 0;
		try {
			List<IResource> scopes = new ArrayList<>();
			if (projectNames.isEmpty()) {
				scopes.add(ResourcesPlugin.getWorkspace().getRoot());
			} else {
				for (String name : projectNames) {
					scopes.add(ResourcesPlugin.getWorkspace().getRoot().getProject(name));
				}
			}
			for (IResource scope : scopes) {
				if (!scope.isAccessible()) {
					continue;
				}
				for (IMarker marker : scope.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
					int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
					if (severity == IMarker.SEVERITY_ERROR) {
						errors++;
					} else if (severity == IMarker.SEVERITY_WARNING) {
						warnings++;
					}
				}
			}
			build.errors = errors;
			build.warnings = warnings;
		} catch (CoreException e) {
			// the counts stay at -1, which the tool reports as unknown
		}
	}

	private static int kindOf(String kind) {
		return switch (kind) {
		case "full" -> IncrementalProjectBuilder.FULL_BUILD; //$NON-NLS-1$
		case "clean" -> IncrementalProjectBuilder.CLEAN_BUILD; //$NON-NLS-1$
		default -> IncrementalProjectBuilder.INCREMENTAL_BUILD;
		};
	}

	public synchronized Build find(String id) {
		return builds.get(id);
	}

	public synchronized Build findLatest() {
		return lastId == null ? null : builds.get(lastId);
	}

	/** The ids still held, oldest first, for a caller that has to name one. */
	public synchronized List<String> ids() {
		return List.copyOf(builds.keySet());
	}
}
