package com.vogella.eclipse.mcp.jdt.internal.corrections;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.ui.text.java.IInvocationContext;

/**
 * The editor-free invocation context the subprocessors read: a compilation unit, its AST and a selection, which for a
 * quick fix is the problem's range. JDT UI's {@code AssistContext} needs an editor; JDT LS has its own copy of this.
 */
final class InvocationContext implements IInvocationContext {

	private final ICompilationUnit unit;
	private final CompilationUnit astRoot;
	private final int offset;
	private final int length;
	private NodeFinder finder;

	InvocationContext(ICompilationUnit unit, CompilationUnit astRoot, int offset, int length) {
		this.unit = unit;
		this.astRoot = astRoot;
		this.offset = offset;
		this.length = length;
	}

	@Override
	public ICompilationUnit getCompilationUnit() {
		return unit;
	}

	@Override
	public int getSelectionOffset() {
		return offset;
	}

	@Override
	public int getSelectionLength() {
		return length;
	}

	@Override
	public CompilationUnit getASTRoot() {
		return astRoot;
	}

	@Override
	public ASTNode getCoveredNode() {
		return finder().getCoveredNode();
	}

	@Override
	public ASTNode getCoveringNode() {
		return finder().getCoveringNode();
	}

	private NodeFinder finder() {
		if (finder == null) {
			finder = new NodeFinder(astRoot, offset, length);
		}
		return finder;
	}
}
