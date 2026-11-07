package com.vogella.eclipse.mcp.jdt.internal;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Returns the source of a type or member, including from source attachments on the
 * classpath.
 */
public final class GetSourceTool implements IMcpTool {

	private static final int DEFAULT_MAX_LENGTH = 40000;

	@Override
	public String getName() {
		return "eclipse_get_source"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Returns the Java source and Javadoc of a type or of its members, resolved through the project classpath. Works for types in libraries too, as long as a source attachment exists, so use this instead of guessing the API of a dependency. For a large type, read eclipse_get_outline first and fetch only the members you need with memberName and paramTypes. Each element reports line and endLine."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["typeName"],
				  "properties": {
				    "typeName":   {"type":"string","description":"Fully qualified type name, e.g. org.eclipse.jface.viewers.TreeViewer; a member type is Outer.Inner or Outer$Inner."},
				    "memberName": {"type":"string","description":"Optional method, field or member type name. Omit to get the source of the whole type. All overloads of a method name are returned unless paramTypes is given."},
				    "paramTypes": {"type":"array","items":{"type":"string"},"description":"Optional with memberName: the parameter types of the one overload to return, e.g. [String, int], [] for none. Matched by simple name with generics erased, so Map.Entry, Map$Entry and Entry are the same, and a qualified name such as java.sql.Date must match the package too; a type variable is matched by its name, e.g. T. When none or several match, the error lists the overloads."},
				    "project":    {"type":"string","description":"Optional project used to resolve the type. Defaults to the whole workspace."},
				    "maxLength":  {"type":"integer","default":40000,"minimum":100,"maximum":200000,"description":"Maximum number of characters per returned element."}
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
		int maxLength = args.getInt("maxLength", DEFAULT_MAX_LENGTH, 100, 200000); //$NON-NLS-1$
		List<String> paramTypes;
		try {
			paramTypes = JavaModelSupport.paramTypes(arguments);
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}
		if (paramTypes != null && memberName == null) {
			return McpToolResult.error("'paramTypes' selects an overload and needs 'memberName'."); //$NON-NLS-1$
		}

		IType type;
		List<? extends IMember> members;
		try {
			List<IJavaProject> projects = JavaModelSupport.javaProjects(projectName);
			if (projects.isEmpty()) {
				return McpToolResult.error("The workspace contains no open Java project."); //$NON-NLS-1$
			}
			type = JavaModelSupport.resolveType(typeName, projects, monitor);
			members = memberName == null ? List.of(type) : JavaModelSupport.findMembers(type, memberName);
			if (paramTypes != null) {
				members = overload(type, members, paramTypes);
			}
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}

		JsonArray elements = new JsonArray();
		boolean anySource = false;
		for (IMember member : members) {
			String source = JavaModelSupport.sourceOf(member);
			anySource |= source != null;
			boolean truncated = source != null && source.length() > maxLength;
			elements.add(new JsonObject().put("element", JavaModelSupport.describe(member)) //$NON-NLS-1$
					.put("line", lineOrNull(member)) //$NON-NLS-1$
					.put("endLine", endLineOrNull(member)) //$NON-NLS-1$
					.put("source", truncated ? source.substring(0, maxLength) : source) //$NON-NLS-1$
					.put("truncated", truncated)); //$NON-NLS-1$
		}

		JsonObject result = new JsonObject().put("type", type.getFullyQualifiedName()) //$NON-NLS-1$
				.put("binary", type.isBinary()) //$NON-NLS-1$
				.put("path", type.getPath() == null ? null : type.getPath().toString()) //$NON-NLS-1$
				.put("sourceAvailable", anySource) //$NON-NLS-1$
				.put("elements", elements); //$NON-NLS-1$
		if (!anySource) {
			result.put("hint", //$NON-NLS-1$
					"No source is attached for this type. Attach a sources jar to the classpath entry to read it.");
		}
		return McpToolResult.of(result.toString());
	}

	/** The one method among {@code members} whose parameters are exactly {@code wanted}. */
	private static List<IMember> overload(IType type, List<? extends IMember> members, List<String> wanted)
			throws ToolInputException {
		List<IMethod> methods = members.stream().filter(IMethod.class::isInstance).map(IMethod.class::cast).toList();
		List<IMethod> matches = methods.stream().filter(method -> JavaModelSupport.takesParameters(method, wanted))
				.toList();
		if (matches.size() == 1) {
			return List.of(matches.get(0));
		}
		String call = "%s(%s)".formatted(members.get(0).getElementName(), String.join(", ", wanted)); //$NON-NLS-1$ //$NON-NLS-2$
		if (matches.isEmpty()) {
			throw new ToolInputException("%s has no %s. Overloads: %s".formatted(type.getFullyQualifiedName(), call, //$NON-NLS-1$
					methods.isEmpty() ? "none, it is not a method" : signatures(methods))); //$NON-NLS-1$
		}
		throw new ToolInputException("%s matches %d overloads of %s, qualify the parameter types: %s".formatted(call, //$NON-NLS-1$
				Integer.valueOf(matches.size()), type.getFullyQualifiedName(), qualifiedSignatures(matches)));
	}

	/** The overloads by simple parameter names, or qualified where two would read the same. */
	private static String signatures(List<IMethod> methods) {
		List<String> simple = methods.stream().map(JavaModelSupport::signatureOf).toList();
		return simple.stream().distinct().count() == simple.size() ? String.join(", ", simple) //$NON-NLS-1$
				: qualifiedSignatures(methods);
	}

	private static String qualifiedSignatures(List<IMethod> methods) {
		StringJoiner all = new StringJoiner(", "); //$NON-NLS-1$
		for (IMethod method : methods) {
			StringJoiner parameters = new StringJoiner(", ", method.getElementName() + '(', ")"); //$NON-NLS-1$ //$NON-NLS-2$
			for (String parameter : method.getParameterTypes()) {
				String erased = Signature.getTypeErasure(parameter);
				String qualified = JavaModelSupport.qualifiedTypeNameOf(method, erased);
				parameters.add(qualified == null ? JavaModelSupport.simpleTypeNameOf(erased) : qualified);
			}
			all.add(parameters.toString());
		}
		return all.toString();
	}

	private static Integer endLineOrNull(IMember member) {
		int[] lines = JavaModelSupport.linesOf(member);
		return lines == null ? null : Integer.valueOf(lines[1]);
	}

	private static Integer lineOrNull(IMember member) {
		int line = JavaModelSupport.lineOf(member);
		return line < 0 ? null : Integer.valueOf(line);
	}
}
