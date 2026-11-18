package com.vogella.eclipse.mcp.jdt.internal.corrections;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.manipulation.ChangeCorrectionProposalCore;
import org.eclipse.jdt.core.manipulation.CleanUpOptionsCore;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.fix.CleanUpConstants;
import org.eclipse.jdt.internal.corext.fix.FixMessages;
import org.eclipse.jdt.internal.corext.fix.IProposableFix;
import org.eclipse.jdt.internal.corext.fix.InlineMethodFixCore;
import org.eclipse.jdt.internal.corext.fix.Java50FixCore;
import org.eclipse.jdt.internal.corext.fix.NullAnnotationsRewriteOperations.ChangeKind;
import org.eclipse.jdt.internal.corext.fix.ReplaceDeprecatedFieldFixCore;
import org.eclipse.jdt.internal.ui.fix.ReplaceDeprecatedFieldCleanUpCore;
import org.eclipse.jdt.internal.ui.text.correction.CorrectionMessages;
import org.eclipse.jdt.internal.ui.text.correction.IProposalRelevance;
import org.eclipse.jdt.internal.ui.text.correction.ModifierCorrectionSubProcessorCore;
import org.eclipse.jdt.internal.ui.text.correction.ProblemLocation;
import org.eclipse.jdt.internal.ui.text.correction.QuickAssistProcessorUtil;
import org.eclipse.jdt.internal.ui.text.correction.proposals.FixCorrectionProposalCore;
import org.eclipse.jdt.internal.ui.text.correction.proposals.ReplaceCorrectionProposalCore;
import org.eclipse.jdt.ui.text.java.IInvocationContext;
import org.eclipse.jdt.ui.text.java.IProblemLocation;

import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.GetterSetter;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.JavadocTags;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.LocalCorrections;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.ModifierCorrections;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.NullAnnotations;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.ReorgCorrections;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.ReturnType;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.SerialVersion;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.SuppressWarningsFixes;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.TypeAnnotation;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.TypeArgumentMismatch;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.TypeMismatch;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.UnInitializedFinalField;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.UnresolvedElements;
import com.vogella.eclipse.mcp.jdt.internal.corrections.SubProcessors.VarargsWarnings;

/**
 * Computes the quick fixes for one compiler problem without JDT UI.
 * <p>
 * The dispatch from problem id to subprocessor follows JDT UI's {@code QuickFixProcessor} as JDT LS ports it, calling
 * the shared base subprocessors in {@code org.eclipse.jdt.core.manipulation} directly. Problems whose fixes exist only
 * in JDT UI (creating a new type, build path changes, self encapsulate field, hashCode generation) get none here.
 */
public final class QuickFixProcessor {

	private final LocalCorrections local = new LocalCorrections();
	private final UnresolvedElements unresolved = new UnresolvedElements();
	private final ReorgCorrections reorg = new ReorgCorrections();
	private final ReturnType returnType = new ReturnType();
	private final TypeMismatch typeMismatch = new TypeMismatch();
	private final ModifierCorrections modifier = new ModifierCorrections();
	private final SuppressWarningsFixes suppressWarnings = new SuppressWarningsFixes();
	private final JavadocTags javadoc = new JavadocTags();
	private final GetterSetter getterSetter = new GetterSetter();
	private final NullAnnotations nullAnnotations = new NullAnnotations();

	/**
	 * Returns the fixes for {@code problem} in {@code astRoot}, most relevant first, one per label.
	 *
	 * @param astRoot an AST of {@code unit} with bindings resolved, so that the problem's offsets match it
	 */
	public List<ChangeCorrectionProposalCore> getCorrections(ICompilationUnit unit, CompilationUnit astRoot,
			IProblem problem) throws CoreException {
		CodeTemplates.ensureInstalled();
		IProblemLocation location = new ProblemLocation(problem);
		IInvocationContext context = new InvocationContext(unit, astRoot, location.getOffset(), location.getLength());
		List<ChangeCorrectionProposalCore> proposals = new NonNullList();
		process(context, location, proposals);
		suppressWarnings.getSuppressWarningsProposals(context, location, proposals);

		List<ChangeCorrectionProposalCore> sorted = new ArrayList<>(proposals);
		sorted.sort(Comparator.comparingInt(ChangeCorrectionProposalCore::getRelevance).reversed());
		Set<String> labels = new HashSet<>();
		sorted.removeIf(proposal -> !labels.add(proposal.getName()));
		return sorted;
	}

	/** Collects proposals, silently skipping the {@code null} a factory returns for a fix that needs JDT UI. */
	private static final class NonNullList extends ArrayList<ChangeCorrectionProposalCore> {

