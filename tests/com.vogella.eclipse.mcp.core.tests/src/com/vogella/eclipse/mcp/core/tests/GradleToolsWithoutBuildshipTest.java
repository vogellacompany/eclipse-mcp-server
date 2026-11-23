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

/** Loads the Gradle tools with Buildship and the Tooling API hidden from their class loader, the way an IDE without Buildship does. */
class GradleToolsWithoutBuildshipTest {

	private static final String GRADLE_BUNDLE = "com.vogella.eclipse.mcp.gradle.";

	@Test
	void everyGradleToolLoadsAndAnswersThatBuildshipIsNotUsable() throws Exception {
		List<IMcpTool> tools = McpToolRegistry.getInstance().getTools().stream()
				.filter(tool -> tool.getClass().getName().startsWith(GRADLE_BUNDLE))
				// the run tool only reads the registry, which needs no Buildship
				.filter(tool -> !tool.getName().equals("eclipse_get_gradle_run")).toList();
		assertFalse(tools.isEmpty(), "No Gradle tools are registered");
		for (IMcpTool tool : tools) {
			ClassLoader loader = new WithoutBuildship(tool.getClass().getClassLoader());
			IMcpTool isolated = (IMcpTool) Class.forName(tool.getClass().getName(), true, loader)
					.getDeclaredConstructor().newInstance();
			McpToolResult answer = isolated.call(Map.of(), new NullProgressMonitor());
			// whether Buildship is in this runtime or not, the tool only sees it missing, and says so
			assertTrue(answer.isError() && answer.text().startsWith("Buildship"),
					tool.getName() + " answered " + answer.text());
		}
	}

	/** Defines the Gradle bundle's classes itself, so that they cannot reach Buildship or the Tooling API through the bundle's wiring. */
	private static final class WithoutBuildship extends ClassLoader {

		private final ClassLoader bundle;

		WithoutBuildship(ClassLoader bundle) {
			super(bundle);
			this.bundle = bundle;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("org.eclipse.buildship.") || name.startsWith("org.gradle.")) {
				throw new ClassNotFoundException(name);
			}
			if (!name.startsWith(GRADLE_BUNDLE)) {
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
