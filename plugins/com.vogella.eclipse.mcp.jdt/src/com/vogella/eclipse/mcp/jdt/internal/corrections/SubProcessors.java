package com.vogella.eclipse.mcp.jdt.internal.corrections;

import java.util.Collection;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ModuleDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite.ImportRewriteContext;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite.TypeLocation;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.manipulation.CUCorrectionProposalCore;
import org.eclipse.jdt.core.manipulation.ChangeCorrectionProposalCore;
import org.eclipse.jdt.core.refactoring.CompilationUnitChange;
import org.eclipse.jdt.internal.core.manipulation.dom.ASTResolving;
import org.eclipse.jdt.internal.core.manipulation.util.BasicElementLabels;
import org.eclipse.jdt.internal.corext.codemanipulation.ContextSensitiveImportRewriteContext;
import org.eclipse.jdt.internal.corext.fix.IProposableFix;
import org.eclipse.jdt.internal.corext.fix.SuppressWarningsFixCore;
import org.eclipse.jdt.internal.corext.refactoring.changes.RenameCompilationUnitChange;
import org.eclipse.jdt.internal.corext.util.Messages;
import org.eclipse.jdt.internal.ui.fix.UnusedCodeCleanUpCore;
import org.eclipse.jdt.internal.ui.text.correction.AddAllMissingJavadocTagsProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.AddAllMissingModuleJavadocTagsProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.AddJavadocCommentProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.AddMissingJavadocTagProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.AddMissingModuleJavadocTagProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.AddSafeVarargsProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.CorrectionMessages;
import org.eclipse.jdt.internal.ui.text.correction.GetterSetterCorrectionBaseSubProcessor;
import org.eclipse.jdt.internal.ui.text.correction.JavadocTagsBaseSubProcessor;
import org.eclipse.jdt.internal.ui.text.correction.LocalCorrectionsBaseSubProcessor;
import org.eclipse.jdt.internal.ui.text.correction.ModifierCorrectionSubProcessorCore;
import org.eclipse.jdt.internal.ui.text.correction.NullAnnotationsCorrectionProcessorCore;
import org.eclipse.jdt.internal.ui.text.correction.ReorgCorrectionsBaseSubProcessor;
import org.eclipse.jdt.internal.ui.text.correction.ReturnTypeBaseSubProcessor;
import org.eclipse.jdt.internal.ui.text.correction.SerialVersionBaseSubProcessor;
import org.eclipse.jdt.internal.ui.text.correction.SerialVersionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.SuppressWarningsBaseSubProcessor;
import org.eclipse.jdt.internal.ui.text.correction.TypeAnnotationBaseSubProcessor;
import org.eclipse.jdt.internal.ui.text.correction.TypeArgumentMismatchBaseSubProcessor;
import org.eclipse.jdt.internal.ui.text.correction.TypeMismatchBaseSubProcessor;
import org.eclipse.jdt.internal.ui.text.correction.UnInitializedFinalFieldBaseSubProcessor;
import org.eclipse.jdt.internal.ui.text.correction.UnresolvedElementsBaseSubProcessor;
import org.eclipse.jdt.internal.ui.text.correction.VarargsWarningsBaseSubProcessor;
import org.eclipse.jdt.internal.ui.text.correction.proposals.AddArgumentCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.AddModuleRequiresCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.AddTypeParameterProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.AssignToVariableAssistProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.CastCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.ChangeMethodSignatureProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.ConstructorFromSuperclassProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.CorrectMainTypeNameProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.CreateNewObjectProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.CreateObjectReferenceProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.CreatePackageInfoWithDefaultNullnessProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.CreateVariableReferenceProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.ExtractToNullCheckedLocalProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.FixCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.GenerateForLoopAssistProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.ImplementInterfaceProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.InitializeFinalFieldProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.LinkedCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.LinkedNamesAssistProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.MakeLocalVariableNonNullProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.MissingAnnotationAttributesProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.MissingReturnTypeCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.MissingReturnTypeInLambdaCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.ModifierChangeCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.NewAnnotationMemberProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.NewDefiningMethodProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.NewLocalVariableCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.NewMethodCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.NewProviderMethodDeclarationCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.NewVariableCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.OptionalCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.QualifyTypeProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.RefactoringCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.RenameNodeCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.ReplaceCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.TypeChangeCorrectionProposalCore;
import org.eclipse.jdt.ui.cleanup.ICleanUp;
import org.eclipse.jdt.ui.text.java.IInvocationContext;
import org.eclipse.jdt.ui.text.java.IProblemLocation;
import org.eclipse.jdt.ui.text.java.correction.ASTRewriteCorrectionProposalCore;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;

