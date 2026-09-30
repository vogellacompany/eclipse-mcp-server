package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.resource.DeleteResourcesDescriptor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Deletes the compilation unit of a Java type, and says what it does not clean up.
 */
public final class DeleteTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_delete"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Deletes a Java type's source file, or with memberName a single field, method or nested type, as the last step of a dead code sweep. A member is removed through the Java model, so its javadoc goes with it rather than being left behind describing something that no longer exists. DELETES A FILE FROM THE WORKSPACE, and runs as a dry run unless dryRun is set to false. It refuses, unless force is passed, when anything still references the type, when a registry position names it, when its package is exported as public API, or when the member carries a framework injection annotation, and it reports all four either way. THE INJECTION CASE IS THE ONE THAT LOOKS SAFEST AND IS NOT: a field annotated @Reference, @Inject, @Autowired and their relatives is written by a framework at runtime and read by no Java, so every reference count and every text search agrees it is dead. Deleting two such @Reference fields, whose only purpose was to order declarative services components, once cost a night and surfaced three subsystems away as a workbench that would not start. A position naming a nested type, Outer$Inner in plugin.xml or a bundleclass URI, counts for the enclosing type too, since deleting the file deletes the nested class, and such evidence carries namedType. It counts an e4 application model as a registry position: a class named by a bundleclass:// URI in a .e4xmi is instantiated by the workbench at every start and referenced from no Java, so it looks dead and is not. READ THIS LIMITATION: the deletion goes through LTK as a resource delete, and PDE's manifest participants are enabled on IType rather than on IResource, so plugin.xml class attributes and Export-Package are NOT updated. Whatever registryEvidence the answer reports is what will be left dangling and has to be fixed by hand. Use eclipse_list_declarations to find candidates and eclipse_find_references to confirm them."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "typeName": {"type":"string","description":"Fully qualified name of the type. Without memberName its whole source file is deleted."},
				    "typeNames": {"type":"array","items":{"type":"string"},"description":"Delete several types in one call. The registry index, which walks every project in the workspace, is then built once for the batch instead of once per type. Each is reported separately with its own refusal; one that cannot be deleted does not stop the others."},
				    "memberName": {"type":"string","description":"A field, method or nested type to delete instead of the file. Its javadoc goes with it. An overloaded method name is refused, since this tool cannot tell which one you mean."},
				    "project":  {"type":"string","description":"Project to resolve the name in. Omit to search every Java project."},
				    "dryRun":   {"type":"boolean","default":true,"description":"Report what would happen and change nothing."},
				    "force":    {"type":"boolean","default":false,"description":"Delete despite references, a registry position, a public API package, or a framework injection annotation."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		List<String> typeNames = new ArrayList<>();
		if (arguments.get("typeNames") instanceof List<?> list) { //$NON-NLS-1$
			list.forEach(value -> typeNames.add(String.valueOf(value).trim()));
		}
		String typeName = args.getString("typeName"); //$NON-NLS-1$
		if (typeName == null && typeNames.isEmpty()) {
			return McpToolResult.error("Give 'typeName', or 'typeNames' to delete several."); //$NON-NLS-1$
		}
		String memberName = args.getString("memberName"); //$NON-NLS-1$
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		boolean force = args.getBoolean("force", false); //$NON-NLS-1$
		IProgressMonitor progress = monitor;

		List<IJavaProject> projects;
		try {
			projects = JavaModelSupport.javaProjects(args.getString("project")); //$NON-NLS-1$
			// the reference count decides whether a deletion is safe, so it must not read a stale index
			JavaModelSupport.refresh(null, progress);
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}
		if (!typeNames.isEmpty()) {
			if (memberName != null) {
				return McpToolResult.error("'memberName' names one member of one type, so it cannot be combined with 'typeNames'."); //$NON-NLS-1$
			}
			// built once for the whole batch: it walks every project in the workspace,
			// which is the cost batching exists to avoid paying per type
			RegistryIndex index = RegistryIndex.build(progress);
			JsonArray reported = new JsonArray();
			for (String name : typeNames) {
				reported.add(deleteOne(name, projects, index, dryRun, force, progress));
			}
			return McpToolResult.of(new JsonObject().put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
					.put("total", Integer.valueOf(typeNames.size())) //$NON-NLS-1$
					.put("results", reported).toString()); //$NON-NLS-1$
		}

		if (memberName != null) {
			IType type;
			try {
				type = JavaModelSupport.findType(typeName, projects, monitor);
			} catch (ToolInputException e) {
				return McpToolResult.error(e.getMessage());
			}
			if (type.isBinary() || type.getCompilationUnit() == null) {
				return McpToolResult.error(
						"'%s' is a binary type, so there is no source file here to delete.".formatted(typeName)); //$NON-NLS-1$
			}
			return deleteMember(type, memberName, dryRun, force, progress);
		}
		return McpToolResult.of(
				deleteOne(typeName, projects, RegistryIndex.build(progress), dryRun, force, progress).toString());
	}

	/**
	 * Deletes one type's file, against an index the caller owns.
	 * <p>
	 * The index is a parameter rather than built here because it walks every project
	 * in the workspace, which a batch must pay once rather than per type.
	 */
	private JsonObject deleteOne(String typeName, List<IJavaProject> projects, RegistryIndex index, boolean dryRun,
			boolean force, IProgressMonitor progress) throws McpToolException {
		IType type;
		try {
			type = JavaModelSupport.findType(typeName, projects, progress);
		} catch (ToolInputException e) {
			return failed(typeName, e.getMessage());
		}
		if (type.isBinary() || type.getCompilationUnit() == null) {
			return failed(typeName, "It is a binary type, so there is no source file here to delete."); //$NON-NLS-1$
		}
		ICompilationUnit unit = type.getCompilationUnit();
		IResource resource = unit.getResource();
		if (!(resource instanceof IFile file) || !file.exists()) {
			return failed(typeName, "It has no file in this workspace."); //$NON-NLS-1$
		}

		JsonObject result = new JsonObject().put("type", type.getFullyQualifiedName()) //$NON-NLS-1$
				.put("file", file.getFullPath().toString()) //$NON-NLS-1$
				.put("dryRun", Boolean.valueOf(dryRun)); //$NON-NLS-1$

		// deleting the file deletes every type in it, which for a unit declaring more
		// than one is not the deletion that was asked for
		List<String> alsoDeclared = new ArrayList<>();
		try {
			for (IType other : unit.getTypes()) {
				if (!other.getElementName().equals(type.getElementName())) {
					alsoDeclared.add(other.getFullyQualifiedName());
				}
			}
		} catch (CoreException e) {
			throw new McpToolException("Could not read " + file.getFullPath(), e); //$NON-NLS-1$
		}
		if (!alsoDeclared.isEmpty()) {
			JsonArray others = new JsonArray();
			alsoDeclared.forEach(others::add);
			return result.put("deleted", Boolean.FALSE).put("alsoDeclaredInThisFile", others) //$NON-NLS-1$ //$NON-NLS-2$
					.put("refusedBecause", //$NON-NLS-1$
							"The file declares other top level types, and deleting it would delete them too. This tool deletes files, not declarations."); //$NON-NLS-1$
		}

		List<RegistryIndex.Evidence> evidence = index.evidenceFor(type.getFullyQualifiedName());
		PackageExports.Export export = new PackageExports().of(type.getJavaProject().getProject(),
				type.getPackageFragment().getElementName());
		int references = countReferences(type, true, progress);

		JsonArray registry = new JsonArray();
		for (RegistryIndex.Evidence one : evidence) {
			registry.add(describe(one));
		}
		// deleting the last type of a package leaves an Export-Package naming a
		// package that no longer exists, which the next build reports against
		// MANIFEST.MF. Saying so lets the caller follow up with eclipse_edit_manifest
		boolean lastInPackage = false;
		try {
			lastInPackage = unit.getParent() instanceof org.eclipse.jdt.core.IPackageFragment fragment
					&& fragment.getCompilationUnits().length <= 1;
		} catch (CoreException e) {
			// a package that cannot be read is simply not reported as emptied
		}
		result.put("references", Integer.valueOf(references)) //$NON-NLS-1$
				.put("registryEvidence", registry) //$NON-NLS-1$
				.put("apiTier", export.tier()) //$NON-NLS-1$
				.put("lastTypeInPackage", Boolean.valueOf(lastInPackage)); //$NON-NLS-1$
		if (lastInPackage && !PackageExports.NOT_EXPORTED.equals(export.tier())
				&& !PackageExports.NOT_A_BUNDLE.equals(export.tier())) {
			result.put("packageBecomesEmpty", //$NON-NLS-1$
					"This is the last type in '%s', which the manifest still exports as %s. After deleting it, remove the export with eclipse_edit_manifest removeExportPackage or the next build reports 'Package does not exist in this plug-in'." //$NON-NLS-1$
							.formatted(type.getPackageFragment().getElementName(), export.tier()));
		}

		List<String> blockers = new ArrayList<>();
		if (references > 0) {
			blockers.add("%d reference(s) to it remain, so deleting it is a compile break rather than a cleanup"
					.formatted(Integer.valueOf(references)));
		}
		if (registry.size() > 0) {
			blockers.add(
					"%d registry position(s) name it, which this tool cannot update and which fail at runtime rather than at compile time"
							.formatted(Integer.valueOf(registry.size())));
		}
		if (PackageExports.PUBLIC.equals(export.tier())) {
			blockers.add(
					"its package is exported as public API, so consumers can exist outside this workspace and no search here can prove otherwise");
		}
		if (!blockers.isEmpty() && !force) {
			return result.put("deleted", Boolean.FALSE) //$NON-NLS-1$
					.put("refusedBecause", String.join("; ", blockers) + ". Pass force to delete it anyway."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (registry.size() > 0) {
			result.put("danglingAfterDelete", //$NON-NLS-1$
					"The registry positions above are NOT updated by this tool and will name a class that no longer exists. Fix them by hand."); //$NON-NLS-1$
		}

		if (dryRun) {
			return result.put("deleted", Boolean.FALSE) //$NON-NLS-1$
					.put("note", "Nothing was changed. Pass dryRun false to delete the file."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		try {
			DeleteResourcesDescriptor descriptor = new DeleteResourcesDescriptor();
			descriptor.setResources(new IResource[] { file });
			RefactoringStatus status = new RefactoringStatus();
			Refactoring refactoring = descriptor.createRefactoring(status);
			if (refactoring == null) {
				return result.put("deleted", Boolean.FALSE).put("error", //$NON-NLS-1$ //$NON-NLS-2$
						"The delete refactoring could not be created: "
								+ status.getMessageMatchingSeverity(RefactoringStatus.ERROR));
			}
			status.merge(refactoring.checkAllConditions(progress));
			if (status.hasError()) {
				return result.put("deleted", Boolean.FALSE).put("refusedBecause", //$NON-NLS-1$ //$NON-NLS-2$
						status.getMessageMatchingSeverity(RefactoringStatus.ERROR));
			}
			Change change = refactoring.createChange(progress);
			change.initializeValidationData(progress);
			PerformChangeOperation operation = new PerformChangeOperation(change);
			ResourcesPlugin.getWorkspace().run(operation, progress);
			if (!operation.changeExecuted()) {
				return result.put("deleted", Boolean.FALSE).put("error", //$NON-NLS-1$ //$NON-NLS-2$
						"The delete was not applied: " + operation.getValidationStatus()); //$NON-NLS-1$
			}
			JsonArray affected = new JsonArray();
			affected.add(file.getFullPath().toString());
			return result.put("deleted", Boolean.TRUE).put("affectedFiles", affected); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (CoreException e) {
			throw new McpToolException("The delete failed", e); //$NON-NLS-1$
		}
	}

	private static JsonObject describe(RegistryIndex.Evidence one) {
		JsonObject entry = new JsonObject().put("kind", one.kind()).put("file", one.file()) //$NON-NLS-1$ //$NON-NLS-2$
				.put("xpathOrHeader", one.position()); //$NON-NLS-1$
		if (one.namedType() != null) {
			entry.put("namedType", one.namedType()); //$NON-NLS-1$
		}
		return entry;
	}

	private static JsonObject failed(String typeName, String reason) {
		return new JsonObject().put("type", typeName).put("deleted", Boolean.FALSE).put("error", reason); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}


	/**
	 * Deletes one member, which is where about half the edits of a real sweep are.
	 * <p>
	 * Through the Java model rather than by editing text: {@code IMember.delete}
	 * works on the element's source range, which includes its javadoc, so the
	 * comment goes with the declaration instead of being left behind referring to
	 * something that no longer exists.
	 */
	private McpToolResult deleteMember(IType type, String memberName, boolean dryRun, boolean force,
			IProgressMonitor progress) throws McpToolException {
		List<IMember> members;
		try {
			members = JavaModelSupport.findMembers(type, memberName);
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}
		if (members.isEmpty()) {
			return McpToolResult.error("'%s' has no member named '%s'." //$NON-NLS-1$
					.formatted(type.getFullyQualifiedName(), memberName));
		}
		if (members.size() > 1) {
			return McpToolResult.error(
					"'%s#%s' resolves to %d members, so this tool cannot tell which to delete. Overloaded methods have to be removed by hand." //$NON-NLS-1$
							.formatted(type.getFullyQualifiedName(), memberName, Integer.valueOf(members.size())));
		}
		IMember member = members.get(0);
		String name = type.getFullyQualifiedName() + "#" + memberName; //$NON-NLS-1$

		RegistryIndex index = RegistryIndex.build(progress);
		List<RegistryIndex.Evidence> evidence = new ArrayList<>(index.evidenceFor(name));
		if (member instanceof IType nested) {
			// a registry names a nested type as Outer$Inner, not as Outer#Inner
			evidence.addAll(index.evidenceFor(nested.getFullyQualifiedName()));
		}
		PackageExports.Export export = new PackageExports().of(type.getJavaProject().getProject(),
				type.getPackageFragment().getElementName());
		// every reference counts here, including those in this same file: deleting a
		// member leaves the file behind, so a use two lines below the declaration is a
		// compile break exactly like one in another bundle
		int references = countReferences(member, false, progress);
		JsonArray registry = new JsonArray();
		for (RegistryIndex.Evidence one : evidence) {
			registry.add(describe(one));
		}
		int flags;
		try {
			flags = member.getFlags();
		} catch (JavaModelException e) {
			throw new McpToolException("Could not read the modifiers of " + name, e); //$NON-NLS-1$
		}
		JsonObject result = new JsonObject().put("type", type.getFullyQualifiedName()) //$NON-NLS-1$
				.put("member", memberName) //$NON-NLS-1$
				.put("kind", member instanceof IType ? "nested type" //$NON-NLS-1$ //$NON-NLS-2$
						: member.getElementType() == org.eclipse.jdt.core.IJavaElement.FIELD ? "field" : "method") //$NON-NLS-1$ //$NON-NLS-2$
				.put("file", type.getResource() == null ? null : type.getResource().getFullPath().toString()) //$NON-NLS-1$
				.put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("references", Integer.valueOf(references)) //$NON-NLS-1$
				.put("registryEvidence", registry) //$NON-NLS-1$
				.put("apiTier", export.tier()); //$NON-NLS-1$

		// the same phenomenon as a registry position, one level down: an injection
		// annotation says a framework reads or writes this member from outside any
		// compilation unit, so the reference count that would otherwise justify the
		// deletion is exactly the number that cannot see it
		List<String> injection = InjectionAnnotations.on(member);
		if (!injection.isEmpty()) {
			JsonArray annotations = new JsonArray();
			injection.forEach(annotations::add);
			result.put("injectionAnnotations", annotations) //$NON-NLS-1$
					.put("injectionNote", InjectionAnnotations.warning(injection)); //$NON-NLS-1$
		}

		List<String> blockers = new ArrayList<>();
		if (!injection.isEmpty()) {
			blockers.add(
					"it declares %s, so a framework wires it from outside Java and a reference count of zero says nothing about whether it is used" //$NON-NLS-1$
							.formatted(String.join(", ", injection.stream().map(a -> "@" + a).toList()))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (references > 0) {
			blockers.add("%d reference(s) to it remain".formatted(Integer.valueOf(references)));
		}
		if (registry.size() > 0) {
			blockers.add("%d registry position(s) name it, and those fail at runtime rather than at compile time"
					.formatted(Integer.valueOf(registry.size())));
		}
		if (PackageExports.PUBLIC.equals(export.tier()) && !org.eclipse.jdt.core.Flags.isPrivate(flags)) {
			// a private member of a public type is nobody else's business; anything
			// visible in a plainly exported package can have consumers no search here
			// can see
			blockers.add("it is visible outside its own class in a package exported as public API");
		}
		if (!blockers.isEmpty() && !force) {
			return McpToolResult.of(result.put("deleted", Boolean.FALSE) //$NON-NLS-1$
					.put("refusedBecause", String.join("; ", blockers) + ". Pass force to delete it anyway.") //$NON-NLS-1$ //$NON-NLS-2$
					.toString());
		}
		if (dryRun) {
			return McpToolResult.of(result.put("deleted", Boolean.FALSE) //$NON-NLS-1$
					.put("note", "Nothing was changed. Pass dryRun false to delete it.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		try {
			member.delete(force, progress);
		} catch (JavaModelException e) {
			throw new McpToolException("Could not delete " + name, e); //$NON-NLS-1$
		}
		return McpToolResult.of(result.put("deleted", Boolean.TRUE).toString()); //$NON-NLS-1$
	}

	/**
	 * References anywhere in the workspace.
	 *
	 * @param wholeFileGoesAway excludes the declaring file, which is right when the
	 *                          file is what is being deleted and wrong for a member,
	 *                          whose own file keeps compiling around it
	 */
	private static int countReferences(IMember type, boolean wholeFileGoesAway, IProgressMonitor monitor)
			throws McpToolException {
		SearchPattern pattern = SearchPattern.createPattern(type, IJavaSearchConstants.REFERENCES);
		if (pattern == null) {
			return 0;
		}
		IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
		IResource own = type.getResource();
		int[] count = { 0 };
		try {
			new SearchEngine().search(pattern, new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
					scope, new SearchRequestor() {
						@Override
						public void acceptSearchMatch(SearchMatch match) {
							if (!wholeFileGoesAway || own == null || !own.equals(match.getResource())) {
								count[0]++;
							}
						}
					}, monitor);
		} catch (CoreException e) {
			throw new McpToolException("Could not search for references to " + type.getElementName(), e); //$NON-NLS-1$
		}
		return count[0];
	}
}
