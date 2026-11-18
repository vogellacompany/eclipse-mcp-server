package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.manipulation.ChangeCorrectionProposalCore;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.RangeMarker;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.jdt.internal.corrections.QuickFixProcessor;

/**
 * What {@link GetQuickFixesTool} and {@link ApplyQuickFixTool} share: the problems of a compilation unit as the
 * compiler reports them on a fresh AST, their fixes, and a description of what a fix would change.
 */
final class QuickFixSupport {

	private QuickFixSupport() {
	}

	/** Parses the unit's current content with bindings, the AST the quick fix offsets refer to. */
	static CompilationUnit parse(ICompilationUnit unit, IProgressMonitor monitor) {
		ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
		parser.setSource(unit);
		parser.setResolveBindings(true);
		parser.setStatementsRecovery(true);
		parser.setBindingsRecovery(true);
		return (CompilationUnit) parser.createAST(monitor);
	}

	/** The unit's errors and, optionally, warnings and infos, in source order. */
	static List<IProblem> problems(CompilationUnit astRoot, boolean includeWarnings) {
		List<IProblem> problems = new ArrayList<>();
		for (IProblem problem : astRoot.getProblems()) {
			if (problem.isError() || includeWarnings) {
				problems.add(problem);
			}
		}
		problems.sort((a, b) -> Integer.compare(a.getSourceStart(), b.getSourceStart()));
		return problems;
	}

	static List<ChangeCorrectionProposalCore> fixes(ICompilationUnit unit, CompilationUnit astRoot, IProblem problem)
			throws CoreException {
		// headless, the jdt.ui preference node the code style is read from is missing
		OrganizeImportsTool.ensureCodeStylePreferences();
		return new QuickFixProcessor().getCorrections(unit, astRoot, problem);
	}

	static JsonObject describe(IProblem problem) {
		return new JsonObject().put("problemId", Integer.valueOf(problem.getID())) //$NON-NLS-1$
				.put("message", problem.getMessage()) //$NON-NLS-1$
				.put("severity", problem.isError() ? "error" : problem.isWarning() ? "warning" : "info") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				.put("line", Integer.valueOf(problem.getSourceLineNumber())) //$NON-NLS-1$
				.put("offset", Integer.valueOf(problem.getSourceStart())) //$NON-NLS-1$
				.put("length", Integer.valueOf(Math.max(0, problem.getSourceEnd() - problem.getSourceStart() + 1))); //$NON-NLS-1$
	}

	/** Lists the files and text edits a change makes, or names a change that is not a text edit. */
	static JsonArray describe(Change change, IProgressMonitor monitor) throws CoreException {
		JsonArray changes = new JsonArray();
		collect(change, changes, monitor);
		return changes;
	}

	private static void collect(Change change, JsonArray changes, IProgressMonitor monitor) throws CoreException {
		if (change instanceof CompositeChange composite) {
			for (Change child : composite.getChildren()) {
				collect(child, changes, monitor);
			}
			return;
		}
		JsonObject entry = new JsonObject().put("name", change.getName()); //$NON-NLS-1$
		if (change instanceof TextChange text) {
			if (text instanceof TextFileChange fileChange) {
				entry.put("file", fileChange.getFile().getFullPath().toString()); //$NON-NLS-1$
			}
			if (text.getEdit() != null) {
				String content = Objects.requireNonNullElse(text.getCurrentContent(monitor), ""); //$NON-NLS-1$
				JsonArray edits = new JsonArray();
				for (TextEdit edit : leaves(text.getEdit())) {
					edits.add(describe(edit, content));
				}
				entry.put("edits", edits); //$NON-NLS-1$
			}
		} else if (change.getModifiedElement() != null) {
			entry.put("element", String.valueOf(change.getModifiedElement())); //$NON-NLS-1$
		}
		changes.add(entry);
	}

	/**
	 * Makes every file the change edits save afterwards. A quick fix is built to edit an open editor, so JDT leaves its
	 * text changes dirty; with no editor holding the buffer that edit would be dropped when the buffer is released.
	 */
	static void saveFiles(Change change) {
		if (change instanceof CompositeChange composite) {
			for (Change child : composite.getChildren()) {
				saveFiles(child);
			}
		} else if (change instanceof TextFileChange file) {
			file.setSaveMode(TextFileChange.KEEP_SAVE_STATE);
		}
	}

	/** The files of the change whose buffer is still dirty, because an editor held unsaved edits to them. */
	static List<String> unsavedFiles(Change change) {
		List<String> unsaved = new ArrayList<>();
		if (change instanceof CompositeChange composite) {
			for (Change child : composite.getChildren()) {
				unsaved.addAll(unsavedFiles(child));
			}
		} else if (change instanceof TextFileChange file) {
			ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager()
					.getTextFileBuffer(file.getFile().getFullPath(), LocationKind.IFILE);
			if (buffer != null && buffer.isDirty()) {
				unsaved.add(file.getFile().getFullPath().toString());
			}
		}
		return unsaved;
	}

	private static JsonObject describe(TextEdit edit, String content) {
		int offset = edit.getOffset();
		int length = edit.getLength();
		String newText = switch (edit) {
			case ReplaceEdit replace -> replace.getText();
			case InsertEdit insert -> insert.getText();
			case DeleteEdit delete -> ""; //$NON-NLS-1$
			default -> null;
		};
		JsonObject entry = new JsonObject().put("line", Integer.valueOf(lineOf(content, offset))) //$NON-NLS-1$
				.put("offset", Integer.valueOf(offset)) //$NON-NLS-1$
				.put("oldText", content.substring(Math.min(content.length(), offset), Math.min(content.length(), offset + length))); //$NON-NLS-1$
		if (newText != null) {
			entry.put("newText", newText); //$NON-NLS-1$
		} else {
			entry.put("kind", edit.getClass().getSimpleName()); //$NON-NLS-1$
		}
		return entry;
	}

	private static List<TextEdit> leaves(TextEdit root) {
		List<TextEdit> leaves = new ArrayList<>();
		if (!root.hasChildren()) {
			// a range marker only tracks a linked position and changes no text
			if (!(root instanceof RangeMarker) && !(root instanceof MultiTextEdit)) {
				leaves.add(root);
			}
			return leaves;
		}
		for (TextEdit child : root.getChildren()) {
			leaves.addAll(leaves(child));
		}
		return leaves;
	}

	private static int lineOf(String content, int offset) {
		int line = 1;
		for (int i = 0; i < offset && i < content.length(); i++) {
			if (content.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}
}