/**
 * The quick fix subprocessors of {@code org.eclipse.jdt.core.manipulation}, bound to plain
 * {@link ChangeCorrectionProposalCore} proposals so they run without JDT UI.
 * <p>
 * Each base class leaves the proposal type open, and JDT UI and JDT LS each wrap it in their own type; this is the
 * same binding JDT LS makes in {@code org.eclipse.jdt.ls.core.internal.corrections}, minus the LSP code action kind.
 * A factory returns {@code null} for a fix that only exists as a JDT UI wizard or dialog.
 */
final class SubProcessors {

	private SubProcessors() {
	}

	/** Turns a clean-up fix into a proposal over its compilation unit change. */
	static ChangeCorrectionProposalCore fixProposal(IProposableFix fix, int relevance) {
		if (fix == null) {
			return null;
		}
		try {
			CompilationUnitChange change = fix.createChange(null);
			return new CUCorrectionProposalCore(change.getName(), change.getCompilationUnit(), change, relevance);
		} catch (CoreException e) {
			ILog.get().log(e.getStatus());
			return null;
		}
	}

	static final class LocalCorrections extends LocalCorrectionsBaseSubProcessor<ChangeCorrectionProposalCore> {

		@Override
		protected ChangeCorrectionProposalCore refactoringCorrectionProposalToT(
				RefactoringCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore linkedCorrectionProposalToT(LinkedCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore changeMethodSignatureProposalToT(
				ChangeMethodSignatureProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore fixCorrectionProposalToT(FixCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore constructorFromSuperClassProposalToT(
				ConstructorFromSuperclassProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore createNewObjectProposalToT(CreateNewObjectProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore createObjectReferenceProposalToT(
				CreateObjectReferenceProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore createVariableReferenceProposalToT(
				CreateVariableReferenceProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore generateForLoopAssistProposalToT(
				GenerateForLoopAssistProposalCore proposal) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore astRewriteCorrectionProposalToT(
				ASTRewriteCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore replaceCorrectionProposalToT(ReplaceCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore cuCorrectionProposalToT(CUCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore linkedNamesAssistProposalToT(LinkedNamesAssistProposalCore proposal) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore assignToVariableAssistProposalToT(
				AssignToVariableAssistProposalCore proposal) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore newVariableCorrectionProposalToT(
				NewVariableCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore newLocalVariableCorrectionProposalToT(
				NewLocalVariableCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore missingAnnotationAttributesProposalToT(
				MissingAnnotationAttributesProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore newMethodCorrectionProposalToT(NewMethodCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore newProviderMethodDeclarationProposalToT(
				NewProviderMethodDeclarationCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore modifierChangeCorrectionProposalToT(
				ModifierChangeCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}
	}

	static final class UnresolvedElements extends UnresolvedElementsBaseSubProcessor<ChangeCorrectionProposalCore> {

		@Override
		protected void addNewTypeProposalsInteractiveInnerLoop(ICompilationUnit cu, Name node, IJavaElement enclosing,
				int relevance, int kind, Name refNode,
						Collection<ChangeCorrectionProposalCore> proposals) throws CoreException {
			// creating a new type is a wizard in JDT UI and has no headless counterpart
		}

		@Override
		protected ReorgCorrectionsBaseSubProcessor<ChangeCorrectionProposalCore> getReorgSubProcessor() {
			return new ReorgCorrections();
		}

		@Override
		protected TypeMismatchBaseSubProcessor<ChangeCorrectionProposalCore> getTypeMismatchSubProcessor() {
			return new TypeMismatch();
		}

		@Override
		protected ChangeCorrectionProposalCore getOriginalProposalFromT(ChangeCorrectionProposalCore proposal) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore newVariableCorrectionProposalToT(
				NewVariableCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore renameNodeCorrectionProposalToT(
				RenameNodeCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore compositeProposalToT(ChangeCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected int getQualifiedTypeNameHistoryBoost(String qualifiedName, int min, int max) {
			return 0;
		}

		@Override
		protected ChangeCorrectionProposalCore linkedProposalToT(LinkedCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore changeCorrectionProposalToT(ChangeCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore qualifyTypeProposalToT(QualifyTypeProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore addTypeParametersToT(AddTypeParameterProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore addModuleRequiresProposalToT(
				AddModuleRequiresCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore replaceCorrectionProposalToT(ReplaceCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore castCorrectionProposalToT(CastCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore addArgumentCorrectionProposalToT(
				AddArgumentCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore changeMethodSignatureProposalToT(
				ChangeMethodSignatureProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore newMethodProposalToT(NewMethodCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore rewriteProposalToT(ASTRewriteCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore newAnnotationProposalToT(NewAnnotationMemberProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore renameNodeProposalToT(RenameNodeCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}
	}

	static final class ReorgCorrections extends ReorgCorrectionsBaseSubProcessor<ChangeCorrectionProposalCore> {

		@Override
		public UnresolvedElementsBaseSubProcessor<ChangeCorrectionProposalCore> getUnresolvedElementsSubProcessor() {
			return new UnresolvedElements();
		}

		@Override
		public ChangeCorrectionProposalCore createRenameCUProposal(String label, RenameCompilationUnitChange change,
				int relevance) {
			return new ChangeCorrectionProposalCore(label, change, relevance);
		}

		@Override
		public ChangeCorrectionProposalCore createCorrectMainTypeNameProposal(ICompilationUnit cu,
				IInvocationContext context, String currentName, String newName, int relevance) {
			String label = Messages.format(CorrectionMessages.ReorgCorrectionsSubProcessor_renametype_description,
					BasicElementLabels.getJavaElementName(newName));
			return new CorrectMainTypeNameProposalCore(label, cu, null, context, currentName, newName, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createCorrectPackageDeclarationProposal(IProposableFix fix,
				IInvocationContext context, int relevance) {
			return fixProposal(fix, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createMoveToNewPackageProposal(String label, CompositeChange change,
				int relevance) {
			return new ChangeCorrectionProposalCore(label, change, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createOrganizeImportsProposal(String label, Change change,
				ICompilationUnit cu, int relevance) {
			return new ChangeCorrectionProposalCore(label, change, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createRemoveUnusedImportProposal(IProposableFix fix,
				UnusedCodeCleanUpCore cleanUp, int relevance, IInvocationContext context) {
			return fixProposal(fix, relevance);
		}

		@Override
		public ChangeCorrectionProposalCore createProjectSetupFixProposal(IInvocationContext context,
				IProblemLocation problem, String missingType, Collection<ChangeCorrectionProposalCore> proposals) {
			// adding a library to the build path is a JDT UI wizard
			return null;
		}

		@Override
		protected ChangeCorrectionProposalCore createChangeToRequiredCompilerComplianceProposal(String label,
				IJavaProject project, boolean workspace, String version, int relevance) {
			return new ChangeCorrectionProposalCore(label, ProjectOptionsChange.compliance(label, project, version,
					false), relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createChangeToRequiredCompilerComplianceProposal(String label,
				IJavaProject project, boolean workspace, String version, boolean enablePreviews, int relevance) {
			return new ChangeCorrectionProposalCore(label, ProjectOptionsChange.compliance(label, project, version,
					enablePreviews), relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createOpenBuildPathCorrectionProposal(IProject project, String label,
				int relevance, IBinding binding) {
			// opens the build path dialog in JDT UI
			return null;
		}
	}

	static final class ReturnType extends ReturnTypeBaseSubProcessor<ChangeCorrectionProposalCore> {

		@Override
		protected TypeMismatchBaseSubProcessor<ChangeCorrectionProposalCore> getTypeMismatchSubProcessor() {
			return new TypeMismatch();
		}

		@Override
		protected ChangeCorrectionProposalCore linkedCorrectionProposal1ToT(LinkedCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore rewriteCorrectionProposalToT(ASTRewriteCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore replaceCorrectionProposalToT(ReplaceCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore missingReturnTypeProposalToT(
				MissingReturnTypeCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore missingReturnTypeInLambdaProposalToT(
				MissingReturnTypeInLambdaCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}
	}

	static final class TypeMismatch extends TypeMismatchBaseSubProcessor<ChangeCorrectionProposalCore> {

		@Override
		protected ChangeCorrectionProposalCore createInsertNullCheckProposal(String label, ICompilationUnit cu,
				ASTRewrite rewrite, int relevance) {
			return new ASTRewriteCorrectionProposalCore(label, cu, rewrite, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createChangeReturnTypeProposal(String label, ICompilationUnit cu,
				ASTRewrite rewrite, int relevance, ITypeBinding binding, AST ast, CompilationUnit astRoot,
						MethodDeclaration method, BodyDeclaration declaration) {
			ASTRewriteCorrectionProposalCore proposal = new ASTRewriteCorrectionProposalCore(label, cu, rewrite,
					relevance);
			ImportRewrite imports = proposal.createImportRewrite(astRoot);
			ImportRewriteContext importContext = new ContextSensitiveImportRewriteContext(declaration, imports);
			Type returnType = imports.addImport(binding, ast, importContext, TypeLocation.RETURN_TYPE);
			rewrite.replace(method.getReturnType2(), returnType, null);
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore createOptionalProposal(String label, ICompilationUnit cu,
				Expression expression, int relevance, int optionalType) {
			return new OptionalCorrectionProposalCore(label, cu, expression, relevance, optionalType);
		}

		@Override
		protected ChangeCorrectionProposalCore createImplementInterfaceProposal(ICompilationUnit cu, ITypeBinding type,
				CompilationUnit astRoot, ITypeBinding interfaceType, int relevance) {
			return new ImplementInterfaceProposalCore(cu, type, astRoot, interfaceType, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createChangeSenderTypeProposal(ICompilationUnit cu, IBinding binding,
				CompilationUnit astRoot, ITypeBinding type, boolean assigned, int relevance) {
			return new TypeChangeCorrectionProposalCore(cu, binding, astRoot, type, assigned, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createChangeConstructorTypeProposal(ICompilationUnit cu, ASTNode caller,
				CompilationUnit astRoot, ITypeBinding type, int relevance) {
			return caller == null ? null : new TypeChangeCorrectionProposalCore(cu, caller, astRoot, type, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createCastCorrectionProposal(String label, ICompilationUnit cu,
				Expression expression, ITypeBinding type, int relevance) {
			return new CastCorrectionProposalCore(label, cu, expression, type, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createChangeReturnTypeOfOverridden(ICompilationUnit cu,
				IMethodBinding overridden, CompilationUnit astRoot, ITypeBinding returnType, boolean offerSuperTypes,
						int relevance, ITypeBinding overriddenType) {
			TypeChangeCorrectionProposalCore proposal = new TypeChangeCorrectionProposalCore(cu, overridden, astRoot,
					returnType, false, relevance);
			String template = overriddenType.isInterface()
					? CorrectionMessages.TypeMismatchSubProcessor_changereturnofimplemented_description
					: CorrectionMessages.TypeMismatchSubProcessor_changereturnofoverridden_description;
			proposal.setDisplayName(Messages.format(template,
					BasicElementLabels.getJavaElementName(overridden.getName())));
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore createChangeIncompatibleReturnTypeProposal(ICompilationUnit cu,
				IMethodBinding method, CompilationUnit astRoot, ITypeBinding returnType, boolean offerSuperTypes,
						int relevance) {
			return new TypeChangeCorrectionProposalCore(cu, method, astRoot, returnType, false, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createChangeMethodSignatureProposal(String label, ICompilationUnit cu,
				CompilationUnit astRoot, IMethodBinding method,
						ChangeMethodSignatureProposalCore.ChangeDescription[] parameterChanges,
								ChangeMethodSignatureProposalCore.ChangeDescription[] exceptionChanges, int relevance) {
			return new ChangeMethodSignatureProposalCore(label, cu, astRoot, method, parameterChanges, exceptionChanges,
					relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createNewVariableCorrectionProposal(String label, ICompilationUnit cu,
				int variableKind, SimpleName name, ITypeBinding senderBinding, int relevance) {
			return new NewVariableCorrectionProposalCore(label, cu, variableKind, name, senderBinding, relevance,
					false);
		}

		@Override
		protected ChangeCorrectionProposalCore createIncompatibleForEachTypeProposal(String label, ICompilationUnit cu,
				ASTRewrite rewrite, int relevance, CompilationUnit astRoot, AST ast, ITypeBinding expected,
						ASTNode selectedNode, SingleVariableDeclaration parameter) {
			ASTRewriteCorrectionProposalCore proposal = new ASTRewriteCorrectionProposalCore(label, cu, rewrite,
					relevance);
			ImportRewrite imports = proposal.createImportRewrite(astRoot);
			ImportRewriteContext importContext = new ContextSensitiveImportRewriteContext(
					ASTResolving.findParentBodyDeclaration(selectedNode), imports);
			Type type = imports.addImport(expected, ast, importContext, TypeLocation.LOCAL_VARIABLE);
			rewrite.replace(parameter.getType(), type, null);
			return proposal;
		}
	}

	static final class ModifierCorrections extends ModifierCorrectionSubProcessorCore<ChangeCorrectionProposalCore> {

		@Override
		protected ChangeCorrectionProposalCore astRewriteCorrectionProposalToT(
				ASTRewriteCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore modifierChangeCorrectionProposalCoreToT(
				ModifierChangeCorrectionProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore fixCorrectionProposalCoreToT(FixCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore newDefiningMethodProposalCoreToT(NewDefiningMethodProposalCore proposal,
				int relevance) {
			return proposal;
		}

		@Override
		protected void collectConstructorProposals(IInvocationContext context, IProblemLocation problem,
				Collection<ChangeCorrectionProposalCore> proposals) {
			try {
				new UnresolvedElements().collectConstructorProposals(context, problem, proposals);
			} catch (CoreException e) {
				ILog.get().log(e.getStatus());
			}
		}

		@Override
		protected void getVariableProposals(IInvocationContext context, IProblemLocation problem,
				IVariableBinding binding, Collection<ChangeCorrectionProposalCore> proposals) {
			try {
				new UnresolvedElements().collectVariableProposals(context, problem, binding, proposals);
			} catch (CoreException e) {
				ILog.get().log(e.getStatus());
			}
		}
	}

	static final class SuppressWarningsFixes extends SuppressWarningsBaseSubProcessor<ChangeCorrectionProposalCore> {

		@Override
		protected ChangeCorrectionProposalCore createSuppressWarningsProposal(IProposableFix fix, ICleanUp cleanUp,
				int relevance, IInvocationContext context) {
			return new FixCorrectionProposalCore(fix, cleanUp, relevance, context);
		}

		@Override
		protected ChangeCorrectionProposalCore createASTRewriteCorrectionProposal(String label, ICompilationUnit cu,
				ASTRewrite rewrite, int relevance) {
			return new ASTRewriteCorrectionProposalCore(label, cu, rewrite, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createFixCorrectionProposal(IProposableFix fix, ICleanUp cleanUp,
				int relevance, IInvocationContext context) {
			return new FixCorrectionProposalCore(fix, cleanUp, relevance, context);
		}

		@Override
		protected boolean alreadyHasProposal(Collection<ChangeCorrectionProposalCore> proposals, String token) {
			for (ChangeCorrectionProposalCore proposal : proposals) {
				if (proposal instanceof FixCorrectionProposalCore fix
						&& fix.getCleanUp() instanceof SuppressWarningsFixCore suppress
						&& token.equals(suppress.getWarningToken())) {
					return true;
				}
			}
			return false;
		}
	}

	static final class UnInitializedFinalField extends UnInitializedFinalFieldBaseSubProcessor<ChangeCorrectionProposalCore> {

		@Override
		protected ChangeCorrectionProposalCore createInitializeFinalFieldProposal(IProblemLocation problem,
				ICompilationUnit cu, SimpleName name, IVariableBinding binding, int relevance) {
			return new InitializeFinalFieldProposalCore(problem, cu, name, binding, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createInitializeFinalFieldProposal(IProblemLocation problem,
				ICompilationUnit cu, MethodDeclaration constructor, int relevance, int updateType) {
			return new InitializeFinalFieldProposalCore(problem, cu, constructor, relevance, updateType);
		}

		@Override
		protected ChangeCorrectionProposalCore conditionallyCreateInitializeFinalFieldProposal(IProblemLocation problem,
				ICompilationUnit cu, MethodDeclaration constructor, int relevance, int updateType) {
			InitializeFinalFieldProposalCore proposal = new InitializeFinalFieldProposalCore(problem, cu, constructor,
					relevance, updateType);
			try {
				return proposal.hasProposal() ? proposal : null;
			} catch (CoreException e) {
				return null;
			}
		}
	}

	static final class JavadocTags extends JavadocTagsBaseSubProcessor<ChangeCorrectionProposalCore> {

		@Override
		protected ChangeCorrectionProposalCore addJavadocCommentProposal(String label, ICompilationUnit cu,
				int relevance, int offset, String comment) {
			return new AddJavadocCommentProposalCore(label, cu, relevance, offset, comment);
		}

		@Override
		protected ChangeCorrectionProposalCore createRemoveJavadocTagProposals(String label, ICompilationUnit cu,
				ASTRewrite rewrite, int relevance) {
			return new ASTRewriteCorrectionProposalCore(label, cu, rewrite, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createRemoveDuplicateModuleJavadocTagProposal(String label,
				ICompilationUnit cu, int offset, int length, String replacement, int relevance) {
			return new ReplaceCorrectionProposalCore(label, cu, offset, length, replacement, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore addAllMissingJavadocTagsProposal(String label, ICompilationUnit cu,
				ASTNode declaration, int relevance) {
			return new AddAllMissingJavadocTagsProposalCore(label, cu, declaration, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore addMissingJavadocTagProposal(String label, ICompilationUnit cu,
				ASTNode declaration, ASTNode node, int relevance) {
			return new AddMissingJavadocTagProposalCore(label, cu, declaration, node, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore addAllMissingModuleJavadocTagsProposal(String label, ICompilationUnit cu,
				ModuleDeclaration module, ASTNode node, int relevance) {
			return new AddAllMissingModuleJavadocTagsProposalCore(label, cu, module, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore addMissingModuleJavadocTagProposal(String label, ICompilationUnit cu,
				ModuleDeclaration module, ASTNode node, int relevance) {
			return new AddMissingModuleJavadocTagProposalCore(label, cu, module, node, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createInvalidQualificationProposal(String label, ICompilationUnit cu,
				ASTRewrite rewrite, int relevance) {
			return new ASTRewriteCorrectionProposalCore(label, cu, rewrite, relevance);
		}
	}

	static final class GetterSetter extends GetterSetterCorrectionBaseSubProcessor<ChangeCorrectionProposalCore> {

		@Override
		protected ChangeCorrectionProposalCore createNonNullMethodGetterProposal(String label, ICompilationUnit cu,
				ASTRewrite rewrite, int relevance) {
			return new ASTRewriteCorrectionProposalCore(label, cu, rewrite, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createFieldGetterProposal(int relevance, IField field) {
			// self encapsulate field is a JDT UI refactoring wizard
			return null;
		}

		@Override
		protected ChangeCorrectionProposalCore createMethodSetterProposal(String label, ICompilationUnit cu,
				ASTRewrite rewrite, int relevance) {
			return new ASTRewriteCorrectionProposalCore(label, cu, rewrite, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createFieldSetterProposal(int relevance, IField field) {
			// self encapsulate field is a JDT UI refactoring wizard
			return null;
		}
	}

	static final class SerialVersion extends SerialVersionBaseSubProcessor<ChangeCorrectionProposalCore> {

		@Override
		protected ChangeCorrectionProposalCore createSerialVersionProposal(IProposableFix fix, int relevance,
				IInvocationContext context, boolean isDefault) {
			return new SerialVersionProposalCore(fix, relevance, context, isDefault);
		}
	}

	static final class VarargsWarnings extends VarargsWarningsBaseSubProcessor<ChangeCorrectionProposalCore> {

		@Override
		protected ChangeCorrectionProposalCore createAddSafeVarargsProposal1(String label, ICompilationUnit cu,
				MethodDeclaration declaration, IMethodBinding binding, int relevance) {
			return new AddSafeVarargsProposalCore(label, cu, declaration, binding, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createAddSafeVarargsToDeclarationProposal1(String label,
				ICompilationUnit cu, Object unused, IMethodBinding binding, int relevance) {
			return new AddSafeVarargsProposalCore(label, cu, null, binding, relevance);
		}

		@Override
		protected ChangeCorrectionProposalCore createRemoveSafeVarargsProposal1(String label, ICompilationUnit cu,
				ASTRewrite rewrite, int relevance) {
			return new ASTRewriteCorrectionProposalCore(label, cu, rewrite, relevance);
		}
	}

	static final class TypeArgumentMismatch extends TypeArgumentMismatchBaseSubProcessor<ChangeCorrectionProposalCore> {

		@Override
		public ChangeCorrectionProposalCore astRewriteCorrectionProposalToT(ASTRewriteCorrectionProposalCore proposal) {
			return proposal;
		}
	}

	static final class TypeAnnotation extends TypeAnnotationBaseSubProcessor<ChangeCorrectionProposalCore> {

		@Override
		protected ChangeCorrectionProposalCore fixCorrectionProposalCoreToT(FixCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}
	}

	static final class NullAnnotations extends NullAnnotationsCorrectionProcessorCore<ChangeCorrectionProposalCore> {

		@Override
		protected ChangeCorrectionProposalCore makeLocalVariableNonNullProposalCoreToT(
				MakeLocalVariableNonNullProposalCore proposal) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore extractToNullCheckedLocalProposalCoreToT(
				ExtractToNullCheckedLocalProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore createPackageInfoWithDefaultNullnessProposalCoreToT(
				CreatePackageInfoWithDefaultNullnessProposalCore proposal, int relevance) {
			return proposal;
		}

		@Override
		protected ChangeCorrectionProposalCore fixCorrectionProposalCoreToT(FixCorrectionProposalCore proposal,
				int relevance) {
			return proposal;
		}
	}
}
