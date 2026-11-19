package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.corext.util.MethodOverrideTester;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the concrete types behind an interface or abstract class, or the methods that
 * override a given one, with where each is declared.
 */
public final class FindImplementationsTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 100;

	@Override
	public String getName() {
		return "eclipse_find_implementations"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Finds the implementations of a Java type or method: the classes, enums, records, anonymous classes and lambdas that implement an interface or extend a class, or, with memberName, the methods that override or implement that method in every subtype; a concrete subtype that declares no override of its own is listed under inheritedBy of the implementation it inherits, so the set of types is complete. It answers 'what actually runs behind this call', which eclipse_get_call_hierarchy's callees and eclipse_find_references stop short of. Overriding is decided the way the compiler decides it, so a generic method implemented with a concrete type argument counts. Abstract classes and interfaces are left out unless includeAbstract is true, and abstract methods likewise. Each entry is one line: the type or method, its kind, and path and line for source, or library for a jar."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["typeName"],
				  "properties": {
				    "typeName":        {"type":"string","description":"Fully qualified interface or class, e.g. java.util.List"},
				    "memberName":      {"type":"string","description":"Optional method name; lists the overriding methods instead of the subtypes. Every overload is followed unless paramTypes picks one."},
				    "paramTypes":      {"type":"array","items":{"type":"string"},"description":"Selects one overload of memberName by its parameter types, as in eclipse_find_references."},
				    "project":         {"type":"string","description":"Project used to resolve the type and to scope the hierarchy. Defaults to the whole workspace."},
				    "includeAbstract": {"type":"boolean","default":false,"description":"Also list abstract classes, interfaces and abstract methods, the intermediate levels of the hierarchy."},
				    "includeBinary":   {"type":"boolean","default":true,"description":"Also list implementations in jars on the build path. False keeps only workspace source."},
				    "maxResults":      {"type":"integer","default":100,"minimum":1,"maximum":2000},
				    """ + JavaModelSupport.REFRESH_PROPERTY + """
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String typeName = args.getString("typeName"); //$NON-NLS-1$
		if (typeName == null) {
			return McpToolResult.error("The argument 'typeName' is required."); //$NON-NLS-1$
		}
		String memberName = args.getString("memberName"); //$NON-NLS-1$
		String projectName = args.getString("project"); //$NON-NLS-1$
		boolean includeAbstract = args.getBoolean("includeAbstract", false); //$NON-NLS-1$
		boolean includeBinary = args.getBoolean("includeBinary", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 2000); //$NON-NLS-1$

		List<IJavaProject> projects;
		IType type;
		List<IMethod> methods;
		JavaModelSupport.Refreshed refreshed;
		try {
			List<String> paramTypes = JavaModelSupport.paramTypes(arguments);
			if (paramTypes != null && memberName == null) {
				return McpToolResult.error("'paramTypes' picks one overload of 'memberName', so give a memberName too."); //$NON-NLS-1$
			}
			projects = JavaModelSupport.javaProjects(projectName);
			if (projects.isEmpty()) {
				return McpToolResult.error("The workspace contains no open Java project."); //$NON-NLS-1$
			}
			refreshed = JavaModelSupport.refreshUnlessSkipped(args, projectName, monitor);
			type = JavaModelSupport.findType(typeName, projects, monitor);
			methods = memberName == null ? List.of() : methodsOf(type, memberName, paramTypes);
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}

		ITypeHierarchy hierarchy;
		try {
			hierarchy = projectName == null ? type.newTypeHierarchy(monitor)
					: type.newTypeHierarchy(projects.get(0), monitor);
		} catch (JavaModelException e) {
			throw new McpToolException("Could not compute the type hierarchy of " + type.getFullyQualifiedName(), e); //$NON-NLS-1$
		}

		List<IMember> found = new ArrayList<>();
		Map<String, IMethod> declaredIn = new HashMap<>();
		Map<IMethod, List<String>> inheritedBy = new HashMap<>();
		try {
			for (IMethod method : methods) {
				// the queried declaration is an implementation too, and the one its non-overriding subtypes run
				declaredIn.put(key(method, type), method);
				if (includeAbstract || !hasNoBody(method)) {
					found.add(method);
				}
			}
			for (IType subtype : hierarchy.getAllSubtypes(type)) {
				if (monitor.isCanceled()) {
					break;
				}
				if (!includeBinary && subtype.isBinary()) {
					continue;
				}
				if (methods.isEmpty()) {
					if (includeAbstract || isConcrete(subtype)) {
						found.add(subtype);
					}
					continue;
				}
				MethodOverrideTester tester = new MethodOverrideTester(subtype, hierarchy);
				for (IMethod method : methods) {
					IMethod overriding = tester.findOverridingMethodInType(subtype, method);
					if (overriding == null && (subtype.isAnonymous() || subtype.isLambda())) {
						overriding = soleCandidate(subtype, method);
					}
					if (overriding != null) {
						declaredIn.put(key(method, subtype), overriding);
						if (includeAbstract || !hasNoBody(overriding)) {
							found.add(overriding);
						}
					}
				}
			}
			if (!methods.isEmpty()) {
				collectInheritors(type, hierarchy, methods, declaredIn, inheritedBy, includeBinary, monitor);
			}
		} catch (JavaModelException e) {
			throw new McpToolException("Could not read the subtypes of " + type.getFullyQualifiedName(), e); //$NON-NLS-1$
		}
		// an anonymous class matched through soleCandidate can answer for two overloads of the same arity
		found = new ArrayList<>(new LinkedHashSet<>(found));
		found.sort(Comparator.comparing((IMember member) -> member.isBinary())
				.thenComparing(FindImplementationsTool::labelOf));

		JsonArray entries = new JsonArray();
		found.stream().limit(maxResults).map(member -> {
			JsonObject entry = entryOf(member);
			List<String> inheritors = inheritedBy.get(member);
			if (inheritors != null) {
				JsonArray names = new JsonArray();
				inheritors.stream().sorted().forEach(names::add);
				entry.put("inheritedBy", names); //$NON-NLS-1$
			}
			return entry;
		}).forEach(entries::add);
		JsonObject result = new JsonObject()
				.put("resolved", type.getFullyQualifiedName() + (memberName == null ? "" //$NON-NLS-1$ //$NON-NLS-2$
						: '#' + (methods.size() == 1 ? JavaModelSupport.signatureOf(methods.get(0)) : memberName)))
				.put("implementations", entries) //$NON-NLS-1$
				.put("total", found.size()) //$NON-NLS-1$
				.put("truncated", found.size() > maxResults || monitor.isCanceled()); //$NON-NLS-1$
		return McpToolResult.of(refreshed.report(result).toString());
	}

	private static String key(IMethod method, IType type) {
		return method.getHandleIdentifier() + '|' + type.getHandleIdentifier();
	}

	/**
	 * Records, for each concrete subtype that declares no override of its own, the nearest
	 * superclass implementation it runs, so a type that only inherits one is not missing.
	 */
	private static void collectInheritors(IType base, ITypeHierarchy hierarchy, List<IMethod> methods,
			Map<String, IMethod> declaredIn, Map<IMethod, List<String>> inheritedBy, boolean includeBinary,
			IProgressMonitor monitor) throws JavaModelException {
		for (IType subtype : hierarchy.getAllSubtypes(base)) {
			if (monitor.isCanceled()) {
				return;
			}
			if (!isConcrete(subtype) || (!includeBinary && subtype.isBinary())) {
				continue;
			}
			for (IMethod method : methods) {
				if (declaredIn.containsKey(key(method, subtype))) {
					continue;
				}
				IMethod implementation = null;
				for (IType up = hierarchy.getSuperclass(subtype); up != null
						&& implementation == null; up = hierarchy.getSuperclass(up)) {
					implementation = declaredIn.get(key(method, up));
				}
				if (implementation == null) {
					// no class supplies it, so a default method does, if exactly one interface declares one
					List<IMethod> defaults = new ArrayList<>();
					for (IType up : hierarchy.getAllSuperInterfaces(subtype)) {
						IMethod candidate = declaredIn.get(key(method, up));
						if (candidate != null && !hasNoBody(candidate)) {
							defaults.add(candidate);
						}
					}
					implementation = defaults.size() == 1 ? defaults.get(0) : null;
				}
				if (implementation != null && !hasNoBody(implementation)) {
					inheritedBy.computeIfAbsent(implementation, k -> new ArrayList<>()).add(labelOf(subtype));
				}
			}
		}
	}

	private static List<IMethod> methodsOf(IType type, String memberName, List<String> paramTypes)
			throws ToolInputException, McpToolException {
		List<IMethod> methods = JavaModelSupport.findMembers(type, memberName).stream()
				.filter(IMethod.class::isInstance).map(IMethod.class::cast)
				.filter(method -> paramTypes == null || JavaModelSupport.takesParameters(method, paramTypes)).toList();
		if (methods.isEmpty()) {
			throw new ToolInputException((paramTypes == null ? "'%s' has no method named '%s'." //$NON-NLS-1$
					: "'%s' has no overload of '%s' taking the given paramTypes.") //$NON-NLS-1$
					.formatted(type.getFullyQualifiedName(), memberName));
		}
		List<IMethod> overridable = new ArrayList<>();
		boolean sawFinal = false;
		for (IMethod method : methods) {
			try {
				int flags = method.getFlags();
				if (!method.isConstructor() && !Flags.isStatic(flags) && !Flags.isPrivate(flags)) {
					if (Flags.isFinal(flags)) {
						sawFinal = true;
						continue;
					}
					overridable.add(method);
				}
			} catch (JavaModelException e) {
				throw new McpToolException("Could not read the flags of " + JavaModelSupport.describe(method), e); //$NON-NLS-1$
			}
		}
		if (overridable.isEmpty() && sawFinal) {
			throw new ToolInputException("'%s#%s' is final, so its own declaration is the only implementation." //$NON-NLS-1$
					.formatted(type.getFullyQualifiedName(), memberName));
		}
		if (overridable.isEmpty()) {
			throw new ToolInputException("'%s#%s' is static, private or a constructor, so nothing can override it." //$NON-NLS-1$
					.formatted(type.getFullyQualifiedName(), memberName));
		}
		return overridable;
	}

	/**
	 * An anonymous class created with a diamond has no type arguments in its model, so the
	 * override test cannot substitute them; it has no name to be overloaded through either, so
	 * the one method of the same name and arity is the implementation.
	 */
	private static IMethod soleCandidate(IType type, IMethod method) throws JavaModelException {
		List<IMethod> candidates = new ArrayList<>();
		for (IMethod candidate : type.getMethods()) {
			if (candidate.getElementName().equals(method.getElementName())
					&& candidate.getNumberOfParameters() == method.getNumberOfParameters()) {
				candidates.add(candidate);
			}
		}
		return candidates.size() == 1 ? candidates.get(0) : null;
	}

	/** Source interface methods carry no abstract flag, so the declaring type decides for them. */
	private static boolean hasNoBody(IMethod method) throws JavaModelException {
		int flags = method.getFlags();
		if (Flags.isAbstract(flags)) {
			return true;
		}
		IType declaring = method.getDeclaringType();
		return declaring != null && declaring.isInterface() && !Flags.isDefaultMethod(flags) && !Flags.isStatic(flags)
				&& !Flags.isPrivate(flags);
	}

	private static boolean isConcrete(IType type) throws JavaModelException {
		return !type.isInterface() && !Flags.isAbstract(type.getFlags());
	}

	private static JsonObject entryOf(IMember member) {
		IType type = member instanceof IType t ? t : member.getDeclaringType();
		JsonObject entry = new JsonObject();
		entry.put(member instanceof IType ? "type" : "method", labelOf(member)); //$NON-NLS-1$ //$NON-NLS-2$
		entry.put("kind", kindOf(type)); //$NON-NLS-1$
		if (member.isBinary()) {
			IJavaElement root = member.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
			entry.put("library", root == null ? null : root.getPath().toString()); //$NON-NLS-1$
			return entry;
		}
		IResource resource = member.getResource();
		entry.put("path", resource == null ? null : resource.getFullPath().toString()); //$NON-NLS-1$
		int line = JavaModelSupport.lineOf(member);
		if (line > 0) {
			entry.put("line", line); //$NON-NLS-1$
		}
		return entry;
	}

	/** Anonymous types and lambdas have no name of their own, so they are named after where they sit. */
	private static String labelOf(IMember member) {
		IType type = member instanceof IType t ? t : member.getDeclaringType();
		boolean unnamed = type != null && (type.isLambda() || isAnonymous(type));
		if (!unnamed) {
			return JavaModelSupport.describe(member);
		}
		IJavaElement enclosing = type.getParent();
		while (enclosing != null && !(enclosing instanceof IMember)) {
			enclosing = enclosing.getParent();
		}
		String where = enclosing == null ? type.getFullyQualifiedName() : JavaModelSupport.describe(enclosing);
		String what = (type.isLambda() ? "lambda in " : "anonymous in ") + where; //$NON-NLS-1$ //$NON-NLS-2$
		return member instanceof IMethod method ? what + " " + JavaModelSupport.signatureOf(method) : what; //$NON-NLS-1$
	}

	private static boolean isAnonymous(IType type) {
		try {
			return type.isAnonymous();
		} catch (JavaModelException e) {
			return false;
		}
	}

	private static String kindOf(IType type) {
		try {
			if (type.isLambda()) {
				return "lambda"; //$NON-NLS-1$
			}
			if (type.isAnonymous()) {
				return "anonymous"; //$NON-NLS-1$
			}
			if (type.isEnum()) {
				return "enum"; //$NON-NLS-1$
			}
			if (type.isRecord()) {
				return "record"; //$NON-NLS-1$
			}
			if (type.isInterface()) {
				return "interface"; //$NON-NLS-1$
			}
			return Flags.isAbstract(type.getFlags()) ? "abstract class" : "class"; //$NON-NLS-1$ //$NON-NLS-2$
		} catch (JavaModelException e) {
			return "class"; //$NON-NLS-1$
		}
	}
}
