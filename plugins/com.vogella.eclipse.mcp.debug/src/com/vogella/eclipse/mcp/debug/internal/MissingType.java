package com.vogella.eclipse.mcp.debug.internal;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * A saved launch configuration whose type no installed plug-in contributes, which the launch
 * manager leaves out of its list.
 */
record MissingType(String name, String typeId, String location, boolean inProject) {

	private static final Pattern TYPE = Pattern.compile("<launchConfiguration[^>]*?\\btype=\"([^\"]+)\""); //$NON-NLS-1$

	JsonObject json() {
		JsonObject json = new JsonObject().put("name", name) //$NON-NLS-1$
				.put("launchable", Boolean.FALSE) //$NON-NLS-1$
				.put("missingType", typeId) //$NON-NLS-1$
				.put("storage", inProject ? "project file" : "workspace metadata") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put(inProject ? "file" : "path", location); //$NON-NLS-1$ //$NON-NLS-2$
		Bundle bundle = contributor(typeId);
		if (bundle != null) {
			json.put("plugin", bundle.getSymbolicName()).put("pluginState", stateName(bundle.getState())); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return json;
	}

	/** Every .launch file in open projects and the workspace store whose type the manager does not know. */
	static List<MissingType> scan(ILaunchManager manager) {
		List<MissingType> found = new ArrayList<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (!project.isAccessible()) {
				continue;
			}
			try {
				project.accept(resource -> {
					if (resource instanceof IFile file && "launch".equals(file.getFileExtension())) { //$NON-NLS-1$
						String type = typeOf(file);
						if (type != null && manager.getLaunchConfigurationType(type) == null) {
							found.add(new MissingType(base(file.getName()), type, file.getFullPath().toString(), true));
						}
						return false;
					}
					return !resource.isDerived();
				});
			} catch (CoreException e) {
				// an unreadable project has nothing to list
			}
		}
		File store = DebugPlugin.getDefault().getStateLocation().append(".launches").toFile(); //$NON-NLS-1$
		File[] files = store.listFiles((dir, fileName) -> fileName.endsWith(".launch")); //$NON-NLS-1$
		if (files != null) {
			for (File file : files) {
				try {
					Matcher matcher = TYPE.matcher(new String(java.nio.file.Files.readAllBytes(file.toPath()),
							StandardCharsets.UTF_8));
					if (matcher.find() && manager.getLaunchConfigurationType(matcher.group(1)) == null) {
						found.add(new MissingType(base(file.getName()), matcher.group(1), file.getAbsolutePath(), false));
					}
				} catch (java.io.IOException e) {
					// an unreadable file is not a configuration anybody can launch either
				}
			}
		}
		return found;
	}

	private static String base(String fileName) {
		return fileName.substring(0, fileName.length() - ".launch".length()); //$NON-NLS-1$
	}

	private static String typeOf(IFile file) {
		try (InputStream in = file.getContents()) {
			Matcher matcher = TYPE.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			return matcher.find() ? matcher.group(1) : null;
		} catch (CoreException | java.io.IOException e) {
			return null;
		}
	}

	/** The installed bundle whose symbolic name is the longest prefix of the type id, if any. */
	private static Bundle contributor(String id) {
		Bundle bundle = FrameworkUtil.getBundle(MissingType.class);
		if (bundle == null || bundle.getBundleContext() == null) {
			return null;
		}
		Bundle best = null;
		for (Bundle candidate : bundle.getBundleContext().getBundles()) {
			String symbolic = candidate.getSymbolicName();
			if (symbolic != null && id.startsWith(symbolic + ".") //$NON-NLS-1$
					&& (best == null || symbolic.length() > best.getSymbolicName().length())) {
				best = candidate;
			}
		}
		return best;
	}

	private static String stateName(int state) {
		return switch (state) {
		case Bundle.INSTALLED -> "installed"; //$NON-NLS-1$
		case Bundle.RESOLVED -> "resolved"; //$NON-NLS-1$
		case Bundle.STARTING -> "starting"; //$NON-NLS-1$
		case Bundle.ACTIVE -> "active"; //$NON-NLS-1$
		default -> "other"; //$NON-NLS-1$
		};
	}
}
