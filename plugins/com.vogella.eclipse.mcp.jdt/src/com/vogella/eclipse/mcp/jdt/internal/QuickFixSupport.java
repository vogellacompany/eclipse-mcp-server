package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
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
import com.vogella.eclipse.mcp.jdt.internal.corrections.ProjectOptionsChange;
import com.vogella.eclipse.mcp.jdt.internal.corrections.QuickFixProcessor;

/**
 * What {@link GetQuickFixesTool} and {@link ApplyQuickFixTool} share: the problems of a compilation unit as the
 * compiler reports them on a fresh AST, their fixes, and a description of what a fix would change.
 */
final class QuickFixSupport {

	/** Most text edits listed for one file; a quick fix rarely makes more than a few. */
	private static final int MAX_EDITS = 100;

	private QuickFixSupport() {
	}

	/**
	 * Parses the unit's current content with bindings, the AST the quick fix offsets refer to. Answers null when the
	 * monitor is cancelled, since the parser signals that with an exception.
	 */
	static CompilationUnit parse(ICompilationUnit unit, IProgressMonitor monitor) {
		ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
		parser.setSource(unit);
		parser.setResolveBindings(true);
		parser.setStatementsRecovery(true);
		parser.setBindingsRecovery(true);
		try {
			return (CompilationUnit) parser.createAST(monitor);
		} catch (OperationCanceledException e) {
			return null;
		}
	}

	/**
	 * The unit's errors and, optionally, warnings and infos, in source order. A problem the compiler reports twice, with
	 * the same id, range and message, is listed once, so a caller cannot take the copy for a second problem.
	 */
	static List<IProblem> problems(CompilationUnit astRoot, boolean includeWarnings) {
		List<IProblem> problems = new ArrayList<>();
		Set<List<Object>> seen = new HashSet<>();
		for (IProblem problem : astRoot.getProblems()) {
			if ((problem.isError() || includeWarnings) && seen.add(List.of(Integer.valueOf(problem.getID()),
					Integer.valueOf(problem.getSourceStart()), Integer.valueOf(problem.getSourceEnd()),
					String.valueOf(problem.getMessage())))) {
				problems.add(problem);
			}
		}
		problems.sort((a, b) -> Integer.compare(a.getSourceStart(), b.getSourceStart()));
		return problems;
	}

	/**
	 * A problem's fixes, and when some of JDT's fix computations failed while others succeeded, the reason the list may
	 * lack a fix; null otherwise.
	 */
	record Fixes(List<ChangeCorrectionProposalCore> proposals, String partialFailure) {
	}

