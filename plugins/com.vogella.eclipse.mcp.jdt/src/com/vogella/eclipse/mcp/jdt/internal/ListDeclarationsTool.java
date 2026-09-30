package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the declarations of a project and says which of them a registry keeps alive.
 */
public final class ListDeclarationsTool implements IMcpTool {

	private static final String DEAD = "dead"; //$NON-NLS-1$

	private static final String LIVE = "live-via-registry"; //$NON-NLS-1$

	private static final String UNDECIDABLE = "undecidable"; //$NON-NLS-1$

	private static final Set<String> KINDS = Set.of("types", "methods", "fields"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	private static final int MAX_REFLECTION_FILES = 4000;

	private static final String EXTENSION_FACTORY = "org.eclipse.core.runtime.IExecutableExtensionFactory"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_list_declarations"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Enumerates the types, methods or fields a project declares in its own source, or reports on a list of types you already have through typeNames, and cross-checks each one against the places an Eclipse runtime instantiates a class by name. This is the candidate generation step of a dead code sweep; eclipse_find_references is the confirm step, and neither replaces the other. Binary types are never listed, so a class that exists a dozen times over inside built jars appears once, as source. registryStatus is THREE valued and the distinction matters: 'dead' means only that no registry position this tool understands names it, never that deleting it is safe; 'live-via-registry' means not provably dead, not that anything still uses it, since an extension can be contributed to a point nobody reads; 'undecidable' means something names it in a position that cannot be judged. Extension attributes are resolved through the extension point's .exsd schema, so only attributes the schema declares java-typed count, and a class name in a comment or a changelog counts for nothing. It also answers which declarations are deprecated, semantically rather than by text search: the @Deprecated annotation and the @deprecated Javadoc tag are reported separately, because they routinely disagree. An annotation added in a later bulk sweep dates the deprecation to the sweep and not to the deprecation, and a tag without an annotation raises no warning at any call site. For deprecated USAGES ask eclipse_get_problems with a messageFilter instead, which is the compiler's own answer and resolves inheritance and binary types."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "project":    {"type":"string","description":"Project to enumerate. Omit both this and 'projects' for every open Java project, which walks the whole workspace and is rarely what you want."},
				    "projects":   {"type":"array","items":{"type":"string"},"description":"Several projects. Use instead of 'project'."},
				    "typeNames":  {"type":"array","items":{"type":"string"},"description":"Report only these fully qualified types, resolved directly instead of by walking a project. Use this when you already have candidates and want the registry and API verdict for each."},
				    "kinds":      {"type":"array","items":{"type":"string","enum":["types","methods","fields"]},"default":["types"]},
				    "visibility": {"type":"array","items":{"type":"string","enum":["public","protected","package","private"]},"description":"Report only these. Omit for all."},
				    "status":     {"type":"string","enum":["dead","live-via-registry","undecidable","all"],"default":"all","description":"Report only declarations with this verdict."},
				    "deprecated": {"type":"string","enum":["all","yes","no","forRemoval","annotationOnly","javadocOnly"],"default":"all","description":"Filter by deprecation. 'yes' is either mark, 'forRemoval' only @Deprecated(forRemoval=true). 'annotationOnly' and 'javadocOnly' find the two marks disagreeing, which is what dates a deprecation wrongly."},
				    "includeTest":{"type":"boolean","default":false,"description":"Include source folders the build path marks as test."},
				    "includeReflection":{"type":"boolean","default":true,"description":"Scan source for Class.forName and loadClass. A literal name counts as a registry position; a name built at runtime makes every dead verdict in that project provisional, which is reported either way."},
				    "maxResults": {"type":"integer","default":500,"minimum":1,"maximum":5000}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		List<String> names = names(arguments, args);
		List<String> kinds = strings(arguments, "kinds"); //$NON-NLS-1$
		if (kinds.isEmpty()) {
			kinds = List.of("types"); //$NON-NLS-1$
		}
		for (String kind : kinds) {
			if (!KINDS.contains(kind)) {
				return McpToolResult.error("Unknown kind '%s', expected types, methods or fields.".formatted(kind)); //$NON-NLS-1$
			}
		}
		Set<String> visibility = new LinkedHashSet<>(strings(arguments, "visibility")); //$NON-NLS-1$
		String status = args.getString("status", "all"); //$NON-NLS-1$ //$NON-NLS-2$
		String deprecated = args.getString("deprecated", "all"); //$NON-NLS-1$ //$NON-NLS-2$
		boolean includeTest = args.getBoolean("includeTest", false); //$NON-NLS-1$
		boolean includeReflection = args.getBoolean("includeReflection", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 500, 1, 5000); //$NON-NLS-1$

		List<IJavaProject> projects = new ArrayList<>();
		if (names.isEmpty()) {
			// every open Java project; naming projects is worth it because this walks every unit it is given
			for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
				IJavaProject javaProject = JavaCore.create(project);
				if (project.isAccessible() && javaProject != null && javaProject.exists()) {
					projects.add(javaProject);
				}
			}
		}
		for (String name : names) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
			if (!project.isAccessible()) {
				return McpToolResult.error("No open project named '%s' in this workspace.".formatted(name)); //$NON-NLS-1$
			}
			IJavaProject javaProject = JavaCore.create(project);
			if (javaProject == null || !javaProject.exists()) {
				return McpToolResult.error("'%s' is not a Java project, so it declares nothing to list." //$NON-NLS-1$
						.formatted(name));
			}
			projects.add(javaProject);
		}

		try {
			List<String> typeNames = strings(arguments, "typeNames"); //$NON-NLS-1$
			JavaModelSupport.refresh(null, monitor);
			RegistryIndex index = RegistryIndex.build(monitor);
			if (!typeNames.isEmpty()) {
				return McpToolResult.of(reportNamed(typeNames, projects, index, kinds, visibility, status, deprecated, maxResults,
						includeReflection, monitor).toString());
			}
			List<IPackageFragmentRoot> roots = sourceRoots(projects, includeTest);
			if (includeReflection) {
				index.indexReflection(containers(roots), MAX_REFLECTION_FILES, monitor);
			}
			return McpToolResult.of(report(roots, index, kinds, visibility, status, deprecated, maxResults, includeReflection,
					monitor).toString());
		} catch (JavaModelException e) {
			throw new McpToolException("Could not read the Java model", e); //$NON-NLS-1$
		} catch (CoreException e) {
			throw new McpToolException("Could not read the workspace", e); //$NON-NLS-1$
		}
	}

	/**
	 * Reports named types directly, without walking anything.
	 * <p>
	 * The registry cross-check is the expensive part and is shared, so asking about
	 * twenty candidates costs one index build rather than twenty project walks.
	 */
	private JsonObject reportNamed(List<String> typeNames, List<IJavaProject> projects, RegistryIndex index,
			List<String> kinds, Set<String> visibility, String status, String deprecated, int maxResults, boolean includeReflection,
			IProgressMonitor monitor) throws CoreException, McpToolException {
		LineIndex lines = new LineIndex();
		PackageExports exports = new PackageExports();
		Set<String> workspaceBundles = workspaceBundles();
		JsonArray declarations = new JsonArray();
		JsonArray unresolved = new JsonArray();
		int total = 0;
		for (String name : typeNames) {
			if (monitor.isCanceled()) {
				return new JsonObject().put("cancelled", Boolean.TRUE); //$NON-NLS-1$
			}
			IType type;
			try {
				type = JavaModelSupport.findType(name, projects, monitor);
			} catch (ToolInputException e) {
				unresolved.add(name);
				continue;
			}
			if (type.isBinary()) {
				unresolved.add(name);
				continue;
			}
			total += collect(type, index, kinds, visibility, status, deprecated, maxResults, declarations, lines, exports,
					workspaceBundles, monitor);
		}
		JsonObject result = new JsonObject().put("kinds", array(kinds)) //$NON-NLS-1$
				.put("status", status) //$NON-NLS-1$
				.put("total", Integer.valueOf(total)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(total > declarations.size())) //$NON-NLS-1$
				.put("declarations", declarations); //$NON-NLS-1$
		if (unresolved.size() > 0) {
			result.put("unresolved", unresolved) //$NON-NLS-1$
					.put("unresolvedNote", //$NON-NLS-1$
							"These names did not resolve to a source type in this workspace. A binary type is not reported, because this tool is about source a project declares."); //$NON-NLS-1$
		}
		addCaveats(result, index, includeReflection, List.of());
		return result;
	}

	private JsonObject report(List<IPackageFragmentRoot> roots, RegistryIndex index, List<String> kinds,
			Set<String> visibility, String status, String deprecated, int maxResults, boolean includeReflection, IProgressMonitor monitor)
			throws CoreException {
		LineIndex lines = new LineIndex();
		PackageExports exports = new PackageExports();
		Set<String> workspaceBundles = workspaceBundles();
		JsonArray declarations = new JsonArray();
		int total = 0;
		for (IPackageFragmentRoot root : roots) {
			for (IJavaElement child : root.getChildren()) {
				if (monitor.isCanceled()) {
					return new JsonObject().put("cancelled", Boolean.TRUE); //$NON-NLS-1$
				}
				if (!(child instanceof IPackageFragment fragment)) {
					continue;
				}
				for (ICompilationUnit unit : fragment.getCompilationUnits()) {
					for (IType type : unit.getTypes()) {
						total += collect(type, index, kinds, visibility, status, deprecated, maxResults, declarations, lines,
								exports, workspaceBundles, monitor);
					}
				}
			}
		}
		JsonObject result = new JsonObject().put("kinds", array(kinds)) //$NON-NLS-1$
				.put("status", status) //$NON-NLS-1$
				.put("total", Integer.valueOf(total)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(total > declarations.size())) //$NON-NLS-1$
				.put("projects", array(projectNames(roots))) //$NON-NLS-1$
				.put("declarations", declarations); //$NON-NLS-1$
		addCaveats(result, index, includeReflection, projectNames(roots));
		return result;
	}

	/** Returns how many declarations matched, which is not how many were reported. */
	private int collect(IType type, RegistryIndex index, List<String> kinds, Set<String> visibility, String status, String deprecated,
			int maxResults, JsonArray into, LineIndex lines, PackageExports exports, Set<String> workspaceBundles,
			IProgressMonitor monitor) throws CoreException {
		int matched = 0;
		String verdict = verdict(type, index, null);
		Api api = api(type, exports, workspaceBundles);
		if (kinds.contains("types")) { //$NON-NLS-1$
			matched += consider(type, type.getFullyQualifiedName(), "type", verdict, index, visibility, status, //$NON-NLS-1$
					deprecated, maxResults, into, lines, api, monitor);
		}
		if (kinds.contains("methods")) { //$NON-NLS-1$
			for (IMethod method : type.getMethods()) {
				matched += consider(method, name(type, method), "method", verdict, index, visibility, status, //$NON-NLS-1$
						deprecated, maxResults, into, lines, api, monitor);
			}
		}
		if (kinds.contains("fields")) { //$NON-NLS-1$
			for (IField field : type.getFields()) {
				matched += consider(field, name(type, field), "field", verdict, index, visibility, status, //$NON-NLS-1$
						deprecated, maxResults, into, lines, api, monitor);
			}
		}
		for (IType nested : type.getTypes()) {
			matched += collect(nested, index, kinds, visibility, status, deprecated, maxResults, into, lines, exports,
					workspaceBundles, monitor);
		}
		return matched;
	}

	private int consider(IMember member, String name, String kind, String enclosingVerdict, RegistryIndex index,
			Set<String> visibility, String status, String deprecated, int maxResults, JsonArray into, LineIndex lines, Api api,
			IProgressMonitor monitor) throws CoreException {
		String visible = visibility(member.getFlags());
		if (!visibility.isEmpty() && !visibility.contains(visible)) {
			return 0;
		}
		// before the registry verdict, which is the expensive one
		Deprecation deprecation = Deprecation.of(member);
		if (!deprecation.matches(deprecated)) {
			return 0;
		}
		String verdict = "type".equals(kind) ? enclosingVerdict : verdict(member, index, enclosingVerdict); //$NON-NLS-1$
		if (!"all".equals(status) && !status.equals(verdict)) { //$NON-NLS-1$
			return 0;
		}
		if (into.size() >= maxResults) {
			return 1;
		}
		IResource resource = member.getResource();
		JsonObject entry = new JsonObject().put("name", name) //$NON-NLS-1$
				.put("kind", kind) //$NON-NLS-1$
				.put("file", resource == null ? null : resource.getFullPath().toString()) //$NON-NLS-1$
				.put("line", Integer.valueOf(line(member, resource, lines))) //$NON-NLS-1$
				.put("visibility", visible) //$NON-NLS-1$
				.put("registryStatus", verdict) //$NON-NLS-1$
				.put("apiTier", api.tier()) //$NON-NLS-1$
				.put("searchIsAuthoritative", api.authoritative()); //$NON-NLS-1$
		if (api.friends() != null) {
			JsonArray friends = new JsonArray();
			api.friends().forEach(friends::add);
			entry.put("friends", friends); //$NON-NLS-1$
		}
		Object restrictions = "type".equals(kind) ? apiRestrictions((IType) member) : null; //$NON-NLS-1$
		if (restrictions != null) {
			entry.put("apiRestrictions", restrictions); //$NON-NLS-1$
		}
		JsonArray evidence = evidence(member, index, name, monitor);
		if (evidence.size() > 0) {
			entry.put("registryEvidence", evidence); //$NON-NLS-1$
		}
		JsonArray typeTests = new JsonArray();
		for (RegistryIndex.Evidence test : index.typeTestsFor(name)) {
			typeTests.add(new JsonObject().put("file", test.file()) //$NON-NLS-1$
					.put("xpathOrHeader", test.position())); //$NON-NLS-1$
		}
		if (typeTests.size() > 0) {
			entry.put("typeTests", typeTests) //$NON-NLS-1$
					.put("typeTestNote", //$NON-NLS-1$
							"Named by an instanceof test in an enablement expression, which is not instantiation and does not make it live. Deleting it breaks the expression silently: it stops matching rather than failing to compile."); //$NON-NLS-1$
		}
		deprecation.describe(entry);
		into.add(entry);
		return 1;
	}

	private record Api(String tier, boolean authoritative, List<String> friends) {
	}

	/**
	 * What the declaring package's export says a workspace search can prove.
	 * <p>
	 * A search is authoritative only when there is nowhere else a reference could
	 * be: the package is not exported at all, or every bundle its x-friends list
	 * names is itself a project here. Everything exported plainly is the opposite
	 * case, and no number of zero results settles it.
	 */
	private static Api api(IType type, PackageExports exports, Set<String> workspaceBundles) {
		PackageExports.Export export = exports.of(type.getJavaProject().getProject(),
				type.getPackageFragment().getElementName());
		// x-internal counts as authoritative too. It declares that NO bundle should
		// use the package, which is a stronger statement than an x-friends list that
		// happens to be enumerable, so crediting the enumerable one and not the
		// absolute one had it backwards. Both rest on a compile time access rule
		// rather than on anything OSGi enforces at runtime, which is why the caveat
		// says so rather than the field pretending to more than it has
		boolean authoritative = PackageExports.NOT_EXPORTED.equals(export.tier())
				|| PackageExports.INTERNAL.equals(export.tier());
		List<String> friends = null;
		if (PackageExports.FRIENDS.equals(export.tier())) {
			friends = export.friends();
			authoritative = !export.friends().isEmpty() && workspaceBundles.containsAll(export.friends());
		}
		return new Api(export.tier(), authoritative, friends);
	}

	/** The bundle symbolic names this workspace has open, for the x-friends check. */
	private static Set<String> workspaceBundles() {
		Set<String> names = new LinkedHashSet<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (project.isAccessible()) {
				names.add(project.getName());
			}
		}
		return names;
	}

	/**
	 * The PDE API Tools javadoc tags on a type. A type in a public package tagged
	 * {@code @noreference} is documented as not for consumption, so no references
	 * means more there than it does for untagged public API.
	 */
	private static JsonArray apiRestrictions(IType type) {
		try {
			org.eclipse.jdt.core.ISourceRange range = type.getJavadocRange();
			if (range == null || type.getCompilationUnit() == null) {
				return null;
			}
			String source = type.getCompilationUnit().getSource();
			if (source == null || range.getOffset() + range.getLength() > source.length()) {
				return null;
			}
			String javadoc = source.substring(range.getOffset(), range.getOffset() + range.getLength());
			JsonArray tags = new JsonArray();
			for (String tag : new String[] { "@noreference", "@noextend", "@noimplement", "@noinstantiate", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					"@nooverride" }) { //$NON-NLS-1$
				if (javadoc.contains(tag)) {
					tags.add(tag.substring(1));
				}
			}
			return tags.size() > 0 ? tags : null;
		} catch (JavaModelException e) {
			return null;
		}
	}

	/**
	 * A member of a type some runtime instantiates cannot be called dead here: the
	 * framework holds the instance and calls whatever its contract says, and no
	 * declaration list can see that.
	 */
	private String verdict(IMember member, RegistryIndex index, String enclosingVerdict) {
		String name = member instanceof IType type ? type.getFullyQualifiedName()
				: name(member.getDeclaringType(), member);
		List<RegistryIndex.Evidence> evidence = index.evidenceFor(name);
		boolean unjudgeable = false;
		for (RegistryIndex.Evidence one : evidence) {
			// an unsatisfied basedOn does not demote. It is a single-valued hint that
			// several real schemas cannot express: org.eclipse.ui.decorators says
			// ILabelDecorator while every lightweight="true" decorator implements
			// ILightweightLabelDecorator instead, and the schema is not lying, only
			// incapable of saying what it means. Unverifiable is not refuted, and
			// unsatisfied is not refuted either; basedOnSatisfied stays as a flag for
			// a person to read
			if (one.schemaKnown()) {
				return LIVE;
			}
			unjudgeable = true;
		}
		if (unjudgeable) {
			return UNDECIDABLE;
		}
		if (enclosingVerdict != null && !DEAD.equals(enclosingVerdict)) {
			return UNDECIDABLE;
		}
		return DEAD;
	}

	private JsonArray evidence(IMember member, RegistryIndex index, String name, IProgressMonitor monitor)
			throws CoreException {
		JsonArray array = new JsonArray();
		for (RegistryIndex.Evidence one : index.evidenceFor(name)) {
			JsonObject entry = new JsonObject().put("kind", one.kind()) //$NON-NLS-1$
					.put("file", one.file()) //$NON-NLS-1$
					.put("xpathOrHeader", one.position()) //$NON-NLS-1$
					.put("schemaAttribute", one.schemaAttribute()) //$NON-NLS-1$
					.put("basedOn", one.basedOn()) //$NON-NLS-1$
					.put("basedOnSatisfied", satisfies(named(member, one), one.basedOn(), monitor)) //$NON-NLS-1$
					.put("schemaKnown", Boolean.valueOf(one.schemaKnown())); //$NON-NLS-1$
			if (one.namedType() != null) {
				entry.put("namedType", one.namedType()); //$NON-NLS-1$
			}
			array.add(entry);
		}
		return array;
	}

	/** The nested type a position names, so basedOn is checked against it rather than the enclosing type. */
	private static IMember named(IMember member, RegistryIndex.Evidence one) {
		if (one.namedType() == null || !(member instanceof IType type)) {
			return member;
		}
		IType nested = type;
		for (String segment : one.namedType().substring(type.getFullyQualifiedName().length() + 1).split("\\$")) { //$NON-NLS-1$
			nested = nested.getType(segment);
		}
		return nested.exists() ? nested : member;
	}

	/**
	 * Whether the class really is what the schema said it would be. A registry entry
	 * naming a class that does not extend the declared supertype is stale, and a
	 * stale entry keeps nothing alive, so this is the difference between trusting
	 * the evidence and verifying it.
	 */
	private Boolean satisfies(IMember member, String basedOn, IProgressMonitor monitor) throws CoreException {
		if (basedOn == null || !(member instanceof IType type)) {
			return null;
		}
		Set<String> required = new LinkedHashSet<>();
		for (String part : basedOn.split(":")) { //$NON-NLS-1$
			if (!part.isBlank()) {
				required.add(part.trim());
			}
		}
		if (required.isEmpty()) {
			return null;
		}
		Set<String> supertypes = new LinkedHashSet<>();
		// the type itself: a class satisfies basedOn X when it IS X, which
		// getAllSupertypes does not report
		supertypes.add(type.getFullyQualifiedName());
		ITypeHierarchy hierarchy = type.newSupertypeHierarchy(monitor);
		for (IType supertype : hierarchy.getAllSupertypes(type)) {
			supertypes.add(supertype.getFullyQualifiedName());
		}
		if (supertypes.contains(EXTENSION_FACTORY)) {
			// class="a.b.Factory:product" names a factory, and basedOn describes what
			// the factory produces rather than the factory itself, so there is nothing
			// here to check against this class
			return null;
		}
		for (String name : required) {
			if (supertypes.contains(name)) {
				continue;
			}
			// not in the hierarchy is only a refutation when the supertype is resolvable
			// here at all. A schema naming a type this project does not compile against
			// cannot be checked, and reporting that as a stale entry would call live code
			// dead, which is the one answer this tool must not give
			if (type.getJavaProject().findType(name) == null) {
				return null;
			}
			return Boolean.FALSE;
		}
		return Boolean.TRUE;
	}

	private static void addCaveats(JsonObject result, RegistryIndex index, boolean includeReflection,
			List<String> projects) {
		JsonArray caveats = new JsonArray();
		if (!includeReflection) {
			caveats.add("Reflection was not scanned, so a class loaded only through Class.forName is reported dead."); //$NON-NLS-1$
		} else if (!index.dynamicReflection().isEmpty()) {
			JsonArray sites = new JsonArray();
			for (String site : index.dynamicReflection().subList(0,
					Math.min(20, index.dynamicReflection().size()))) {
				sites.add(site);
			}
			result.put("dynamicReflectionSites", sites); //$NON-NLS-1$
			caveats.add(
					"%d reflective loads build the class name at runtime, which no static analysis resolves. Every 'dead' verdict here is provisional." //$NON-NLS-1$
							.formatted(Integer.valueOf(index.dynamicReflection().size())));
		}
		if (index.reflectionCapped()) {
			caveats.add("The reflection scan stopped at %d files, so later files were not read." //$NON-NLS-1$
					.formatted(Integer.valueOf(index.reflectionFilesScanned())));
		}
		Set<String> unknownPoints = index.pointsWithoutSchema(projects);
		if (!unknownPoints.isEmpty()) {
			JsonArray points = new JsonArray();
			for (String point : List.copyOf(unknownPoints).subList(0, Math.min(20, unknownPoints.size()))) {
				points.add(point);
			}
			result.put("extensionPointsWithoutSchema", points); //$NON-NLS-1$
			caveats.add(
					"%d extension points these projects contribute to are declared outside this workspace, so their schemas could not be read. Class-looking attribute values under those points are reported as undecidable rather than judged." //$NON-NLS-1$
							.formatted(Integer.valueOf(unknownPoints.size())));
		}
		caveats.add(
				"searchIsAuthoritative rests on a declared access rule, not on runtime enforcement: x-internal and x-friends are checked by JDT and PDE when consumers compile, and OSGi exports the package either way, so a consumer that ignores the rule is still possible."); //$NON-NLS-1$
		caveats.add(
				"apiTier qualifies every verdict: 'dead' in a public-api package proves nothing, because consumers may exist outside this workspace entirely, while 'dead' where searchIsAuthoritative is true has nowhere else to hide."); //$NON-NLS-1$
		caveats.add(
				"'dead' means no registry position this tool understands names it. Confirm with eclipse_find_references before deleting anything, and remember that neither answers whether the code is reachable."); //$NON-NLS-1$
		result.put("caveats", caveats); //$NON-NLS-1$
	}

	private static List<String> projectNames(List<IPackageFragmentRoot> roots) {
		Set<String> names = new LinkedHashSet<>();
		for (IPackageFragmentRoot root : roots) {
			names.add(root.getJavaProject().getElementName());
		}
		return List.copyOf(names);
	}

	private static List<IPackageFragmentRoot> sourceRoots(List<IJavaProject> projects, boolean includeTest)
			throws JavaModelException {
		List<IPackageFragmentRoot> roots = new ArrayList<>();
		for (IJavaProject project : projects) {
			for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
				// source only, which is what keeps the copies of a type inside built jars
				// out of the list entirely rather than deduplicated afterwards
				if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
					continue;
				}
				IClasspathEntry entry = root.getRawClasspathEntry();
				if (!includeTest && entry != null && entry.isTest()) {
					continue;
				}
				roots.add(root);
			}
		}
		return roots;
	}

	private static List<IContainer> containers(List<IPackageFragmentRoot> roots) {
		List<IContainer> containers = new ArrayList<>();
		for (IPackageFragmentRoot root : roots) {
			if (root.getResource() instanceof IContainer container) {
				containers.add(container);
			}
		}
		return containers;
	}

	private static String name(IType type, IMember member) {
		return type == null ? member.getElementName() : type.getFullyQualifiedName() + "#" + member.getElementName(); //$NON-NLS-1$
	}

	private static int line(IMember member, IResource resource, LineIndex lines) {
		try {
			return member.getNameRange() == null ? -1 : lines.lineOf(resource, member.getNameRange().getOffset());
		} catch (JavaModelException e) {
			return -1;
		}
	}

	private static String visibility(int flags) {
		if (Flags.isPublic(flags)) {
			return "public"; //$NON-NLS-1$
		}
		if (Flags.isProtected(flags)) {
			return "protected"; //$NON-NLS-1$
		}
		if (Flags.isPrivate(flags)) {
			return "private"; //$NON-NLS-1$
		}
		return "package"; //$NON-NLS-1$
	}

	private static List<String> names(Map<String, Object> arguments, ToolArguments args) {
		List<String> names = new ArrayList<>(strings(arguments, "projects")); //$NON-NLS-1$
		String single = args.getString("project"); //$NON-NLS-1$
		if (single != null && !names.contains(single)) {
			names.add(single);
		}
		return names;
	}

	private static List<String> strings(Map<String, Object> arguments, String name) {
		List<String> values = new ArrayList<>();
		if (arguments.get(name) instanceof List<?> list) {
			for (Object value : list) {
				if (value != null && !String.valueOf(value).isBlank()) {
					values.add(String.valueOf(value).trim());
				}
			}
		}
		return values;
	}

	private static JsonArray array(List<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}
}
