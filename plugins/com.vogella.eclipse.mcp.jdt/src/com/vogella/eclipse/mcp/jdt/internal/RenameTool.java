package com.vogella.eclipse.mcp.jdt.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusEntry;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Renames a Java element through the JDT refactoring engine.
 */
public final class RenameTool implements IMcpTool {

	private static final List<String> KINDS = List.of("auto", "type", "method", "field", "package", "compilationUnit"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

	@Override
	public String getName() {
		return "eclipse_rename"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Renames a Java type, method, field, package or compilation unit through the JDT refactoring engine, updating every reference across the workspace. MODIFIES SOURCE FILES, and a rename can touch hundreds. Runs as a dry run unless dryRun is set to false, reporting the files that would change. Because it goes through the refactoring engine rather than editing text, overrides and implementations follow, and the participants that update non-Java references such as plugin.xml class attributes fire. A rename whose preconditions fail is refused with the reason rather than half applied."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["typeName","newName"],
				  "properties": {
				    "typeName":    {"type":"string","description":"Fully qualified type name. With memberName, the type declaring it. For a package rename, the package name."},
				    "memberName":  {"type":"string","description":"Method or field to rename. Omit to rename the type itself. A method name must be unambiguous: if it is overloaded the rename is refused."},
				    "newName":     {"type":"string","description":"The new simple name."},
				    "project":     {"type":"string","description":"Project used to resolve the element and to scope the rename. Defaults to the whole workspace."},
				    "kind":        {"type":"string","enum":["auto","type","method","field","package","compilationUnit"],"default":"auto","description":"What to rename. 'auto' infers it from the arguments."},
				    "updateReferences":      {"type":"boolean","default":true,"description":"Update the references too. With false only the declaration is renamed, which usually breaks compilation."},
				    "updateQualifiedNames":  {"type":"boolean","default":false,"description":"Also update fully qualified names in non-Java files, matched textually."},
				    "renameGettersAndSetters": {"type":"boolean","default":false,"description":"For a field, also rename its getter and setter."},
				    "dryRun":      {"type":"boolean","default":true,"description":"Report the affected files without writing anything."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		// the rename processors read jdt.ui preferences, which are unregistered headless
		if (JavaManipulation.getPreferenceNodeId() == null) {
			JavaManipulation.setPreferenceNodeId("org.eclipse.jdt.ui"); //$NON-NLS-1$
		}
		ToolArguments args = ToolArguments.of(arguments);
		String typeName = args.getString("typeName"); //$NON-NLS-1$
		String newName = args.getString("newName"); //$NON-NLS-1$
		if (typeName == null || newName == null) {
			return McpToolResult.error("The arguments 'typeName' and 'newName' are both required."); //$NON-NLS-1$
		}
		String memberName = args.getString("memberName"); //$NON-NLS-1$
		String projectName = args.getString("project"); //$NON-NLS-1$
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$

		IJavaElement element;
		try {
			List<IJavaProject> projects = JavaModelSupport.javaProjects(projectName);
			if (projects.isEmpty()) {
				return McpToolResult.error("The workspace contains no open Java project."); //$NON-NLS-1$
			}
			JavaModelSupport.refresh(projectName, monitor);
			element = resolve(typeName, memberName, args.getString("kind", "auto"), projects); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}
		// the refactoring engine dereferences a binary type's missing compilation unit
		String binary = binaryReason(element);
		if (binary != null) {
			return McpToolResult.error(binary);
		}
		String refactoringId = refactoringIdOf(element);
		if (refactoringId == null) {
			return McpToolResult.error("Renaming %s is not supported.".formatted(element.getClass().getSimpleName())); //$NON-NLS-1$
		}

		RenameJavaElementDescriptor descriptor = (RenameJavaElementDescriptor) RefactoringCore
				.getRefactoringContribution(refactoringId).createDescriptor();
		descriptor.setJavaElement(element);
		descriptor.setNewName(newName);
		descriptor.setUpdateReferences(args.getBoolean("updateReferences", true)); //$NON-NLS-1$
		descriptor.setUpdateQualifiedNames(args.getBoolean("updateQualifiedNames", false)); //$NON-NLS-1$
		if (element instanceof IField) {
			boolean accessors = args.getBoolean("renameGettersAndSetters", false); //$NON-NLS-1$
			descriptor.setRenameGetters(accessors);
			descriptor.setRenameSetters(accessors);
		}
		if (projectName != null) {
			descriptor.setProject(projectName);
		}

		try {
			RefactoringStatus status = new RefactoringStatus();
			Refactoring refactoring = descriptor.createRefactoring(status);
			if (refactoring == null) {
				return McpToolResult.error("The refactoring could not be created: " + describe(status)); //$NON-NLS-1$
			}
			// conditions first: a rename that would break compilation must not be applied
			status.merge(refactoring.checkAllConditions(monitor));
			if (status.hasError()) {
				return McpToolResult.error("Refused: %s".formatted(describe(status))); //$NON-NLS-1$
			}

			Change change = refactoring.createChange(monitor);
			JsonObject result = new JsonObject().put("element", JavaModelSupport.describe(element)) //$NON-NLS-1$
					.put("newName", newName) //$NON-NLS-1$
					.put("refactoring", refactoringId) //$NON-NLS-1$
					.put("dryRun", dryRun) //$NON-NLS-1$
					.put("warnings", warnings(status)); //$NON-NLS-1$
			Set<String> files = new LinkedHashSet<>();
			collectFiles(change, files);
			JsonArray affected = new JsonArray();
			files.forEach(affected::add);
			result.put("affectedFiles", affected).put("affectedFileCount", files.size()); //$NON-NLS-1$ //$NON-NLS-2$

			if (dryRun) {
				return McpToolResult.of(result.put("applied", Boolean.FALSE).toString()); //$NON-NLS-1$
			}
			// without this the change refuses with "has not been initialialized"
			IProgressMonitor progress = monitor;
			change.initializeValidationData(progress);
			PerformChangeOperation operation = new PerformChangeOperation(change);
			ResourcesPlugin.getWorkspace().run(operation, progress);
			if (!operation.changeExecuted()) {
				return McpToolResult.error("The rename was not applied: %s".formatted(operation.getValidationStatus())); //$NON-NLS-1$
			}
			return McpToolResult.of(result.put("applied", Boolean.TRUE).toString()); //$NON-NLS-1$
		} catch (CoreException e) {
			throw new McpToolException("The rename failed", e); //$NON-NLS-1$
		}
	}

	/**
	 * Collects the files a change touches, walking composites.
	 */
	private static void collectFiles(Change change, Set<String> into) {
		if (change == null) {
			return;
		}
		Object modified = change.getModifiedElement();
		if (modified instanceof IResource resource) {
			into.add(resource.getFullPath().toString());
		} else if (modified instanceof IJavaElement element && element.getResource() != null) {
			into.add(element.getResource().getFullPath().toString());
		}
		if (change instanceof CompositeChange composite) {
			for (Change child : composite.getChildren()) {
				collectFiles(child, into);
			}
		}
	}

	private static IJavaElement resolve(String typeName, String memberName, String kind, List<IJavaProject> projects)
			throws ToolInputException, McpToolException {
		if (!KINDS.contains(kind)) {
			throw new ToolInputException("Unknown kind '%s', expected one of %s.".formatted(kind, String.join(", ", KINDS))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if ("package".equals(kind)) { //$NON-NLS-1$
			for (IJavaProject project : projects) {
				try {
					for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
						IPackageFragment fragment = root.getPackageFragment(typeName);
						if (fragment != null && fragment.exists() && !fragment.isReadOnly()) {
							return fragment;
						}
					}
				} catch (CoreException e) {
					throw new McpToolException("Could not read the package fragments of " + project.getElementName(), e); //$NON-NLS-1$
				}
			}
			throw new ToolInputException("No source package named '%s' in the workspace.".formatted(typeName));
		}
		IType type = JavaModelSupport.findType(typeName, projects);
		if (memberName == null) {
			if ("method".equals(kind) || "field".equals(kind)) { //$NON-NLS-1$ //$NON-NLS-2$
				throw new ToolInputException("kind '%s' needs a memberName.".formatted(kind)); //$NON-NLS-1$
			}
			return "compilationUnit".equals(kind) ? type.getCompilationUnit() : type; //$NON-NLS-1$
		}
		List<IMember> members = JavaModelSupport.findMembers(type, memberName);
		if (members.size() > 1) {
			throw new ToolInputException(
					"'%s#%s' is ambiguous, it resolves to %d members. A rename has to name exactly one, so overloaded methods cannot be renamed through this tool."
							.formatted(typeName, memberName, members.size()));
		}
		IMember member = members.get(0);
		if ("method".equals(kind) && !(member instanceof IMethod) || "field".equals(kind) && !(member instanceof IField) //$NON-NLS-1$ //$NON-NLS-2$
				|| "type".equals(kind)) { //$NON-NLS-1$
			throw new ToolInputException("'%s#%s' is not a %s.".formatted(typeName, memberName, kind)); //$NON-NLS-1$
		}
		return member;
	}

	/** Returns why the element cannot be renamed because it is compiled, or {@code null}. */
	private static String binaryReason(IJavaElement element) {
		IType type = element instanceof IType declared ? declared
				: (IType) element.getAncestor(IJavaElement.TYPE);
		if (type == null || !type.isBinary()) {
			return null;
		}
		// getElementName, never toString: a package fragment root prints every package it contains
		IJavaElement root = type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
		return "'%s' resolved to a binary type in %s, and renaming needs source. This usually means the name was found in build output rather than in a source project; pass 'project' to scope the resolution."
				.formatted(type.getFullyQualifiedName(), root == null ? "a library" : root.getElementName());
	}

	private static String refactoringIdOf(IJavaElement element) {
		if (element instanceof IType) {
			return IJavaRefactorings.RENAME_TYPE;
		}
		if (element instanceof IMethod) {
			return IJavaRefactorings.RENAME_METHOD;
		}
		if (element instanceof IField) {
			return IJavaRefactorings.RENAME_FIELD;
		}
		if (element instanceof IPackageFragment) {
			return IJavaRefactorings.RENAME_PACKAGE;
		}
		if (element instanceof ICompilationUnit) {
			return IJavaRefactorings.RENAME_COMPILATION_UNIT;
		}
		return null;
	}

	private static JsonArray warnings(RefactoringStatus status) {
		JsonArray warnings = new JsonArray();
		for (RefactoringStatusEntry entry : status.getEntries()) {
			if (entry.isWarning() || entry.isInfo()) {
				warnings.add(entry.getMessage());
			}
		}
		return warnings;
	}

	private static String describe(RefactoringStatus status) {
		StringBuilder text = new StringBuilder();
		for (RefactoringStatusEntry entry : status.getEntries()) {
			if (entry.isFatalError() || entry.isError()) {
				if (text.length() > 0) {
					text.append("; "); //$NON-NLS-1$
				}
				text.append(entry.getMessage());
			}
		}
		return text.length() == 0 ? status.toString() : text.toString();
	}
}
