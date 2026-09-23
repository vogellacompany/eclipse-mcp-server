package com.vogella.eclipse.mcp.p2.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
import org.eclipse.equinox.p2.engine.ProvisioningContext;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Shared plumbing for the provisioning tools: the agent, the repository
 * allowlist and the job handles.
 */
final class Provisioning {

	private static final AtomicLong IDS = new AtomicLong();

	private static final Map<String, Operation> OPERATIONS = new LinkedHashMap<>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Operation> eldest) {
			return size() > 10 && !eldest.getValue().running;
		}
	};

	private static String lastId;

	private Provisioning() {
	}

	/** One provisioning job, polled through {@code eclipse_get_provisioning_status}. */
	static final class Operation {

		private final String id;
		private final String kind;
		private final long startedAt = System.currentTimeMillis();
		private final CountDownLatch finished = new CountDownLatch(1);

		private final List<Runnable> cleanup = new ArrayList<>();

		private boolean cleanedUp;

		private volatile boolean running = true;
		private volatile long endedAt;
		private volatile String state = "running"; //$NON-NLS-1$
		private volatile String message;
		private volatile JsonArray changes = new JsonArray();
		private volatile int changesTotal = -1;
		private volatile boolean changesTruncated;
		private volatile String previousConfiguration;
		private volatile JsonArray trustPrompts = new JsonArray();

		/** How many p2 asked about, which is more than the list keeps. */
		private volatile int trustPromptCount;
		private volatile boolean trustedUnsigned;
		private volatile Job job;

		Operation(String id, String kind) {
			this.id = id;
			this.kind = kind;
		}

		boolean await(long seconds) throws InterruptedException {
			return finished.await(seconds, TimeUnit.SECONDS);
		}

		JsonObject toJson() {
			JsonObject json = new JsonObject().put("operationId", id) //$NON-NLS-1$
					.put("kind", kind) //$NON-NLS-1$
					.put("state", state) //$NON-NLS-1$
					.put("elapsedMillis", (endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt)
					.put("changes", changes) //$NON-NLS-1$
					.put("message", message) //$NON-NLS-1$
					.put("previousConfiguration", previousConfiguration) //$NON-NLS-1$
					.put("trustedUnsigned", trustedUnsigned) //$NON-NLS-1$
					// one key whatever happened: naming it after the intention hid what
					// p2 had actually asked about when the operation then failed on trust
					.put("trustPrompts", trustPrompts) //$NON-NLS-1$
					.put("trustPromptsTotal", Integer.valueOf(trustPromptCount)) //$NON-NLS-1$
					.put("trustPromptsTruncated", Boolean.valueOf(trustPromptCount > trustPrompts.size())); //$NON-NLS-1$
			if (changesTotal >= 0) {
				json.put("total", Integer.valueOf(changesTotal)).put("truncated", Boolean.valueOf(changesTruncated)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (trustPrompts.size() > 0 && trustedUnsigned) {
				json.put("trustNote", //$NON-NLS-1$
						"p2 asked whether to trust content that is unsigned or signed by a certificate this IDE does not trust, and this server accepted it, because an install it performs is unattended and there is nobody to answer a dialog. A client can configure a new repository through eclipse_add_repository, so which sites are configured does not bound this. Nothing was added to the IDE's permanent trust store. Pass trustUnsigned false to refuse instead."); //$NON-NLS-1$
			}
			if (trustPrompts.size() > 0 && !trustedUnsigned) {
				json.put("blockedBy", //$NON-NLS-1$
						"trustUnsigned was false, so this server refused content that is unsigned or signed by an untrusted certificate rather than installing it."); //$NON-NLS-1$
			}
			if ("done".equals(state)) { //$NON-NLS-1$
				json.put("restartRequired", true) //$NON-NLS-1$
						.put("note", //$NON-NLS-1$
								"The new code is not active until the IDE restarts. Use eclipse_restart, which works independently of this tool, so a half applied update can still be recovered. If the update turns out to be bad, revert from Help > About > Installation Details > Installation History using the previousConfiguration timestamp above."); //$NON-NLS-1$
			}
			return json;
		}
	}

	static synchronized Operation start(String kind, Function<Operation, Job> jobFactory) {
		String id = "provisioning-" + IDS.incrementAndGet(); //$NON-NLS-1$
		Operation operation = new Operation(id, kind);
		operation.previousConfiguration = currentConfiguration();
		OPERATIONS.put(id, operation);
		lastId = id;
		Job job = jobFactory.apply(operation);
		operation.job = job;
		job.addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(IJobChangeEvent event) {
				// before anything is released, so an answer built by a waiter already
				// carries what the cleanup records
				runCleanup(operation);
				IStatus status = event.getResult();
				operation.state = ProvisioningStatus.stateOf(status);
				if (status != null && status.getSeverity() != IStatus.OK) {
					operation.message = ProvisioningStatus.describe(status);
				}
				operation.endedAt = System.currentTimeMillis();
				operation.running = false;
				operation.finished.countDown();
			}
		});
		job.schedule();
		return operation;
	}

	/** Records a finished operation that never needed a job, such as a failed resolve. */
	static synchronized Operation record(String kind, String state, String message, JsonArray changes) {
		String id = "provisioning-" + IDS.incrementAndGet(); //$NON-NLS-1$
		Operation operation = new Operation(id, kind);
		operation.previousConfiguration = currentConfiguration();
		operation.state = state;
		operation.message = message;
		operation.changes = changes == null ? new JsonArray() : changes;
		operation.running = false;
		operation.endedAt = System.currentTimeMillis();
		runCleanup(operation);
		operation.finished.countDown();
		OPERATIONS.put(id, operation);
		lastId = id;
		return operation;
	}

	/**
	 * Runs {@code after} once the operation's job has finished, however it ended,
	 * and before anyone waiting on the operation is released.
	 */
	static void onFinished(Operation operation, Runnable after) {
		boolean now;
		synchronized (operation) {
			now = operation.cleanedUp;
			if (!now) {
				operation.cleanup.add(after);
			}
		}
		if (now) {
			after.run();
		}
	}

	private static void runCleanup(Operation operation) {
		List<Runnable> steps;
		synchronized (operation) {
			operation.cleanedUp = true;
			steps = List.copyOf(operation.cleanup);
		}
		for (Runnable step : steps) {
			try {
				step.run();
			} catch (RuntimeException e) {
				ILog.of(Provisioning.class).error("Cleanup after " + operation.id + " failed", e); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
	}

	/** Cancels a running operation. p2's jobs honour cancellation. */
	static boolean cancel(Operation operation) {
		Job job = operation.job;
		if (job == null || !operation.running) {
			return false;
		}
		job.cancel();
		operation.state = "cancelling"; //$NON-NLS-1$
		return true;
	}

	static synchronized Operation find(String id) {
		return OPERATIONS.get(id);
	}

	static synchronized Operation findLatest() {
		return lastId == null ? null : OPERATIONS.get(lastId);
	}

	/** The ids still held, oldest first, for a caller that has to name one. */
	static synchronized List<String> ids() {
		return List.copyOf(OPERATIONS.keySet());
	}

	static void setChanges(Operation operation, JsonArray changes) {
		setChanges(operation, changes, -1, false);
	}

	/** Records the change list with the count behind a {@code maxResults} cap. */
	static void setChanges(Operation operation, JsonArray changes, int total, boolean truncated) {
		operation.changes = changes;
		operation.changesTotal = total;
		operation.changesTruncated = truncated;
	}

	/** Records what p2 asked about, so the answer is visible instead of looking like a slow download. */
	static void setTrust(Operation operation, HeadlessTrust trust, boolean trustUnsigned) {
		JsonArray prompts = new JsonArray();
		trust.prompts().forEach(prompts::add);
		operation.trustPrompts = prompts;
		operation.trustPromptCount = trust.promptCount();
		operation.trustedUnsigned = trustUnsigned && trust.prompted();
	}

	static IProvisioningAgent agent() {
		BundleContext context = FrameworkUtil.getBundle(Provisioning.class).getBundleContext();
		if (context == null) {
			return null;
		}
		var reference = context.getServiceReference(IProvisioningAgent.class);
		return reference == null ? null : context.getService(reference);
	}

	/**
	 * The repositories the IDE is already configured with.
	 * <p>
	 * Installing from an arbitrary URL fetches and runs code from the network, which
	 * is a larger step than anything else here does, so the allowlist is what the
	 * user already trusted rather than a switch that gets turned on once.
	 */
	static List<URI> knownRepositories(IProvisioningAgent agent) {
		IMetadataRepositoryManager manager = agent.getService(IMetadataRepositoryManager.class);
		if (manager == null) {
			return List.of();
		}
		List<URI> known = new ArrayList<>();
		for (URI uri : manager.getKnownRepositories(IMetadataRepositoryManager.REPOSITORIES_ALL)) {
			known.add(uri);
		}
		return known;
	}

	/**
	 * Re-reads the configured repositories and reports the state of each.
	 * <p>
	 * p2 caches repository metadata, and a cached miss is reported as "no updates
	 * found", which is exactly what a genuinely current IDE reports. That makes a
	 * stale cache invisible in the one workflow these tools exist for. The composite
	 * document itself has to be re-read, because a release replaces the child rather
	 * than adding one.
	 */
	private static JsonArray describe(IProvisioningAgent agent, boolean refresh, List<URI> locations,
			IProgressMonitor monitor) {
		IMetadataRepositoryManager manager = agent.getService(IMetadataRepositoryManager.class);
		JsonArray reported = new JsonArray();
		if (manager == null) {
			return reported;
		}
		for (URI uri : locations) {
			JsonObject entry = new JsonObject().put("uri", uri.toString()); //$NON-NLS-1$
			if (refresh) {
				try {
					manager.refreshRepository(uri, monitor);
					refreshArtifacts(agent, uri, monitor);
					entry.put("refreshed", Boolean.TRUE); //$NON-NLS-1$
				} catch (org.eclipse.equinox.p2.core.ProvisionException | OperationCanceledException e) {
					entry.put("refreshed", Boolean.FALSE).put("error", e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
				}
			} else {
				entry.put("refreshed", Boolean.FALSE); //$NON-NLS-1$
			}
			String timestamp = manager.getRepositoryProperty(uri, org.eclipse.equinox.p2.repository.IRepository.PROP_TIMESTAMP);
			entry.put("timestamp", timestamp == null ? null : stamp(timestamp)); //$NON-NLS-1$
			reported.add(entry);
		}
		return reported;
	}

	private static String stamp(String millis) {
		try {
			return millis + " (" + new Date(Long.parseLong(millis)) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
		} catch (NumberFormatException e) {
			return millis;
		}
	}

	static List<String> stringList(Map<String, Object> arguments, String name) {
		List<String> values = new ArrayList<>();
		if (arguments != null && arguments.get(name) instanceof List<?> list) {
			for (Object entry : list) {
				String value = String.valueOf(entry).trim();
				if (!value.isEmpty()) {
					values.add(value);
				}
			}
		}
		return values;
	}

	/** The installed units matching {@code ids}, for scoping an update to named things. */
	static Set<org.eclipse.equinox.p2.metadata.IInstallableUnit> installedUnits(IProvisioningAgent agent,
			List<String> ids) {
		IProfileRegistry registry = agent.getService(IProfileRegistry.class);
		var profile = registry == null ? null : registry.getProfile(IProfileRegistry.SELF);
		Set<org.eclipse.equinox.p2.metadata.IInstallableUnit> units = new LinkedHashSet<>();
		if (profile == null) {
			return units;
		}
		for (String id : ids) {
			profile.query(org.eclipse.equinox.p2.query.QueryUtil.createIUQuery(id), null).forEach(units::add);
		}
		return units;
	}

	/**
	 * The repositories that can supply {@code units}, resolved through composite
	 * children and references.
	 * <p>
	 * Asking p2 which locations matter beats refreshing every configured site: an
	 * IDE with a dozen of them pays a network round trip for each, and a targeted
	 * check only ever needed one.
	 */
	/**
	 * Retries an operation against every enabled repository.
	 * <p>
	 * Scoping to the repositories that can supply a unit finds where the INSTALLED
	 * version lives, and an update is by definition somewhere else: with a composite
	 * whose child location changes per release, the child the current version came
	 * from is exactly the one that will never hold a newer one. So a scoped
	 * resolution finding nothing is not an answer, it is a reason to look properly.
	 *
	 * @return whether the retry was needed
	 */
	static boolean widenToAllRepositories(IProvisioningAgent agent,
			org.eclipse.equinox.p2.operations.ProfileChangeOperation operation, IProgressMonitor monitor) {
		// the metadata was refreshed for the scoped repositories only, so the enabled
		// ones, the composite among them, are still whatever p2 had cached. Widening
		// the scope without widening the refresh reads the same stale answer from a
		// bigger set: the composite's child list is exactly what has changed
		describeRepositories(agent, true, null, monitor);
		operation.setProvisioningContext(new ProvisioningContext(agent));
		operation.resolveModal(monitor);
		return true;
	}

	static URI[] sourcesFor(IProvisioningAgent agent,
			Collection<org.eclipse.equinox.p2.metadata.IInstallableUnit> units, IProgressMonitor monitor) {
		if (units.isEmpty()) {
			return null;
		}
		ProvisioningContext context = new ProvisioningContext(agent);
		Map<URI, Set<org.eclipse.equinox.p2.metadata.IInstallableUnit>> sources = context
				.getInstallableUnitSources(units, monitor);
		return sources == null || sources.isEmpty() ? null : sources.keySet().toArray(URI[]::new);
	}

	/**
	 * Restricts an operation to {@code locations}, or leaves it workspace wide when
	 * {@code locations} is null. Only the named repositories are loaded at all.
	 */
	static ProvisioningContext scope(IProvisioningAgent agent, URI[] locations) {
		ProvisioningContext context = new ProvisioningContext(agent);
		if (locations != null && locations.length > 0) {
			context.setMetadataRepositories(locations);
		}
		return context;
	}

	/**
	 * Refreshes the artifact side of a repository as well as its metadata.
	 * <p>
	 * Skipping this looks like a saving, and it is not. A composite site whose
	 * release replaces the child rather than adding one leaves the cached artifact
	 * composite pointing at a directory that no longer exists, while the metadata
	 * refresh happily reports the new version. The update then resolves and fails in
	 * the download phase with "No repository found containing", which names neither
	 * the cache nor the site.
	 */
	private static void refreshArtifacts(IProvisioningAgent agent, URI uri, IProgressMonitor monitor) {
		var artifacts = agent.getService(org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager.class);
		if (artifacts == null || !artifacts.contains(uri)) {
			return;
		}
		try {
			artifacts.refreshRepository(uri, monitor);
		} catch (org.eclipse.equinox.p2.core.ProvisionException | OperationCanceledException e) {
			// a site with no artifact repository of its own is normal, and the
			// metadata refresh above is what the caller asked about
		}
	}

	/** Refreshes just {@code locations}, or every configured repository when null. */
	static JsonArray describeRepositories(IProvisioningAgent agent, boolean refresh, URI[] locations,
			IProgressMonitor monitor) {
		List<URI> scoped = locations == null ? knownRepositories(agent) : List.of(locations);
		return describe(agent, refresh, scoped, monitor);
	}

	/** The timestamp of the current configuration, which is the revert point. */
	private static String currentConfiguration() {
		IProvisioningAgent agent = agent();
		if (agent == null) {
			return null;
		}
		IProfileRegistry registry = agent.getService(IProfileRegistry.class);
		if (registry == null) {
			return null;
		}
		long[] timestamps = registry.listProfileTimestamps(IProfileRegistry.SELF);
		if (timestamps == null || timestamps.length == 0) {
			return null;
		}
		long latest = timestamps[timestamps.length - 1];
		return latest + " (" + new Date(latest) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
	}
}
