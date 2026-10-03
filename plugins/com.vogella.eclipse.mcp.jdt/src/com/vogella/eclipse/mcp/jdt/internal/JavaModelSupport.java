package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
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
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Shared resolution of projects, types and members for the Java model tools.
 */
final class JavaModelSupport {

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

	/** Returns the one-based line the member starts on, or {@code -1} when unknown. */
	static int lineOf(IMember member) {
		try {
			String all = member.getTypeRoot().getSource();
			ISourceRange range = member.getSourceRange();
			if (all == null || range == null || range.getOffset() < 0) {
				return -1;
			}
			return FileText.lineOf(all, range.getOffset());
		} catch (JavaModelException e) {
			return -1;
		}
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
			joiner.add(Signature.getSimpleName(Signature.toString(parameterType)));
		}
		return joiner.toString();
	}
}
