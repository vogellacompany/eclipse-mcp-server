package com.vogella.eclipse.mcp.jdt.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IInitializer;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the members of a type with their signatures and line ranges but without bodies, so
 * a large class can be read member by member.
 */
public final class GetOutlineTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 200;

	/** Method bits that are no modifiers but share their values with transient and volatile. */
	private static final int METHOD_BITS = Flags.AccVarargs | Flags.AccBridge | Flags.AccSynthetic;

	/** ACC_SUPER of a class file, which shares its value with synchronized. */
	private static final int ACC_SUPER = 0x20;

	@Override
	public String getName() {
		return "eclipse_get_outline"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Returns the outline of a Java type: its members with signatures, modifiers and line ranges, but no method bodies or field initializers. Read it first for a large class, then fetch single members with eclipse_get_source or a line range with eclipse_read_file; for unused members use eclipse_list_declarations, for callers eclipse_find_references. Give exactly one of typeName (any type on the classpath) or path (a workspace .java file, all its top-level types). Each type has type, kind, modifiers, typeParameters, superclass, extends or implements, startLine, endLine, members (binary and path for a top-level type); each member has kind, name, signature, modifiers, startLine, endLine, and member types nest as types. Answers total and truncated."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "typeName":   {"type":"string","description":"Fully qualified type name, e.g. org.eclipse.jface.viewers.TreeViewer; a member type is Outer.Inner or Outer$Inner. A library type without a source attachment still lists its members and signatures, with lines null and sourceAvailable false. A type created outside the IDE is found once its package folder is refreshed, which a missed lookup does."},
				    "path":       {"type":"string","description":"Workspace path of a .java file, e.g. /app/src/com/example/Main.java. Outlines every top-level type in it."},
				    "project":    {"type":"string","description":"Optional project used to resolve typeName. Defaults to the whole workspace."},
				    "javadoc":    {"type":"boolean","default":false,"description":"Add the first sentence of each Javadoc as javadoc, {@inheritDoc} when it only inherits. Needs source."},
				    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":5000,"description":"Maximum number of members over all nesting levels; total counts them all. kind is class, interface, enum, record or annotation for a type and field, enumConstant, recordComponent, constructor, method or initializer (name null) for a member. Lines include Javadoc and annotations. Anonymous and local types are not listed."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String typeName = args.getString("typeName"); //$NON-NLS-1$
		String path = args.getString("path"); //$NON-NLS-1$
		if ((typeName == null) == (path == null)) {
			return McpToolResult.error("Give exactly one of 'typeName' and 'path'."); //$NON-NLS-1$
		}
		Budget budget = new Budget(args.getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 5000)); //$NON-NLS-1$
		boolean javadoc = args.getBoolean("javadoc", false); //$NON-NLS-1$

		List<IType> types;
		try {
			if (path != null) {
				types = topLevelTypes(path, monitor);
			} else {
				List<IJavaProject> projects = JavaModelSupport.javaProjects(args.getString("project")); //$NON-NLS-1$
				if (projects.isEmpty()) {
					return McpToolResult.error("The workspace contains no open Java project."); //$NON-NLS-1$
				}
				types = List.of(JavaModelSupport.resolveType(typeName, projects, monitor));
			}
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}

		JsonArray outlines = new JsonArray();
		boolean sourceAvailable = false;
		for (IType type : types) {
			try {
				String source = type.getTypeRoot().getSource();
				SourceText text = source == null ? null : SourceText.of(source);
				sourceAvailable |= text != null;
				outlines.add(outline(type, text, javadoc, budget));
			} catch (JavaModelException e) {
				throw new McpToolException("Could not read the outline of " + JavaModelSupport.describe(type), e); //$NON-NLS-1$
			}
		}
		return McpToolResult.of(new JsonObject().put("types", outlines) //$NON-NLS-1$
				.put("sourceAvailable", sourceAvailable) //$NON-NLS-1$
				.put("total", budget.total) //$NON-NLS-1$
				.put("truncated", budget.total > budget.max) //$NON-NLS-1$
				.toString());
	}

	private static List<IType> topLevelTypes(String path, IProgressMonitor monitor)
			throws ToolInputException, McpToolException {
		ICompilationUnit unit = JavaModelSupport.compilationUnit(path, monitor);
		JavaModelSupport.refreshSource(unit, monitor);
		IType[] types;
		try {
			types = unit.getTypes();
		} catch (JavaModelException e) {
			throw new McpToolException("Could not read the types of " + path, e); //$NON-NLS-1$
		}
		if (types.length == 0) {
			throw new ToolInputException("'%s' declares no type.".formatted(path)); //$NON-NLS-1$
		}
		return List.of(types);
	}

	private static JsonObject outline(IType type, SourceText text, boolean javadoc, Budget budget)
			throws JavaModelException {
		JsonObject result = new JsonObject().put("type", type.getFullyQualifiedName('.')) //$NON-NLS-1$
				.put("kind", kindOf(type)) //$NON-NLS-1$
				.put("modifiers", modifiers(type)); //$NON-NLS-1$
		if (type.getDeclaringType() == null) {
			result.put("binary", type.isBinary()) //$NON-NLS-1$
					.put("path", type.getPath() == null ? null : type.getPath().toString()); //$NON-NLS-1$
		}
		String typeParameters = typeParameters(type.getTypeParameters());
		if (!typeParameters.isEmpty()) {
			result.put("typeParameters", typeParameters); //$NON-NLS-1$
		}
		String superclass = type.getSuperclassName();
		if (superclass != null && !type.isEnum() && !isRecord(type) && !"java.lang.Object".equals(superclass)) { //$NON-NLS-1$
			result.put("superclass", JavaModelSupport.simpleTypeName(superclass)); //$NON-NLS-1$
		}
		String[] interfaces = type.getSuperInterfaceNames();
		if (interfaces.length > 0 && !type.isAnnotation()) {
			JsonArray names = new JsonArray();
			for (String name : interfaces) {
				names.add(JavaModelSupport.simpleTypeName(name));
			}
			result.put(type.isInterface() ? "extends" : "implements", names); //$NON-NLS-1$ //$NON-NLS-2$
		}
		lines(result, type, text);
		if (javadoc) {
			result.put("javadoc", summary(type, text)); //$NON-NLS-1$
		}
		JsonArray members = new JsonArray();
		for (IJavaElement child : type.getChildren()) {
			if (!(child instanceof IMember member) || hidden(member)) {
				continue;
			}
			if (!budget.take()) {
				if (member instanceof IType nested) {
					countMembers(nested, budget);
				}
				continue;
			}
			members.add(member instanceof IType nested ? outline(nested, text, javadoc, budget)
					: entry(member, text, javadoc));
		}
		return result.put("members", members); //$NON-NLS-1$
	}

	private static void countMembers(IType type, Budget budget) throws JavaModelException {
		for (IJavaElement child : type.getChildren()) {
			if (child instanceof IMember member && !hidden(member)) {
				budget.take();
				if (member instanceof IType nested) {
					countMembers(nested, budget);
				}
			}
		}
	}

	private static JsonObject entry(IMember member, SourceText text, boolean javadoc) throws JavaModelException {
		JsonObject entry = new JsonObject().put("kind", kindOf(member)) //$NON-NLS-1$
				.put("name", member instanceof IInitializer ? null : member.getElementName()) //$NON-NLS-1$
				.put("signature", signature(member)) //$NON-NLS-1$
				.put("modifiers", modifiers(member)); //$NON-NLS-1$
		lines(entry, member, text);
		if (javadoc) {
			entry.put("javadoc", summary(member, text)); //$NON-NLS-1$
		}
		return entry;
	}

	/**
	 * Anonymous, local and lambda types belong to a body, and a class file also carries the compiler's own members
	 * marked synthetic, such as {@code lambda$0}, neither of which is part of the declaration.
	 */
	private static boolean hidden(IMember member) throws JavaModelException {
		if (member instanceof IType type) {
			return type.isAnonymous() || type.isLocal() || type.isLambda();
		}
		return member.isBinary() && Flags.isSynthetic(member.getFlags());
	}

	private static String kindOf(IMember member) throws JavaModelException {
		return switch (member) {
		case IType type when type.isAnnotation() -> "annotation"; //$NON-NLS-1$
		case IType type when type.isEnum() -> "enum"; //$NON-NLS-1$
		case IType type when isRecord(type) -> "record"; //$NON-NLS-1$
		case IType type when type.isInterface() -> "interface"; //$NON-NLS-1$
		case IType type -> "class"; //$NON-NLS-1$
		case IField field when field.isEnumConstant() -> "enumConstant"; //$NON-NLS-1$
		case IField field when field.isRecordComponent() -> "recordComponent"; //$NON-NLS-1$
		case IField field -> "field"; //$NON-NLS-1$
		case IMethod method when method.isConstructor() -> "constructor"; //$NON-NLS-1$
		case IMethod method -> "method"; //$NON-NLS-1$
		case IInitializer initializer -> "initializer"; //$NON-NLS-1$
		default -> "member"; //$NON-NLS-1$
		};
	}

	private static String signature(IMember member) throws JavaModelException {
		return switch (member) {
		case IField field -> field.isEnumConstant() ? field.getElementName()
				: JavaModelSupport.simpleTypeNameOf(field.getTypeSignature()) + ' ' + field.getElementName();
		case IMethod method -> methodSignature(method);
		default -> null;
		};
	}

	private static String methodSignature(IMethod method) throws JavaModelException {
		StringBuilder signature = new StringBuilder();
		String typeParameters = typeParameters(method.getTypeParameters());
		if (!typeParameters.isEmpty()) {
			signature.append(typeParameters).append(' ');
		}
		if (!method.isConstructor()) {
			signature.append(JavaModelSupport.simpleTypeNameOf(method.getReturnType())).append(' ');
		}
		signature.append(method.getElementName());
		String[] parameterTypes = method.getParameterTypes();
		String[] parameterNames = parameterNames(method);
		StringJoiner parameters = new StringJoiner(", ", "(", ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		for (int i = 0; i < parameterTypes.length; i++) {
			String parameter = JavaModelSupport.simpleTypeNameOf(parameterTypes[i]);
			if (i == parameterTypes.length - 1 && Flags.isVarargs(method.getFlags()) && parameter.endsWith("[]")) { //$NON-NLS-1$
				parameter = parameter.substring(0, parameter.length() - 2) + "..."; //$NON-NLS-1$
			}
			parameters.add(parameterNames == null ? parameter : parameter + ' ' + parameterNames[i]);
		}
		signature.append(parameters);
		String[] exceptions = method.getExceptionTypes();
		if (exceptions.length > 0) {
			StringJoiner throwsClause = new StringJoiner(", ", " throws ", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			for (String exception : exceptions) {
				throwsClause.add(JavaModelSupport.simpleTypeNameOf(exception));
			}
			signature.append(throwsClause);
		}
		return signature.toString();
	}

	/** Type parameters with their bounds, e.g. {@code <K, V extends Comparable<V>>}, or empty. */
	private static String typeParameters(ITypeParameter[] typeParameters) throws JavaModelException {
		if (typeParameters.length == 0) {
			return ""; //$NON-NLS-1$
		}
		StringJoiner joiner = new StringJoiner(", ", "<", ">"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		for (ITypeParameter typeParameter : typeParameters) {
			String[] bounds = typeParameter.getBounds();
			joiner.add(bounds.length == 0 ? typeParameter.getElementName()
					: typeParameter.getElementName() + " extends " + String.join(" & ", //$NON-NLS-1$ //$NON-NLS-2$
							Arrays.stream(bounds).map(JavaModelSupport::simpleTypeName).toList()));
		}
		return joiner.toString();
	}

	private static String[] parameterNames(IMethod method) {
		try {
			String[] names = method.getParameterNames();
			return names.length == method.getNumberOfParameters() ? names : null;
		} catch (JavaModelException e) {
			return null;
		}
	}

	/**
	 * The modifiers as written: class file bits that only share a value with a modifier are cleared, and so are
	 * the modifiers a class file spells out but source leaves implicit, so binary and source types agree.
	 */
	private static String modifiers(IMember member) throws JavaModelException {
		int flags = member.getFlags();
		switch (member) {
		case IType type -> {
			flags &= ~(Flags.AccInterface | Flags.AccAnnotation | Flags.AccEnum | Flags.AccRecord);
			if (type.isBinary()) {
				flags &= ~ACC_SUPER;
				if (type.isInterface()) {
					flags &= ~Flags.AccAbstract;
				}
				if (type.isEnum() || isRecord(type)) {
					// an enum whose constants have bodies is abstract instead of final
					flags &= ~(Flags.AccFinal | Flags.AccAbstract);
				}
				if (type.getDeclaringType() != null && (type.isEnum() || isRecord(type) || type.isInterface())) {
					flags &= ~Flags.AccStatic;
				}
			}
		}
		case IMethod method -> {
			flags &= ~METHOD_BITS;
			if (method.isBinary() && declaredInInterface(method)) {
				if (!Flags.isAbstract(flags) && !Flags.isStatic(flags) && !Flags.isPrivate(flags)) {
					flags |= Flags.AccDefaultMethod;
				}
				flags &= ~(Flags.AccPublic | Flags.AccAbstract);
			}
		}
		case IField field -> {
			if (field.isEnumConstant() || field.isRecordComponent()) {
				return ""; //$NON-NLS-1$
			}
			if (field.isBinary() && declaredInInterface(field)) {
				flags &= ~(Flags.AccPublic | Flags.AccStatic | Flags.AccFinal);
			}
		}
		default -> {
			// an initializer is static or not, which its flags say as they are
		}
		}
		return Flags.toString(flags);
	}

	/** A record, also one read from a class file, which JDT's model does not always mark as one. */
	private static boolean isRecord(IType type) throws JavaModelException {
		return type.isRecord() || type.isBinary() && "java.lang.Record".equals(type.getSuperclassName()); //$NON-NLS-1$
	}

	private static boolean declaredInInterface(IMember member) throws JavaModelException {
		IType declaring = member.getDeclaringType();
		return declaring != null && declaring.isInterface();
	}

	private static void lines(JsonObject target, IMember member, SourceText text) throws JavaModelException {
		ISourceRange range = text == null ? null : member.getSourceRange();
		if (range == null || range.getOffset() < 0) {
			target.put("startLine", null).put("endLine", null); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}
		int[] lines = JavaModelSupport.linesOf(range, text);
		target.put("startLine", lines[0]).put("endLine", lines[1]); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static String summary(IMember member, SourceText text) throws JavaModelException {
		ISourceRange range = text == null ? null : member.getJavadocRange();
		if (range == null || range.getOffset() < 0) {
			return null;
		}
		String content = text.content();
		return JavadocSummary.of(content.substring(range.getOffset(),
				Math.min(content.length(), range.getOffset() + range.getLength())));
	}

	/** Counts every member it is asked for and admits the first {@code max}. */
	private static final class Budget {

		final int max;

		int total;

		Budget(int max) {
			this.max = max;
		}

		boolean take() {
			return ++total <= max;
		}
	}
}
