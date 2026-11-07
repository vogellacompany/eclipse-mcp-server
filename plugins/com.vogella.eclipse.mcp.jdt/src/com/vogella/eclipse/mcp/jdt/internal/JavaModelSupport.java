package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Shared resolution of projects, types and members for the Java model tools.
 */
final class JavaModelSupport {

	/** A private-use character, no identifier part, which carries a $ through Signature.toString. */
	private static final char DOLLAR_STAND_IN = '\uE000';

	private static final Pattern QUALIFIED_NAME = Pattern
			.compile("\\p{javaJavaIdentifierPart}+(?:\\.\\p{javaJavaIdentifierPart}+)+"); //$NON-NLS-1$

	/** A {@code $} between two names, which a class file uses to join a member type to its outer type. */
	private static final Pattern MEMBER_TYPE_DOLLAR = Pattern.compile("(?<=[\\p{javaJavaIdentifierPart}&&[^$]])\\$(?=\\p{javaJavaIdentifierStart})"); //$NON-NLS-1$

	private JavaModelSupport() {
	}

	/**
	 * Returns the Java projects to work on: the named one, or every open Java project.
	 *
	 * @throws ToolInputException if the named project does not exist, is closed or is not a Java project
	 */
	static List<IJavaProject> javaProjects(String projectName) throws ToolInputException, McpToolException {
		if (projectName == null) {
			try {
				return List.of(JavaCore.create(ResourcesPlugin.getWorkspace().getRoot()).getJavaProjects());
			} catch (JavaModelException e) {
				throw new McpToolException("Could not read the Java projects of the workspace", e); //$NON-NLS-1$
			}
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.exists()) {
			throw new ToolInputException("There is no project named '%s' in the workspace.".formatted(projectName)); //$NON-NLS-1$
		}
		if (!project.isOpen()) {
			throw new ToolInputException("Project '%s' is closed.".formatted(projectName)); //$NON-NLS-1$
		}
		try {
			if (!project.hasNature(JavaCore.NATURE_ID)) {
				throw new ToolInputException("Project '%s' is not a Java project.".formatted(projectName)); //$NON-NLS-1$
			}
		} catch (CoreException e) {
			throw new McpToolException("Could not read the natures of project " + projectName, e); //$NON-NLS-1$
		}
		return List.of(JavaCore.create(project));
	}

	/** The {@code refresh} argument of the search tools that narrow it to {@code project}. */
	static final String REFRESH_PROPERTY = refreshProperty(
			"Read changes made outside the IDE into the workspace before searching: the whole workspace, or only 'project' when one is named, not the projects on its build path, which the search still reads."); //$NON-NLS-1$

	/** The {@code refresh} argument, its description starting with what {@code refreshed} covers. */
	static String refreshProperty(String what) {
		return """
				"refresh": {"type":"boolean","default":true,"description":"%s It walks every file it covers, which takes tens of seconds in a workspace of hundreds of projects even when nothing changed; refreshMillis in the answer says what it cost. Pass false when no file was changed outside the IDE since the last refresh, for example after one eclipse_refresh following your edits."}""" //$NON-NLS-1$
				.formatted(what);
	}

	/** Whether the call refreshed and how long it took, reported in the answer. */
	record Refreshed(boolean done, long millis) {
		JsonObject report(JsonObject result) {
			result.put("refreshed", Boolean.valueOf(done)); //$NON-NLS-1$
			if (done) {
				result.put("refreshMillis", Long.valueOf(millis)); //$NON-NLS-1$
			}
			return result;
		}
	}

	/** Refreshes like {@link #refresh(String, IProgressMonitor)} unless the {@code refresh} argument is false. */
	static Refreshed refreshUnlessSkipped(ToolArguments args, String projectName, IProgressMonitor monitor)
			throws McpToolException {
		if (!args.getBoolean("refresh", true)) { //$NON-NLS-1$
			return new Refreshed(false, 0);
		}
		long start = System.nanoTime();
		refresh(projectName, monitor);
		return new Refreshed(true, (System.nanoTime() - start) / 1_000_000);
	}

