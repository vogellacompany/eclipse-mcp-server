package com.vogella.eclipse.mcp.pde.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTRequestor;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.BundleSpecification;
import org.eclipse.osgi.service.resolver.ExportPackageDescription;
import org.eclipse.osgi.service.resolver.ImportPackageSpecification;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Compares what a bundle declares it needs with what its source actually uses.
 */
public final class AnalyzeDependenciesTool implements IMcpTool {

	private static final int SAMPLES = 5;

	@Override
	public String getName() {
		return "eclipse_analyze_dependencies"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Compares what a plug-in declares in Require-Bundle and Import-Package with the bundles its source actually resolves against, which is the analysis a reexport or dependency cleanup needs and which cannot be done by hand at any scale. Read-only. Reports declared, actuallyUsed, unused, and viaReexport: bundles the source uses that are only reachable because a declared bundle reexports them, which is exactly the list a consumer must gain before that reexport can be dropped. 'unused' IS NOT A DELETION INSTRUCTION for the same reason 'dead' is not in eclipse_list_declarations: a bundle can be needed for a class named in plugin.xml, an OSGi service, or a Class.forName that no type reference reveals, and an optional or platform-filtered requirement that does not resolve on this machine is not unused either. Both are reported rather than judged."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "project":  {"type":"string","description":"Plug-in project to analyse."},
				    "projects": {"type":"array","items":{"type":"string"},"description":"Several projects."},
				    "maxResults": {"type":"integer","default":50,"minimum":1,"maximum":500}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public String getOutputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "total":     {"type":"integer"},
				    "truncated": {"type":"boolean"},
				    "projects":  {"type":"array","items":{"type":"object","properties":{"project":{"type":"string"},"error":{"type":"string"},"bundle":{"type":"string"},"declaredRequireBundle":{"type":"array","items":{"type":"object"}},"declaredImportPackage":{"type":"array","items":{"type":"string"}},"actuallyUsed":{"type":"array","items":{"type":"object"}},"unused":{"type":"array","items":{"type":"object"}},"viaReexport":{"type":"array","items":{"type":"object"}},"undeclared":{"type":"array","items":{"type":"object"}},"caveats":{"type":"array","items":{"type":"string"}}}}}
				  },
				  "required": ["total","truncated","projects"]
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		List<String> names = new ArrayList<>();
		if (arguments != null && arguments.get("projects") instanceof List<?> list) { //$NON-NLS-1$
			list.forEach(value -> names.add(String.valueOf(value).trim()));
		}
		String single = args.getString("project"); //$NON-NLS-1$
		if (single != null && !names.contains(single)) {
			names.add(single);
		}
		if (names.isEmpty()) {
			return McpToolResult.error("Name a project through 'project' or 'projects'."); //$NON-NLS-1$
		}
		int maxResults = args.getInt("maxResults", 50, 1, 500); //$NON-NLS-1$
		IProgressMonitor progress = monitor == null ? new NullProgressMonitor() : monitor;