		private static final long serialVersionUID = 1L;

		@Override
		public boolean add(ChangeCorrectionProposalCore proposal) {
			return proposal != null && super.add(proposal);
		}
	}

	private void process(IInvocationContext context, IProblemLocation problem,
			List<ChangeCorrectionProposalCore> proposals) throws CoreException {
		int id = problem.getProblemId();
		switch (id) {
			case IProblem.UnterminatedString:
				int position = moveBack(problem.getOffset() + problem.getLength(), problem.getOffset(), "\n\r", //$NON-NLS-1$
						context.getCompilationUnit());
				proposals.add(new ReplaceCorrectionProposalCore(CorrectionMessages.JavaCorrectionProcessor_addquote_description,
						context.getCompilationUnit(), position, 0, "\"", IProposalRelevance.ADD_QUOTE)); //$NON-NLS-1$
				break;
			case IProblem.UnusedImport:
			case IProblem.DuplicateImport:
			case IProblem.CannotImportPackage:
			case IProblem.ConflictingImport:
				reorg.addRemoveImportStatementProposals(context, problem, proposals);
				break;
			case IProblem.ImportNotFound:
				reorg.addImportNotFoundProposals(context, problem, proposals);
				reorg.addRemoveImportStatementProposals(context, problem, proposals);
				break;
			case IProblem.PublicClassMustMatchFileName:
				reorg.addWrongTypeNameProposals(context, problem, proposals);
				break;
			case IProblem.PackageIsNotExpectedPackage:
				reorg.addWrongPackageDeclNameProposals(context, problem, proposals);
				break;
			case IProblem.UndefinedMethod:
				unresolved.collectMethodProposals(context, problem, false, proposals);
				break;
			case IProblem.ParameterMismatch:
				unresolved.collectMethodProposals(context, problem, true, proposals);
				break;
			case IProblem.UndefinedConstructor:
				unresolved.collectConstructorProposals(context, problem, proposals);
				break;
			case IProblem.UndefinedAnnotationMember:
				unresolved.collectAnnotationMemberProposals(context, problem, proposals);
				break;
			case IProblem.MethodButWithConstructorName:
				returnType.collectMethodWithConstrNameProposals(context, problem, proposals);
				break;
			case IProblem.UndefinedField:
			case IProblem.UndefinedName:
			case IProblem.UnresolvedVariable:
				unresolved.collectVariableProposals(context, problem, null, proposals);
				break;
			case IProblem.ExpressionShouldBeAVariable:
				local.getExpressionShouldBeAVariableProposalsBase(context, problem, proposals);
				break;
			case IProblem.UninitializedBlankFinalField:
				new UnInitializedFinalField().addProposals(context, problem, proposals);
				break;
			case IProblem.AmbiguousType:
			case IProblem.JavadocAmbiguousType:
				unresolved.collectAmbiguosTypeReferenceProposals(context, problem, proposals);
				break;
			case IProblem.UndefinedType:
			case IProblem.JavadocUndefinedType:
			case IProblem.VarIsNotAllowedHere:
				unresolved.collectTypeProposals(context, problem, proposals);
				break;
			case IProblem.TypeMismatch:
			case IProblem.ReturnTypeMismatch:
				typeMismatch.collectTypeMismatchProposals(context, withTwoArguments(problem), proposals);
				break;
			case IProblem.IncompatibleTypesInForeach:
				typeMismatch.collectTypeMismatchInForEachProposals(context, problem, proposals);
				break;
			case IProblem.IncompatibleReturnType:
				typeMismatch.collectIncompatibleReturnTypeProposals(context, problem, proposals);
				break;
			case IProblem.IncompatibleExceptionInThrowsClause:
				typeMismatch.collectIncompatibleThrowsProposals(context, problem, proposals);
				break;
			case IProblem.UnhandledException:
			case IProblem.UnhandledExceptionOnAutoClose:
				local.getUncaughtExceptionProposals(context, problem, proposals);
				break;
			case IProblem.RedundantSuperinterface:
				local.getRedundantSuperInterfaceProposal(context, problem, proposals);
				break;
			case IProblem.VoidMethodReturnsValue:
				returnType.collectVoidMethodReturnsProposals(context, problem, proposals);
				break;
			case IProblem.MethodReturnsVoid:
				returnType.collectMethodReturnsVoidProposals(context, problem, proposals);
				break;
			case IProblem.MissingReturnType:
				returnType.collectMissingReturnTypeProposals(context, problem, proposals);
				break;
			case IProblem.ShouldReturnValue:
			case IProblem.ShouldReturnValueHintMissingDefault:
				returnType.collectMissingReturnStatementProposals(context, problem, proposals);
				break;
			case IProblem.NonExternalizedStringLiteral:
				local.getAddNLSTagProposalsCore(context, problem, proposals);
				break;
			case IProblem.UnnecessaryNLSTag:
				local.getUnnecessaryNLSTagProposalsCore(context, problem, proposals);
				break;
			case IProblem.NonStaticAccessToStaticField:
			case IProblem.NonStaticAccessToStaticMethod:
			case IProblem.NonStaticOrAlienTypeReceiver:
			case IProblem.IndirectAccessToStaticField:
			case IProblem.IndirectAccessToStaticMethod:
				local.getCorrectAccessToStaticProposals(context, problem, proposals, modifier);
				break;
			case IProblem.SealedMissingClassModifier:
			case IProblem.SealedMissingInterfaceModifier:
				modifier.getSealedMissingModifierProposal(context, problem, proposals);
				break;
			case IProblem.SealedNotDirectSuperInterface:
			case IProblem.SealedNotDirectSuperClass:
				local.getSealedAsDirectSuperTypeProposal(context, problem, proposals);
				break;
			case IProblem.SealedSuperClassDoesNotPermit:
			case IProblem.SealedSuperInterfaceDoesNotPermit:
				local.getTypeAsPermittedSubTypeProposal(context, problem, proposals);
				break;
			case IProblem.StaticMethodRequested:
			case IProblem.NonStaticFieldFromStaticInvocation:
				local.getObjectReferenceProposal(context, problem, proposals);
				local.getVariableReferenceProposal(context, problem, proposals);
				local.getNewObjectProposal(context, problem, proposals);
				//$FALL-THROUGH$
			case IProblem.InstanceMethodDuringConstructorInvocation:
			case IProblem.InstanceFieldDuringConstructorInvocation:
				modifier.getNonAccessibleReferenceProposal(context, problem, proposals,
						ModifierCorrectionSubProcessorCore.TO_STATIC, IProposalRelevance.CHANGE_MODIFIER_TO_STATIC);
				break;
			case IProblem.NonBlankFinalLocalAssignment:
			case IProblem.DuplicateFinalLocalInitialization:
			case IProblem.FinalFieldAssignment:
			case IProblem.DuplicateBlankFinalFieldInitialization:
			case IProblem.AnonymousClassCannotExtendFinalClass:
			case IProblem.ClassExtendFinalClass:
				modifier.getNonAccessibleReferenceProposal(context, problem, proposals,
						ModifierCorrectionSubProcessorCore.TO_NON_FINAL, IProposalRelevance.REMOVE_FINAL_MODIFIER);
				break;
			case IProblem.InheritedMethodReducesVisibility:
			case IProblem.MethodReducesVisibility:
			case IProblem.OverridingNonVisibleMethod:
				modifier.getChangeOverriddenModifierProposal(context, problem, proposals,
						ModifierCorrectionSubProcessorCore.TO_VISIBLE);
				break;
			case IProblem.FinalMethodCannotBeOverridden:
				modifier.getChangeOverriddenModifierProposal(context, problem, proposals,
						ModifierCorrectionSubProcessorCore.TO_NON_FINAL);
				break;
			case IProblem.CannotOverrideAStaticMethodWithAnInstanceMethod:
				modifier.getChangeOverriddenModifierProposal(context, problem, proposals,
						ModifierCorrectionSubProcessorCore.TO_NON_STATIC);
				break;
			case IProblem.CannotHideAnInstanceMethodWithAStaticMethod:
			case IProblem.IllegalModifierForInterfaceMethod:
			case IProblem.IllegalModifierForInterface:
			case IProblem.IllegalModifierForClass:
			case IProblem.IllegalModifierForInterfaceField:
			case IProblem.UnexpectedStaticModifierForField:
			case IProblem.IllegalModifierCombinationFinalVolatileForField:
			case IProblem.IllegalModifierForMemberInterface:
			case IProblem.IllegalModifierForMemberClass:
			case IProblem.IllegalModifierForLocalClass:
			case IProblem.IllegalModifierForArgument:
			case IProblem.IllegalModifierForField:
			case IProblem.IllegalModifierForMethod:
			case IProblem.IllegalModifierForConstructor:
			case IProblem.IllegalModifierForVariable:
			case IProblem.IllegalModifierForEnum:
			case IProblem.IllegalModifierForEnumConstant:
			case IProblem.IllegalModifierForEnumConstructor:
			case IProblem.IllegalModifierForMemberEnum:
			case IProblem.IllegalVisibilityModifierForInterfaceMemberType:
			case IProblem.UnexpectedStaticModifierForMethod:
			case IProblem.IllegalModifierForInterfaceMethod18:
				modifier.getRemoveInvalidModifiersProposal(context, problem, proposals,
						IProposalRelevance.REMOVE_INVALID_MODIFIERS);
				break;
			case IProblem.NotVisibleField:
				getterSetter.addGetterSetterProposals(context, problem, proposals,
						IProposalRelevance.GETTER_SETTER_NOT_VISIBLE_FIELD);
				modifier.getNonAccessibleReferenceProposal(context, problem, proposals,
						ModifierCorrectionSubProcessorCore.TO_VISIBLE, IProposalRelevance.CHANGE_VISIBILITY);
				break;
			case IProblem.NotVisibleMethod:
			case IProblem.NotVisibleConstructor:
			case IProblem.NotVisibleType:
			case IProblem.JavadocNotVisibleType:
				modifier.getNonAccessibleReferenceProposal(context, problem, proposals,
						ModifierCorrectionSubProcessorCore.TO_VISIBLE, IProposalRelevance.CHANGE_VISIBILITY);
				break;
			case IProblem.BodyForAbstractMethod:
			case IProblem.AbstractMethodInAbstractClass:
			case IProblem.AbstractMethodInEnum:
			case IProblem.EnumAbstractMethodMustBeImplemented:
				modifier.getAbstractMethodProposals(context, problem, proposals);
				break;
			case IProblem.AbstractMethodsInConcreteClass:
				modifier.getAbstractTypeProposals(context, problem, proposals);
				break;
			case IProblem.AbstractMethodMustBeImplemented:
			case IProblem.EnumConstantMustImplementAbstractMethod:
				local.getUnimplementedMethodsProposals(context, problem, proposals);
				break;
			case IProblem.MissingValueForAnnotationMember:
				local.getValueForAnnotationProposals(context, problem, proposals);
				break;
			case IProblem.BodyForNativeMethod:
				modifier.getNativeMethodProposals(context, problem, proposals);
				break;
			case IProblem.MethodRequiresBody:
				modifier.getMethodRequiresBodyProposals(context, problem, proposals);
				break;
			case IProblem.OuterLocalMustBeEffectivelyFinal:
				modifier.getNonFinalLocalProposal(context, problem, proposals);
				break;
			case IProblem.UninitializedLocalVariable:
			case IProblem.UninitializedLocalVariableHintMissingDefault:
				local.getUninitializedLocalVariableProposal(context, problem, proposals);
				break;
			case IProblem.UnhandledExceptionInDefaultConstructor:
			case IProblem.UndefinedConstructorInDefaultConstructor:
			case IProblem.NotVisibleConstructorInDefaultConstructor:
				local.getConstructorFromSuperclassProposal(context, problem, proposals);
				break;
			case IProblem.UnusedPrivateMethod:
			case IProblem.UnusedPrivateConstructor:
			case IProblem.UnusedPrivateType:
			case IProblem.LocalVariableIsNeverUsed:
			case IProblem.ArgumentIsNeverUsed:
			case IProblem.LambdaParameterIsNeverUsed:
			case IProblem.UnusedPrivateField:
				local.getUnusedMemberProposal(context, problem, proposals, javadoc, getterSetter);
				break;
			case IProblem.NeedToEmulateFieldReadAccess:
			case IProblem.NeedToEmulateFieldWriteAccess:
			case IProblem.NeedToEmulateMethodAccess:
			case IProblem.NeedToEmulateConstructorAccess:
				modifier.getNonAccessibleReferenceProposal(context, problem, proposals,
						ModifierCorrectionSubProcessorCore.TO_NON_PRIVATE,
						IProposalRelevance.CHANGE_VISIBILITY_TO_NON_PRIVATE);
				break;
			case IProblem.SuperfluousSemicolon:
				local.getSuperfluousSemicolonProposal(context, problem, proposals);
				break;
			case IProblem.UnnecessaryCast:
				local.getUnnecessaryCastProposal(context, problem, proposals);
				break;
			case IProblem.UnnecessaryInstanceof:
				local.getUnnecessaryInstanceofProposal(context, problem, proposals);
				break;
			case IProblem.UnusedMethodDeclaredThrownException:
			case IProblem.UnusedConstructorDeclaredThrownException:
				local.getUnnecessaryThrownExceptionProposal(context, problem, proposals, javadoc);
				break;
			case IProblem.UnqualifiedFieldAccess:
				getterSetter.addGetterSetterProposals(context, problem, proposals,
						IProposalRelevance.GETTER_SETTER_UNQUALIFIED_FIELD_ACCESS);
				local.getUnqualifiedFieldAccessProposal(context, problem, proposals);
				break;
			case IProblem.JavadocMissing:
				javadoc.addMissingJavadocCommentProposals(context, problem, proposals);
				break;
			case IProblem.JavadocMissingParamTag:
			case IProblem.JavadocMissingReturnTag:
			case IProblem.JavadocMissingThrowsTag:
				javadoc.addMissingJavadocTagProposals(context, problem, proposals);
				break;
			case IProblem.JavadocInvalidThrowsClassName:
			case IProblem.JavadocDuplicateThrowsClassName:
			case IProblem.JavadocDuplicateReturnTag:
			case IProblem.JavadocDuplicateParamName:
			case IProblem.JavadocInvalidParamName:
			case IProblem.JavadocUnexpectedTag:
			case IProblem.JavadocInvalidTag:
				javadoc.addRemoveJavadocTagProposals(context, problem, proposals);
				break;
			case IProblem.JavadocInvalidMemberTypeQualification:
				javadoc.addInvalidQualificationProposals(context, problem, proposals);
				break;
			case IProblem.LocalVariableHidingLocalVariable:
			case IProblem.LocalVariableHidingField:
			case IProblem.FieldHidingLocalVariable:
			case IProblem.FieldHidingField:
			case IProblem.ArgumentHidingLocalVariable:
			case IProblem.ArgumentHidingField:
			case IProblem.UseAssertAsAnIdentifier:
			case IProblem.UseEnumAsAnIdentifier:
			case IProblem.RedefinedLocal:
			case IProblem.RedefinedArgument:
			case IProblem.DuplicateField:
			case IProblem.DuplicateMethod:
			case IProblem.DuplicateTypeVariable:
			case IProblem.DuplicateNestedType:
			case IProblem.LambdaRedeclaresLocal:
			case IProblem.LambdaRedeclaresArgument:
				local.getInvalidVariableNameProposals(context, problem, proposals);
				break;
			case IProblem.NoMessageSendOnArrayType:
				unresolved.collectArrayAccessProposals(context, problem, proposals);
				break;
			case IProblem.InvalidOperator:
				local.getInvalidOperatorProposalsBase(context, problem, proposals);
				break;
			case IProblem.MissingSerialVersion:
				new SerialVersion().addSerialVersionProposals(context, problem, proposals);
				break;
			case IProblem.UnnecessaryElse:
				local.getUnnecessaryElseProposalsBase(context, problem, proposals);
				break;
			case IProblem.SuperclassMustBeAClass:
				local.getInterfaceExtendsClassProposalsBase(context, problem, proposals);
				break;
			case IProblem.CodeCannotBeReached:
			case IProblem.DeadCode:
				local.getUnreachableCodeProposalsBase(context, problem, proposals);
				break;
			case IProblem.NonGenericType:
				new TypeArgumentMismatch().addRemoveMismatchedArgumentProposals(context, problem, proposals);
				break;
			case IProblem.MissingOverrideAnnotation:
			case IProblem.MissingOverrideAnnotationForInterfaceMethodImplementation:
				modifier.getOverrideAnnotationProposal(context, problem, proposals);
				break;
			case IProblem.MethodMustOverride:
			case IProblem.MethodMustOverrideOrImplement:
				modifier.getRemoveOverrideAnnotationProposal(context, problem, proposals);
				break;
			case IProblem.FieldMissingDeprecatedAnnotation:
			case IProblem.MethodMissingDeprecatedAnnotation:
			case IProblem.TypeMissingDeprecatedAnnotation:
			case IProblem.MemberOfDeprecatedTypeNotDeprecated:
				modifier.getDeprecatedAnnotationProposal(context, problem, proposals);
				break;
			case IProblem.OverridingDeprecatedMethod:
			case IProblem.OverridingDeprecatedSinceVersionMethod:
			case IProblem.OverridingTerminallyDeprecatedMethod:
			case IProblem.OverridingTerminallyDeprecatedSinceVersionMethod:
				modifier.getOverridingDeprecatedMethodProposal(context, problem, proposals);
				break;
			case IProblem.UsingDeprecatedMethod:
				addInlineDeprecatedMethodProposal(context, problem, proposals);
				break;
			case IProblem.UsingDeprecatedField:
				addReplaceDeprecatedFieldProposal(context, problem, proposals);
				break;
			case IProblem.AssignmentHasNoEffect:
				local.getAssignmentHasNoEffectProposalsBase(context, problem, proposals);
				break;
			case IProblem.UnsafeTypeConversion:
			case IProblem.RawTypeReference:
			case IProblem.UnsafeRawMethodInvocation:
				local.getDeprecatedFieldsToMethodsProposals(context, problem, proposals);
				//$FALL-THROUGH$
			case IProblem.UnsafeElementTypeConversion:
				IProposableFix rawTypeFix = Java50FixCore.createRawTypeReferenceFix(context.getASTRoot(), problem);
				if (rawTypeFix != null) {
					proposals.add(new FixCorrectionProposalCore(rawTypeFix, null, IProposalRelevance.RAW_TYPE_REFERENCE,
							context));
				}
				break;
			case IProblem.RedundantSpecificationOfTypeArguments:
				local.getRemoveRedundantTypeArgumentsProposals(context, problem, proposals);
				break;
			case IProblem.FallthroughCase:
			case IProblem.IllegalFallthroughToPattern:
				local.getFallThroughProposals(context, problem, proposals);
				break;
			case IProblem.UnhandledWarningToken:
				suppressWarnings.getUnknownSuppressWarningProposals(context, problem, proposals);
				break;
			case IProblem.ProblemNotAnalysed:
			case IProblem.UnusedWarningToken:
				suppressWarnings.getRemoveUnusedSuppressWarningProposals(context, problem, proposals);
				break;
			case IProblem.MissingEnumConstantCase:
			case IProblem.MissingEnumDefaultCase:
			case IProblem.SwitchExpressionsYieldMissingEnumConstantCase:
				local.getMissingEnumConstantCaseProposalsBase(context, problem, proposals);
				break;
			case IProblem.EnhancedSwitchMissingDefault:
			case IProblem.SwitchExpressionsYieldMissingDefaultCase:
				local.getPermittedTypesProposal(context, problem, proposals);
				local.getMissingDefaultCaseProposal(context, problem, proposals);
				break;
			case IProblem.MissingDefaultCase:
				local.getMissingDefaultCaseProposal(context, problem, proposals);
				break;
			case IProblem.IllegalTotalPatternWithDefault:
				local.removeDefaultCaseProposalBase(context, problem, proposals);
				break;
			case IProblem.MissingEnumConstantCaseDespiteDefault:
				local.getMissingEnumConstantCaseProposalsBase(context, problem, proposals);
				local.getCasesOmittedProposals(context, problem, proposals);
				break;
			case IProblem.SwitchExpressionsReturnWithinSwitchExpression:
				returnType.collectReplaceReturnWithYieldStatementProposals(context, problem, proposals);
				break;
			case IProblem.UnclosedCloseable:
			case IProblem.PotentiallyUnclosedCloseable:
				local.getTryWithResourceProposalsBase(context, problem, proposals);
				break;
			case IProblem.NotAccessibleType:
				if (!reorg.addImportNotFoundProposals(context, problem, proposals)
						&& problem.getCoveredNode(context.getASTRoot()) instanceof Name name) {
					unresolved.collectRequiresModuleProposals(context.getCompilationUnit(), name,
							IProposalRelevance.IMPORT_NOT_FOUND_ADD_REQUIRES_MODULE, proposals, true);
				}
				break;
			case IProblem.AbstractServiceImplementation:
			case IProblem.ProviderMethodOrConstructorRequiredForServiceImpl:
			case IProblem.ServiceImplDefaultConstructorNotPublic:
				local.getServiceProviderProposal(context, problem, proposals);
				local.getServiceProviderConstructorProposals(context, problem, proposals);
				break;
			case IProblem.MissingSynchronizedModifierInInheritedMethod:
				modifier.getAddMethodModifierProposal(context, problem, proposals, Modifier.SYNCHRONIZED,
						CorrectionMessages.ModifierCorrectionSubProcessor_addsynchronized_description);
				break;
			case IProblem.UnusedObjectAllocation:
				local.getUnusedObjectAllocationProposalsBase(context, problem, proposals);
				break;
			case IProblem.MethodCanBeStatic:
			case IProblem.MethodCanBePotentiallyStatic:
				modifier.getAddMethodModifierProposal(context, problem, proposals, Modifier.STATIC,
						CorrectionMessages.ModifierCorrectionSubProcessor_addstatic_description);
				break;
			case IProblem.PotentialHeapPollutionFromVararg:
				new VarargsWarnings().createAddSafeVarargsProposals(context, problem, proposals);
				break;
			case IProblem.UnsafeGenericArrayForVarargs:
				new VarargsWarnings().createAddSafeVarargsToDeclarationProposals(context, problem, proposals);
				break;
			case IProblem.SafeVarargsOnFixedArityMethod:
			case IProblem.SafeVarargsOnNonFinalInstanceMethod:
				new VarargsWarnings().createRemoveSafeVarargsProposals(context, problem, proposals);
				break;
			case IProblem.IllegalReturnNullityRedefinition:
			case IProblem.IllegalDefinitionToNonNullParameter:
			case IProblem.IllegalRedefinitionToNonNullParameter:
				boolean isArgumentProblem = id != IProblem.IllegalReturnNullityRedefinition;
				nullAnnotations.getNullAnnotationInSignatureProposal(context, problem, proposals, ChangeKind.LOCAL,
						isArgumentProblem);
				nullAnnotations.getNullAnnotationInSignatureProposal(context, problem, proposals, ChangeKind.OVERRIDDEN,
						isArgumentProblem);
				break;
			case IProblem.RequiredNonNullButProvidedSpecdNullable:
			case IProblem.RequiredNonNullButProvidedUnknown:
				nullAnnotations.getExtractCheckedLocalProposal(context, problem, proposals);
				//$FALL-THROUGH$
			case IProblem.RequiredNonNullButProvidedNull:
			case IProblem.RequiredNonNullButProvidedPotentialNull:
			case IProblem.NullityUncheckedTypeAnnotation:
			case IProblem.ParameterLackingNonNullAnnotation:
			case IProblem.ParameterLackingNullableAnnotation:
				nullAnnotations.getReturnAndArgumentTypeProposal(context, problem, ChangeKind.LOCAL, proposals);
				nullAnnotations.getReturnAndArgumentTypeProposal(context, problem, ChangeKind.TARGET, proposals);
				break;
			case IProblem.RedundantNullCheckAgainstNonNullType:
			case IProblem.SpecdNonNullLocalVariableComparisonYieldsFalse:
			case IProblem.RedundantNullCheckOnSpecdNonNullLocalVariable:
				if (nullAnalysisEnabled(context)) {
					nullAnnotations.getReturnAndArgumentTypeProposal(context, problem, ChangeKind.LOCAL, proposals);
				}
				break;
			case IProblem.RedundantNullAnnotation:
			case IProblem.RedundantNullDefaultAnnotationPackage:
			case IProblem.RedundantNullDefaultAnnotationType:
			case IProblem.RedundantNullDefaultAnnotationMethod:
			case IProblem.RedundantNullDefaultAnnotationLocal:
			case IProblem.RedundantNullDefaultAnnotationField:
				nullAnnotations.getRemoveRedundantAnnotationProposal(context, problem, proposals);
				break;
			case IProblem.DereferencingNullableExpression:
			case IProblem.NullityMismatchingTypeAnnotation:
				nullAnnotations.getReplaceNullableAnnotationProposal(context, problem, proposals);
				break;
			case IProblem.ContradictoryNullAnnotations:
				nullAnnotations.getRemoveContradictoryAnnotationProposals(context, problem, proposals);
				break;
			case IProblem.UnusedTypeParameter:
				local.getUnusedTypeParameterProposal(context, problem, proposals, javadoc);
				break;
			case IProblem.NullableFieldReference:
				nullAnnotations.getExtractCheckedLocalProposal(context, problem, proposals);
				break;
			case IProblem.ConflictingNullAnnotations:
			case IProblem.ConflictingInheritedNullAnnotations:
				nullAnnotations.getReturnAndArgumentTypeProposal(context, problem, ChangeKind.LOCAL, proposals);
				nullAnnotations.getReturnAndArgumentTypeProposal(context, problem, ChangeKind.INVERSE, proposals);
				nullAnnotations.getReturnAndArgumentTypeProposal(context, problem, ChangeKind.OVERRIDDEN, proposals);
				break;
			case IProblem.IllegalQualifiedEnumConstantLabel:
				local.getIllegalQualifiedEnumConstantLabelProposal(context, problem, proposals);
				break;
			case IProblem.DuplicateInheritedDefaultMethods:
			case IProblem.InheritedDefaultMethodConflictsWithOtherInherited:
				local.getOverrideDefaultMethodProposal(context, problem, proposals);
				break;
			case IProblem.PotentialNullLocalVariableReference:
				if (nullAnalysisEnabled(context)) {
					nullAnnotations.getLocalVariableAnnotationProposal(context, problem, proposals);
				}
				break;
			case IProblem.MissingNonNullByDefaultAnnotationOnPackage:
				nullAnnotations.getAddMissingDefaultNullnessProposal(context, problem, proposals);
				break;
			case IProblem.FeatureNotSupported:
				reorg.addNeedHigherComplianceProposals(context, problem, proposals);
				break;
			case IProblem.MultiConstantCaseLabelsNotSupported:
				reorg.addNeedHigherComplianceProposals(context, problem, proposals, JavaCore.VERSION_14);
				break;
			case IProblem.TypeAnnotationAtQualifiedName:
			case IProblem.IllegalTypeAnnotationsInStaticMemberAccess:
			case IProblem.NullAnnotationAtQualifyingType:
			case IProblem.IllegalAnnotationForBaseType:
				new TypeAnnotation().getMoveTypeAnnotationToTypeProposal(context, problem, proposals);
				break;
			default:
				break;
		}
	}

