package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.TreeResources;

/** How eclipse_set_selection finds the tree item that stands for a resource. */
class TreeResourcesTest {

	// a private Display, disposed again, so no other test sees this thread as the UI thread
	private Display display;
	private Tree tree;

	@BeforeEach
	void open() {
		try {
			display = new Display();
		} catch (SWTError | SWTException | LinkageError e) {
			assumeTrue(false, "no display: " + e.getMessage());
			return;
		}
		tree = new Tree(new Shell(display), SWT.MULTI);
	}

	@AfterEach
	void close() {
		if (display != null) {
			display.dispose();
		}
	}

	private static IResource file(String path) {
		return ResourcesPlugin.getWorkspace().getRoot().getFile(IPath.fromPortableString(path));
	}

	private static TreeItem item(Object parent, Object data) {
		TreeItem item = parent instanceof Tree t ? new TreeItem(t, SWT.NONE) : new TreeItem((TreeItem) parent, SWT.NONE);
		item.setData(data);
		return item;
	}

	/** project, src, package, file: the file is four levels down, as in the Package Explorer. */
	private TreeItem packageWith(IResource file) {
		TreeItem project = item(tree, file.getProject());
		TreeItem src = item(project, file.getParent().getParent());
		TreeItem pkg = item(src, file.getParent());
		TreeItem leaf = item(pkg, file);
		project.setExpanded(true);
		src.setExpanded(true);
		pkg.setExpanded(true);
		return leaf;
	}

	@Test
	void aFileFourLevelsDownIsFound() {
		IResource file = file("/p/src/a/B.java");
		TreeItem leaf = packageWith(file);

		TreeResources.Found found = TreeResources.find(tree, Set.of(file.getFullPath()));

		assertSame(leaf.getData(), found.data().get(file.getFullPath()));
		assertFalse(found.truncated());
	}

	@Test
	void aCollapsedParentIsNotDescended() {
		IResource file = file("/p/src/a/B.java");
		packageWith(file).getParentItem().setExpanded(false);

		assertTrue(TreeResources.find(tree, Set.of(file.getFullPath())).data().isEmpty());
	}

	@Test
	void severalPathsAreMatchedInOneWalk() {
		IResource first = file("/p/src/a/B.java");
		IResource second = file("/q/src/c/D.java");
		packageWith(first);
		packageWith(second);

		TreeResources.Found found = TreeResources.find(tree, Set.of(first.getFullPath(), second.getFullPath()));

		assertEquals(2, found.data().size());
	}

	@Test
	void theShallowestItemWins() {
		IResource file = file("/p/src/a/B.java");
		packageWith(file);
		TreeItem top = item(tree, file);

		assertSame(top.getData(), TreeResources.find(tree, Set.of(file.getFullPath())).data().get(file.getFullPath()));
	}

	@Test
	void theCapIsReported() {
		IResource file = file("/p/src/a/B.java");
		packageWith(file);

		TreeResources.Found found = TreeResources.find(tree, Set.of(file.getFullPath()), 2);

		assertTrue(found.truncated());
		assertTrue(found.data().isEmpty());
	}
}