	/** Reads outside edits into the workspace, so the search index does not answer from stale files. */
	static void refresh(String projectName, IProgressMonitor monitor) throws McpToolException {
		try {
			WorkspaceSync.refresh(projectName == null ? ResourcesPlugin.getWorkspace().getRoot()
					: ResourcesPlugin.getWorkspace().getRoot().getProject(projectName), monitor);
		} catch (CoreException e) {
			throw new McpToolException("Could not refresh the workspace", e); //$NON-NLS-1$
		}
	}

	/**
	 * Returns the first type resolvable under the given fully qualified name.
	 *
	 * @throws ToolInputException if no project resolves the name
	 */
	static IType findType(String typeName, List<IJavaProject> projects) throws ToolInputException, McpToolException {
		return findType(typeName, projects, null);
	}

	/**
	 * Returns the first type resolvable under the given fully qualified name.
	 * A monitor makes secondary types findable, since only the monitor-taking overload consults the index.
	 *
	 * @throws ToolInputException if no project resolves the name
	 */
	static IType findType(String typeName, List<IJavaProject> projects, IProgressMonitor monitor)
			throws ToolInputException, McpToolException {
		IType binaryFallback = null;
		for (IJavaProject project : projects) {
			try {
				IType type = project.findType(typeName);
				if (type != null && type.exists()) {
					// source wins over the same type compiled into build output
					if (!type.isBinary()) {
						return type;
					}
					// a copy inside build output binds to nothing, so prefer one a project compiles against
					if (binaryFallback == null || (isBuildOutput(binaryFallback) && !isBuildOutput(type))) {
						binaryFallback = type;
					}
				}
			} catch (JavaModelException e) {
				throw new McpToolException("Could not resolve type %s in project %s".formatted(typeName, //$NON-NLS-1$
						project.getElementName()), e);
			}
		}
		if (binaryFallback != null) {
			return binaryFallback;
		}
		// the index lookup for secondary types is slower, so it comes last
		IType secondary = findSecondaryType(typeName, projects, monitor);
		if (secondary != null) {
			return secondary;
		}
		throw new ToolInputException(
				"Could not resolve the type '%s' on the classpath of %s. Use a fully qualified name." //$NON-NLS-1$
						.formatted(typeName, projects.size() == 1 ? "project " + projects.get(0).getElementName() //$NON-NLS-1$
								: "any Java project in the workspace")); //$NON-NLS-1$
	}

	/**
	 * Every source copy of a fully qualified name across the given projects, or the
	 * single type {@link #findType} resolves when there is no source copy.
	 * One name can be declared more than once (SWT declares {@code Image} per window system), and a search
	 * bound to one copy misses the others. A copy is a distinct declaration, not a distinct project.
	 */
	static List<IType> findTypes(String typeName, List<IJavaProject> projects, IProgressMonitor monitor)
			throws ToolInputException, McpToolException {
		List<IType> types = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (IJavaProject project : projects) {
			try {
				IType type = project.findType(typeName);
				if (type != null && type.exists() && !type.isBinary() && seen.add(type.getHandleIdentifier())) {
					types.add(type);
				}
			} catch (JavaModelException e) {
				throw new McpToolException("Could not resolve type %s in project %s".formatted(typeName, //$NON-NLS-1$
						project.getElementName()), e);
			}
		}
		if (types.isEmpty()) {
			types.add(findType(typeName, projects, monitor));
		}
		return types;
	}

