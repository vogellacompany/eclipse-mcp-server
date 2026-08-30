package com.vogella.eclipse.mcp.core.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Installs one bundle jar into the running framework, or copies it into the
 * installation's dropins directory, without going through p2.
 */
public final class InstallBundleTool implements IMcpTool {

	/** Anything whose bundle starts with this can take this server down when it is refreshed. */
	private static final String SELF_PREFIX = "com.vogella.eclipse.mcp"; //$NON-NLS-1$

	private static final String P2_NOTE =
			"A hot install is invisible to p2: eclipse_get_installation does not show it, and Eclipse's simpleconfigurator reconciles the framework against bundles.info at every start, so a restart restores the original bundle and this patch is gone. That is right for a throwaway test and a trap otherwise; use mode dropins, or the p2 tools eclipse_add_repository and eclipse_install, for anything meant to stay."; //$NON-NLS-1$

	private static final String LOADED_CLASSES_NOTE =
			"Classes already loaded keep running: code that was on the old version, a view that is open among them, keeps the old behaviour until it is closed and reopened or its bundle is restarted."; //$NON-NLS-1$

	private static final String REGISTRY_CAVEAT =
			"A contribution in the extension registry does not mean the component that consumes it noticed: some readers take the registry once at startup and keep their own list, and the e4 theme engine is believed to be one of those, so what was newly contributed can be in the registry and still invisible until the IDE restarts."; //$NON-NLS-1$

	/** Long enough for the HTTP response to be on the wire before the refresh stops the server. */
	private static final int DEFERRED_REFRESH_DELAY_MILLIS = 2000;

	private static final int REFRESH_WAIT_SECONDS = 25;

	/** The registry can lag a refresh's bundle events; an expected attribution is polled this long before it is believed. */
	private static final int CONTRIBUTION_POLL_ATTEMPTS = 10;

	private static final long CONTRIBUTION_POLL_MILLIS = 100;

