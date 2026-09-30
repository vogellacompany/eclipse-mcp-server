package com.vogella.eclipse.mcp.jdt.internal;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Where this workspace names a class in a position some runtime reads.
 * <p>
 * The rule is positional, not textual. A class name in a changelog or a comment
 * keeps nothing alive; a class name in an extension attribute the extension
 * point's schema declares as java-typed does. So extension attributes are not
 * grepped for {@code class=}: the element is resolved to its extension point,
 * the point's {@code .exsd} says which of its attributes are java-typed, and
 * only those count.
 * <p>
 * The schemas are read directly rather than through PDE's model, which exports
 * {@code org.eclipse.pde.internal.core.schema} only to {@code org.eclipse.pde.ui}.
 * What is needed here is two attributes on one element, and that is a far
 * smaller thing to own than a dependency PDE is free to break.
 */
final class RegistryIndex {

	/**
	 * One place a class is named. {@code schemaKnown} is false when the extension
	 * point is contributed from outside this workspace, so its schema cannot be
	 * read and the position can be reported but not judged.
	 */
	record Evidence(String kind, String file, String position, String schemaAttribute, String basedOn,
			boolean schemaKnown, String namedType) {

		Evidence(String kind, String file, String position, String schemaAttribute, String basedOn,
				boolean schemaKnown) {
			this(kind, file, position, schemaAttribute, basedOn, schemaKnown, null);
		}

		/** The same position, attributed to a type that encloses the one it names. */
		Evidence naming(String nested) {
			return new Evidence(kind, file, position, schemaAttribute, basedOn, schemaKnown, nested);
		}
	}

	// the literal has to be the WHOLE argument. Without the trailing delimiter,
	// Class.forName("registry." + suffix) matches as a resolved literal named
	// "registry." and the one case that must stay undecidable is reported as live
	private static final Pattern LITERAL_REFLECTION = Pattern
			.compile("(?:Class\\.forName|loadClass)\\s*\\(\\s*\"([\\w.$]+)\"\\s*[,)]"); //$NON-NLS-1$

	private static final Pattern ANY_REFLECTION = Pattern.compile("(?:Class\\.forName|loadClass)\\s*\\("); //$NON-NLS-1$

	// bundleclass://<bundle>/<fully.qualified.Class>, the fixed grammar an e4
	// application model uses to name a class it will instantiate
	private static final Pattern BUNDLECLASS = Pattern
			.compile("bundleclass://[^/\\s\"']+/([A-Za-z_$][\\w.$]*)"); //$NON-NLS-1$

	private final Map<String, List<Evidence>> byName = new HashMap<>();

	/** Physical location plus position per name, so nested projects count once. */
	private final Map<String, Set<String>> seen = new HashMap<>();

	private final Map<String, IFile> schemaFiles = new HashMap<>();

	/** Point id to element name to attribute name to basedOn. Absent means unread. */
	private final Map<String, Map<String, Map<String, String>>> schemas = new HashMap<>();

	/** Keyed by the project whose plugin.xml contributed to the point. */
	private final Map<String, Set<String>> pointsWithoutSchema = new HashMap<>();

	private final Map<String, List<Evidence>> typeTests = new HashMap<>();

	private final List<String> dynamicReflection = new ArrayList<>();

	private int reflectionFilesScanned;

	private boolean reflectionCapped;

	private RegistryIndex() {
	}

	List<Evidence> evidenceFor(String name) {
		return byName.getOrDefault(name, List.of());
	}

	/**
	 * The points contributed to from {@code projects} whose schema could not be read.
	 * <p>
	 * Scoped, because the index covers the whole workspace while a result is about
	 * the projects that were asked for. A workspace-wide list attached to a
	 * project-scoped answer describes a limit that answer does not have, and invites
	 * distrust of a result that was in fact fully judged.
	 */
	Set<String> pointsWithoutSchema(Collection<String> projects) {
		Set<String> points = new TreeSet<>();
		for (String project : projects) {
			points.addAll(pointsWithoutSchema.getOrDefault(project, Set.of()));
		}
		return points;
	}

	/**
	 * Where a class is named by an {@code instanceof} test rather than instantiated.
	 * <p>
	 * A type test is not a registry position and does not make a class live, so this
	 * is kept apart from the evidence and never changes a verdict. It is reported
	 * because deleting such a class breaks the expression silently: it stops
	 * matching rather than failing to compile, which is worse than an error.
	 */
	List<Evidence> typeTestsFor(String name) {
		return typeTests.getOrDefault(name, List.of());
	}

