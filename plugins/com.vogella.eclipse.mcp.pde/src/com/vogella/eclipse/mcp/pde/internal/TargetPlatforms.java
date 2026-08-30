package com.vogella.eclipse.mcp.pde.internal;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetHandle;
import org.eclipse.pde.core.target.ITargetLocation;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.eclipse.pde.core.target.TargetBundle;
import org.eclipse.pde.core.target.TargetFeature;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.core.JreUsability;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Shared plumbing for the target platform tools: the PDE service, the handle a
 * caller names, and the JSON view of a target definition.
 */
final class TargetPlatforms {

	private TargetPlatforms() {
	}

	/** Runs {@code body} with PDE's target platform service, releasing it afterwards. */
	static McpToolResult with(Function<ITargetPlatformService, McpToolResult> body) {
		// the bundle is lazily activated, so its own context only exists once it started
		BundleContext context = FrameworkUtil.getBundle(TargetPlatforms.class).getBundleContext();
		if (context == null) {
			context = FrameworkUtil.getBundle(ITargetPlatformService.class).getBundleContext();
		}
		if (context == null) {
			return McpToolResult
					.error("Neither this bundle nor PDE is active, so the target platform service cannot be reached."); //$NON-NLS-1$
		}
		ServiceReference<ITargetPlatformService> reference = context
				.getServiceReference(ITargetPlatformService.class);
		if (reference == null) {
			return McpToolResult.error("PDE does not offer its target platform service in this IDE."); //$NON-NLS-1$
		}
		try {
			return body.apply(context.getService(reference));
		} finally {
			context.ungetService(reference);
		}
	}

	/**
	 * Resolves a workspace path, a file system path or a memento to a target handle.
	 *
	 * @return {@code null} when nothing of that name exists
	 */
	static ITargetHandle handle(ITargetPlatformService service, String file, String memento) throws CoreException {
		if (memento != null) {
			return service.getTarget(memento);
		}
		IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(file);
		if (resource instanceof IFile target) {
			return service.getTarget(target);
		}
		File onDisk = new File(file);
		return onDisk.isFile() ? service.getTarget(onDisk.toURI()) : null;
	}