	@Override
	public String getName() {
		return "eclipse_install_bundle"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Installs one OSGi bundle jar into the running IDE without a p2 repository, a build or a restart. CHANGES THE RUNNING IDE: mode hot installs or updates the bundle in the live framework, and the refresh that follows restarts every bundle wired to it, which for a low level bundle is a large part of the IDE, so the dependency closure is reported up front and dryRun defaults to true. A HOT INSTALL DOES NOT SURVIVE A RESTART: it is invisible to p2 and simpleconfigurator puts the original bundle back at the next start, so it is a throwaway test; mode dropins copies the jar into the installation's dropins directory instead, which survives a restart and needs one. DROPINS IS SILENTLY INEFFECTIVE FOR MOST OF AN SDK, and the copy still reports success: the reconciler will not replace a bundle that a p2 installed feature requires at an exact version, which covers nearly everything an SDK ships, and a singleton already installed through p2 wins over the dropin. Use eclipse_substitute_bundle to run a workspace project in place of an installed bundle, or build a small p2 repository and go through eclipse_add_repository with a file: URL and eclipse_install, which is the only way that leaves p2's own picture of the installation correct. After a hot install it reports what the extension registry attributes to the bundle per extension point; a jar whose plugin.xml ends up with nothing attributed needs a restart for its contribution, and even an attributed contribution can go unseen by components that read the registry only at startup, the e4 theme engine among them. Replacing a bundle whose refresh would stop this server's own bundles is refused, because the answer could not be delivered through a server the refresh is about to stop; pass allowSelf to have the install happen at once, the answer come back first, and the refresh follow a couple of seconds later, after which reconnecting is the caller's job. This tool installs, updates and copies only; it never uninstalls anything."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "jar":       {"type":"string","description":"Absolute path to an OSGi bundle jar."},
				    "mode":      {"type":"string","enum":["hot","dropins"],"default":"hot","description":"hot installs or updates in the live framework, effective immediately and undone by the next restart. dropins copies the jar into the installation's dropins directory, effective at the next start ONLY when nothing installed through p2 already provides that bundle; for an SDK bundle it usually changes nothing at all and the copy still succeeds."},
				    "dryRun":    {"type":"boolean","default":true,"description":"Report what would happen, including the dependency closure a refresh would take along, and change nothing."},
				    "start":     {"type":"boolean","default":true,"description":"hot only. Start the bundle after installing. A fragment cannot be started and is reported instead."},
				    "allowSelf": {"type":"boolean","default":false,"description":"Permit an operation whose refresh would take this server's own com.vogella.eclipse.mcp bundles with it. The answer is sent first and the refresh follows a couple of seconds later; reconnecting afterwards is the caller's job."},
				    "maxResults":{"type":"integer","default":200,"minimum":1,"maximum":2000,"description":"Cap on the refreshed and extension point lists."}
				  },
				  "required": ["jar"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (monitor.isCanceled()) {
			return McpToolResult.error("The request was cancelled."); //$NON-NLS-1$
		}
		ToolArguments args = ToolArguments.of(arguments);
		String jarArgument = args.getString("jar"); //$NON-NLS-1$
		if (jarArgument == null) {
			return McpToolResult.error("The argument 'jar' is required: an absolute path to an OSGi bundle jar."); //$NON-NLS-1$
		}
		Path jar = Path.of(jarArgument);
		if (!jar.isAbsolute()) {
			return McpToolResult.error("'%s' is not an absolute path.".formatted(jarArgument)); //$NON-NLS-1$
		}
		if (!Files.isRegularFile(jar)) {
			return McpToolResult.error("No file at '%s', or it is not a regular file.".formatted(jar)); //$NON-NLS-1$
		}
		String symbolicName = null;
		Version version = Version.emptyVersion;
		try (JarFile opened = new JarFile(jar.toFile())) {
			Manifest manifest = opened.getManifest();
			if (manifest == null) {
				return notABundle(jar, "it carries no manifest at all"); //$NON-NLS-1$
			}
			Attributes main = manifest.getMainAttributes();
			symbolicName = symbolOf(main.getValue(Constants.BUNDLE_SYMBOLICNAME));
			String versionHeader = main.getValue(Constants.BUNDLE_VERSION);
			version = versionHeader == null ? Version.emptyVersion : Version.parseVersion(versionHeader);
		} catch (IOException e) {
			return McpToolResult
					.error("'%s' could not be read as a jar: %s".formatted(jar, rootMessage(e))); //$NON-NLS-1$
		} catch (IllegalArgumentException e) {
			return notABundle(jar, "its Bundle-Version does not parse: %s".formatted(e.getMessage())); //$NON-NLS-1$
		}
		if (symbolicName == null || symbolicName.isBlank()) {
			return notABundle(jar, "its manifest carries no Bundle-SymbolicName"); //$NON-NLS-1$
		}
		String mode = args.getString("mode", "hot"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!"hot".equals(mode) && !"dropins".equals(mode)) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("Unknown mode '%s', expected 'hot' or 'dropins'.".formatted(mode)); //$NON-NLS-1$
		}
		BundleContext context = frameworkContext();
		if (context == null) {
			return McpToolResult.error("No OSGi framework is running, so there is nothing to install into."); //$NON-NLS-1$
		}
		List<Bundle> sameName = installedWithName(context, symbolicName);
		String previousVersion = sameName.isEmpty() ? null : sameName.get(sameName.size() - 1).getVersion()
				.toString();

