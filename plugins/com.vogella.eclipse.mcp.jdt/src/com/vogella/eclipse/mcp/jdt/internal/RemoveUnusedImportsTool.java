package com.vogella.eclipse.mcp.jdt.internal;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportContainer;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Removes exactly the imports the compiler reports as unused.
 */
public final class RemoveUnusedImportsTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_remove_unused_imports"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Removes the imports the compiler reports as unused, and nothing else. MODIFIES SOURCE FILES, and runs as a dry run unless dryRun is set to false. Different from eclipse_organize_imports, which also sorts and regroups: on a file where one import is dead that rewrites the whole import block and the diff hides the change among lines nobody meant to touch. It removes what JDT's own compiler flagged, so the project's settings decide what counts, including whether a reference from javadoc keeps an import alive; a remover that does not consult the compiler gets that wrong and leaves 'Javadoc: X cannot be resolved' behind. The markers are only as current as the last build, so it builds first unless told not to: deleting an import the compiler flagged before your last edit would remove one that is now in use."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "path":    {"type":"string","description":"Workspace path of one Java file."},
				    "project": {"type":"string","description":"Every file in this project that has an unused import. Use instead of 'path'."},
				    "dryRun":  {"type":"boolean","default":true},
				    "build":   {"type":"boolean","default":true,"description":"Build first so the markers are current. Set false only when you know nothing has changed since the last build."},
				    "maxResults": {"type":"integer","default":500,"minimum":1,"maximum":5000}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path"); //$NON-NLS-1$
		String projectName = args.getString("project"); //$NON-NLS-1$
		if (path == null && projectName == null) {
			return McpToolResult.error("Give 'path' for one file or 'project' for all of them."); //$NON-NLS-1$
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		boolean build = args.getBoolean("build", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 500, 1, 5000); //$NON-NLS-1$

		IResource scope;
		IProject project;
		if (path != null) {
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(IPath.fromPortableString(path));
			if (!file.exists()) {
				return McpToolResult.error("No file at the workspace path '%s'.".formatted(path)); //$NON-NLS-1$
			}
			scope = file;
			project = file.getProject();
		} else {
			project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (!project.isAccessible()) {
				return McpToolResult.error("No open project named '%s' in this workspace.".formatted(projectName)); //$NON-NLS-1$
			}
			scope = project;
		}

		try {
			WorkspaceSync.refresh(scope, monitor);
			if (build) {
				// stale markers would remove an import a new reference now needs
				WorkspaceSync.build(project, monitor);
			}
		} catch (CoreException e) {
			throw new McpToolException("Could not refresh and build before reading the markers", e); //$NON-NLS-1$
		}

		Map<ICompilationUnit, Set<String>> unused = new LinkedHashMap<>();
		try {
			for (IMarker marker : scope.findMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, true,
					IResource.DEPTH_INFINITE)) {
				if (marker.getAttribute(IJavaModelMarker.ID, 0) != IProblem.UnusedImport) {
					continue;
				}
				if (!(marker.getResource() instanceof IFile file)
						|| !(JavaCore.create(file) instanceof ICompilationUnit unit)) {
					continue;
				}
				String name = importAt(unit, marker.getAttribute(IMarker.CHAR_START, -1));
				if (name != null) {
					unused.computeIfAbsent(unit, key -> new LinkedHashSet<>()).add(name);
				}
			}
		} catch (CoreException e) {
			throw new McpToolException("Could not read the problem markers", e); //$NON-NLS-1$
		}

		JsonArray files = new JsonArray();
		int total = 0;
		int removed = 0;
		for (Map.Entry<ICompilationUnit, Set<String>> entry : unused.entrySet()) {
			total += entry.getValue().size();
			JsonArray imports = new JsonArray();
			entry.getValue().forEach(imports::add);
			JsonObject file = new JsonObject()
					.put("file", entry.getKey().getResource() == null ? entry.getKey().getElementName() //$NON-NLS-1$
							: entry.getKey().getResource().getFullPath().toString())
					.put("unusedImports", imports); //$NON-NLS-1$
			if (!dryRun) {
				try {
					for (String name : entry.getValue()) {
						IImportDeclaration declaration = entry.getKey().getImport(name);
						if (declaration.exists()) {
							// one at a time keeps the rest of the block in order, unlike organize_imports
							declaration.delete(false, monitor);
							removed++;
						}
					}
					entry.getKey().save(monitor, false);
				} catch (JavaModelException e) {
					file.put("error", String.valueOf(e.getMessage())); //$NON-NLS-1$
				}
			}
			if (files.size() < maxResults) {
				files.add(file);
			}
		}

		JsonObject result = new JsonObject().put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("built", Boolean.valueOf(build)) //$NON-NLS-1$
				.put("files", Integer.valueOf(unused.size())) //$NON-NLS-1$
				.put("unusedImports", Integer.valueOf(total)) //$NON-NLS-1$
				.put("removed", Integer.valueOf(removed)) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(unused.size() > files.size())) //$NON-NLS-1$
				.put("details", files); //$NON-NLS-1$
		if (dryRun) {
			result.put("note", "Nothing was changed. Pass dryRun false to remove them."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return McpToolResult.of(result.toString());
	}

	/** The import declaration covering an offset, by its source range. */
	private static String importAt(ICompilationUnit unit, int offset) throws CoreException {
		if (offset < 0) {
			return null;
		}
		for (IJavaElement element : unit.getChildren()) {
			if (!(element instanceof IImportContainer container)) {
				continue;
			}
			for (IJavaElement child : container.getChildren()) {
				if (child instanceof IImportDeclaration declaration) {
					ISourceRange range = declaration.getSourceRange();
					if (range != null && offset >= range.getOffset()
							&& offset < range.getOffset() + range.getLength()) {
						return declaration.getElementName();
					}
				}
			}
		}
		return null;
	}
}
