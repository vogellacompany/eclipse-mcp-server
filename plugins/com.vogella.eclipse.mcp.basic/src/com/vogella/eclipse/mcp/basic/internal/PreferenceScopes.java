package com.vogella.eclipse.mcp.basic.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.preferences.ConfigurationScope;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * The preference scopes a tool can address, in the order the platform resolves them.
 */
public final class PreferenceScopes {

	/** Scope names in lookup order, which is also the order a value overrides the one below it. */
	public static final List<String> LOOKUP_ORDER = List.of("project", "instance", //$NON-NLS-1$ //$NON-NLS-2$
			"configuration", "default"); //$NON-NLS-1$ //$NON-NLS-2$

	private PreferenceScopes() {
	}

	/**
	 * Returns the preference nodes of {@code qualifier} that exist, keyed by scope
	 * name, in lookup order. The project scope is only present when
	 * {@code projectName} names an accessible project.
	 */
	public static Map<String, IEclipsePreferences> nodes(String qualifier, String projectName) {
		Map<String, IEclipsePreferences> nodes = new LinkedHashMap<>();
		if (projectName != null) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (project.isAccessible()) {
				nodes.put("project", new ProjectScope(project).getNode(qualifier)); //$NON-NLS-1$
			}
		}
		nodes.put("instance", InstanceScope.INSTANCE.getNode(qualifier)); //$NON-NLS-1$
		nodes.put("configuration", ConfigurationScope.INSTANCE.getNode(qualifier)); //$NON-NLS-1$
		nodes.put("default", DefaultScope.INSTANCE.getNode(qualifier)); //$NON-NLS-1$
		return nodes;
	}

	/** Returns the writable scope context for {@code scope}, or {@code null} when it is not writable. */
	public static IScopeContext writableContext(String scope, String projectName) {
		if ("project".equals(scope)) { //$NON-NLS-1$
			if (projectName == null) {
				return null;
			}
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			return project.isAccessible() ? new ProjectScope(project) : null;
		}
		return "instance".equals(scope) ? InstanceScope.INSTANCE : null; //$NON-NLS-1$
	}
}