	private static void addInlineDeprecatedMethodProposal(IInvocationContext context, IProblemLocation problem,
			List<ChangeCorrectionProposalCore> proposals) {
		ASTNode node = problem.getCoveredNode(context.getASTRoot());
		if (node != null && !(node instanceof MethodInvocation)) {
			node = node.getParent();
		}
		if (node instanceof MethodInvocation invocation
				&& QuickAssistProcessorUtil.isDeprecatedMethodCallWithReplacement(invocation)) {
			IProposableFix fix = InlineMethodFixCore.create(FixMessages.InlineDeprecatedMethod_msg,
					(CompilationUnit) invocation.getRoot(), invocation);
			if (fix != null) {
				proposals.add(new FixCorrectionProposalCore(fix, null, IProposalRelevance.INLINE_DEPRECATED_METHOD,
						context));
			}
		}
	}

	private static void addReplaceDeprecatedFieldProposal(IInvocationContext context, IProblemLocation problem,
			List<ChangeCorrectionProposalCore> proposals) {
		ASTNode node = problem.getCoveredNode(context.getASTRoot());
		if (node != null && !(node instanceof QualifiedName) && !(node instanceof FieldAccess)
				&& !(node instanceof SuperFieldAccess)) {
			ASTNode original = node;
			node = ASTNodes.getFirstAncestorOrNull(node, QualifiedName.class, FieldAccess.class, SuperFieldAccess.class);
			if (node == null && original instanceof SimpleName) {
				node = original;
			}
		}
		if (node == null) {
			return;
		}
		String replacement = QuickAssistProcessorUtil.getDeprecatedFieldReplacement(node);
		if (replacement == null) {
			return;
		}
		IProposableFix fix = ReplaceDeprecatedFieldFixCore.create(FixMessages.ReplaceDeprecatedField_msg, replacement,
				(CompilationUnit) node.getRoot(), node);
		if (fix != null) {
			Map<String, String> options = Map.of(CleanUpConstants.REPLACE_DEPRECATED_FIELDS, CleanUpOptionsCore.TRUE);
			proposals.add(new FixCorrectionProposalCore(fix, new ReplaceDeprecatedFieldCleanUpCore(options),
					IProposalRelevance.REPLACE_DEPRECATED_FIELD, context));
		}
	}

