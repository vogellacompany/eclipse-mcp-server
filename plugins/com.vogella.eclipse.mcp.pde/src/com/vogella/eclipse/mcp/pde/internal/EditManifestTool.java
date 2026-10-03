package com.vogella.eclipse.mcp.pde.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.ImportPackageSpecification;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.core.project.IBundleProjectDescription;
import org.eclipse.pde.core.project.IBundleProjectService;
import org.eclipse.pde.core.project.IPackageExportDescription;
import org.eclipse.pde.core.project.IPackageImportDescription;
import org.eclipse.pde.core.project.IRequiredBundleDescription;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Edits the OSGi headers of a plug-in project.
 */
public final class EditManifestTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_edit_manifest"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Adds and removes Require-Bundle, Import-Package and Export-Package entries of a plug-in project, with their directives: version ranges, optional, reexport, x-internal and x-friends. MODIFIES MANIFEST.MF, and runs as a dry run unless dryRun is set to false. It goes through PDE's own project model rather than editing text, so the header is written the way PDE writes it, with the continuation lines and the byte-counted line folding correct. Removing an export or a required bundle that other workspace bundles still consume is REFUSED unless force is passed, and the consumers are reported either way: an export removed under a consumer still compiles here and fails to resolve at runtime, which is the failure this check exists to prevent."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["project"],
				  "properties": {
				    "project": {"type":"string","description":"Plug-in project whose manifest to edit."},
				    "addExportPackage": {"type":"array","description":"Packages to export, each an object with package and optionally version, internal and friends.","items":{"type":"object","properties":{
				        "package":{"type":"string"},"version":{"type":"string"},
				        "internal":{"type":"boolean","description":"Write x-internal:=true."},
				        "friends":{"type":"array","items":{"type":"string"},"description":"Write x-friends."}},
				        "required":["package"],"additionalProperties":false}},
				    "removeExportPackage": {"type":"array","items":{"type":"string"},"description":"Package names to stop exporting."},
				    "addRequireBundle": {"type":"array","description":"Bundles to require, each an object with bundle and optionally versionRange, optional and reexport.","items":{"type":"object","properties":{
				        "bundle":{"type":"string"},"versionRange":{"type":"string"},
				        "optional":{"type":"boolean"},"reexport":{"type":"boolean"}},
				        "required":["bundle"],"additionalProperties":false}},
				    "removeRequireBundle": {"type":"array","items":{"type":"string"},"description":"Bundle symbolic names to stop requiring."},
				    "addImportPackage": {"type":"array","description":"Packages to import, each an object with package and optionally versionRange and optional.","items":{"type":"object","properties":{
				        "package":{"type":"string"},"versionRange":{"type":"string"},"optional":{"type":"boolean"}},
				        "required":["package"],"additionalProperties":false}},
				    "removeImportPackage": {"type":"array","items":{"type":"string"},"description":"Package names to stop importing."},
				    "dryRun": {"type":"boolean","default":true,"description":"Report the resulting headers without writing the manifest."},
				    "includeFullHeaders": {"type":"boolean","default":false,"description":"Also return every entry of the resulting headers. Off by default: on a platform bundle that is tens of kilobytes describing one changed line."},
				    "force":  {"type":"boolean","default":false,"description":"Remove an export or a required bundle that workspace bundles still consume."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String projectName = args.getString("project"); //$NON-NLS-1$
		if (projectName == null) {
			return McpToolResult.error("The argument 'project' is required."); //$NON-NLS-1$
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.isAccessible()) {
			return McpToolResult.error("No open project named '%s' in this workspace.".formatted(projectName)); //$NON-NLS-1$
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		boolean force = args.getBoolean("force", false); //$NON-NLS-1$

		try {
			return PdeServices.with(IBundleProjectService.class, "bundle project service", //$NON-NLS-1$
					service -> edit(service, project, arguments, dryRun, force, monitor));
		} catch (CoreException e) {
			throw new McpToolException("Could not edit the manifest of " + projectName, e); //$NON-NLS-1$
		}
	}

	private McpToolResult edit(IBundleProjectService service, IProject project, Map<String, Object> arguments,
			boolean dryRun, boolean force, IProgressMonitor monitor) throws CoreException {
		IBundleProjectDescription description = service.getDescription(project);
		if (description.getSymbolicName() == null) {
			return McpToolResult.error("'%s' is not a plug-in project; it has no bundle manifest." //$NON-NLS-1$
					.formatted(project.getName()));
		}
		// apply() writes the whole PDE model back and drops any attribute or directive
		// it cannot represent, so a manifest it cannot rewrite faithfully is refused
		String unsupported = unsupported(project);
		if (unsupported != null) {
			return McpToolResult.error(unsupported);
		}
		List<IPackageExportDescription> exportsBefore = List
				.of(description.getPackageExports() == null ? new IPackageExportDescription[0]
						: description.getPackageExports());
		List<IRequiredBundleDescription> requiresBefore = List
				.of(description.getRequiredBundles() == null ? new IRequiredBundleDescription[0]
						: description.getRequiredBundles());
		List<IPackageImportDescription> importsBefore = List
				.of(description.getPackageImports() == null ? new IPackageImportDescription[0]
						: description.getPackageImports());
		List<String> removeExports = strings(arguments, "removeExportPackage"); //$NON-NLS-1$
		List<String> removeRequires = strings(arguments, "removeRequireBundle"); //$NON-NLS-1$
		List<String> removeImports = strings(arguments, "removeImportPackage"); //$NON-NLS-1$

		JsonObject result = new JsonObject().put("project", project.getName()) //$NON-NLS-1$
				.put("bundle", description.getSymbolicName()) //$NON-NLS-1$
				.put("dryRun", Boolean.valueOf(dryRun)); //$NON-NLS-1$

		// who would break, computed before anything is changed
		JsonArray blocked = new JsonArray();
		JsonArray dependents = new JsonArray();
		List<String> requiring = removeExports.isEmpty() ? List.of() : requirersOf(description.getSymbolicName());
		for (String exported : removeExports) {
			List<String> importers = importersOf(exported, description.getSymbolicName());
			if (!importers.isEmpty()) {
				blocked.add(new JsonObject().put("removing", "Export-Package " + exported) //$NON-NLS-1$ //$NON-NLS-2$
						.put("importedBy", array(importers))); //$NON-NLS-1$
			}
			if (!requiring.isEmpty()) {
				// Require-Bundle dependents only MIGHT use the package; blocking on them
				// refused almost every removal on a platform bundle
				dependents.add(new JsonObject().put("package", exported) //$NON-NLS-1$
						.put("requireBundleDependents", array(requiring)) //$NON-NLS-1$
						.put("note", //$NON-NLS-1$
								"These require this bundle rather than importing the package, so they may or may not use it. Confirm with eclipse_find_references on a type of the package before removing the export; this does not block.")); //$NON-NLS-1$
			}
		}
		if (dependents.size() > 0) {
			result.put("mightAlsoUseIt", dependents); //$NON-NLS-1$
		}
		// removing what THIS bundle requires cannot break anyone else, so only
		// exports are checked for consumers
		if (blocked.size() > 0) {
			result.put("wouldBreak", blocked); //$NON-NLS-1$
			if (!force) {
				return McpToolResult.of(result.put("applied", Boolean.FALSE) //$NON-NLS-1$
						.put("refusedBecause", //$NON-NLS-1$
								"Workspace bundles still consume what you are removing. They keep compiling and fail to resolve at runtime, which is why this is refused rather than warned about. Pass force to do it anyway.") //$NON-NLS-1$
						.toString());
			}
		}

		List<IPackageExportDescription> exports = new ArrayList<>(exportsBefore);
		exports.removeIf(export -> removeExports.contains(export.getName()));
		for (Map<String, Object> entry : objects(arguments, "addExportPackage")) { //$NON-NLS-1$
			String name = string(entry, "package"); //$NON-NLS-1$
			String version = string(entry, "version"); //$NON-NLS-1$
			List<String> friends = strings(entry, "friends"); //$NON-NLS-1$
			boolean internal = Boolean.TRUE.equals(entry.get("internal")); //$NON-NLS-1$
			exports.removeIf(export -> export.getName().equals(name));
			// api false is what PDE writes as x-internal, and a friend list is written
			// as x-friends; the two are alternatives rather than independent flags
			// an empty list rather than null: PDE dereferences the collection, and its
			// javadoc says a version may be null but is silent about this one
			exports.add(service.newPackageExport(name, version == null ? null : Version.parseVersion(version),
					!internal, friends));
		}

		List<IRequiredBundleDescription> requires = new ArrayList<>(requiresBefore);
		requires.removeIf(required -> removeRequires.contains(required.getName()));
		for (Map<String, Object> entry : objects(arguments, "addRequireBundle")) { //$NON-NLS-1$
			String name = string(entry, "bundle"); //$NON-NLS-1$
			String range = string(entry, "versionRange"); //$NON-NLS-1$
			requires.removeIf(required -> required.getName().equals(name));
			requires.add(service.newRequiredBundle(name, range == null ? null : new VersionRange(range),
					Boolean.TRUE.equals(entry.get("optional")), Boolean.TRUE.equals(entry.get("reexport")))); //$NON-NLS-1$ //$NON-NLS-2$
		}

		List<IPackageImportDescription> imports = new ArrayList<>(importsBefore);
		imports.removeIf(imported -> removeImports.contains(imported.getName()));
		for (Map<String, Object> entry : objects(arguments, "addImportPackage")) { //$NON-NLS-1$
			String name = string(entry, "package"); //$NON-NLS-1$
			String range = string(entry, "versionRange"); //$NON-NLS-1$
			imports.removeIf(imported -> imported.getName().equals(name));
			imports.add(service.newPackageImport(name, range == null ? null : new VersionRange(range),
					Boolean.TRUE.equals(entry.get("optional")))); //$NON-NLS-1$
		}

		// the change only: a whole platform manifest is tens of kilobytes of JSON
		JsonObject changes = new JsonObject()
				.put("exportPackage", //$NON-NLS-1$
						difference(exportsBefore, exports, IPackageExportDescription::getName,
								EditManifestTool::exportJson))
				.put("requireBundle", //$NON-NLS-1$
						difference(requiresBefore, requires, IRequiredBundleDescription::getName,
								EditManifestTool::requireJson))
				.put("importPackage", //$NON-NLS-1$
						difference(importsBefore, imports, IPackageImportDescription::getName,
								EditManifestTool::importJson));
		result.put("changes", changes); //$NON-NLS-1$
		if (ToolArguments.of(arguments).getBoolean("includeFullHeaders", false)) { //$NON-NLS-1$
			result.put("exportPackage", exportsJson(exports)) //$NON-NLS-1$
					.put("requireBundle", requiresJson(requires)) //$NON-NLS-1$
					.put("importPackage", importsJson(imports)); //$NON-NLS-1$
		}
		if (dryRun) {
			return McpToolResult.of(result.put("applied", Boolean.FALSE) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"Nothing was written. 'changes' is what would differ; pass includeFullHeaders to see every entry of the resulting headers, and dryRun false to write it.") //$NON-NLS-1$
					.toString());
		}
		description.setPackageExports(exports.toArray(IPackageExportDescription[]::new));
		description.setRequiredBundles(requires.toArray(IRequiredBundleDescription[]::new));
		description.setPackageImports(imports.isEmpty() ? null : imports.toArray(IPackageImportDescription[]::new));
		description.apply(monitor);
		return McpToolResult.of(result.put("applied", Boolean.TRUE).toString()); //$NON-NLS-1$
	}

	/** Attributes and directives PDE's project model can carry, per header. */
	private static final Map<String, Set<String>> SUPPORTED = Map.of( //
			"Export-Package", Set.of("version", "x-internal", "x-friends"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"Require-Bundle", Set.of("bundle-version", "visibility", "resolution"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"Import-Package", Set.of("version", "resolution")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	/**
	 * The first thing in this manifest that a write would destroy, or {@code null}
	 * when the file only uses what the model can hold.
	 */
	private static String unsupported(IProject project) {
		org.eclipse.core.resources.IFile file = project.getFile("META-INF/MANIFEST.MF"); //$NON-NLS-1$
		String content;
		try (InputStream in = file.getContents(true)) {
			content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (CoreException | IOException e) {
			return "Could not read the manifest of %s: %s".formatted(project.getName(), e.getMessage()); //$NON-NLS-1$
		}
		// continuation lines start with a single space
		String unfolded = content.replace("\r\n", "\n").replace("\n ", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		for (Map.Entry<String, Set<String>> header : SUPPORTED.entrySet()) {
			String value = null;
			for (String line : unfolded.split("\n")) { //$NON-NLS-1$
				if (line.startsWith(header.getKey() + ":")) { //$NON-NLS-1$
					value = line.substring(header.getKey().length() + 1).trim();
				}
			}
			if (value == null || value.isBlank()) {
				continue;
			}
			org.eclipse.osgi.util.ManifestElement[] elements;
			try {
				elements = org.eclipse.osgi.util.ManifestElement.parseHeader(header.getKey(), value);
			} catch (org.osgi.framework.BundleException e) {
				return "The %s header of %s could not be parsed, so this tool will not rewrite it: %s" //$NON-NLS-1$
						.formatted(header.getKey(), project.getName(), e.getMessage());
			}
			for (org.eclipse.osgi.util.ManifestElement element : elements == null
					? new org.eclipse.osgi.util.ManifestElement[0]
					: elements) {
				for (String key : Collections.list(element.getKeys() == null
						? Collections.<String>emptyEnumeration()
						: element.getKeys())) {
					if (!header.getValue().contains(key)) {
						return refusal(project, header.getKey(), element.getValue(), key, "attribute"); //$NON-NLS-1$
					}
				}
				for (String key : Collections.list(element.getDirectiveKeys() == null
						? Collections.<String>emptyEnumeration()
						: element.getDirectiveKeys())) {
					if (!header.getValue().contains(key)) {
						return refusal(project, header.getKey(), element.getValue(), key, "directive"); //$NON-NLS-1$
					}
				}
			}
		}
		return null;
	}

	private static String refusal(IProject project, String header, String clause, String key, String kind) {
		return ("Refused, and nothing was changed. %s of %s has '%s' on '%s', and PDE's project model cannot carry it: "
				+ "the model holds only the package or bundle name, a version, x-internal and x-friends, and applying it "
				+ "rewrites the WHOLE header from the model, so every %s it cannot represent would be dropped from every "
				+ "clause. On a bundle with split packages that alone stops it resolving. Edit this header by hand.")
						.formatted(header, project.getName(), key, clause, kind);
	}

	/** The workspace bundles that import a package, which is what an export removal breaks. */
	private static List<String> importersOf(String packageName, String exporter) {
		Set<String> consumers = new LinkedHashSet<>();
		for (IPluginModelBase model : PluginRegistry.getWorkspaceModels()) {
			BundleDescription description = model.getBundleDescription();
			if (description == null || description.getSymbolicName() == null
					|| description.getSymbolicName().equals(exporter)) {
				continue;
			}
			for (ImportPackageSpecification specification : description.getImportPackages()) {
				if (packageName.equals(specification.getName())) {
					consumers.add(description.getSymbolicName());
				}
			}
		}
		return List.copyOf(consumers);
	}

	/** Bundles that require this one, and so see its exports without naming them. */
	private static List<String> requirersOf(String bundle) {
		Set<String> consumers = new LinkedHashSet<>();
		for (IPluginModelBase model : PluginRegistry.getWorkspaceModels()) {
			BundleDescription description = model.getBundleDescription();
			if (description == null || description.getSymbolicName() == null
					|| description.getSymbolicName().equals(bundle)) {
				continue;
			}
			for (org.eclipse.osgi.service.resolver.BundleSpecification specification : description
					.getRequiredBundles()) {
				if (bundle.equals(specification.getName())) {
					consumers.add(description.getSymbolicName());
				}
			}
		}
		return List.copyOf(consumers);
	}

	private static JsonObject exportJson(IPackageExportDescription export) {
		JsonObject entry = new JsonObject().put("package", export.getName()) //$NON-NLS-1$
				.put("version", export.getVersion() == null ? null : export.getVersion().toString()) //$NON-NLS-1$
				.put("visibility", export.isApi() ? "public" : "x-internal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (export.getFriends() != null && export.getFriends().length > 0) {
			entry.put("visibility", "x-friends").put("friends", array(List.of(export.getFriends()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		return entry;
	}

	private static JsonArray exportsJson(List<IPackageExportDescription> exports) {
		JsonArray array = new JsonArray();
		exports.forEach(export -> array.add(exportJson(export)));
		return array;
	}

	private static JsonObject requireJson(IRequiredBundleDescription required) {
		return new JsonObject().put("bundle", required.getName()) //$NON-NLS-1$
				.put("versionRange", //$NON-NLS-1$
						required.getVersionRange() == null ? null : required.getVersionRange().toString())
				.put("optional", Boolean.valueOf(required.isOptional())) //$NON-NLS-1$
				.put("reexport", Boolean.valueOf(required.isExported())); //$NON-NLS-1$
	}

	private static JsonArray requiresJson(List<IRequiredBundleDescription> requires) {
		JsonArray array = new JsonArray();
		requires.forEach(required -> array.add(requireJson(required)));
		return array;
	}

	private static JsonObject importJson(IPackageImportDescription imported) {
		return new JsonObject().put("package", imported.getName()) //$NON-NLS-1$
				.put("versionRange", //$NON-NLS-1$
						imported.getVersionRange() == null ? null : imported.getVersionRange().toString())
				.put("optional", Boolean.valueOf(imported.isOptional())); //$NON-NLS-1$
	}

	private static JsonArray importsJson(List<IPackageImportDescription> imports) {
		JsonArray array = new JsonArray();
		imports.forEach(imported -> array.add(importJson(imported)));
		return array;
	}

	/**
	 * What differs between two rendered headers, keyed by the entry's name.
	 * <p>
	 * An entry present on both sides with different directives counts as changed
	 * rather than as an addition and a removal, since that is the edit a person
	 * would recognise.
	 */
	private static <T> JsonObject difference(List<T> before, List<T> after,
			Function<T, String> name, Function<T, JsonObject> render) {
		Map<String, T> was = new LinkedHashMap<>();
		before.forEach(entry -> was.put(name.apply(entry), entry));
		Map<String, T> is = new LinkedHashMap<>();
		after.forEach(entry -> is.put(name.apply(entry), entry));
		JsonArray added = new JsonArray();
		JsonArray removed = new JsonArray();
		JsonArray changed = new JsonArray();
		for (Map.Entry<String, T> entry : is.entrySet()) {
			T previous = was.get(entry.getKey());
			JsonObject now = render.apply(entry.getValue());
			if (previous == null) {
				added.add(now);
			} else {
				JsonObject then = render.apply(previous);
				if (!then.toString().equals(now.toString())) {
					changed.add(new JsonObject().put("from", then).put("to", now)); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
		}
		for (Map.Entry<String, T> entry : was.entrySet()) {
			if (!is.containsKey(entry.getKey())) {
				removed.add(render.apply(entry.getValue()));
			}
		}
		JsonObject difference = new JsonObject();
		if (added.size() > 0) {
			difference.put("added", added); //$NON-NLS-1$
		}
		if (removed.size() > 0) {
			difference.put("removed", removed); //$NON-NLS-1$
		}
		if (changed.size() > 0) {
			difference.put("changed", changed); //$NON-NLS-1$
		}
		return difference;
	}

	private static JsonArray array(List<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	private static String string(Map<String, Object> entry, String key) {
		Object value = entry.get(key);
		return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
	}

	private static List<String> strings(Map<String, Object> arguments, String name) {
		List<String> values = new ArrayList<>();
		if (arguments != null && arguments.get(name) instanceof List<?> list) {
			for (Object value : list) {
				if (value != null && !String.valueOf(value).isBlank()) {
					values.add(String.valueOf(value).trim());
				}
			}
		}
		return values;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> objects(Map<String, Object> arguments, String name) {
		List<Map<String, Object>> values = new ArrayList<>();
		if (arguments != null && arguments.get(name) instanceof List<?> list) {
			for (Object value : list) {
				if (value instanceof Map<?, ?> map) {
					values.add((Map<String, Object>) map);
				}
			}
		}
		return values;
	}
}
