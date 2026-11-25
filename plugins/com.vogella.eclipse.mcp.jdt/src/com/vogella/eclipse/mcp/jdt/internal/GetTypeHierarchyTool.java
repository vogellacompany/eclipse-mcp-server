package com.vogella.eclipse.mcp.jdt.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the supertypes and subtypes of a Java type as known to JDT.
 */
public final class GetTypeHierarchyTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 200;

	private static final Set<String> DIRECTIONS = Set.of("supertypes", "subtypes", "both"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	@Override
	public String getName() {
		return "eclipse_get_type_hierarchy"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Returns the supertypes and subtypes of a Java type as known to JDT, including types from the classpath. The full lists are sorted names; superclass, interfaces and directSubtypes give the immediate neighbours, which is the shape of the tree without paying for all of it, and supertypeTotal and subtypeTotal count what maxResults cut. To find the classes that can actually run behind an interface or an abstract method, use eclipse_find_implementations."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["typeName"],
				  "properties": {
				    "typeName":   {"type":"string","description":"Fully qualified type name, e.g. org.eclipse.jface.viewers.TreeViewer"},
				    "project":    {"type":"string","description":"Optional project used to resolve the type and to scope the hierarchy. Defaults to the whole workspace."},
				    "direction":  {"type":"string","enum":["supertypes","subtypes","both"],"default":"both"},
				    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000}
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
				    "type":           {"type":"string"},
				    "superclass":     {"type":["string","null"]},
				    "interfaces":     {"type":"array","items":{"type":"string"}},
				    "supertypes":     {"type":"array","items":{"type":"string"}},
				    "supertypeTotal": {"type":"integer"},
				    "directSubtypes": {"type":"array","items":{"type":"string"}},
				    "subtypes":       {"type":"array","items":{"type":"string"}},
				    "subtypeTotal":   {"type":"integer"},
				    "truncated":      {"type":"boolean"}
				  },
				  "required": ["type","truncated"]
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String typeName = args.getString("typeName"); //$NON-NLS-1$
		if (typeName == null) {
			return McpToolResult.error("The argument 'typeName' is required."); //$NON-NLS-1$
		}
		String direction = args.getString("direction", "both"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!DIRECTIONS.contains(direction)) {
			return McpToolResult.error("Unknown direction '%s', expected one of supertypes, subtypes, both." //$NON-NLS-1$
					.formatted(direction));
		}
		String projectName = args.getString("project"); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 2000); //$NON-NLS-1$
		boolean wantSupertypes = !"subtypes".equals(direction); //$NON-NLS-1$
		boolean wantSubtypes = !"supertypes".equals(direction); //$NON-NLS-1$

		IType type;
		List<IJavaProject> projects;
		try {
			projects = JavaModelSupport.javaProjects(projectName);
			if (projects.isEmpty()) {
				return McpToolResult.error("The workspace contains no open Java project."); //$NON-NLS-1$
			}
			type = JavaModelSupport.findType(typeName, projects, monitor);
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}

		JsonObject result = new JsonObject().put("type", type.getFullyQualifiedName()); //$NON-NLS-1$
		boolean truncated = false;
		try {
			// the subtype direction is the expensive one, so only build it when asked for
			ITypeHierarchy hierarchy = wantSubtypes ? newHierarchy(type, projectName, projects, monitor)
					: type.newSupertypeHierarchy(monitor);
			if (wantSupertypes) {
				IType superclass = hierarchy.getSuperclass(type);
				result.put("superclass", superclass == null ? null : superclass.getFullyQualifiedName()); //$NON-NLS-1$
				List<String> interfaces = names(hierarchy.getSuperInterfaces(type));
				result.put("interfaces", toArray(interfaces, maxResults)); //$NON-NLS-1$
				truncated |= interfaces.size() > maxResults;
				List<String> all = names(hierarchy.getAllSupertypes(type));
				result.put("supertypes", toArray(all, maxResults)); //$NON-NLS-1$
				result.put("supertypeTotal", all.size()); //$NON-NLS-1$
				truncated |= all.size() > maxResults;
			}
			if (wantSubtypes) {
				List<String> direct = names(hierarchy.getSubtypes(type));
				result.put("directSubtypes", toArray(direct, maxResults)); //$NON-NLS-1$
				List<String> all = names(hierarchy.getAllSubtypes(type));
				result.put("subtypes", toArray(all, maxResults)); //$NON-NLS-1$
				result.put("subtypeTotal", all.size()); //$NON-NLS-1$
				truncated |= all.size() > maxResults;
			}
		} catch (JavaModelException e) {
			throw new McpToolException("Could not compute the type hierarchy of " + type.getFullyQualifiedName(), e); //$NON-NLS-1$
		}
		result.put("truncated", truncated); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	private static ITypeHierarchy newHierarchy(IType type, String projectName, List<IJavaProject> projects,
			IProgressMonitor monitor) throws JavaModelException {
		return projectName == null ? type.newTypeHierarchy(monitor) : type.newTypeHierarchy(projects.get(0), monitor);
	}

	private static List<String> names(IType[] types) {
		return Arrays.stream(types).map(IType::getFullyQualifiedName).distinct().sorted().toList();
	}

	private static JsonArray toArray(List<String> names, int maxResults) {
		JsonArray array = new JsonArray();
		names.stream().limit(maxResults).forEach(array::add);
		return array;
	}
}