	static Fixes fixes(ICompilationUnit unit, CompilationUnit astRoot, IProblem problem) throws CoreException {
		// headless, the jdt.ui preference node the code style is read from is missing
		OrganizeImportsTool.ensureCodeStylePreferences();
		try {
			QuickFixProcessor processor = new QuickFixProcessor();
			List<ChangeCorrectionProposalCore> proposals = processor.getCorrections(unit, astRoot, problem);
			Exception failure = processor.partialFailure();
			return new Fixes(proposals, failure == null ? null
					: "Some of JDT's fix computations failed, so a fix may be missing (see the Error Log): " //$NON-NLS-1$
							+ Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getName()));
		} catch (LinkageError e) {
			// the port subclasses internal classes whose abstract methods change between releases
			throw new IllegalStateException(mismatch(e), e);
		}
	}

	/** Explains a linkage error from the internal subprocessors the port is compiled against. */
	static String mismatch(LinkageError e) {
		return "The installed org.eclipse.jdt.core.manipulation does not match the one these quick fixes were built against: " //$NON-NLS-1$
				+ e;
	}

	static JsonObject describe(IProblem problem) {
		JsonObject entry = new JsonObject().put("problemId", Integer.valueOf(problem.getID())) //$NON-NLS-1$
				.put("message", problem.getMessage()) //$NON-NLS-1$
				.put("severity", problem.isError() ? "error" : problem.isWarning() ? "warning" : "info"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		if (problem.getSourceLineNumber() > 0) {
			// a problem without a position reports line 0, which eclipse_apply_quick_fix would refuse
			entry.put("line", Integer.valueOf(problem.getSourceLineNumber())); //$NON-NLS-1$
		}
		if (problem.getSourceStart() < 0) {
			// an offset of -1 would be refused by eclipse_apply_quick_fix, so say why there is none
			return entry.put("note", "JDT reports this problem without a position in the file, so it has no quick fix here."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return entry.put("offset", Integer.valueOf(problem.getSourceStart())) //$NON-NLS-1$
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
				SourceText lines = SourceText.of(content);
				JsonArray edits = new JsonArray();
				List<TextEdit> leaves = leaves(text.getEdit());
				// an edit without a position changes nothing in the file, so it is neither listed nor counted
				int total = 0;
				for (TextEdit edit : leaves) {
					if (edit.getOffset() < 0) {
						continue;
					}
					total++;
					if (edits.size() < MAX_EDITS) {
						edits.add(describe(edit, content, lines));
					}
				}
				entry.put("edits", edits); //$NON-NLS-1$
				if (total > edits.size()) {
					entry.put("editsTotal", Integer.valueOf(total)).put("editsTruncated", Boolean.TRUE); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
		} else if (change instanceof ProjectOptionsChange options) {
			JsonObject changed = new JsonObject();
			options.changedOptions().forEach(changed::put);
			entry.put("project", options.projectName()).put("options", changed) //$NON-NLS-1$ //$NON-NLS-2$
					.put("note", "Only the compiler options change. The JRE on the build path stays as it is, so a problem " //$NON-NLS-1$ //$NON-NLS-2$
							+ "that needs a newer library remains."); //$NON-NLS-1$
		} else {
			// a move, rename or new file: name what it is, since it changes more than text
			entry.put("kind", change.getClass().getSimpleName()); //$NON-NLS-1$
			if (change.getModifiedElement() instanceof IJavaElement element) {
				entry.put("element", element.getElementName()); //$NON-NLS-1$
			} else if (change.getModifiedElement() instanceof IResource resource) {
				entry.put("element", resource.getFullPath().toString()); //$NON-NLS-1$
			} else if (change.getModifiedElement() instanceof IPath file) {
				// a new file, such as a package-info.java, is named by the path it will have
				entry.put("file", file.toString()); //$NON-NLS-1$
			}
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

	/** The workspace paths of the files the change edits. */
	static List<String> files(Change change) {
		List<String> files = new ArrayList<>();
		if (change instanceof CompositeChange composite) {
			for (Change child : composite.getChildren()) {
				files.addAll(files(child));
			}
		} else if (change instanceof TextFileChange file) {
			files.add(file.getFile().getFullPath().toString());
		} else if (change instanceof ProjectOptionsChange options) {
			// the project's compiler options live in its JDT core preference file
			files.add("/" + options.projectName() + "/.settings/org.eclipse.jdt.core.prefs"); //$NON-NLS-1$ //$NON-NLS-2$
		} else if (change.getModifiedElement() instanceof IJavaElement element && element.getResource() != null) {
			// a move, rename or new file
			files.add(element.getResource().getFullPath().toString());
		} else if (change.getModifiedElement() instanceof IResource resource) {
			files.add(resource.getFullPath().toString());
		} else if (change.getModifiedElement() instanceof IPath path) {
			// a new file, such as a package-info.java, is named by the path it will have
			files.add(path.toString());
		}
		return files;
	}

	/** The files of the change whose buffer is still dirty, because an editor held unsaved edits to them. */
	static List<String> unsavedFiles(List<IPath> textFiles) {
		List<String> unsaved = new ArrayList<>();
		for (IPath path : textFiles) {
			ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(path, LocationKind.IFILE);
			if (buffer != null && buffer.isDirty()) {
				unsaved.add(path.toString());
			}
		}
		return unsaved;
	}

	/**
	 * The workspace paths of the files the change edits as text. Read before performing, since a performed
	 * {@link CompositeChange} drops its children.
	 */
	static List<IPath> textFiles(Change change) {
		List<IPath> paths = new ArrayList<>();
		if (change instanceof CompositeChange composite) {
			for (Change child : composite.getChildren()) {
				paths.addAll(textFiles(child));
			}
		} else if (change instanceof TextFileChange file) {
			paths.add(file.getFile().getFullPath());
		}
		return paths;
	}

	private static JsonObject describe(TextEdit edit, String content, SourceText lines) {
		int offset = edit.getOffset();
		int length = Math.max(0, edit.getLength());
		String newText = switch (edit) {
			case ReplaceEdit replace -> replace.getText();
			case InsertEdit insert -> insert.getText();
			case DeleteEdit delete -> ""; //$NON-NLS-1$
			default -> null;
		};
		JsonObject entry = new JsonObject().put("line", Integer.valueOf(lines.lineOf(Math.min(offset, content.length())))) //$NON-NLS-1$
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

}