	List<String> dynamicReflection() {
		return dynamicReflection;
	}

	int reflectionFilesScanned() {
		return reflectionFilesScanned;
	}

	boolean reflectionCapped() {
		return reflectionCapped;
	}

	/**
	 * Indexes every open project, not only the ones being enumerated: a class is
	 * regularly named from the plugin.xml of a different bundle, a fragment above
	 * all, and an index that stopped at the project boundary would call it dead.
	 */
	static RegistryIndex build(IProgressMonitor monitor) {
		RegistryIndex index = new RegistryIndex();
		List<IProject> projects = new ArrayList<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (project.isAccessible()) {
				projects.add(project);
			}
		}
		// extension points first: an extension in the first project read can be
		// contributed to a point declared in the last one
		for (IProject project : projects) {
			index.indexExtensionPoints(project);
		}
		for (IProject project : projects) {
			if (monitor.isCanceled()) {
				throw new OperationCanceledException();
			}
			index.indexExtensions(project);
			index.indexComponents(project);
			index.indexActivator(project);
			index.indexServices(project);
			index.indexApplicationModel(project);
		}
		return index;
	}

	private void add(String name, Evidence evidence) {
		if (name == null || name.isBlank() || name.indexOf('.') <= 0) {
			return;
		}
		IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(evidence.file());
		if (resource instanceof IFile file && isBuildOutput(file)) {
			// a Tycho product build copies every plugin.xml and .e4xmi of the platform
			// into its output, and none of it is marked derived
			return;
		}
		// one file on disk is one position however many nested projects contain it.
		// In a platform workspace the aggregator, the repository, a bundles project
		// and the bundle itself all hold the same fragment.e4xmi, and reporting "68
		// registry positions" for four real ones makes a refusal read as certainty
		String key = (resource == null || resource.getLocation() == null ? evidence.file()
				: resource.getLocation().toString()) + "|" + evidence.position(); //$NON-NLS-1$
		String trimmed = name.trim();
		if (!seen.computeIfAbsent(trimmed, key0 -> new LinkedHashSet<>()).add(key)) {
			return;
		}
		byName.computeIfAbsent(trimmed, key0 -> new ArrayList<>()).add(evidence);
		// a.b.Outer$Inner is registry evidence for a.b.Outer as well: deleting the
		// outer type's file takes the nested class with it, and a plain lookup of the
		// enclosing name once let eclipse_delete through on WizardHandler while
		// plugin.xml still named WizardHandler$New as a handler
		if (trimmed.indexOf('#') < 0) {
			// keyed by the nested name too: three commands with defaultHandler
			// WizardHandler$New, $Import and $Export share one xpath and are three
			// positions, not one
			String nestedKey = key + "|" + trimmed; //$NON-NLS-1$
			for (int dollar = trimmed.lastIndexOf('$'); dollar > 0; dollar = trimmed.lastIndexOf('$', dollar - 1)) {
				String enclosing = trimmed.substring(0, dollar);
				if (seen.computeIfAbsent(enclosing, key0 -> new LinkedHashSet<>()).add(nestedKey)) {
					byName.computeIfAbsent(enclosing, key0 -> new ArrayList<>()).add(evidence.naming(trimmed));
				}
			}
		}
	}

	/**
	 * Whether a file is build output rather than something a person wrote.
	 * <p>
	 * Maven and Tycho output is not marked derived, and a product build copies every
	 * plugin.xml and .e4xmi of the platform into it, so an unfiltered index counts
	 * fifty copies of one registration.
	 */
	private static boolean isBuildOutput(IFile file) {
		if (file.isDerived(IResource.CHECK_ANCESTORS)) {
			return true;
		}
		for (String segment : file.getFullPath().segments()) {
			if ("target".equals(segment) || "bin".equals(segment)) { //$NON-NLS-1$ //$NON-NLS-2$
				return true;
			}
		}
		return false;
	}

	// --- plugin.xml -------------------------------------------------------

	private void indexExtensionPoints(IProject project) {
		String bundle = FileText.symbolicName(project.getFile("META-INF/MANIFEST.MF"));
		if (bundle == null) {
			return;
		}
		for (IFile file : manifestFiles(project)) {
			// most plugin.xml files declare no point, so skip the DOM parse for them
			String text = FileText.read(file);
			Document document = text == null || !text.contains("extension-point") ? null : parse(file); //$NON-NLS-1$
			if (document == null) {
				continue;
			}
			for (Element declaration : elements(document.getDocumentElement(), "extension-point")) { //$NON-NLS-1$
				String id = declaration.getAttribute("id"); //$NON-NLS-1$
				String schema = declaration.getAttribute("schema"); //$NON-NLS-1$
				if (id.isBlank() || schema.isBlank()) {
					continue;
				}
				IFile exsd = project.getFile(schema);
				if (exsd.exists()) {
					schemaFiles.put(id.contains(".") ? id : bundle + "." + id, exsd); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
		}
	}

	private void indexExtensions(IProject project) {
		for (IFile file : manifestFiles(project)) {
			Document document = parse(file);
			if (document == null) {
				continue;
			}
			for (Element extension : elements(document.getDocumentElement(), "extension")) { //$NON-NLS-1$
				String point = extension.getAttribute("point"); //$NON-NLS-1$
				if (!point.isBlank()) {
					visit(extension, point, "extension[" + point + "]", file); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
		}
	}

	private void visit(Element parent, String point, String path, IFile file) {
		for (Element child : elements(parent, null)) {
			String childPath = path + "/" + child.getTagName(); //$NON-NLS-1$
			Map<String, String> javaAttributes = javaAttributes(point, child.getTagName());
			if (javaAttributes == null) {
				pointsWithoutSchema.computeIfAbsent(file.getProject().getName(), key -> new TreeSet<>()).add(point);
			}
			NamedNodeMap attributes = child.getAttributes();
			for (int i = 0; i < attributes.getLength(); i++) {
				Node attribute = attributes.item(i);
				String value = attribute.getNodeValue();
				if (value == null || value.isBlank()) {
					continue;
				}
				// the value can be class:argument, as filters and initialisers use
				String type = value.split(":", 2)[0].trim(); //$NON-NLS-1$
				String name = attribute.getNodeName();
				String position = childPath + "@" + name; //$NON-NLS-1$
				if ("instanceof".equals(child.getTagName()) && "value".equals(name) && looksLikeType(type)) { //$NON-NLS-1$ //$NON-NLS-2$
					typeTests.computeIfAbsent(type, key -> new ArrayList<>()).add(new Evidence("type test", //$NON-NLS-1$
							file.getFullPath().toString(), position, name, null, true));
					continue;
				}
				if (javaAttributes == null) {
					if (looksLikeType(type)) {
						add(type, new Evidence("plugin.xml", file.getFullPath().toString(), position, name, null, //$NON-NLS-1$
								false));
					}
				} else if (javaAttributes.containsKey(name)) {
					add(type, new Evidence("plugin.xml", file.getFullPath().toString(), position, name, //$NON-NLS-1$
							javaAttributes.get(name), true));
				}
			}
			visit(child, point, childPath, file);
		}
	}

	/**
	 * The java-typed attributes of one element, or {@code null} when the point's
	 * schema is not in this workspace and the question cannot be answered.
	 */
	private Map<String, String> javaAttributes(String point, String element) {
		if (!schemas.containsKey(point)) {
			schemas.put(point, readSchema(point));
		}
		Map<String, Map<String, String>> schema = schemas.get(point);
		if (schema == null) {
			return null;
		}
		return schema.getOrDefault(element, Map.of());
	}

	private Map<String, Map<String, String>> readSchema(String point) {
		IFile file = schemaFiles.get(point);
		if (file == null) {
			return null;
		}
		Document document = parse(file);
		if (document == null) {
			return null;
		}
		Map<String, Map<String, String>> byElement = new HashMap<>();
		for (Element element : descendants(document.getDocumentElement(), "element")) { //$NON-NLS-1$
			String name = element.getAttribute("name"); //$NON-NLS-1$
			if (name.isBlank()) {
				continue;
			}
			Map<String, String> java = new HashMap<>();
			for (Element attribute : descendants(element, "attribute")) { //$NON-NLS-1$
				String attributeName = attribute.getAttribute("name"); //$NON-NLS-1$
				for (Element meta : descendants(attribute, "meta.attribute")) { //$NON-NLS-1$
					if ("java".equals(meta.getAttribute("kind"))) { //$NON-NLS-1$ //$NON-NLS-2$
						String basedOn = meta.getAttribute("basedOn"); //$NON-NLS-1$
						java.put(attributeName, basedOn.isBlank() ? null : basedOn);
					}
				}
			}
			byElement.put(name, java);
		}
		return byElement;
	}

	// --- declarative services, activator, service loader ------------------

	private void indexComponents(IProject project) {
		IContainer folder = project.getFolder("OSGI-INF"); //$NON-NLS-1$
		if (!folder.exists()) {
			return;
		}
		for (IResource member : members(folder)) {
			if (!(member instanceof IFile file) || !"xml".equals(file.getFileExtension())) { //$NON-NLS-1$
				continue;
			}
			Document document = parse(file);
			if (document == null || !"component".equals(document.getDocumentElement().getTagName())) { //$NON-NLS-1$
				continue;
			}
			String path = file.getFullPath().toString();
			String implementation = null;
			for (Element element : descendants(document.getDocumentElement(), "implementation")) { //$NON-NLS-1$
				implementation = element.getAttribute("class"); //$NON-NLS-1$
				add(implementation, new Evidence("declarative service", path, "implementation@class", "class", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						true));
			}
			for (Element element : descendants(document.getDocumentElement(), "provide")) { //$NON-NLS-1$
				add(element.getAttribute("interface"), //$NON-NLS-1$
						new Evidence("declarative service", path, "provide@interface", "interface", null, true)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			if (implementation != null) {
				addComponentMethods(document.getDocumentElement(), implementation, path);
			}
		}
	}

	/** The lifecycle and binding methods a component names, which nothing calls in source. */
	private void addComponentMethods(Element component, String implementation, String path) {
		for (String name : new String[] { "activate", "deactivate", "modified" }) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			String method = component.getAttribute(name);
			if (!method.isBlank()) {
				add(implementation + "#" + method, //$NON-NLS-1$
						new Evidence("declarative service", path, "component@" + name, name, null, true)); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		for (Element reference : descendants(component, "reference")) { //$NON-NLS-1$
			for (String name : new String[] { "bind", "unbind", "updated" }) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				String method = reference.getAttribute(name);
				if (!method.isBlank()) {
					add(implementation + "#" + method, //$NON-NLS-1$
							new Evidence("declarative service", path, "reference@" + name, name, null, true)); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
		}
	}

	private void indexActivator(IProject project) {
		IFile manifest = project.getFile("META-INF/MANIFEST.MF"); //$NON-NLS-1$
		String activator = FileText.manifestHeader(manifest, "Bundle-Activator"); //$NON-NLS-1$
		if (activator != null) {
			add(activator, new Evidence("manifest", manifest.getFullPath().toString(), "Bundle-Activator", null, //$NON-NLS-1$ //$NON-NLS-2$
					"org.osgi.framework.BundleActivator", true)); //$NON-NLS-1$
		}
	}

	private void indexServices(IProject project) {
		IContainer folder = project.getFolder("META-INF/services"); //$NON-NLS-1$
		if (!folder.exists()) {
			return;
		}
		for (IResource member : members(folder)) {
			if (!(member instanceof IFile file)) {
				continue;
			}
			String path = file.getFullPath().toString();
			String service = file.getName();
			add(service, new Evidence("service loader", path, "file name", null, null, true)); //$NON-NLS-1$ //$NON-NLS-2$
			for (String line : lines(file)) {
				String implementation = line.split("#", 2)[0].trim(); //$NON-NLS-1$
				if (!implementation.isEmpty()) {
					add(implementation, new Evidence("service loader", path, "provider line", null, service, true)); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
		}
	}

	/**
	 * The classes an e4 application model names.
	 * <p>
	 * A .e4xmi is a registry position like any other: the workbench instantiates
	 * what its contributionURI points at, on every start. Nothing in Java refers to
	 * those classes, so without this they look dead, and a sweep acting on that
	 * deletes an addon the IDE needs and still compiles cleanly. That happened.
	 * <p>
	 * The files are found by walking the project, because unlike plugin.xml they
	 * live wherever the bundle put them, and a proxy visit is cheap enough for that.
	 */
	private void indexApplicationModel(IProject project) {
		try {
			project.accept(proxy -> {
				if (proxy.getType() == IResource.FILE && proxy.getName().endsWith(".e4xmi")) { //$NON-NLS-1$
					IResource resource = proxy.requestResource();
					if (resource instanceof IFile file) {
						indexApplicationModelFile(file);
					}
					return false;
				}
				return true;
			}, IResource.NONE);
		} catch (CoreException e) {
			// a project that cannot be walked contributes nothing, which is the same
			// as one with no application model
		}
	}

	private void indexApplicationModelFile(IFile file) {
		String content = FileText.read(file);
		if (content == null || !content.contains("bundleclass://")) { //$NON-NLS-1$
			return;
		}
		String path = file.getFullPath().toString();
		Matcher matcher = BUNDLECLASS.matcher(content);
		while (matcher.find()) {
			add(matcher.group(1), new Evidence("e4xmi", path, "bundleclass URI", null, null, true)); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	// --- reflection over string literals ----------------------------------

	/**
	 * Scans source for reflective loads. A literal name is a position like any
	 * other; a name built at runtime is not resolvable by any static analysis, and
	 * is recorded so that a dead verdict in the same project can be reported as
	 * provisional rather than as a fact.
	 */
	void indexReflection(List<IContainer> sourceFolders, int maxFiles, IProgressMonitor monitor) {
		for (IContainer folder : sourceFolders) {
			try {
				folder.accept(resource -> {
					if (monitor.isCanceled() || reflectionCapped) {
						return false;
					}
					if (!(resource instanceof IFile file) || !"java".equals(file.getFileExtension())) { //$NON-NLS-1$
						return true;
					}
					if (reflectionFilesScanned >= maxFiles) {
						reflectionCapped = true;
						return false;
					}
					reflectionFilesScanned++;
					scanReflection(file);
					return true;
				});
			} catch (CoreException e) {
				// an unreadable source folder is not a reason to fail the sweep
			}
		}
	}

	private void scanReflection(IFile file) {
		String content = FileText.read(file);
		if (content == null || !content.contains("forName") && !content.contains("loadClass")) { //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}
		String path = file.getFullPath().toString();
		Set<Integer> literals = new HashSet<>();
		Matcher literal = LITERAL_REFLECTION.matcher(content);
		while (literal.find()) {
			literals.add(Integer.valueOf(literal.start()));
			add(literal.group(1), new Evidence("reflection", path, "string literal", null, null, true)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		Matcher any = ANY_REFLECTION.matcher(content);
		while (any.find()) {
			if (!literals.contains(Integer.valueOf(any.start()))) {
				dynamicReflection.add(path + ":" + FileText.lineOf(content, any.start())); //$NON-NLS-1$
			}
		}
	}

	// --- small helpers ----------------------------------------------------

	private static boolean looksLikeType(String value) {
		int lastDot = value.lastIndexOf('.');
		return lastDot > 0 && lastDot < value.length() - 1 && Character.isUpperCase(value.charAt(lastDot + 1))
				&& value.chars().noneMatch(Character::isWhitespace);
	}

	private static List<IFile> manifestFiles(IProject project) {
		List<IFile> files = new ArrayList<>();
		for (String name : new String[] { "plugin.xml", "fragment.xml" }) { //$NON-NLS-1$ //$NON-NLS-2$
			IFile file = project.getFile(name);
			if (file.exists()) {
				files.add(file);
			}
		}
		return files;
	}

	private static List<String> lines(IFile file) {
		String content = FileText.read(file);
		return content == null ? List.of() : List.of(content.split("\r?\n")); //$NON-NLS-1$
	}

	private static List<IResource> members(IContainer container) {
		try {
			return List.of(container.members());
		} catch (CoreException e) {
			return List.of();
		}
	}

	private static Document parse(IFile file) {
		try (InputStream in = file.getContents(true)) {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setNamespaceAware(false);
			factory.setExpandEntityReferences(false);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); //$NON-NLS-1$
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); //$NON-NLS-1$
			return factory.newDocumentBuilder().parse(in);
		} catch (Exception e) {
			// a malformed plugin.xml is the project's problem, not this tool's
			return null;
		}
	}

	/** Direct child elements, optionally of one name. */
	private static List<Element> elements(Element parent, String name) {
		List<Element> found = new ArrayList<>();
		NodeList children = parent.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			if (children.item(i) instanceof Element element
					&& (name == null || name.equals(element.getTagName()))) {
				found.add(element);
			}
		}
		return found;
	}

	private static List<Element> descendants(Element parent, String name) {
		List<Element> found = new ArrayList<>();
		NodeList nodes = parent.getElementsByTagName(name);
		for (int i = 0; i < nodes.getLength(); i++) {
			found.add((Element) nodes.item(i));
		}
		return found;
	}
}
