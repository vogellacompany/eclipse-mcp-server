package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeDeclarationStatement;
import org.eclipse.jdt.core.dom.TypeMethodReference;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the methods a method body calls, from the resolved bindings of its AST, for
 * {@code eclipse_get_call_hierarchy}'s callee direction.
 */
final class Callees {

	private static final List<String> JDK_PACKAGES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

	private final boolean includeJdk;

	private final GetCallHierarchyTool.Counter counter;

	private final IProgressMonitor monitor;

	private final Set<String> expanded = new HashSet<>();

	private int jdkCallsHidden;

	Callees(boolean includeJdk, GetCallHierarchyTool.Counter counter, IProgressMonitor monitor) {
		this.includeJdk = includeJdk;
		this.counter = counter;
		this.monitor = monitor;
	}

	int jdkCallsHidden() {
		return jdkCallsHidden;
	}

	/** The requested methods whose callees could not be read, each with why, so none reads as calling nothing. */
	JsonArray withoutBody() {
		return withoutBody;
	}

	private final JsonArray withoutBody = new JsonArray();

	/**
	 * The callees of every method in {@code methods}, each followed {@code depth} levels.
	 * With several overloads each top-level callee names the overload calling it in {@code from}.
	 */
	JsonArray of(List<? extends IMember> methods, int depth) {
		List<IMethod> roots = methods.stream().filter(IMethod.class::isInstance).map(IMethod.class::cast)
				.filter(m -> expanded.add(m.getHandleIdentifier())).toList();
		// every root is marked before any is walked, so one overload calling another is listed, not expanded twice
		JsonArray nodes = new JsonArray();
		for (IMethod root : roots) {
			if (calleesOf(root, depth, nodes, roots.size() > 1 ? JavaModelSupport.signatureOf(root) : null) == null) {
				withoutBody.add(new JsonObject().put("method", JavaModelSupport.describe(root)) //$NON-NLS-1$
						.put("reason", reasonWithoutBody(root))); //$NON-NLS-1$
			}
		}
		return nodes;
	}

	/** Appends the callees of {@code method} to {@code nodes}, or answers {@code null} when it has no body to read. */
	private JsonArray calleesOf(IMethod method, int depth, JsonArray nodes, String from) {
		List<Call> calls = callsIn(method);
		if (calls == null) {
			return null;
		}
		for (Call call : calls) {
			if (monitor.isCanceled() || !counter.take()) {
				return nodes;
			}
			JsonObject node = new JsonObject().put("callee", call.label()); //$NON-NLS-1$
			if (from != null) {
				node.put("from", from); //$NON-NLS-1$
			}
			if (call.line() > 0) {
				node.put("line", call.line()); //$NON-NLS-1$
			}
			if (call.count() > 1) {
				node.put("calls", call.count()); //$NON-NLS-1$
			}
			if (call.binary()) {
				node.put("origin", "binary"); //$NON-NLS-1$ //$NON-NLS-2$
			} else if (depth > 1 && call.target() != null) {
				if (expanded.add(call.target().getHandleIdentifier())) {
					JsonArray deeper = calleesOf(call.target(), depth - 1, new JsonArray(), null);
					if (deeper == null) {
						node.put("body", reasonWithoutBody(call.target())); //$NON-NLS-1$
					} else if (deeper.size() > 0) {
						node.put("callees", deeper); //$NON-NLS-1$
					}
				} else {
					// already walked elsewhere in this answer; an absent key would read as "calls nothing"
					node.put("repeated", true); //$NON-NLS-1$
				}
			}
			nodes.add(node);
		}
		return nodes;
	}

	/** One callee of a body: the first line calling it and how often it is called. */
	private record Call(String label, IMethod target, boolean binary, int line, int count) {
		Call again() {
			return new Call(label, target, binary, line, count + 1);
		}
	}

	/** Why a method's callees cannot be listed: it has no source, or it is abstract or native. */
	private static String reasonWithoutBody(IMethod method) {
		try {
			ITypeRoot root = method.getTypeRoot();
			if (root == null || root.getSource() == null) {
				return "noSource"; //$NON-NLS-1$
			}
		} catch (JavaModelException e) {
			return "noSource"; //$NON-NLS-1$
		}
		return "abstract"; //$NON-NLS-1$
	}

