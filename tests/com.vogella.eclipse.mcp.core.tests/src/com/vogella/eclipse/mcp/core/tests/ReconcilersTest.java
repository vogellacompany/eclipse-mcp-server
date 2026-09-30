package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.Reconcilers;
import com.vogella.eclipse.mcp.ui.internal.Reconcilers.Verdict;

/**
 * The parts of the reconciler probe that need no real source viewer.
 */
class ReconcilersTest {

	static class NotATextEditor {
	}

	static class EditorWithoutViewer {
		Object getSourceViewer() {
			return null;
		}
	}

	static class EditorWithForeignViewer {
		Object getSourceViewer() {
			return new Object();
		}
	}

	@Test
	void anEditorWithoutASourceViewerIsNotACandidate() {
		assertEquals(Verdict.NONE, Reconcilers.verdict(new NotATextEditor()));
		assertEquals(Verdict.NONE, Reconcilers.verdict(new EditorWithoutViewer()));
	}

	@Test
	void aViewerThatIsNotASourceViewerIsNotACandidate() {
		assertEquals(Verdict.NONE, Reconcilers.verdict(new EditorWithForeignViewer()));
	}
}
