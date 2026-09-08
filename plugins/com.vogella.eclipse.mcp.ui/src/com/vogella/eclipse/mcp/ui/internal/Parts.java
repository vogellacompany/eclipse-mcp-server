package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;

/**
 * Resolving the {@code part} argument of the tools that act on one part.
 * <p>
 * A part id is not unique: every Java editor is
 * {@code org.eclipse.jdt.ui.CompilationUnitEditor}, so a workspace with two
 * Java files open has as many parts under that id as it has editors, and only
 * one of them is on screen. Taking the first match and then testing it is what
 * every tool here did, which refused a capture of a part that was visible and
 * active, and silently acted on the wrong editor where nothing tested
 * visibility at all.
 * <p>
 * So the active part wins, then a visible one, then the first; and the
 * argument also accepts a part's title, which is what tells two parts under one
 * id apart in {@code eclipse_list_ui_targets}. The id is tried first, so a
 * caller that passes one keeps the answer it always had.
 */
final class Parts {

	/**
	 * What a lookup found: the reference, how the argument matched it, and what
	 * else carried the same id, which is the part an ambiguous answer has to say.
	 */
	record Match(IWorkbenchPartReference reference, String matchedBy, List<String> sharingId) {

		boolean shared() {
			return sharingId.size() > 1;
		}
	}

	private Parts() {
	}

	/** The reference a {@code part} argument names, or {@code null}. */
	static IWorkbenchPartReference reference(IWorkbenchPage page, String part) {
		Match match = find(page, part);
		return match == null ? null : match.reference();
	}

	/** The part a {@code part} argument names, created if it is not there yet. */
	static IWorkbenchPart part(IWorkbenchPage page, String part) {
		IWorkbenchPartReference reference = reference(page, part);
		return reference == null ? null : reference.getPart(true);
	}

	/**
	 * Resolves by id first and by title second, preferring the active part and
	 * then a visible one.
	 */
	static Match find(IWorkbenchPage page, String part) {
		if (page == null || part == null || part.isBlank()) {
			return null;
		}
		List<IWorkbenchPartReference> byId = new ArrayList<>();
		for (IWorkbenchPartReference candidate : ScreenshotTools.ListTargets.allReferences(page)) {
			if (part.equals(candidate.getId())) {
				byId.add(candidate);
			}
		}
		if (!byId.isEmpty()) {
			return new Match(preferred(page, byId), "id", titlesOf(byId)); //$NON-NLS-1$
		}
		List<IWorkbenchPartReference> byTitle = matching(page, part, true);
		if (byTitle.isEmpty()) {
			byTitle = matching(page, part, false);
		}
		return byTitle.isEmpty() ? null : new Match(preferred(page, byTitle), "title", titlesOf(byTitle)); //$NON-NLS-1$
	}

	/** Says which parts share the id, for an answer that had to choose between them. */
	static String ambiguityNote(Match match) {
		if (match == null || !match.shared()) {
			return null;
		}
		return "%d open parts share this id and one was chosen: the active part, or a visible one, or the first. They are %s; pass a title instead of the id to name one of them." //$NON-NLS-1$
				.formatted(Integer.valueOf(match.sharingId().size()), String.join(", ", match.sharingId())); //$NON-NLS-1$
	}

	private static List<IWorkbenchPartReference> matching(IWorkbenchPage page, String title, boolean exact) {
		String needle = title.toLowerCase(Locale.ROOT);
		List<IWorkbenchPartReference> found = new ArrayList<>();
		for (IWorkbenchPartReference candidate : ScreenshotTools.ListTargets.allReferences(page)) {
			String own = candidate.getTitle();
			if (own == null) {
				continue;
			}
			String lower = own.toLowerCase(Locale.ROOT);
			if (exact ? lower.equals(needle) : lower.contains(needle)) {
				found.add(candidate);
			}
		}
		return found;
	}

	/**
	 * The active part, else a visible one, else the first.
	 * <p>
	 * Visibility is read off the part only when it already exists:
	 * {@code getPart(true)} would build every hidden editor of the workspace just
	 * to ask whether it is on screen, and one that has never been restored is not.
	 */
	private static IWorkbenchPartReference preferred(IWorkbenchPage page, List<IWorkbenchPartReference> candidates) {
		IWorkbenchPart active = page.getActivePart();
		for (IWorkbenchPartReference candidate : candidates) {
			if (active != null && candidate.getPart(false) == active) {
				return candidate;
			}
		}
		for (IWorkbenchPartReference candidate : candidates) {
			IWorkbenchPart open = candidate.getPart(false);
			if (open != null && page.isPartVisible(open)) {
				return candidate;
			}
		}
		return candidates.get(0);
	}

	private static List<String> titlesOf(List<IWorkbenchPartReference> references) {
		List<String> titles = new ArrayList<>();
		for (IWorkbenchPartReference reference : references) {
			titles.add(reference.getTitle());
		}
		return titles;
	}
}
