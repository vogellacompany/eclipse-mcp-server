package com.vogella.eclipse.mcp.jdt.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reduces a Javadoc comment to its first sentence as plain text.
 */
public final class JavadocSummary {

	/** What a comment that only inherits its documentation answers. */
	public static final String INHERITED = "{@inheritDoc}"; //$NON-NLS-1$

	private static final int LIMIT = 200;

	private static final Pattern INLINE_TAG = Pattern.compile("\\{@(\\w+)\\s*([^}]*)\\}"); //$NON-NLS-1$

	private static final Pattern HTML = Pattern.compile("</?[A-Za-z][^>]*>"); //$NON-NLS-1$

	private JavadocSummary() {
	}

	/**
	 * The first sentence of {@code comment}, given with its delimiters, with inline tags reduced
	 * to their text and HTML removed; {@link #INHERITED} for a comment that only inherits; {@code null} when it
	 * has no main description.
	 */
	public static String of(String comment) {
		String body = description(comment);
		if (body.isEmpty()) {
			return null;
		}
		String plain = plain(body);
		if (plain.isEmpty()) {
			return body.contains(INHERITED) ? INHERITED : null;
		}
		int end = sentenceEnd(plain);
		String sentence = end < 0 ? plain : plain.substring(0, end + 1);
		return sentence.length() > LIMIT ? sentence.substring(0, LIMIT - 3) + "..." : sentence; //$NON-NLS-1$
	}

	/**
	 * The main description: the comment's lines without delimiters and stars, up to the first block tag. A line
	 * starting with {@code @} inside {@code <pre>} is code, such as an annotation, not a block tag.
	 */
	private static String description(String comment) {
		StringBuilder body = new StringBuilder();
		String inner = comment.strip().replaceFirst("^/\\*\\*", "").replaceFirst("\\*/$", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		boolean inPre = false;
		for (String line : inner.split("\\R")) { //$NON-NLS-1$
			String trimmed = line.strip().replaceFirst("^\\*+\\s?", "").strip(); //$NON-NLS-1$ //$NON-NLS-2$
			if (!inPre && trimmed.startsWith("@")) { //$NON-NLS-1$
				break;
			}
			String lower = trimmed.toLowerCase();
			inPre = lower.lastIndexOf("<pre") > lower.lastIndexOf("</pre") || inPre && !lower.contains("</pre"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if (!trimmed.isEmpty()) {
				body.append(body.isEmpty() ? "" : " ").append(trimmed); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return body.toString();
	}

	/** HTML is removed only outside inline tags, so {@code List<String>} inside a code tag survives. */
	private static String plain(String body) {
		StringBuilder plain = new StringBuilder();
		Matcher tag = INLINE_TAG.matcher(body);
		int last = 0;
		while (tag.find()) {
			plain.append(HTML.matcher(body.substring(last, tag.start())).replaceAll("")); //$NON-NLS-1$
			if (!"inheritDoc".equals(tag.group(1))) { //$NON-NLS-1$
				plain.append(label(tag.group(1), tag.group(2).strip()));
			}
			last = tag.end();
		}
		plain.append(HTML.matcher(body.substring(last)).replaceAll("")); //$NON-NLS-1$
		return plain.toString().replaceAll("\\s+", " ").strip(); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** A link reads as its label when it has one, otherwise as its target. */
	private static String label(String tag, String content) {
		if (tag.startsWith("link")) { //$NON-NLS-1$
			int space = content.indexOf(' ');
			if (space > 0) {
				return content.substring(space + 1).strip();
			}
			return content.startsWith("#") ? content.substring(1) : content; //$NON-NLS-1$
		}
		return content;
	}

	/** A period followed by whitespace or the end, unless it belongs to e.g. or i.e. */
	private static int sentenceEnd(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '.' && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)))
					&& !abbreviation(text, i)) {
				return i;
			}
		}
		return -1;
	}

	private static boolean abbreviation(String text, int period) {
		String before = text.substring(Math.max(0, period - 3), period + 1).toLowerCase();
		return before.endsWith("e.g.") || before.endsWith("i.e."); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
