package com.vogella.eclipse.mcp.jdt.internal;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IAnnotatable;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMemberValuePair;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaModelException;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What marks a member as deprecated, with the annotation and the Javadoc tag kept apart because they often
 * disagree and either alone dates a deprecation to the wrong release.
 */
record Deprecation(boolean annotated, boolean tagged, boolean flagged, boolean forRemoval, String since, String note) {

	static final Deprecation NONE = new Deprecation(false, false, false, false, null, null);

	private static final String TAG = "@deprecated"; //$NON-NLS-1$

	/** Longest replacement advice carried in an answer, in characters. */
	private static final int MAX_NOTE = 400;

	private static final Pattern NEXT_BLOCK_TAG = Pattern.compile("\\n\\s*\\*?\\s*@[a-zA-Z]"); //$NON-NLS-1$

	boolean deprecated() {
		return annotated || tagged || flagged;
	}

	static Deprecation of(IMember member) throws JavaModelException {
		boolean annotated = false;
		boolean forRemoval = false;
		String since = null;
		if (member instanceof IAnnotatable annotatable) {
			for (IAnnotation annotation : annotatable.getAnnotations()) {
				if (!"Deprecated".equals(simpleName(annotation.getElementName()))) { //$NON-NLS-1$
					continue;
				}
				annotated = true;
				for (IMemberValuePair pair : annotation.getMemberValuePairs()) {
					if ("forRemoval".equals(pair.getMemberName())) { //$NON-NLS-1$
						forRemoval = Boolean.TRUE.equals(pair.getValue());
					} else if ("since".equals(pair.getMemberName()) && pair.getValue() != null) { //$NON-NLS-1$
						since = String.valueOf(pair.getValue());
					}
				}
			}
		}
		String note = javadocNote(member);
		boolean flagged = Flags.isDeprecated(member.getFlags());
		if (!annotated && note == null && !flagged) {
			return NONE;
		}
		return new Deprecation(annotated, note != null, flagged, forRemoval, since, note);
	}

	/** The text after the Javadoc tag, which is where the replacement is named. */
	private static String javadocNote(IMember member) throws JavaModelException {
		ISourceRange range = member.getJavadocRange();
		if (range == null || range.getLength() <= 0) {
			return null;
		}
		IOpenable openable = member.getOpenable();
		IBuffer buffer = openable == null ? null : openable.getBuffer();
		if (buffer == null) {
			return null;
		}
		String javadoc = buffer.getText(range.getOffset(), range.getLength());
		int at = javadoc.indexOf(TAG);
		if (at < 0) {
			return null;
		}
		String text = javadoc.substring(at + TAG.length());
		// stop at the next block tag
		Matcher next = NEXT_BLOCK_TAG.matcher(text);
		if (next.find()) {
			text = text.substring(0, next.start());
		}
		String cleaned = text.replaceAll("\\s*\\*/\\s*$", "").replaceAll("(?m)^\\s*\\*\\s?", " ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				.replaceAll("\\s+", " ").strip(); //$NON-NLS-1$ //$NON-NLS-2$
		if (cleaned.isEmpty()) {
			// a bare tag still marks the member as deprecated
			return ""; //$NON-NLS-1$
		}
		return cleaned.length() > MAX_NOTE ? cleaned.substring(0, MAX_NOTE) + "..." : cleaned; //$NON-NLS-1$
	}

	private static String simpleName(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? name : name.substring(dot + 1);
	}

	/** Whether this passes the caller's filter. */
	boolean matches(String filter) {
		return switch (filter) {
		case "yes" -> deprecated(); //$NON-NLS-1$
		case "no" -> !deprecated(); //$NON-NLS-1$
		case "forRemoval" -> forRemoval; //$NON-NLS-1$
		case "annotationOnly" -> annotated && !tagged; //$NON-NLS-1$
		case "javadocOnly" -> tagged && !annotated; //$NON-NLS-1$
		default -> true;
		};
	}

	/** Adds what is worth saying, and nothing when the member is not deprecated. */
	void describe(JsonObject entry) {
		if (!deprecated()) {
			return;
		}
		JsonArray by = new JsonArray();
		if (annotated) {
			by.add("annotation"); //$NON-NLS-1$
		}
		if (tagged) {
			by.add("javadoc"); //$NON-NLS-1$
		}
		if (by.size() == 0) {
			// neither is visible here, which is what a binary member looks like
			by.add("model"); //$NON-NLS-1$
		}
		entry.put("deprecated", Boolean.TRUE).put("deprecatedBy", by); //$NON-NLS-1$ //$NON-NLS-2$
		if (forRemoval) {
			entry.put("forRemoval", Boolean.TRUE); //$NON-NLS-1$
		}
		if (since != null) {
			entry.put("deprecatedSince", since); //$NON-NLS-1$
		}
		if (note != null && !note.isEmpty()) {
			entry.put("deprecationNote", note); //$NON-NLS-1$
		}
		if (annotated != tagged) {
			entry.put("deprecationMismatch", annotated //$NON-NLS-1$
					? "Annotated but with no @deprecated tag, so nothing here says since when or what to use instead. An annotation is often added in a later bulk sweep, so its commit does NOT date the deprecation." //$NON-NLS-1$
					: "Tagged in the Javadoc but not annotated, so the compiler raises no deprecation warning at call sites and a search for the annotation misses it entirely."); //$NON-NLS-1$
		}
	}
}
