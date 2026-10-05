package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PartInitException;

/**
 * Resolves the {@code part} argument of the UI tools to one open part.
 * <p>
 * The argument is a part id, optionally qualified as {@code id@qualifier} where the
 * qualifier is the workspace path of the editor input or the part title; a bare
 * path starting with {@code /} names an editor by input alone. Several parts with
 * one id are told apart by the qualifier, and without one the active part wins,
 * then a visible one, then the first, so a hidden tab is never preferred silently.
 */
public final class PartAddress {

	private static final char SEPARATOR = '@';

	private PartAddress() {
	}

	/** A part as the matcher sees it; {@code ref} is whatever the caller resolves to. */
	public record Candidate<T>(T ref, String id, String title, String path, boolean active, boolean visible) {
	}

	/** A parsed argument; {@code id} is null for a bare input path, {@code qualifier} null when absent. */
	public record Query(String id, String qualifier) {
	}

	public static Query parse(String argument) {
		if (argument.startsWith("/")) { //$NON-NLS-1$
			return new Query(null, argument);
		}
		int at = argument.indexOf(SEPARATOR);
		if (at < 0) {
			return new Query(argument, null);
		}
		return new Query(argument.substring(0, at), argument.substring(at + 1));
	}

	/** The candidate the query selects, or null when none matches. */
	public static <T> Candidate<T> choose(List<Candidate<T>> candidates, Query query) {
		List<Candidate<T>> matching = new ArrayList<>();
		for (Candidate<T> candidate : candidates) {
			if ((query.id() == null || query.id().equals(candidate.id()))
					&& (query.qualifier() == null || query.qualifier().equals(candidate.path())
							|| query.qualifier().equals(candidate.title()))) {
				matching.add(candidate);
			}
		}
		for (Candidate<T> candidate : matching) {
			if (candidate.active()) {
				return candidate;
			}
		}
		for (Candidate<T> candidate : matching) {
			if (candidate.visible()) {
				return candidate;
			}
		}
		return matching.isEmpty() ? null : matching.get(0);
	}

	/** The argument that selects this candidate, or its plain id when the id is unique; parts equal in id, path and title cannot be told apart. */
	public static String address(Candidate<?> candidate, List<? extends Candidate<?>> all) {
		long sameId = all.stream().filter(other -> other.id().equals(candidate.id())).count();
		if (sameId < 2) {
			return candidate.id();
		}
		String qualifier = candidate.path() != null ? candidate.path() : candidate.title();
		return qualifier == null ? candidate.id() : candidate.id() + SEPARATOR + qualifier;
	}

	/** The refusal for a qualified argument that matches nothing. */
	public static String refusal(String argument, List<? extends Candidate<?>> all) {
		Set<String> addresses = new LinkedHashSet<>();
		for (Candidate<?> candidate : all) {
			addresses.add(address(candidate, all));
		}
		return "No open part matches '%s'. Open parts: %s.".formatted(argument, String.join(", ", addresses)); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** The candidates of a page, views first and then editors. */
	public static List<Candidate<IWorkbenchPartReference>> candidates(IWorkbenchPage page) {
		IWorkbenchPartReference active = page.getActivePartReference();
		List<Candidate<IWorkbenchPartReference>> result = new ArrayList<>();
		for (IWorkbenchPartReference reference : ScreenshotTools.ListTargets.allReferences(page)) {
			var part = reference.getPart(false);
			result.add(new Candidate<>(reference, reference.getId(), reference.getTitle(), inputPath(reference),
					reference == active, part != null && page.isPartVisible(part)));
		}
		return result;
	}

	/**
	 * The reference the argument names, or null when a plain id matches nothing.
	 *
	 * @throws IllegalStateException when a qualified argument matches nothing
	 */
	public static IWorkbenchPartReference find(IWorkbenchPage page, String argument) {
		Query query = parse(argument);
		List<Candidate<IWorkbenchPartReference>> candidates = candidates(page);
		Candidate<IWorkbenchPartReference> chosen = choose(candidates, query);
		if (chosen != null) {
			return chosen.ref();
		}
		if (query.qualifier() != null) {
			throw new IllegalStateException(refusal(argument, candidates));
		}
		return null;
	}

	/** The workspace path of an editor's input, or null for a view or an input that is no workspace file. */
	public static String inputPath(IWorkbenchPartReference reference) {
		if (!(reference instanceof IEditorReference editor)) {
			return null;
		}
		try {
			IEditorInput input = editor.getEditorInput();
			IFile file = input == null ? null : Adapters.adapt(input, IFile.class);
			return file == null ? null : file.getFullPath().toString();
		} catch (PartInitException e) {
			return null;
		}
	}
}