		Map<String, String> exporters = exportingBundles();
		JsonArray projects = new JsonArray();
		try {
			for (String name : names) {
				if (projects.size() >= maxResults) {
					break;
				}
				projects.add(analyse(name, exporters, progress));
			}
		} catch (CoreException e) {
			throw new McpToolException("Could not analyse the dependencies", e); //$NON-NLS-1$
		}
		return McpToolResult.of(new JsonObject().put("total", Integer.valueOf(names.size())) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(names.size() > projects.size())) //$NON-NLS-1$
				.put("projects", projects).toString()); //$NON-NLS-1$
	}

	private JsonObject analyse(String name, Map<String, String> exporters, IProgressMonitor monitor)
			throws CoreException {
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
		JsonObject result = new JsonObject().put("project", name); //$NON-NLS-1$
		if (!project.isAccessible()) {
			return result.put("error", "No open project of that name."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		IPluginModelBase model = PluginRegistry.findModel(project);
		BundleDescription description = model == null ? null : model.getBundleDescription();
		if (description == null || description.getSymbolicName() == null) {
			return result.put("error", "Not a plug-in project, or PDE has not resolved it."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		String self = description.getSymbolicName();
		result.put("bundle", self); //$NON-NLS-1$

		Map<String, BundleSpecification> declared = new LinkedHashMap<>();
		JsonArray declaredJson = new JsonArray();
		for (BundleSpecification specification : description.getRequiredBundles()) {
			declared.put(specification.getName(), specification);
			declaredJson.add(new JsonObject().put("bundle", specification.getName()) //$NON-NLS-1$
					.put("reexported", Boolean.valueOf(specification.isExported())) //$NON-NLS-1$
					.put("optional", Boolean.valueOf(specification.isOptional())) //$NON-NLS-1$
					.put("resolved", Boolean.valueOf(specification.isResolved()))); //$NON-NLS-1$
		}
		Set<String> importedPackages = new LinkedHashSet<>();
		for (ImportPackageSpecification specification : description.getImportPackages()) {
			importedPackages.add(specification.getName());
		}
		result.put("declaredRequireBundle", declaredJson) //$NON-NLS-1$
				.put("declaredImportPackage", array(importedPackages)); //$NON-NLS-1$

		Map<String, Set<String>> usedPackages = usedPackages(JavaCore.create(project), monitor);
		Map<String, Set<String>> usedBundles = new LinkedHashMap<>();
		for (Map.Entry<String, Set<String>> entry : usedPackages.entrySet()) {
			String exporter = exporters.get(entry.getKey());
			if (exporter == null || exporter.equals(self)) {
				continue;
			}
			usedBundles.computeIfAbsent(exporter, key -> new LinkedHashSet<>()).addAll(entry.getValue());
		}

		JsonArray used = new JsonArray();
		for (Map.Entry<String, Set<String>> entry : usedBundles.entrySet()) {
			List<String> types = new ArrayList<>(entry.getValue());
			used.add(new JsonObject().put("bundle", entry.getKey()) //$NON-NLS-1$
					.put("types", Integer.valueOf(types.size())) //$NON-NLS-1$
					.put("sample", array(types.subList(0, Math.min(SAMPLES, types.size()))))); //$NON-NLS-1$
		}
		result.put("actuallyUsed", used); //$NON-NLS-1$

		JsonArray unused = new JsonArray();
		for (Map.Entry<String, BundleSpecification> entry : declared.entrySet()) {
			if (usedBundles.containsKey(entry.getKey())) {
				continue;
			}
			unused.add(new JsonObject().put("bundle", entry.getKey()) //$NON-NLS-1$
					.put("optional", Boolean.valueOf(entry.getValue().isOptional())) //$NON-NLS-1$
					.put("resolved", Boolean.valueOf(entry.getValue().isResolved()))); //$NON-NLS-1$
		}
		result.put("unused", unused); //$NON-NLS-1$

		// what a declared bundle passes on: this is what makes a missing declaration
		// compile today and stop compiling the moment somebody tidies a reexport
		Map<String, List<String>> reexported = reexportClosure(declared.keySet());
		JsonArray viaReexport = new JsonArray();
		JsonArray undeclared = new JsonArray();
		for (Map.Entry<String, Set<String>> entry : usedBundles.entrySet()) {
			if (declared.containsKey(entry.getKey())) {
				continue;
			}
			if (coveredByImportPackage(entry.getKey(), usedPackages, importedPackages, exporters)) {
				continue;
			}
			List<String> chain = reexported.get(entry.getKey());
			JsonObject item = new JsonObject().put("bundle", entry.getKey()) //$NON-NLS-1$
					.put("types", Integer.valueOf(entry.getValue().size())); //$NON-NLS-1$
			if (chain != null) {
				viaReexport.add(item.put("reachableBecause", array(chain))); //$NON-NLS-1$
			} else {
				undeclared.add(item);
			}
		}
		result.put("viaReexport", viaReexport).put("undeclared", undeclared); //$NON-NLS-1$ //$NON-NLS-2$
		result.put("caveats", new JsonArray() //$NON-NLS-1$
				.add("'unused' means no type in this project's source resolves to that bundle. It is not a deletion instruction: a class named in plugin.xml, an OSGi service or a Class.forName leaves no type reference, and eclipse_list_declarations reports those positions.") //$NON-NLS-1$
				.add("An optional or platform-filtered requirement that does not resolve on this machine cannot be used here and is reported with resolved false rather than judged.") //$NON-NLS-1$
				.add("'viaReexport' is the edit list a reexport cleanup needs: those entries have to be declared here before the reexport that currently supplies them can be dropped.")); //$NON-NLS-1$
		return result;
	}

	/** Whether the package actually used is covered by this bundle's Import-Package. */
	private static boolean coveredByImportPackage(String bundle, Map<String, Set<String>> usedPackages,
			Set<String> importedPackages, Map<String, String> exporters) {
		for (String packageName : usedPackages.keySet()) {
			if (bundle.equals(exporters.get(packageName)) && !importedPackages.contains(packageName)) {
				return false;
			}
		}
		return true;
	}

	/** Bundles reachable through a declared bundle's reexports, with the chain. */
	private static Map<String, List<String>> reexportClosure(Set<String> declared) {
		Map<String, List<String>> reachable = new LinkedHashMap<>();
		for (String start : declared) {
			IPluginModelBase model = PluginRegistry.findModel(start);
			BundleDescription description = model == null ? null : model.getBundleDescription();
			if (description != null) {
				walk(description, new ArrayList<>(List.of(start)), reachable, new LinkedHashSet<>());
			}
		}
		return reachable;
	}

	private static void walk(BundleDescription from, List<String> chain, Map<String, List<String>> reachable,
			Set<String> seen) {
		for (BundleSpecification specification : from.getRequiredBundles()) {
			if (!specification.isExported() || !seen.add(specification.getName())) {
				continue;
			}
			List<String> next = new ArrayList<>(chain);
			next.add(specification.getName());
			reachable.putIfAbsent(specification.getName(), next);
			if (specification.getSupplier() instanceof BundleDescription supplier) {
				walk(supplier, next, reachable, seen);
			}
		}
	}

	/** Package name to the bundle exporting it, across everything PDE has resolved. */
	private static Map<String, String> exportingBundles() {
		Map<String, String> exporters = new HashMap<>();
		for (IPluginModelBase model : PluginRegistry.getActiveModels(true)) {
			BundleDescription description = model.getBundleDescription();
			if (description == null || description.getSymbolicName() == null) {
				continue;
			}
			for (ExportPackageDescription export : description.getExportPackages()) {
				exporters.putIfAbsent(export.getName(), description.getSymbolicName());
			}
		}
		return exporters;
	}

	/**
	 * The packages this project's source resolves against, with the types that put
	 * them there.
	 * <p>
	 * From resolved bindings rather than from the import statements: a fully
	 * qualified use has no import, and an import may survive the last use of it.
	 */
	private static Map<String, Set<String>> usedPackages(IJavaProject project, IProgressMonitor monitor)
			throws CoreException {
		List<ICompilationUnit> units = new ArrayList<>();
		if (project != null && project.exists()) {
			for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
				if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
					continue;
				}
				for (var child : root.getChildren()) {
					if (child instanceof IPackageFragment fragment) {
						units.addAll(List.of(fragment.getCompilationUnits()));
					}
				}
			}
		}
		Map<String, Set<String>> packages = new LinkedHashMap<>();
		if (units.isEmpty()) {
			return packages;
		}
		ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
		parser.setProject(project);
		parser.setResolveBindings(true);
		parser.setBindingsRecovery(true);
		parser.createASTs(units.toArray(ICompilationUnit[]::new), new String[0], new ASTRequestor() {
			@Override
			public void acceptAST(ICompilationUnit source, CompilationUnit ast) {
				ast.accept(new Collector(packages));
			}
		}, monitor);
		return packages;
	}

	/** Collects the package of every type a binding resolves to. */
	private static final class Collector extends ASTVisitor {

		private final Map<String, Set<String>> packages;

		Collector(Map<String, Set<String>> packages) {
			this.packages = packages;
		}

		private void add(ITypeBinding binding) {
			if (binding == null) {
				return;
			}
			ITypeBinding erasure = binding.getErasure() == null ? binding : binding.getErasure();
			if (erasure.isPrimitive() || erasure.isNullType() || erasure.isTypeVariable()) {
				return;
			}
			if (erasure.isArray()) {
				add(erasure.getComponentType());
				return;
			}
			org.eclipse.jdt.core.dom.IPackageBinding owner = erasure.getPackage();
			if (owner == null || owner.getName().isEmpty()) {
				return;
			}
			packages.computeIfAbsent(owner.getName(), key -> new LinkedHashSet<>())
					.add(erasure.getQualifiedName());
		}

		@Override
		public boolean visit(SimpleType node) {
			add(node.resolveBinding());
			return true;
		}

		@Override
		public boolean visit(ClassInstanceCreation node) {
			add(node.resolveTypeBinding());
			return true;
		}

		@Override
		public boolean visit(MethodInvocation node) {
			IMethodBinding binding = node.resolveMethodBinding();
			if (binding != null) {
				add(binding.getDeclaringClass());
			}
			return true;
		}

		@Override
		public boolean visit(SuperMethodInvocation node) {
			IMethodBinding binding = node.resolveMethodBinding();
			if (binding != null) {
				add(binding.getDeclaringClass());
			}
			return true;
		}

		@Override
		public boolean visit(QualifiedName node) {
			if (node.resolveBinding() instanceof IVariableBinding variable && variable.isField()) {
				add(variable.getDeclaringClass());
			}
			return true;
		}

		@Override
		public boolean visit(org.eclipse.jdt.core.dom.MarkerAnnotation node) {
			add(node.resolveTypeBinding());
			return true;
		}

		@Override
		public boolean visit(org.eclipse.jdt.core.dom.NormalAnnotation node) {
			add(node.resolveTypeBinding());
			return true;
		}

		@Override
		public boolean visit(org.eclipse.jdt.core.dom.SingleMemberAnnotation node) {
			add(node.resolveTypeBinding());
			return true;
		}
	}

	private static JsonArray array(Collection<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}
}