	/** The calls in the method's body, or {@code null} when there is no body to read. */
	private List<Call> callsIn(IMethod method) {
		MethodDeclaration declaration = declarationOf(method);
		if (declaration == null || declaration.getBody() == null) {
			return null;
		}
		CompilationUnit unit = (CompilationUnit) declaration.getRoot();
		Map<String, Call> calls = new LinkedHashMap<>();
		declaration.getBody().accept(new ASTVisitor() {
			@Override
			public boolean visit(AnonymousClassDeclaration node) {
				return false;
			}

			@Override
			public boolean visit(TypeDeclarationStatement node) {
				return false;
			}

			@Override
			public boolean visit(MethodInvocation node) {
				record(node.resolveMethodBinding(), node);
				return true;
			}

			@Override
			public boolean visit(SuperMethodInvocation node) {
				record(node.resolveMethodBinding(), node);
				return true;
			}

			@Override
			public boolean visit(ClassInstanceCreation node) {
				record(node.resolveConstructorBinding(), node);
				// the arguments are evaluated here, the anonymous body is not
				node.arguments().forEach(argument -> ((ASTNode) argument).accept(this));
				if (node.getExpression() != null) {
					node.getExpression().accept(this);
				}
				return false;
			}

			@Override
			public boolean visit(ConstructorInvocation node) {
				record(node.resolveConstructorBinding(), node);
				return true;
			}

			@Override
			public boolean visit(SuperConstructorInvocation node) {
				record(node.resolveConstructorBinding(), node);
				return true;
			}

			@Override
			public boolean visit(EnumConstantDeclaration node) {
				record(node.resolveConstructorBinding(), node);
				return true;
			}

			@Override
			public void endVisit(ExpressionMethodReference node) {
				record(node.resolveMethodBinding(), node);
			}

			@Override
			public void endVisit(SuperMethodReference node) {
				record(node.resolveMethodBinding(), node);
			}

			@Override
			public void endVisit(TypeMethodReference node) {
				record(node.resolveMethodBinding(), node);
			}

			@Override
			public void endVisit(CreationReference node) {
				record(node.resolveMethodBinding(), node);
			}

			private void record(IMethodBinding binding, ASTNode node) {
				if (binding == null) {
					return;
				}
				IMethodBinding declared = binding.getMethodDeclaration();
				ITypeBinding declaringType = declared.getDeclaringClass();
				String typeName = typeNameOf(declaringType);
				if (!includeJdk && isJdk(typeName)) {
					jdkCallsHidden++;
					return;
				}
				IMethod target = declared.getJavaElement() instanceof IMethod found ? found : null;
				String label = target != null ? JavaModelSupport.describe(target) : labelOf(declared, typeName);
				String key = target != null ? target.getHandleIdentifier() : label;
				Call earlier = calls.get(key);
				calls.put(key, earlier != null ? earlier.again()
						: new Call(label, target,
								target != null ? target.isBinary() : declaringType == null || !declaringType.isFromSource(),
								unit.getLineNumber(node.getStartPosition()), 1));
			}
		});
		return new ArrayList<>(calls.values());
	}

	/** Local and anonymous types have no qualified name, so they fall back to their binary name. */
	private static String typeNameOf(ITypeBinding type) {
		if (type == null) {
			return ""; //$NON-NLS-1$
		}
		ITypeBinding erasure = type.getErasure();
		String name = erasure.getQualifiedName();
		if (name.isEmpty()) {
			name = erasure.getBinaryName() != null ? erasure.getBinaryName() : erasure.getName();
		}
		return name;
	}

	private static boolean isJdk(String typeName) {
		return JDK_PACKAGES.stream().anyMatch(typeName::startsWith);
	}

	/** A label for a binding without a Java element, such as an implicit default constructor. */
	private static String labelOf(IMethodBinding binding, String typeName) {
		StringBuilder label = new StringBuilder(typeName).append('.')
				.append(binding.isConstructor() ? binding.getDeclaringClass().getErasure().getName() : binding.getName())
				.append('(');
		ITypeBinding[] parameters = binding.getParameterTypes();
		for (int i = 0; i < parameters.length; i++) {
			label.append(i == 0 ? "" : ", ").append(parameters[i].getErasure().getName()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return label.append(')').toString();
	}

	/** The method's declaration in a fully resolved AST, or {@code null} without source. */
	private static MethodDeclaration declarationOf(IMethod method) {
		ITypeRoot root = method.getTypeRoot();
		try {
			ISourceRange name = method.getNameRange();
			if (root == null || name == null || name.getOffset() < 0 || root.getSource() == null) {
				return null;
			}
			ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
			parser.setSource(root);
			parser.setResolveBindings(true);
			parser.setBindingsRecovery(true);
			// only the body around the focal position is kept, which makes parsing a large file cheap
			parser.setFocalPosition(name.getOffset());
			CompilationUnit unit = (CompilationUnit) parser.createAST(null);
			ASTNode node = NodeFinder.perform(unit, name.getOffset(), name.getLength());
			while (node != null && !(node instanceof MethodDeclaration)) {
				if (node instanceof AbstractTypeDeclaration) {
					return null;
				}
				node = node.getParent();
			}
			return (MethodDeclaration) node;
		} catch (JavaModelException e) {
			return null;
		}
	}
}