	private static boolean nullAnalysisEnabled(IInvocationContext context) {
		IJavaProject project = context.getCompilationUnit().getJavaProject();
		return project != null
				&& JavaCore.ENABLED.equals(project.getOption(JavaCore.COMPILER_ANNOTATION_NULL_ANALYSIS, true));
	}

	/** The type mismatch subprocessor indexes two problem arguments, which some mismatches do not carry. */
	private static IProblemLocation withTwoArguments(IProblemLocation problem) {
		String[] arguments = problem.getProblemArguments();
		if (arguments != null && arguments.length >= 2) {
			return problem;
		}
		String[] padded = { arguments != null && arguments.length == 1 ? arguments[0] : "", "" }; //$NON-NLS-1$ //$NON-NLS-2$
		return new ProblemLocation(problem.getOffset(), problem.getLength(), problem.getProblemId(), padded,
				problem.isError(), problem.getMarkerType());
	}

	private static int moveBack(int offset, int start, String ignoreCharacters, ICompilationUnit unit) {
		try {
			IBuffer buffer = unit.getBuffer();
			while (offset >= start) {
				if (ignoreCharacters.indexOf(buffer.getChar(offset - 1)) == -1) {
					return offset;
				}
				offset--;
			}
		} catch (JavaModelException e) {
			// fall back to the start of the problem
		}
		return start;
	}
}
