package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IPath;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/** Finds the items of a tree that stand for workspace resources. */
public final class TreeResources {

	static final int MAX_ITEMS = 20_000;

	/** The item data found per path, and whether the item cap cut the walk short. */
	public record Found(Map<IPath, Object> data, boolean truncated) {
	}

	/**
	 * The requested elements as the viewer holds them; {@code unmatched} counts the
	 * resources the tree shows no item for, and nothing is searched without a tree.
	 */
	public record Matched(List<Object> elements, boolean searched, int count, int unmatched, boolean truncated) {
	}

	private TreeResources() {
	}

	/**
	 * Replaces each requested resource with the data of the item the tree shows for
	 * it; one without an item is kept as it was, since a provider that is not a tree
	 * may well accept it.
	 */
	public static Matched match(List<Object> requested, Tree tree) {
		if (tree == null) {
			return new Matched(requested, false, 0, 0, false);
		}
		Set<IPath> wanted = new HashSet<>();
		for (Object element : requested) {
			IResource resource = Adapters.adapt(element, IResource.class);
			if (resource != null) {
				wanted.add(resource.getFullPath());
			}
		}
		Found found = wanted.isEmpty() ? new Found(Map.of(), false) : find(tree, wanted);
		List<Object> shown = new ArrayList<>();
		int count = 0;
		int unmatched = 0;
		for (Object element : requested) {
			IResource resource = Adapters.adapt(element, IResource.class);
			Object match = resource == null ? null : found.data().get(resource.getFullPath());
			if (match != null) {
				count++;
			} else if (resource != null) {
				unmatched++;
			}
			shown.add(match == null ? element : match);
		}
		return new Matched(shown, true, count, unmatched, found.truncated());
	}

	public static Found find(Tree tree, Set<IPath> wanted) {
		return find(tree, wanted, MAX_ITEMS);
	}

	/**
	 * Maps each wanted path to the data of its shallowest item, descending only
	 * into expanded items and passing over virtual rows the viewer has not filled,
	 * so that no row is built.
	 */
	public static Found find(Tree tree, Set<IPath> wanted, int maxItems) {
		boolean virtual = (tree.getStyle() & SWT.VIRTUAL) != 0;
		Map<IPath, Object> found = new HashMap<>();
		ArrayDeque<TreeItem> queue = new ArrayDeque<>(List.of(tree.getItems()));
		for (int seen = 0; !queue.isEmpty() && found.size() < wanted.size(); seen++) {
			if (seen >= maxItems) {
				return new Found(found, true);
			}
			TreeItem item = queue.poll();
			Object data = item.getData();
			// getItems on an unfilled virtual row fires SetData, which runs the label provider
			if (data == null && virtual) {
				continue;
			}
			IResource shown = data == null ? null : Adapters.adapt(data, IResource.class);
			if (shown != null && wanted.contains(shown.getFullPath())) {
				found.putIfAbsent(shown.getFullPath(), data);
			}
			if (item.getExpanded()) {
				queue.addAll(List.of(item.getItems()));
			}
		}
		return new Found(found, false);
	}
}
