package com.vogella.eclipse.mcp.jdt.internal;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;

/**
 * Turns character offsets into line numbers, reading every file at most once.
 */
final class LineIndex {

	private static final int[] UNAVAILABLE = new int[0];

	// only the line starts are kept, since one index serves a whole workspace sweep
	private final Map<IFile, int[]> lineStarts = new HashMap<>();

	/** Returns the one-based line for {@code offset}, or {@code -1} when it cannot be determined. */
	int lineOf(IResource resource, int offset) {
		if (!(resource instanceof IFile file) || offset < 0) {
			return -1;
		}
		int[] starts = lineStarts.computeIfAbsent(file, LineIndex::read);
		return starts == UNAVAILABLE ? -1 : SourceText.lineOf(starts, offset);
	}

	private static int[] read(IFile file) {
		String content = FileText.read(file);
		return content == null ? UNAVAILABLE : SourceText.lineStartsOf(content);
	}
}
