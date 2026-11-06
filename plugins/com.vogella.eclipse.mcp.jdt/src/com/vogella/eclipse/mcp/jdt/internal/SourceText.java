package com.vogella.eclipse.mcp.jdt.internal;

import java.util.Arrays;

/**
 * A text with its line starts, so every offset turns into a line by binary search.
 */
record SourceText(String content, int[] lineStarts) {

	/** Indexes the line starts of {@code content}. */
	static SourceText of(String content) {
		return new SourceText(content, lineStartsOf(content));
	}

	/** The offsets at which the lines of {@code content} start. */
	static int[] lineStartsOf(String content) {
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

	/** The one-based line of {@code offset}. */
	int lineOf(int offset) {
		return lineOf(lineStarts, offset);
	}

	/** The one-based line of {@code offset} among {@code lineStarts}. */
	static int lineOf(int[] lineStarts, int offset) {
		int index = Arrays.binarySearch(lineStarts, offset);
		return index >= 0 ? index + 1 : -index - 1;
	}
}
