package com.vogella.eclipse.mcp.pde.internal;

import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.BundleSpecification;
import org.eclipse.osgi.service.resolver.ExportPackageDescription;
import org.eclipse.osgi.service.resolver.HostSpecification;
import org.eclipse.osgi.service.resolver.ImportPackageSpecification;

import com.vogella.eclipse.mcp.core.Globs;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports bundles as PDE resolved them against the active target platform.
 */
public final class GetBundleInfoTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_get_bundle_info"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports OSGi bundles as PDE resolved them against the active target platform: symbolic name, version, whether it resolved, its host if it is a fragment, its platform filter, and every Require-Bundle and Import-Package with whether that particular constraint was satisfied and what it bound to. This is what turns 'Cannot resolve plug-in: org.eclipse.opengl' into an answer. Parsing MANIFEST.MF only shows what was asked for, never what was found."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "symbolicName":   {"type":"string","description":"Exact bundle symbolic name."},
				    "project":        {"type":"string","description":"Workspace project name, for when you know the project rather than the bundle id."},
				    "namePattern":    {"type":"string","description":"Glob over symbolic names, '*' and '?' allowed, case insensitive."},
				    "workspaceOnly":  {"type":"boolean","default":true,"description":"Only bundles that are projects in this workspace. With false, target platform bundles are included too."},
				    "unresolvedOnly": {"type":"boolean","default":false,"description":"Only bundles that did not resolve, which is the usual reason to ask."},
				    "includeConstraints": {"type":"boolean","default":true,"description":"List Require-Bundle and Import-Package entries with their resolution status. Set false for a compact overview."},
				    "maxResults":     {"type":"integer","default":100,"minimum":1,"maximum":2000}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String symbolicName = args.getString("symbolicName"); //$NON-NLS-1$
		Pattern namePattern = Globs.compile(args.getString("namePattern")); //$NON-NLS-1$
		String projectName = args.getString("project"); //$NON-NLS-1$
		boolean workspaceOnly = args.getBoolean("workspaceOnly", true); //$NON-NLS-1$
		boolean unresolvedOnly = args.getBoolean("unresolvedOnly", false); //$NON-NLS-1$
		boolean includeConstraints = args.getBoolean("includeConstraints", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 100, 1, 2000); //$NON-NLS-1$

		IPluginModelBase[] models = workspaceOnly ? PluginRegistry.getWorkspaceModels()
				: PluginRegistry.getActiveModels(true);
		JsonArray reported = new JsonArray();
		int matched = 0;
		for (IPluginModelBase model : models) {
			if (monitor.isCanceled()) {
				return McpToolResult.error("The request was cancelled."); //$NON-NLS-1$
			}
			BundleDescription description = model.getBundleDescription();
			if (description == null) {
				continue;
			}
			String name = description.getSymbolicName();
			if (name == null) {
				continue;
			}
			if (symbolicName != null && !symbolicName.equals(name)) {
				continue;
			}
			if (projectName != null) {
				var resource = model.getUnderlyingResource();
				if (resource == null || !projectName.equals(resource.getProject().getName())) {
					continue;
				}
			}
			if (namePattern != null && !namePattern.matcher(name).matches()) {
				continue;
			}
			if (unresolvedOnly && description.isResolved()) {
				continue;
			}
			matched++;
			if (reported.size() < maxResults) {
				reported.add(describe(model, description, includeConstraints));
			}
		}
		JsonObject result = new JsonObject().put("workspaceOnly", workspaceOnly) //$NON-NLS-1$
				.put("total", matched) //$NON-NLS-1$
				.put("truncated", matched > reported.size()) //$NON-NLS-1$
				.put("bundles", reported); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	private static JsonObject describe(IPluginModelBase model, BundleDescription description,
			boolean includeConstraints) {
		JsonObject json = new JsonObject().put("symbolicName", description.getSymbolicName()) //$NON-NLS-1$
				.put("version", description.getVersion() == null ? null : description.getVersion().toString()) //$NON-NLS-1$
				.put("resolved", description.isResolved()) //$NON-NLS-1$
				.put("location", model.getInstallLocation()) //$NON-NLS-1$
				.put("workspace", model.getUnderlyingResource() != null) //$NON-NLS-1$
				.put("platformFilter", description.getPlatformFilter()); //$NON-NLS-1$

		HostSpecification host = description.getHost();
		if (host != null) {
			json.put("fragmentHost", new JsonObject().put("name", host.getName()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("versionRange", host.getVersionRange() == null ? null : host.getVersionRange().toString()) //$NON-NLS-1$
					.put("resolved", host.isResolved())); //$NON-NLS-1$
		}
		// before the constraint guard on purpose: exports are not constraints, and
		// they are what apiTier and every "can this have consumers" question rest on,
		// so a compact overview that drops them drops the useful half
		JsonArray exports = new JsonArray();
		for (ExportPackageDescription export : description.getExportPackages()) {
			// the directives, not just the name: x-internal and x-friends decide whether
			// a package can have consumers outside this workspace at all, which is what
			// makes a "no references" result mean something or nothing
			Object internal = export.getDirective("x-internal"); //$NON-NLS-1$
			Object friendly = export.getDirective("x-friends"); //$NON-NLS-1$
			JsonObject entry = new JsonObject().put("package", export.getName()); //$NON-NLS-1$
			if (friendly != null) {
				JsonArray friends = new JsonArray();
				for (Object friend : friendly instanceof Object[] many ? many : new Object[] { friendly }) {
					friends.add(String.valueOf(friend));
				}
				entry.put("visibility", "x-friends").put("friends", friends); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			} else if (internal != null && Boolean.parseBoolean(String.valueOf(internal))) {
				entry.put("visibility", "x-internal"); //$NON-NLS-1$ //$NON-NLS-2$
			} else {
				entry.put("visibility", "public"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			exports.add(entry);
		}
		json.put("exportPackage", exports); //$NON-NLS-1$
		if (!includeConstraints) {
			return json;
		}

		JsonArray required = new JsonArray();
		for (BundleSpecification specification : description.getRequiredBundles()) {
			required.add(new JsonObject().put("name", specification.getName()) //$NON-NLS-1$
					.put("versionRange", //$NON-NLS-1$
							specification.getVersionRange() == null ? null : specification.getVersionRange().toString())
					.put("optional", specification.isOptional()) //$NON-NLS-1$
					// visibility:=reexport, the other half of the directives this header
					// carries. Without it a reexport cleanup has to parse manifests as
					// text, and reexport is the whole subject of that question
					.put("reexported", Boolean.valueOf(specification.isExported())) //$NON-NLS-1$
					.put("resolved", specification.isResolved()) //$NON-NLS-1$
					.put("boundTo", specification.getSupplier() == null ? null //$NON-NLS-1$
							: specification.getSupplier().toString()));
		}
		json.put("requireBundle", required); //$NON-NLS-1$

		JsonArray imports = new JsonArray();
		for (ImportPackageSpecification specification : description.getImportPackages()) {
			imports.add(new JsonObject().put("package", specification.getName()) //$NON-NLS-1$
					.put("versionRange", //$NON-NLS-1$
							specification.getVersionRange() == null ? null : specification.getVersionRange().toString())
					.put("optional", //$NON-NLS-1$
							ImportPackageSpecification.RESOLUTION_OPTIONAL.equals(
									specification.getDirective(org.osgi.framework.Constants.RESOLUTION_DIRECTIVE)))
					.put("resolved", specification.isResolved()) //$NON-NLS-1$
					.put("boundTo", specification.getSupplier() == null ? null //$NON-NLS-1$
							: specification.getSupplier().toString()));
		}
		json.put("importPackage", imports); //$NON-NLS-1$

		return json;
	}
}