	/** Whether the type lives in build output rather than in a dependency. */
	static boolean isBuildOutput(IType type) {
		IJavaElement root = type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
		if (root == null || root.getPath() == null) {
			return false;
		}
		String path = root.getPath().toString();
		return path.contains("/target/") || path.contains("/bin/"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Where a resolved type came from, for saying which binding was searched. */
	static String originOf(IType type) {
		IJavaElement root = type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
		return root == null || root.getPath() == null ? null : root.getPath().toString();
	}

	/** A package-private type declared in a file named after a different type. */
	private static IType findSecondaryType(String typeName, List<IJavaProject> projects, IProgressMonitor monitor)
			throws McpToolException {
		for (IJavaProject project : projects) {
			try {
				IType type = project.findType(typeName,
						monitor == null ? new NullProgressMonitor() : monitor);
				if (type != null && type.exists() && !type.isBinary()) {
					return type;
				}
			} catch (JavaModelException e) {
				throw new McpToolException("Could not resolve type %s in project %s".formatted(typeName, //$NON-NLS-1$
						project.getElementName()), e);
			}
		}
		return null;
	}

	/**
	 * Records where a search match actually lives.
	 * {@code SearchMatch.getResource()} names the owning project for a jar match, which would read as a source match.
	 */
	static void describeLocation(org.eclipse.jdt.core.search.SearchMatch match, JsonObject entry) {
		org.eclipse.core.resources.IResource resource = match.getResource();
		IJavaElement element = match.getElement() instanceof IJavaElement found ? found : null;
		boolean binary = element != null && element.getAncestor(IJavaElement.CLASS_FILE) != null;
		entry.put("origin", binary ? "binary" : "source"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (binary) {
			IJavaElement root = element.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
			// compiled code belongs to no project path
			entry.put("path", null) //$NON-NLS-1$
					.put("project", null) //$NON-NLS-1$
					.put("library", root == null ? null : root.getPath().toString()); //$NON-NLS-1$
			return;
		}
		entry.put("path", resource == null ? null : resource.getFullPath().toString()) //$NON-NLS-1$
				.put("project", resource == null || resource.getProject() == null ? null //$NON-NLS-1$
						: resource.getProject().getName())
				.put("library", null); //$NON-NLS-1$
	}

	/**
	 * Returns the methods and fields of {@code type} named {@code memberName}, all overloads included.
	 *
	 * @throws ToolInputException if the type has no such member
	 */
	static List<IMember> findMembers(IType type, String memberName) throws ToolInputException, McpToolException {
		List<IMember> members = new ArrayList<>();
		List<String> available = new ArrayList<>();
		try {
			for (IMethod method : type.getMethods()) {
				available.add(method.getElementName());
				if (method.getElementName().equals(memberName)) {
					members.add(method);
				}
			}
			for (IField field : type.getFields()) {
				available.add(field.getElementName());
				if (field.getElementName().equals(memberName)) {
					members.add(field);
				}
			}
			for (IType nested : type.getTypes()) {
				available.add(nested.getElementName());
				if (nested.getElementName().equals(memberName)) {
					members.add(nested);
				}
			}
		} catch (JavaModelException e) {
			throw new McpToolException("Could not read the members of " + type.getFullyQualifiedName(), e); //$NON-NLS-1$
		}
		if (members.isEmpty()) {
			throw new ToolInputException("Type '%s' has no member named '%s'. Available members: %s".formatted( //$NON-NLS-1$
					type.getFullyQualifiedName(), memberName,
					available.isEmpty() ? "none" : String.join(", ", available.stream().distinct().sorted().toList()))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return members;
	}

	/** {@link #compilationUnit(String)}, first reading a file or package folder created outside the IDE. */
	static ICompilationUnit compilationUnit(String path, IProgressMonitor monitor)
			throws ToolInputException, McpToolException {
		IPath location = IPath.fromPortableString(path);
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		if (root.findMember(location) == null && location.segmentCount() > 1
				&& root.findMember(location.segment(0)) instanceof IProject project && project.isOpen()) {
			refreshPackageFolder(project, location.removeFirstSegments(1).segments(), monitor);
		}
		return compilationUnit(path);
	}

	/**
	 * Resolves a workspace path such as {@code /app/src/com/example/Main.java} to a Java
	 * source file on a project's build path.
	 */
	static ICompilationUnit compilationUnit(String path) throws ToolInputException {
		IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(IPath.fromPortableString(path));
		if (!(resource instanceof IFile file)) {
			throw new ToolInputException(
					"There is no file at the workspace path '%s'. Paths look like /project/src/com/example/Main.java." //$NON-NLS-1$
							.formatted(path));
		}
		if (!(JavaCore.create(file) instanceof ICompilationUnit unit) || !unit.exists()) {
			throw new ToolInputException(
					"'%s' is not a Java source file on the build path of its project.".formatted(path)); //$NON-NLS-1$
		}
		return unit;
	}

	/**
	 * Returns the source of a member including its Javadoc, or {@code null} when no source
	 * is attached.
	 */
	static String sourceOf(IMember member) throws McpToolException {
		try {
			String all = member.getTypeRoot().getSource();
			ISourceRange range = member.getSourceRange();
			if (all == null || range == null || range.getOffset() < 0) {
				return null;
			}
			ISourceRange javadoc = member.getJavadocRange();
			int start = javadoc != null && javadoc.getOffset() >= 0 ? javadoc.getOffset() : range.getOffset();
			int end = Math.min(range.getOffset() + range.getLength(), all.length());
			return start >= end ? null : all.substring(start, end);
		} catch (JavaModelException e) {
			throw new McpToolException("Could not read the source of " + member.getElementName(), e); //$NON-NLS-1$
		}
	}

	/** Reads outside edits to the file that holds {@code element}, when it is in the workspace. */
	static void refreshSource(IJavaElement element, IProgressMonitor monitor) throws McpToolException {
		IResource resource = element.getResource();
		if (resource == null) {
			return;
		}
		try {
			WorkspaceSync.refresh(resource, monitor);
		} catch (CoreException e) {
			throw new McpToolException("Could not refresh " + resource.getFullPath(), e); //$NON-NLS-1$
		}
	}

	/**
	 * Resolves a type by its fully qualified name, accepting the binary spelling {@code Outer$Inner}, and
	 * refreshes its file. On a miss the folder of its package under each source folder of the projects is
	 * refreshed and the lookup retried once, so a type created outside the IDE is found without refreshing
	 * the workspace.
	 */
	static IType resolveType(String typeName, List<IJavaProject> projects, IProgressMonitor monitor)
			throws ToolInputException, McpToolException {
		IType type;
		try {
			type = findTypeAnySpelling(typeName, projects, monitor);
		} catch (ToolInputException e) {
			try {
				if (!refreshPackageFolders(typeName, projects, monitor)) {
					throw e;
				}
			} catch (McpToolException refreshFailed) {
				e.addSuppressed(refreshFailed);
				throw e;
			}
			type = findTypeAnySpelling(typeName, projects, monitor);
		}
		refreshSource(type, monitor);
		return type;
	}

	/** The type under its own name, or else with a binary member-type {@code $} read as {@code .}. */
	private static IType findTypeAnySpelling(String typeName, List<IJavaProject> projects, IProgressMonitor monitor)
			throws ToolInputException, McpToolException {
		try {
			return findType(typeName, projects, monitor);
		} catch (ToolInputException asGiven) {
			String dotted = dotted(typeName);
			if (dotted.equals(typeName)) {
				throw asGiven;
			}
			try {
				return findType(dotted, projects, monitor);
			} catch (ToolInputException e) {
				throw asGiven;
			}
		}
	}

	/**
	 * Refreshes the folder that would hold {@code typeName} under every source folder, one level deep, and
	 * answers whether there was a source folder to look in. A member type's name is tried with every prefix as
	 * the package, the default package is the source folder itself, and a package folder that is new itself is
	 * read in whole below the deepest folder that exists.
	 */
	private static boolean refreshPackageFolders(String typeName, List<IJavaProject> projects,
			IProgressMonitor monitor) throws McpToolException {
		String[] segments = dotted(typeName).split("\\."); //$NON-NLS-1$
		boolean refreshed = false;
		for (IJavaProject project : projects) {
			try {
				for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
					if (root.getKind() == IPackageFragmentRoot.K_SOURCE && root.getResource() instanceof IContainer folder) {
						refreshed |= refreshPackageFolder(folder, segments, monitor);
					}
				}
			} catch (JavaModelException e) {
				throw new McpToolException("Could not read the source folders of " + project.getElementName(), e); //$NON-NLS-1$
			}
		}
		return refreshed;
	}

	private static boolean refreshPackageFolder(IContainer sourceFolder, String[] segments, IProgressMonitor monitor)
			throws McpToolException {
		for (int length = segments.length - 1; length >= 0; length--) {
			IContainer folder = length == 0 ? sourceFolder
					: sourceFolder.findMember(String.join("/", Arrays.copyOf(segments, length))) instanceof IContainer found //$NON-NLS-1$
							? found
							: null;
			if (folder == null) {
				continue;
			}
			try {
				folder.refreshLocal(IResource.DEPTH_ONE, monitor);
				if (length < segments.length - 1 && folder.findMember(segments[length]) instanceof IContainer created) {
					created.refreshLocal(IResource.DEPTH_INFINITE, monitor);
				}
			} catch (CoreException e) {
				throw new McpToolException("Could not refresh " + folder.getFullPath(), e); //$NON-NLS-1$
			}
			return true;
		}
		return false;
	}

	/** A binary member-type name such as {@code java.util.Map$Entry} with each {@code $} between names read as {@code .}. */
	private static String dotted(String typeName) {
		return MEMBER_TYPE_DOLLAR.matcher(typeName).replaceAll("."); //$NON-NLS-1$
	}

	/**
	 * The simple name of a type as written in Java, e.g. {@code Entry<K, V>} for {@code java.util.Map$Entry<K, V>}:
	 * the binary {@code $} of a member type reads as {@code .}, so binary and source types agree, while a
	 * {@code $} that starts a name, as in {@code $AutoValue_Foo}, stays.
	 */
	static String simpleTypeName(String typeName) {
		// Signature.getSimpleName keeps Map$Entry whole, so qualified names are cut here
		Matcher qualified = QUALIFIED_NAME.matcher(dotted(typeName));
		return qualified.replaceAll(name -> Matcher.quoteReplacement(
				name.group().substring(name.group().lastIndexOf('.') + 1)));
	}

	/** {@link #simpleTypeName} of a type signature such as {@code Ljava.util.Map$Entry;}. */
	static String simpleTypeNameOf(String typeSignature) {
		// Signature.toString drops a $ that starts a name, so it is hidden from it
		String readable = Signature.toString(typeSignature.replace('$', DOLLAR_STAND_IN));
		return simpleTypeName(readable.replace(DOLLAR_STAND_IN, '$'));
	}

	/**
	 * The {@code paramTypes} argument that selects one overload, or {@code null} when absent or null, which is not
	 * the empty list: that is the no-argument overload.
	 */
	static List<String> paramTypes(Map<String, Object> arguments) throws ToolInputException {
		if (arguments.get("paramTypes") == null) { //$NON-NLS-1$
			return null;
		}
		if (!(arguments.get("paramTypes") instanceof List<?> list) //$NON-NLS-1$
				|| list.stream().anyMatch(item -> !(item instanceof String text) || text.isBlank())) {
			throw new ToolInputException(
					"'paramTypes' must be a list of type names, e.g. [\"String\", \"int\"], or [] for no parameters."); //$NON-NLS-1$
		}
		return list.stream().map(item -> ((String) item).strip()).toList();
	}

	/**
	 * Whether {@code method} takes exactly {@code wanted}, written as in Java with or without package and type
	 * arguments, {@code ...} for varargs and {@code $} or {@code .} for a member type. A qualified name must also
	 * match the parameter's qualified name, so {@code java.sql.Date} does not select {@code set(java.util.Date)}.
	 */
	static boolean takesParameters(IMethod method, List<String> wanted) {
		String[] parameters = method.getParameterTypes();
		if (parameters.length != wanted.size()) {
			return false;
		}
		for (int i = 0; i < parameters.length; i++) {
			String given = dotted(wanted.get(i).strip().replaceAll("<.*>", "").replace("...", "[]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			String erased = Signature.getTypeErasure(parameters[i]);
			if (!simpleTypeNameOf(erased).equals(simpleTypeName(given))) {
				return false;
			}
			if (given.indexOf('.') >= 0) {
				String qualified = qualifiedTypeNameOf(method, erased);
				if (qualified != null && !qualified.equals(given) && !qualified.endsWith('.' + given)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * The qualified name of an erased parameter type of {@code method}, such as {@code java.util.Map.Entry[]},
	 * resolved against the declaring type when the source wrote it unqualified, or {@code null} when unknown.
	 */
	static String qualifiedTypeNameOf(IMethod method, String erasedSignature) {
		String element = Signature.getElementType(erasedSignature);
		if (element.charAt(0) == Signature.C_TYPE_VARIABLE) {
			return null;
		}
		String name = Signature.toString(element.replace('$', DOLLAR_STAND_IN)).replace(DOLLAR_STAND_IN, '$');
		if (element.charAt(0) == Signature.C_UNRESOLVED) {
			try {
				String[][] resolved = method.getDeclaringType().resolveType(name);
				if (resolved == null || resolved.length != 1) {
					return null;
				}
				name = resolved[0][0].isEmpty() ? resolved[0][1] : resolved[0][0] + '.' + resolved[0][1];
			} catch (JavaModelException e) {
				return null;
			}
		}
		return dotted(name) + "[]".repeat(Signature.getArrayCount(erasedSignature)); //$NON-NLS-1$
	}

	/**
	 * Returns the one-based first and last line of a member including its Javadoc and
	 * annotations, or {@code null} when no source is attached.
	 */
	static int[] linesOf(IMember member) {
		try {
			String all = member.getTypeRoot().getSource();
			ISourceRange range = member.getSourceRange();
			if (all == null || range == null || range.getOffset() < 0) {
				return null;
			}
			return linesOf(range, SourceText.of(all));
		} catch (JavaModelException e) {
			return null;
		}
	}

	/** The first and last line {@code range} covers in {@code text}. */
	static int[] linesOf(ISourceRange range, SourceText text) {
		int last = Math.max(range.getOffset(),
				Math.min(range.getOffset() + range.getLength(), text.content().length()) - 1);
		return new int[] { text.lineOf(range.getOffset()), text.lineOf(last) };
	}

	/** Returns the one-based line the member starts on, or {@code -1} when unknown. */
	static int lineOf(IMember member) {
		int[] lines = linesOf(member);
		return lines == null ? -1 : lines[0];
	}

	/** Returns a readable label such as {@code com.example.View.createPartControl(Composite)}. */
	static String describe(IJavaElement element) {
		return switch (element) {
		case null -> null;
		case IType type -> type.getFullyQualifiedName();
		case IMethod method -> qualify(method) + parameters(method);
		case IMember member -> qualify(member);
		default -> element.getElementName();
		};
	}

	/** The member's own signature, e.g. {@code loadBySize(String, int, int)}. */
	static String signatureOf(IMember member) {
		return member instanceof IMethod method ? method.getElementName() + parameters(method)
				: member.getElementName();
	}

	private static String qualify(IMember member) {
		IType declaringType = member.getDeclaringType();
		if (declaringType == null) {
			return member.getElementName();
		}
		return declaringType.getFullyQualifiedName() + '.' + member.getElementName();
	}

	private static String parameters(IMethod method) {
		StringJoiner joiner = new StringJoiner(", ", "(", ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		for (String parameterType : method.getParameterTypes()) {
			joiner.add(simpleTypeNameOf(parameterType));
		}
		return joiner.toString();
	}
}
