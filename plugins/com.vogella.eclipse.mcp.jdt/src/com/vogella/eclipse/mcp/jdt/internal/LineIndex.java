package com.vogella.eclipse.mcp.jdt.internal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;

/**
 * Turns character offsets into line numbers, reading every file at most once.
 */
final class LineIndex {

	private static final int[] UNAVAILABLE = new int[0];

	private final Map<IFile, int[]> lineStarts = new HashMap<>();

	/** Returns the one-based line for {@code offset}, or {@code -1} when it cannot be determined. */
	int lineOf(IResource resource, int offset) {
		if (!(resource instanceof IFile file) || offset < 0) {
			return -1;
		}
		int[] starts = lineStarts.computeIfAbsent(file, LineIndex::readLineStarts);
		if (starts.length == 0) {
			return -1;
		}
		int index = Arrays.binarySearch(starts, offset);
		return index >= 0 ? index + 1 : -index - 1;
	}

	private static int[] readLineStarts(IFile file) {
		String content = FileText.read(file);
		if (content == null) {
			return UNAVAILABLE;
		}
		int[] starts = new int[16];
		int count = 0;
		starts[count++] = 0;
		for (int i = 0; i < content.length(); i++) {
			if (content.charAt(i) == '\n') {
				if (count == starts.length) {
					starts = Arrays.copyOf(starts, count * 2);
				}
				starts[count++] = i + 1;
			}
		}
		return Arrays.copyOf(starts, count);
	}
}