		if ("dropins".equals(mode)) { //$NON-NLS-1$
			return dropins(args, jar, symbolicName, previousVersion, version);
		}
		return hot(args, jar, monitor, context, symbolicName, previousVersion, version,
				sameName.isEmpty() ? null : sameName.get(sameName.size() - 1));
	}

	private McpToolResult hot(ToolArguments args, Path jar, IProgressMonitor monitor, BundleContext context,
			String symbolicName, String previousVersion, Version version, Bundle affected) {
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		boolean start = args.getBoolean("start", true); //$NON-NLS-1$
		boolean allowSelf = args.getBoolean("allowSelf", false); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 200, 1, 2000); //$NON-NLS-1$
		FrameworkWiring wiring = context.getBundle(0).adapt(FrameworkWiring.class);

		List<Bundle> closure = List.of();
		if (affected != null && wiring != null) {
			closure = sorted(wiring.getDependencyClosure(Set.of(affected)));
		}
		Set<String> selfHits = selfBundles(closure, symbolicName);
		if (!selfHits.isEmpty() && !allowSelf) {
			return McpToolResult.error(
					"Refused: replacing %s would refresh %d bundle(s), among them this server's own: %s. The refresh stops the very server that owes you this answer, so the response could never be delivered and the call looks like a crash. Pass allowSelf true to go ahead: the install happens immediately, this answer is sent first, and the refresh follows a couple of seconds later, after which reconnecting is your job."
							.formatted(symbolicName, Integer.valueOf(closure.size()), String.join(", ", selfHits))); //$NON-NLS-1$
		}
		if (dryRun) {
			boolean update = affected != null;
			JsonObject json = base(symbolicName, previousVersion, version).put("mode", "hot") //$NON-NLS-1$ //$NON-NLS-2$
					.put("dryRun", Boolean.TRUE) //$NON-NLS-1$
					.put("wouldBe", update ? "updated" : "installed") //$NON-NLS-1$ //$NON-NLS-2$
					.put("startAfterwards", Boolean.valueOf(start))
					.put("closureSize", Integer.valueOf(closure.size()));
			putRefreshed(json, closure, maxResults);
			json.put("notes", notes(update, update && affected.getVersion().equals(version), false, P2_NOTE)); //$NON-NLS-1$
			return McpToolResult.of(json.toString());
		}

		if (affected != null && affected.getVersion().equals(version) && contentUnchanged(affected, jar)) {
			return McpToolResult.error(
					"Refused: %s %s is already installed from %s and the file has not changed. The framework rejects identical content as a duplicate anyway; build the jar again with different content or give it a higher version."
							.formatted(symbolicName, version, affected.getLocation())); //$NON-NLS-1$
		}

		if (monitor.isCanceled()) {
			return McpToolResult.error("The request was cancelled."); //$NON-NLS-1$
		}
		Bundle bundle;
		String outcome;
		try {
			if (affected == null) {
				bundle = context.installBundle("file:" + jar); //$NON-NLS-1$
				outcome = "installed"; //$NON-NLS-1$
			} else {
				try (InputStream in = Files.newInputStream(jar)) {
					affected.update(in);
				}
				bundle = affected;
				outcome = "updated"; //$NON-NLS-1$
			}
		} catch (BundleException e) {
			return McpToolResult.error("The framework refused to %s %s: %s" //$NON-NLS-1$
					.formatted(affected == null ? "install" : "update", symbolicName, rootMessage(e))); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (IOException e) {
			return McpToolResult.error("The jar could not be read back while updating: %s".formatted(rootMessage(e))); //$NON-NLS-1$
		}

		closure = wiring == null ? List.of(bundle) : sorted(wiring.getDependencyClosure(Set.of(bundle)));

		boolean deferred = !selfHits.isEmpty();
		boolean refreshTimedOut = false;
		if (deferred) {
			scheduleRefresh(context, bundle, closure, start);
		} else {
			refreshTimedOut = !waitForRefresh(monitor, wiring, closure);
		}

		boolean fragment = isFragment(bundle);
		boolean started = false;
		String resolutionError = null;
		if (!deferred && !monitor.isCanceled()) {
			if (fragment && start) {
				started = false;
			} else if (start) {
				try {
					bundle.start(0);
					started = true;
				} catch (BundleException e) {
					resolutionError = rootMessage(e);
				}
			}
		}
		int state = bundle.getState();
		JsonObject json = base(symbolicName, previousVersion, version).put("mode", "hot") //$NON-NLS-1$ //$NON-NLS-2$
				.put("dryRun", Boolean.FALSE) //$NON-NLS-1$
				.put("outcome", outcome) //$NON-NLS-1$
				.put("state", stateName(state)) //$NON-NLS-1$
				.put("resolved", Boolean.valueOf(isResolved(bundle))) //$NON-NLS-1$
				.put("fragment", Boolean.valueOf(fragment)) //$NON-NLS-1$
				.put("started", Boolean.valueOf(started)); //$NON-NLS-1$
		if (resolutionError != null) {
			json.put("resolutionError", resolutionError); //$NON-NLS-1$
		}
		if (!deferred && start && fragment) {
			json.put("fragmentNote", "It is a fragment, and fragments cannot be started; it is attached to its host all the same."); //$NON-NLS-1$
		}
		if (deferred) {
			json.put("refreshScheduledInMillis", Integer.valueOf(DEFERRED_REFRESH_DELAY_MILLIS)); //$NON-NLS-1$
		}
		if (affected != null && affected.getVersion().equals(version)) {
			json.put("versionChanged", Boolean.FALSE); //$NON-NLS-1$
		}
		putRefreshed(json, closure, maxResults);
		List<String> notes = notes(outcome.equals("updated"), affected != null && affected.getVersion().equals(version), //$NON-NLS-1$
				deferred, P2_NOTE);
		if (deferred) {
			notes.add("The refresh is deferred so this answer could be delivered. IMPORTANT: the connection will drop when it runs, because the server's own bundles are stopped by it. Reconnecting is your job; the effect of the refresh cannot be reported, because nothing will be there to report it."); //$NON-NLS-1$
			notes.add("The extension registry report is left out, because the refresh that delivers new contributions has not run yet."); //$NON-NLS-1$
		} else if (refreshTimedOut) {
			notes.add("The extension registry report is left out, because the refresh that delivers new contributions had not finished within its bounded wait when this answer was written."); //$NON-NLS-1$
		} else {
			json.put("extensions", extensionReport(bundle, jar, maxResults, notes)); //$NON-NLS-1$
		}
		json.put("notes", notes); //$NON-NLS-1$
		return McpToolResult.of(json.toString());
	}

	private McpToolResult dropins(ToolArguments args, Path jar, String symbolicName, String previousVersion,
			Version version) {
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 200, 1, 2000); //$NON-NLS-1$
		var installLocation = Platform.getInstallLocation();
		if (installLocation == null || installLocation.getURL() == null) {
			return McpToolResult.error("This IDE has no readable installation location, so there is no dropins directory to copy into."); //$NON-NLS-1$
		}
		Path installation = FileLocations.pathOf(installLocation.getURL());
		if (installation == null) {
			return McpToolResult.error(
					"The installation location %s is not a local directory.".formatted(installLocation.getURL())); //$NON-NLS-1$
		}
		Path dropins = installation.resolve("dropins"); //$NON-NLS-1$
		Path target = dropins.resolve(jar.getFileName());
		boolean directoryExists = Files.isDirectory(dropins);
		Path writableCheck = directoryExists ? dropins : installation;
		boolean writable = Files.isWritable(writableCheck);
		JsonObject json = base(symbolicName, previousVersion, version).put("mode", "dropins") //$NON-NLS-1$ //$NON-NLS-2$
				.put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("target", target.toString()) //$NON-NLS-1$
				.put("dropinsExisted", Boolean.valueOf(directoryExists)) //$NON-NLS-1$
				.put("writable", Boolean.valueOf(writable)); //$NON-NLS-1$
		if (!dryRun) {
			if (!writable) {
				return McpToolResult.error(
						"Refused: %s is not writable, which is normal for a shared or packaged install. Copying the jar there needs write access; unpacked installations and user installs usually are writable."
								.formatted(directoryExists ? dropins : installation)); //$NON-NLS-1$
			}
			try {
				if (!directoryExists) {
					Files.createDirectories(dropins);
				}
				Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				return McpToolResult
						.error("Copying into %s failed: %s".formatted(dropins, rootMessage(e))); //$NON-NLS-1$
			}
			json.put("outcome", "copied"); //$NON-NLS-1$ //$NON-NLS-2$
		} else {
			json.put("wouldBe", "copied"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		putRefreshed(json, List.of(), maxResults);
		json.put("survivesRestart", Boolean.TRUE) //$NON-NLS-1$
				.put("needsRestart", Boolean.TRUE); //$NON-NLS-1$
		json.put("notes", List.of(//$NON-NLS-1$
				"The jar is copied into the installation's dropins directory, which p2's reconciler picks up at the next start, so this one takes effect then and survives the restart.", //$NON-NLS-1$
				"This is the opposite of mode hot, which acts at once and is undone by a restart.")); //$NON-NLS-1$
		return McpToolResult.of(json.toString());
	}

	private static JsonObject base(String symbolicName, String previousVersion, Version version) {
		return new JsonObject().put("symbolicName", symbolicName) //$NON-NLS-1$
				.put("previousVersion", previousVersion) //$NON-NLS-1$
				.put("version", version.toString()); //$NON-NLS-1$
	}

	/**
	 * The context of the bundle this tool is loaded from, starting it transiently
	 * when it was merely resolved, which is how it looks under the headless test harness.
	 */
	private static BundleContext frameworkContext() {
		Bundle self = FrameworkUtil.getBundle(InstallBundleTool.class);
		if (self == null) {
			return null;
		}
		if (self.getBundleContext() == null) {
			try {
				self.start(Bundle.START_TRANSIENT);
			} catch (BundleException e) {
				ILog.get().warn("The core bundle could not be started for eclipse_install_bundle: %s" //$NON-NLS-1$
						.formatted(rootMessage(e)));
			}
		}
		return self.getBundleContext();
	}

	private static void putRefreshed(JsonObject json, List<Bundle> closure, int maxResults) {
		JsonArray items = new JsonArray();
		for (int i = 0; i < closure.size() && i < maxResults; i++) {
			Bundle bundle = closure.get(i);
			items.add(new JsonObject().put("symbolicName", bundle.getSymbolicName()) //$NON-NLS-1$
					.put("version", bundle.getVersion().toString())); //$NON-NLS-1$
		}
		json.put("refreshed", items); //$NON-NLS-1$
		json.put("total", Integer.valueOf(closure.size())); //$NON-NLS-1$
		json.put("truncated", Boolean.valueOf(closure.size() > items.size())); //$NON-NLS-1$
	}

	/**
	 * What the extension registry attributes to the bundle right now, per extension
	 * point. The registry can lag the bundle events a refresh fires behind the call,
	 * so an expected attribution that has not appeared yet is polled briefly before it
	 * is believed.
	 */
	private static JsonObject extensionReport(Bundle bundle, Path jar, int maxResults, List<String> notes) {
		String symbolicName = bundle.getSymbolicName();
		boolean pluginXmlInJar = jarHasPluginXml(jar);
		IExtensionRegistry registry = Platform.getExtensionRegistry();
		Map<String, Integer> counts = new TreeMap<>();
		if (registry != null) {
			for (IExtension extension : attributions(registry, symbolicName, pluginXmlInJar)) {
				counts.merge(extension.getExtensionPointUniqueIdentifier(), 1, Integer::sum);
			}
		}
		JsonArray points = new JsonArray();
		int shown = 0;
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			if (shown >= maxResults) {
				break;
			}
			points.add(new JsonObject().put("extensionPoint", entry.getKey()) //$NON-NLS-1$
					.put("count", entry.getValue())); //$NON-NLS-1$
			shown++;
		}
		if (pluginXmlInJar && counts.isEmpty() && registry != null) {
			if (!isSingleton(bundle)) {
				notes.add("The jar carries a plugin.xml whose contributions the registry will never read: %s is not a singleton, and the extension registry ignores every non-singleton bundle's contributions. Add 'singleton:=true' to its Bundle-SymbolicName and install again." //$NON-NLS-1$
						.formatted(symbolicName));
			} else {
				notes.add("The jar carries a plugin.xml, but the extension registry attributes no extension to %s: the contribution did not take while the IDE runs, and a restart is needed." //$NON-NLS-1$
						.formatted(symbolicName));
			}
		}
		notes.add(REGISTRY_CAVEAT);
		return new JsonObject().put("pluginXmlInJar", Boolean.valueOf(pluginXmlInJar)) //$NON-NLS-1$
				.put("points", points) //$NON-NLS-1$
				.put("total", Integer.valueOf(counts.size())) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(counts.size() > shown)); //$NON-NLS-1$
	}

	private static boolean isSingleton(Bundle bundle) {
		try {
			String header = bundle.getHeaders().get(Constants.BUNDLE_SYMBOLICNAME);
			if (header == null) {
				return false;
			}
			for (String clause : header.split(";")) { //$NON-NLS-1$
				if ("singleton:=true".equals(clause.trim())) { //$NON-NLS-1$
					return true;
				}
			}
			return false;
		} catch (IllegalStateException e) {
			return false;
		}
	}

	private static List<IExtension> attributions(IExtensionRegistry registry, String symbolicName,
			boolean expectContributions) {
		for (int attempt = 0;; attempt++) {
			IExtension[] found = registry.getExtensions(symbolicName);
			if (found.length > 0 || !expectContributions || attempt >= CONTRIBUTION_POLL_ATTEMPTS) {
				return List.of(found);
			}
			try {
				Thread.sleep(CONTRIBUTION_POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return List.of(found);
			}
		}
	}

	private static boolean jarHasPluginXml(Path jar) {
		try (JarFile opened = new JarFile(jar.toFile())) {
			return opened.getEntry("plugin.xml") != null; //$NON-NLS-1$
		} catch (IOException e) {
			return false;
		}
	}

	private static List<String> notes(boolean update, boolean sameVersion, boolean deferred, String p2Note) {
		List<String> notes = new ArrayList<>();
		notes.add(p2Note);
		if (update) {
			notes.add(LOADED_CLASSES_NOTE);
		}
		if (sameVersion && !deferred) {
			notes.add("The version did not change; the content did. Nothing else shows that difference, so compare timestamps rather than versions when checking whether a patch landed."); //$NON-NLS-1$
		}
		return notes;
	}

	private static void scheduleRefresh(BundleContext context, Bundle bundle, List<Bundle> closure, boolean start) {
		FrameworkWiring wiring = context.getBundle(0).adapt(FrameworkWiring.class);
		Set<Bundle> toRefresh = new HashSet<>(closure);
		Job job = new Job("Deferred refresh after eclipse_install_bundle") { //$NON-NLS-1$
			@Override
			protected IStatus run(IProgressMonitor progress) {
				CountDownLatch done = new CountDownLatch(1);
				wiring.refreshBundles(toRefresh, event -> done.countDown());
				try {
					boolean finished = done.await(CallBudget.boundedWaitSeconds(REFRESH_WAIT_SECONDS), TimeUnit.SECONDS);
					if (finished && start && !isFragment(bundle)) {
						try {
							bundle.start(0);
						} catch (BundleException e) {
							ILog.get().warn("The deferred start of %s failed: %s" //$NON-NLS-1$
									.formatted(bundle.getSymbolicName(), rootMessage(e)));
						}
					}
					ILog.get().info("The deferred refresh of %s %s" //$NON-NLS-1$
							.formatted(bundle.getSymbolicName(), finished ? "finished" : "did not finish within its bounded wait")); //$NON-NLS-1$ //$NON-NLS-2$
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return Status.OK_STATUS;
			}
		};
		job.schedule(DEFERRED_REFRESH_DELAY_MILLIS);
	}

	/** Returns whether the refresh callback arrived within the bounded wait. */
	private static boolean waitForRefresh(IProgressMonitor monitor, FrameworkWiring wiring, List<Bundle> closure) {
		if (wiring == null) {
			return false;
		}
		CountDownLatch done = new CountDownLatch(1);
		wiring.refreshBundles(new HashSet<>(closure), event -> done.countDown());
		try {
			boolean finished = done.await(CallBudget.boundedWaitSeconds(REFRESH_WAIT_SECONDS), TimeUnit.SECONDS);
			if (!finished) {
				ILog.get().warn("The refresh after eclipse_install_bundle did not finish within %d seconds" //$NON-NLS-1$
						.formatted(Integer.valueOf(CallBudget.boundedWaitSeconds(REFRESH_WAIT_SECONDS))));
			}
			return finished;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static boolean isFragment(Bundle bundle) {
		BundleRevision revision = bundle.adapt(BundleRevision.class);
		return revision != null && (revision.getTypes() & BundleRevision.TYPE_FRAGMENT) != 0;
	}

	private static boolean isResolved(Bundle bundle) {
		int state = bundle.getState();
		return state == Bundle.RESOLVED || state == Bundle.STARTING || state == Bundle.ACTIVE
				|| state == Bundle.STOPPING || bundle.adapt(BundleWiring.class) != null;
	}

	private static String stateName(int state) {
		return switch (state) {
			case Bundle.UNINSTALLED -> "UNINSTALLED"; //$NON-NLS-1$
			case Bundle.INSTALLED -> "INSTALLED"; //$NON-NLS-1$
			case Bundle.RESOLVED -> "RESOLVED"; //$NON-NLS-1$
			case Bundle.STARTING -> "STARTING"; //$NON-NLS-1$
			case Bundle.STOPPING -> "STOPPING"; //$NON-NLS-1$
			case Bundle.ACTIVE -> "ACTIVE"; //$NON-NLS-1$
			default -> String.valueOf(state);
		};
	}

	private static List<Bundle> installedWithName(BundleContext context, String symbolicName) {
		List<Bundle> matches = new ArrayList<>();
		for (Bundle bundle : context.getBundles()) {
			if (symbolicName.equals(bundle.getSymbolicName())) {
				matches.add(bundle);
			}
		}
		matches.sort(Comparator.comparing(Bundle::getVersion));
		return matches;
	}

	private static List<Bundle> sorted(Collection<Bundle> bundles) {
		List<Bundle> sorted = new ArrayList<>(bundles);
		sorted.sort(Comparator.comparing(bundle -> bundle.getSymbolicName() == null ? "" : bundle.getSymbolicName(), //$NON-NLS-1$
				Comparator.naturalOrder()));
		return sorted;
	}

	private static Set<String> selfBundles(List<Bundle> closure, String symbolicName) {
		Set<String> hits = new TreeSet<>();
		if (symbolicName.startsWith(SELF_PREFIX)) {
			hits.add(symbolicName);
		}
		for (Bundle bundle : closure) {
			String name = bundle.getSymbolicName();
			if (name != null && name.startsWith(SELF_PREFIX)) {
				hits.add(name);
			}
		}
		return hits;
	}

	private static boolean contentUnchanged(Bundle installed, Path jar) {
		Path old = localJarOf(installed);
		if (old == null) {
			return false;
		}
		Path fresh = jar.toAbsolutePath().normalize();
		if (old.equals(fresh)) {
			return true;
		}
		try {
			return sha256(old).equals(sha256(fresh));
		} catch (IOException e) {
			return false;
		}
	}

	private static Path localJarOf(Bundle bundle) {
		String location = bundle.getLocation();
		if (location == null) {
			return null;
		}
		String path = location;
		if (path.startsWith("initial@")) { //$NON-NLS-1$
			path = path.substring("initial@".length()); //$NON-NLS-1$
		}
		if (path.startsWith("reference:")) { //$NON-NLS-1$
			path = path.substring("reference:".length()); //$NON-NLS-1$
		}
		if (!path.startsWith("file:")) { //$NON-NLS-1$
			return null;
		}
		Path candidate = FileLocations.pathOf(path);
		return candidate != null && Files.isRegularFile(candidate) ? candidate : null;
	}

	private static String sha256(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static String symbolOf(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		int cut = header.indexOf(';');
		return (cut < 0 ? header : header.substring(0, cut)).trim();
	}

	private static McpToolResult notABundle(Path jar, String why) {
		return McpToolResult.error("Refused: '%s' is not an OSGi bundle, %s.".formatted(jar, why)); //$NON-NLS-1$
	}

	private static String rootMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null ? throwable.toString() : message;
	}
}
