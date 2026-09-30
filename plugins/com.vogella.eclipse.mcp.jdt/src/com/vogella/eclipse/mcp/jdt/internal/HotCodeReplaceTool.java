package com.vogella.eclipse.mcp.jdt.internal;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Replaces the bytecode of classes in the running IDE with freshly compiled
 * class files, the way the debugger's hot code replace does, without a restart.
 */
public final class HotCodeReplaceTool implements IMcpTool {

	private static final String CLASS_SUFFIX = ".class"; //$NON-NLS-1$

	/** When this tool last replaced classes of a bundle, so a second call picks up only what changed since. */
	private static final Map<String, Long> LAST_REPLACE = new ConcurrentHashMap<>();

	/** The attach helper is a fresh JVM; it takes seconds, never the whole call budget. */
	private static final int MAX_ATTACH_SECONDS = 20;

	private record Candidate(String name, Path file) {
	}

	@Override
	public String getName() {
		return "eclipse_hot_code_replace"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Replaces the bytecode of classes in the RUNNING IDE with the class files a workspace project or a directory holds, without a restart, which is how a trace statement or a changed method body gets into a plug-in this IDE is running, this MCP server included. CHANGES THE RUNNING JVM: the new method bodies take effect for every call from then on, and a restart puts the installed bundle's code back. The first call loads a Java agent through a one-off helper process, which takes a few seconds and needs a JDK with jdk.attach; later calls reuse it. The limits are the JVM's, the same as the debugger's hot code replace: only method bodies, constant pools and attributes can change. A class whose fields, methods, signatures, supertypes or modifiers changed is refused by name with the JVM's reason, threads already inside a replaced method keep running its old body until it returns, static initializers do not run again, and a class the installed bundle does not have cannot be added. COMPILE WITH THE SAME COMPILER THE RUNNING CLASS CAME FROM: lambdas and other synthetic members are named differently by javac and by the Eclipse compiler, so a class with a lambda compiled by javac is refused as having added a method when the running one was built by Tycho or the workspace builder, which both use the Eclipse compiler; the project's build output is the safe source, and for a directory the batch compiler jar org.eclipse.jdt.core.compiler.batch that ships in every IDE is. Name the classes, or omit them to replace every class file newer than the bundle's installation or than the last replace through this tool. The class file is read from the project's build output, so save and let the builder run first; sourceNewerThanClass in the answer says when the file is behind the source. Not for wiring changes: a new extension, a new dependency or a new class needs eclipse_install_bundle or eclipse_substitute_bundle."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "project":       {"type":"string","description":"Workspace Java project whose build output holds the new class files. Its META-INF/MANIFEST.MF names the installed bundle unless 'bundle' is passed."},
				    "directory":     {"type":"string","description":"Absolute path of a class file root such as target/classes, instead of 'project'."},
				    "classes":       {"type":"array","items":{"type":"string"},"description":"Fully qualified names of the classes to replace. Omit to replace every class file newer than the bundle's installation or than the last replace through this tool."},
				    "includeNested": {"type":"boolean","default":true,"description":"Also replace the nested and anonymous classes of each named class, whose class files sit beside it."},
				    "bundle":        {"type":"string","description":"Symbolic name of the installed bundle whose classes are replaced. Defaults to the project's Bundle-SymbolicName; without either, every loaded class of that name is replaced whatever loaded it."},
				    "dryRun":        {"type":"boolean","default":false,"description":"Report which class files would be used and change nothing."},
				    "java":          {"type":"string","description":"Java executable for the one-off attach helper. Defaults to the JDK this IDE runs on, which needs the jdk.attach module."},
				    "maxResults":    {"type":"integer","default":200,"minimum":1,"maximum":2000,"description":"Cap on the classes replaced in one call when 'classes' is omitted."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String projectName = args.getString("project"); //$NON-NLS-1$
		String directory = args.getString("directory"); //$NON-NLS-1$
		if (projectName == null && directory == null) {
			return McpToolResult.error("Pass 'project' or 'directory' to say where the new class files are."); //$NON-NLS-1$
		}
		String bundleName = args.getString("bundle"); //$NON-NLS-1$
		boolean includeNested = args.getBoolean("includeNested", true); //$NON-NLS-1$
		boolean dryRun = args.getBoolean("dryRun", false); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 200, 1, 2000); //$NON-NLS-1$
		List<String> names = stringList(arguments.get("classes")); //$NON-NLS-1$

		JsonObject result = new JsonObject();
		IJavaProject javaProject = null;
		List<Path> roots;
		if (projectName != null) {
			try {
				javaProject = JavaModelSupport.javaProjects(projectName).get(0);
			} catch (ToolInputException e) {
				return McpToolResult.error(e.getMessage());
			}
			// the class files are the builder's, so a build still running is what is being read
			WorkspaceSync.waitForBuild(monitor);
			roots = outputRoots(javaProject);
			result.put("project", projectName); //$NON-NLS-1$
			if (bundleName == null) {
				bundleName = FileText.symbolicName(javaProject.getProject().getFile("META-INF/MANIFEST.MF"));
			}
		} else {
			Path dir = Path.of(directory);
			if (!dir.isAbsolute() || !Files.isDirectory(dir)) {
				return McpToolResult.error("'directory' must be an absolute path of an existing directory: " + directory); //$NON-NLS-1$
			}
			roots = List.of(dir);
			result.put("directory", dir.toString()); //$NON-NLS-1$
		}
		JsonArray rootsJson = new JsonArray();
		roots.forEach(root -> rootsJson.add(root.toString()));
		result.put("outputRoots", rootsJson); //$NON-NLS-1$

		Bundle bundle = null;
		if (bundleName != null) {
			bundle = Platform.getBundle(bundleName);
			if (bundle == null) {
				return McpToolResult.error("No bundle named '%s' is installed in this IDE.".formatted(bundleName)); //$NON-NLS-1$
			}
			result.put("bundle", bundleName).put("bundleVersion", bundle.getVersion().toString()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("bundleInstalledAt", Instant.ofEpochMilli(bundle.getLastModified()).toString()); //$NON-NLS-1$
		}
		String key = bundle != null ? bundleName : roots.get(0).toString();

		JsonArray failed = new JsonArray();
		List<Candidate> candidates;
		try {
			if (!names.isEmpty()) {
				candidates = named(names, roots, includeNested, failed);
				result.put("selection", "explicit"); //$NON-NLS-1$ //$NON-NLS-2$
			} else {
				if (bundle == null) {
					return McpToolResult.error("Without a bundle to compare against, pass 'classes': there is no installation time to find changed class files by."); //$NON-NLS-1$
				}
				long since = Math.max(bundle.getLastModified(), LAST_REPLACE.getOrDefault(key, Long.valueOf(0)).longValue());
				candidates = changedSince(roots, since);
				result.put("selection", "changedSince").put("changedSince", Instant.ofEpochMilli(since).toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
		} catch (IOException e) {
			throw new McpToolException("Could not read the class files", e); //$NON-NLS-1$
		}
		int total = candidates.size();
		boolean truncated = total > maxResults;
		if (truncated) {
			candidates = candidates.subList(0, maxResults);
		}
		result.put("total", total).put("truncated", truncated).put("dryRun", dryRun); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

		if (dryRun) {
			JsonArray would = new JsonArray();
			for (Candidate candidate : candidates) {
				would.add(describe(candidate, javaProject));
			}
			result.put("wouldReplace", would).put("failed", failed) //$NON-NLS-1$ //$NON-NLS-2$
					.put("agent", new JsonObject().put("loaded", HotSwapSupport.existing() != null)); //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.of(result.toString());
		}

		long now = System.currentTimeMillis();
		HotSwapSupport.Attachment attachment = HotSwapSupport.attach(args.getString("java"), //$NON-NLS-1$
				Math.min(MAX_ATTACH_SECONDS, CallBudget.maxWaitSeconds()));
		Instrumentation instrumentation = attachment.instrumentation();
		result.put("agent", new JsonObject().put("how", attachment.how()) //$NON-NLS-1$ //$NON-NLS-2$
				.put("helperMillis", attachment.helperMillis()) //$NON-NLS-1$
				.put("agentJar", attachment.agentJar() == null ? null : attachment.agentJar().toString())); //$NON-NLS-1$

		Map<String, List<Class<?>>> loadedByName = HotSwapSupport.loadedByName(instrumentation);
		List<ClassDefinition> definitions = new ArrayList<>();
		List<JsonObject> entries = new ArrayList<>();
		for (Candidate candidate : candidates) {
			byte[] bytes;
			try {
				bytes = Files.readAllBytes(candidate.file());
			} catch (IOException e) {
				failed.add(failure(candidate.name(), "could not read " + candidate.file() + ": " + e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			for (Class<?> target : targets(candidate, bundle, loadedByName, failed)) {
				JsonObject entry = describe(candidate, javaProject).put("wasLoaded", loadedByName.containsKey(candidate.name())) //$NON-NLS-1$
						.put("bytes", bytes.length); //$NON-NLS-1$
				Bundle owner = FrameworkUtil.getBundle(target);
				entry.put("owner", owner == null ? null : owner.getSymbolicName()); //$NON-NLS-1$
				if (owner == null) {
					entry.put("loader", String.valueOf(target.getClassLoader())); //$NON-NLS-1$
				}
				definitions.add(new ClassDefinition(target, bytes));
				entries.add(entry);
			}
		}

		boolean atomic = true;
		JsonArray redefined = new JsonArray();
		if (!definitions.isEmpty()) {
			String batchError = redefine(instrumentation, definitions.toArray(ClassDefinition[]::new));
			if (batchError == null) {
				entries.forEach(redefined::add);
			} else {
				// one incompatible class fails the whole batch and names nothing, so the
				// answer has to be earned one class at a time
				atomic = false;
				for (int i = 0; i < definitions.size(); i++) {
					String error = redefine(instrumentation, definitions.get(i));
					if (error == null) {
						redefined.add(entries.get(i));
					} else {
						failed.add(failure(definitions.get(i).getDefinitionClass().getName(), error));
					}
				}
			}
		}
		if (redefined.size() > 0) {
			LAST_REPLACE.put(key, Long.valueOf(now));
		}
		result.put("replaced", redefined.size()).put("atomic", atomic).put("redefined", redefined) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("failed", failed).put("notes", notes(bundle)); //$NON-NLS-1$ //$NON-NLS-2$
		if (redefined.size() == 0 && failed.size() > 0) {
			return McpToolResult.error(result.toString());
		}
		return McpToolResult.of(result.toString());
	}

	private static List<Class<?>> targets(Candidate candidate, Bundle bundle, Map<String, List<Class<?>>> loadedByName,
			JsonArray failed) {
		if (bundle == null) {
			List<Class<?>> loaded = loadedByName.getOrDefault(candidate.name(), List.of());
			if (loaded.isEmpty()) {
				failed.add(failure(candidate.name(),
						"nothing in this IDE has loaded a class of that name, and no bundle was named that could; pass 'bundle' or a project with a manifest")); //$NON-NLS-1$
			}
			return loaded;
		}
		try {
			Class<?> target = bundle.loadClass(candidate.name());
			Bundle owner = FrameworkUtil.getBundle(target);
			if (owner != null && !owner.equals(bundle)) {
				failed.add(failure(candidate.name(),
						"the name resolves to bundle " + owner.getSymbolicName() + ", not to " + bundle.getSymbolicName())); //$NON-NLS-1$ //$NON-NLS-2$
				return List.of();
			}
			return List.of(target);
		} catch (ClassNotFoundException | LinkageError e) {
			failed.add(failure(candidate.name(), "the installed " + bundle.getSymbolicName() //$NON-NLS-1$
					+ " has no such class, and redefinition cannot add one; install the bundle instead (" + e + ")")); //$NON-NLS-1$ //$NON-NLS-2$
			return List.of();
		}
	}

	/** The JVM's reason, or {@code null} when the definitions were applied. */
	private static String redefine(Instrumentation instrumentation, ClassDefinition... definitions) {
		try {
			instrumentation.redefineClasses(definitions);
			return null;
		} catch (Exception | LinkageError e) {
			return e.getClass().getSimpleName() + ": " + e.getMessage(); //$NON-NLS-1$
		}
	}

	private static List<Candidate> named(List<String> names, List<Path> roots, boolean includeNested, JsonArray failed)
			throws IOException {
		Map<String, Candidate> selected = new LinkedHashMap<>();
		for (String name : names) {
			Path file = find(roots, name);
			if (file == null) {
				failed.add(failure(name, "no class file for it under " + roots)); //$NON-NLS-1$
				continue;
			}
			selected.putIfAbsent(name, new Candidate(name, file));
			if (!includeNested) {
				continue;
			}
			String simple = file.getFileName().toString();
			simple = simple.substring(0, simple.length() - CLASS_SUFFIX.length());
			String prefix = simple + '$';
			try (Stream<Path> siblings = Files.list(file.getParent())) {
				for (Path sibling : siblings.sorted().toList()) {
					String fileName = sibling.getFileName().toString();
					if (fileName.startsWith(prefix) && fileName.endsWith(CLASS_SUFFIX)) {
						String nested = name + fileName.substring(simple.length(), fileName.length() - CLASS_SUFFIX.length());
						selected.putIfAbsent(nested, new Candidate(nested, sibling));
					}
				}
			}
		}
		return new ArrayList<>(selected.values());
	}

	private static Path find(List<Path> roots, String name) {
		String relative = name.replace('.', '/') + CLASS_SUFFIX;
		for (Path root : roots) {
			Path file = root.resolve(relative);
			if (Files.isRegularFile(file)) {
				return file;
			}
		}
		return null;
	}

	private static List<Candidate> changedSince(List<Path> roots, long since) throws IOException {
		Map<String, Candidate> selected = new LinkedHashMap<>();
		for (Path root : roots) {
			if (!Files.isDirectory(root)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(root)) {
				for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
					String fileName = file.getFileName().toString();
					if (!fileName.endsWith(CLASS_SUFFIX) || Files.getLastModifiedTime(file).toMillis() <= since) {
						continue;
					}
					String relative = root.relativize(file).toString().replace(root.getFileSystem().getSeparator(), "."); //$NON-NLS-1$
					String name = relative.substring(0, relative.length() - CLASS_SUFFIX.length());
					selected.putIfAbsent(name, new Candidate(name, file));
				}
			}
		}
		return new ArrayList<>(selected.values());
	}

	private static JsonObject describe(Candidate candidate, IJavaProject javaProject) {
		JsonObject json = new JsonObject().put("class", candidate.name()).put("classFile", candidate.file().toString()); //$NON-NLS-1$ //$NON-NLS-2$
		long classModified = candidate.file().toFile().lastModified();
		json.put("classFileModified", Instant.ofEpochMilli(classModified).toString()); //$NON-NLS-1$
		if (javaProject != null) {
			Long sourceModified = sourceModified(javaProject, candidate.name());
			if (sourceModified != null) {
				json.put("sourceNewerThanClass", sourceModified.longValue() > classModified); //$NON-NLS-1$
			}
		}
		return json;
	}

	private static Long sourceModified(IJavaProject javaProject, String name) {
		int nested = name.indexOf('$');
		String topLevel = nested < 0 ? name : name.substring(0, nested);
		try {
			IType type = javaProject.findType(topLevel);
			ICompilationUnit unit = type == null ? null : type.getCompilationUnit();
			IResource resource = unit == null ? null : unit.getResource();
			IPath location = resource == null ? null : resource.getLocation();
			return location == null ? null : Long.valueOf(location.toFile().lastModified());
		} catch (JavaModelException e) {
			return null;
		}
	}

	private static List<Path> outputRoots(IJavaProject javaProject) throws McpToolException {
		Set<IPath> paths = new LinkedHashSet<>();
		try {
			paths.add(javaProject.getOutputLocation());
			for (IClasspathEntry entry : javaProject.getRawClasspath()) {
				if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE && entry.getOutputLocation() != null) {
					paths.add(entry.getOutputLocation());
				}
			}
		} catch (JavaModelException e) {
			throw new McpToolException("Could not read the output folders of " + javaProject.getElementName(), e); //$NON-NLS-1$
		}
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		List<Path> result = new ArrayList<>();
		for (IPath path : paths) {
			IResource folder = path.segmentCount() == 1 ? root.getProject(path.segment(0)) : root.getFolder(path);
			IPath location = folder.getLocation();
			if (location != null) {
				result.add(location.toFile().toPath());
			}
		}
		return result;
	}

	private static JsonArray notes(Bundle bundle) {
		JsonArray notes = new JsonArray();
		notes.add("Threads already inside a replaced method keep running its old body until it returns; static initializers do not run again."); //$NON-NLS-1$
		notes.add("A restart puts the installed code back; make the change permanent with eclipse_install_bundle, eclipse_substitute_bundle or an install."); //$NON-NLS-1$
		if (bundle != null) {
			notes.add("A class the installed " + bundle.getSymbolicName() //$NON-NLS-1$
					+ " does not have was not added: redefinition changes existing classes only."); //$NON-NLS-1$
		}
		return notes;
	}

	private static JsonObject failure(String name, String error) {
		return new JsonObject().put("class", name).put("error", error); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static List<String> stringList(Object value) {
		List<String> result = new ArrayList<>();
		if (value instanceof List<?> list) {
			for (Object item : list) {
				if (item != null && !String.valueOf(item).isBlank()) {
					result.add(String.valueOf(item).trim());
				}
			}
		} else if (value != null && !String.valueOf(value).isBlank()) {
			result.add(String.valueOf(value).trim());
		}
		return result;
	}
}
