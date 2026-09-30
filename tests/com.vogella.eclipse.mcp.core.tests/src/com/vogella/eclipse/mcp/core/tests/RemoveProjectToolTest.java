package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RemoveProjectToolTest {

	private static final String TOOL = "eclipse_remove_project";

	private static final String PROJECT = "mcp-remove-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void aDryRunKeepsTheProject() throws Exception {
		IProject project = fixture.createProject(PROJECT + "-a");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("projects", List.of(project.getName())));

		assertEquals(Boolean.TRUE, result.get("dryRun"));
		assertEquals(Integer.valueOf(0), result.get("removed"));
		assertTrue(project.exists());
	}

	@Test
	void removesTheWorkspaceEntryAndLeavesTheFilesOnDisk() throws Exception {
		IProject project = fixture.createProject(PROJECT + "-a");
		File location = project.getLocation().toFile();

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("projects", List.of(project.getName()), "dryRun", Boolean.FALSE));

		assertEquals(Integer.valueOf(1), result.get("removed"), result.toString());
		assertFalse(ResourcesPlugin.getWorkspace().getRoot().getProject(project.getName()).exists());
		try {
			assertTrue(new File(location, ".project").isFile(), "the working tree has to stay");
		} finally {
			deleteTree(location.toPath());
		}
	}

	@Test
	void refusesAProjectThatAnOpenDependentReferencesUnlessForced() throws Exception {
		IProject required = fixture.createProject(PROJECT + "-a");
		IProject dependent = fixture.createProject(PROJECT + "-b");
		IProjectDescription description = dependent.getDescription();
		description.setReferencedProjects(new IProject[] { required });
		dependent.setDescription(description, new NullProgressMonitor());

		Map<String, Object> refused = TestFixture.callAndParse(TOOL,
				Map.of("projects", List.of(required.getName()), "dryRun", Boolean.FALSE));

		assertEquals(Integer.valueOf(1), refused.get("refused"), refused.toString());
		assertTrue(required.exists());

		Map<String, Object> together = TestFixture.callAndParse(TOOL,
				Map.of("projects", List.of(required.getName(), dependent.getName()), "dryRun", Boolean.FALSE));

		assertEquals(Integer.valueOf(2), together.get("removed"), together.toString());
	}

	private static void deleteTree(Path root) throws IOException {
		try (var walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
