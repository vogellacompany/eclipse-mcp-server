package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolRegistry;
import com.vogella.eclipse.mcp.core.McpToolResult;

/** Loads the git tools with JGit and EGit hidden from their class loader, the way an IDE whose EGit did not wire does. */
class GitToolsWithoutEGitTest {

	private static final String GIT_BUNDLE = "com.vogella.eclipse.mcp.git.";

	@Test
	void everyGitToolLoadsAndAnswersThatEGitIsNotWired() throws Exception {
		List<IMcpTool> tools = McpToolRegistry.getInstance().getTools().stream()
				.filter(tool -> tool.getClass().getName().startsWith(GIT_BUNDLE)).toList();
		assertFalse(tools.isEmpty(), "No git tools are registered");
		for (IMcpTool tool : tools) {
			ClassLoader loader = new WithoutGit(tool.getClass().getClassLoader());
			IMcpTool isolated = (IMcpTool) Class.forName(tool.getClass().getName(), true, loader)
					.getDeclaredConstructor().newInstance();
			McpToolResult answer = isolated.call(Map.of(), new NullProgressMonitor());
			// the EGit bundle is in this runtime, only hidden from the tool, which is the unwired case
			assertTrue(answer.isError() && answer.text().contains("not wired to it"),
					tool.getName() + " answered " + answer.text());
		}
	}

	/** Defines the git bundle's classes itself, so that they cannot reach JGit or EGit through the bundle's wiring. */
	private static final class WithoutGit extends ClassLoader {

		private final ClassLoader bundle;

		WithoutGit(ClassLoader bundle) {
			super(bundle);
			this.bundle = bundle;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("org.eclipse.jgit.") || name.startsWith("org.eclipse.egit.")) {
				throw new ClassNotFoundException(name);
			}
			if (!name.startsWith(GIT_BUNDLE)) {
				return super.loadClass(name, resolve);
			}
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded != null) {
					return loaded;
				}
				try (InputStream in = bundle.getResourceAsStream(name.replace('.', '/') + ".class")) {
					if (in == null) {
						throw new ClassNotFoundException(name);
					}
					byte[] bytes = in.readAllBytes();
					return defineClass(name, bytes, 0, bytes.length);
				} catch (IOException e) {
					throw new ClassNotFoundException(name, e);
				}
			}
		}
	}
}