	/**
	 * The JRE a target binds, resolved to the install it actually names.
	 * <p>
	 * The container path alone says {@code JavaSE-21} and hides which JDK on this
	 * machine that is. Activating writes the binding into the projects, it outlives
	 * the target that set it, and a JDK that cannot serve {@code --release} then
	 * fails every plug-in project at once with a message about ct.sym that names no
	 * target at all. Naming the install and checking it before the caller commits
	 * is the difference between a decision and a surprise.
	 */
	static JsonObject jre(ITargetDefinition definition) {
		org.eclipse.core.runtime.IPath container = definition.getJREContainer();
		if (container == null) {
			return new JsonObject().put("container", null) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"This target names no JRE, so activating it leaves the projects on the JRE they already have."); //$NON-NLS-1$
		}
		JsonObject json = new JsonObject().put("container", container.toString()); //$NON-NLS-1$
		org.eclipse.jdt.launching.IVMInstall vm = org.eclipse.jdt.launching.JavaRuntime.getVMInstall(container);
		if (vm == null) {
			return json.put("resolved", Boolean.FALSE) //$NON-NLS-1$
					.put("warning", //$NON-NLS-1$
							"The JRE container resolves to no installed VM, so activating this target binds the projects to a JRE that is not there."); //$NON-NLS-1$
		}
		File location = vm.getInstallLocation();
		json.put("resolved", Boolean.TRUE).put("vmName", vm.getName()) //$NON-NLS-1$ //$NON-NLS-2$
				.put("installLocation", location == null ? null : location.getAbsolutePath()); //$NON-NLS-1$
		String unusable = JreUsability.reason(location);
		if (unusable != null) {
			json.put("usable", Boolean.FALSE).put("warning", unusable); //$NON-NLS-1$ //$NON-NLS-2$
		} else {
			json.put("usable", Boolean.TRUE); //$NON-NLS-1$
		}
		return json;
	}

	/** The full JSON view of a definition, as far as it has been resolved. */
	static JsonObject describe(ITargetDefinition definition, boolean includeLocations, int maxProblems) {
		JsonObject json = new JsonObject().put("name", definition.getName()) //$NON-NLS-1$
				.put("memento", memento(definition.getHandle())) //$NON-NLS-1$
				.put("resolved", definition.isResolved()) //$NON-NLS-1$
				.put("status", status(definition.getStatus())) //$NON-NLS-1$
				.put("jreContainer", //$NON-NLS-1$
						definition.getJREContainer() == null ? null : definition.getJREContainer().toString())
				.put("jre", jre(definition)) //$NON-NLS-1$
				.put("environment", new JsonObject().put("os", definition.getOS()) //$NON-NLS-1$ //$NON-NLS-2$
						.put("ws", definition.getWS()) //$NON-NLS-1$
						.put("arch", definition.getArch()) //$NON-NLS-1$
						.put("nl", definition.getNL())); //$NON-NLS-1$
		if (!definition.isResolved()) {
			return json;
		}

		TargetBundle[] bundles = definition.getBundles();
		TargetFeature[] features = definition.getAllFeatures();
		json.put("bundleCount", bundles == null ? 0 : bundles.length); //$NON-NLS-1$
		json.put("featureCount", features == null ? 0 : features.length); //$NON-NLS-1$

		JsonArray problems = new JsonArray();
		int broken = 0;
		if (bundles != null) {
			for (TargetBundle bundle : bundles) {
				IStatus bundleStatus = bundle.getStatus();
				if (bundleStatus == null || bundleStatus.isOK()) {
					continue;
				}
				broken++;
				if (problems.size() < maxProblems) {
					// toString rather than getBundleInfo(): PDE returns that from API, but
					// the type itself is internal and the compiler refuses it
					problems.add(new JsonObject().put("bundle", String.valueOf(bundle)) //$NON-NLS-1$
							.put("severity", severity(bundleStatus.getSeverity())) //$NON-NLS-1$
							.put("message", bundleStatus.getMessage())); //$NON-NLS-1$
				}
			}
		}
		json.put("bundleProblems", problems) //$NON-NLS-1$
				.put("bundleProblemCount", broken) //$NON-NLS-1$
				.put("bundleProblemsTruncated", broken > problems.size()); //$NON-NLS-1$

		if (includeLocations) {
			JsonArray locations = new JsonArray();
			for (ITargetLocation location : definition.getTargetLocations() == null ? new ITargetLocation[0]
					: definition.getTargetLocations()) {
				TargetBundle[] fromLocation = location.getBundles();
				String xml = serialize(location);
				locations.add(new JsonObject().put("type", location.getType()) //$NON-NLS-1$
						.put("location", location(location)) //$NON-NLS-1$
						.put("label", attribute(xml, "label")) //$NON-NLS-1$ //$NON-NLS-2$
						.put("repositories", repositories(xml)) //$NON-NLS-1$
						.put("resolved", location.isResolved()) //$NON-NLS-1$
						.put("bundleCount", fromLocation == null ? 0 : fromLocation.length) //$NON-NLS-1$
						.put("status", status(location.getStatus()))); //$NON-NLS-1$
			}
			json.put("locations", locations); //$NON-NLS-1$
		}
		return json;
	}

	/** A status with its children, which is where a failed location says what it could not reach. */
	static JsonObject status(IStatus status) {
		if (status == null) {
			return null;
		}
		JsonObject json = new JsonObject().put("severity", severity(status.getSeverity())) //$NON-NLS-1$
				.put("message", status.getMessage()); //$NON-NLS-1$
		if (status.getException() != null) {
			json.put("exception", String.valueOf(status.getException())); //$NON-NLS-1$
		}
		if (status.isMultiStatus() && status.getChildren().length > 0) {
			JsonArray children = new JsonArray();
			for (IStatus child : status.getChildren()) {
				children.add(status(child));
			}
			json.put("children", children); //$NON-NLS-1$
		}
		return json;
	}

	static String severity(int severity) {
		return switch (severity) {
		case IStatus.OK -> "OK"; //$NON-NLS-1$
		case IStatus.INFO -> "INFO"; //$NON-NLS-1$
		case IStatus.WARNING -> "WARNING"; //$NON-NLS-1$
		case IStatus.ERROR -> "ERROR"; //$NON-NLS-1$
		case IStatus.CANCEL -> "CANCEL"; //$NON-NLS-1$
		default -> String.valueOf(severity);
		};
	}

	static String memento(ITargetHandle handle) {
		try {
			return handle == null ? null : handle.getMemento();
		} catch (CoreException e) {
			return null;
		}
	}

	/**
	 * The path a location reads from, where it has one.
	 * <p>
	 * A Maven location has none, and PDE answers with the JVM's temp directory,
	 * which reads as a target configured to load bundles out of /tmp. It is not
	 * one, so the field is left empty and the label says which location it is.
	 */
	private static String location(ITargetLocation location) {
		try {
			String path = location.getLocation(false);
			if (path == null) {
				return null;
			}
			return isTemporaryDirectory(path) ? null : path;
		} catch (CoreException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Compared as paths rather than as strings: java.io.tmpdir ends with a
	 * separator on Windows and not on Linux, the separator itself differs, and
	 * Windows paths differing only in case are one directory. A string comparison
	 * therefore said "not the temp directory" off Linux and let PDE's placeholder
	 * through as if it were a real location.
	 */
	private static boolean isTemporaryDirectory(String path) {
		Path candidate = FileLocations.pathOf(path);
		Path temporary = FileLocations.pathOf(System.getProperty("java.io.tmpdir")); //$NON-NLS-1$
		return candidate != null && temporary != null
				&& candidate.toAbsolutePath().normalize().equals(temporary.toAbsolutePath().normalize());
	}

	/** The location's own XML, which carries what the API does not expose. */
	private static String serialize(ITargetLocation location) {
		try {
			return location.serialize();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String attribute(String xml, String name) {
		if (xml == null) {
			return null;
		}
		var matcher = Pattern.compile(name + "=\"([^\"]*)\"").matcher(xml); //$NON-NLS-1$
		return matcher.find() ? matcher.group(1) : null;
	}

	/** The p2 repositories an installable unit location resolves against, which its path never names. */
	private static JsonArray repositories(String xml) {
		if (xml == null) {
			return null;
		}
		JsonArray urls = new JsonArray();
		var matcher = Pattern.compile("<repository[^>]*location=\"([^\"]*)\"").matcher(xml); //$NON-NLS-1$
		while (matcher.find()) {
			urls.add(matcher.group(1));
		}
		return urls.size() == 0 ? null : urls;
	}
}
