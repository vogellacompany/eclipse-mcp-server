package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IPath;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/** Finds the items of a tree that stand for workspace resources. */
public final class TreeResources {

	static final int MAX_ITEMS = 20_000;

	/** The item data found per path, and whether the item cap cut the walk short. */
	public record Found(Map<IPath, Object> data, boolean truncated) {
	}

	private TreeResources() {
	}

	public static Found find(Tree tree, Set<IPath> wanted) {
		return find(tree, wanted, MAX_ITEMS);
	}

	/**
	 * Maps each wanted path to the data of its shallowest item, descending only
	 * into expanded items so that no lazy or virtual row is built.
	 */
	public static Found find(Tree tree, Set<IPath> wanted, int maxItems) {
		Map<IPath, Object> found = new HashMap<>();
		ArrayDeque<TreeItem> queue = new ArrayDeque<>(List.of(tree.getItems()));
		for (int seen = 0; !queue.isEmpty() && found.size() < wanted.size(); seen++) {
			if (seen >= maxItems) {
				return new Found(found, true);
			}
			TreeItem item = queue.poll();
			Object data = item.getData();
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
