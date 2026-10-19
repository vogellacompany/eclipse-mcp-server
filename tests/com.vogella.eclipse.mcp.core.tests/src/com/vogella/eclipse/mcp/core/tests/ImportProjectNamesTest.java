package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.ide.internal.ImportProjectTool;

/**
 * Every entry of an eclipse_import_project answer is named, so a caller can find a project by name.
 */
class ImportProjectNamesTest {

	@TempDir
	Path directory;

	private IProject project;

	@AfterEach
	void delete() throws Exception {
		if (project != null && project.exists()) {
			project.delete(false, true, null);
		}
	}

	@Test
	void anImportedProjectIsNamedAfterItselfAndFoundByItsLocation() throws Exception {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		project = workspace.getRoot().getProject("mcp-import-named");
		IProjectDescription description = workspace.newProjectDescription(project.getName());
		description.setLocation(IPath.fromPath(directory));
		project.create(description, null);

		File location = directory.toFile();
		IProject found = ImportProjectTool.projectAt(List.of(project), location);

		assertSame(project, found);
		assertEquals("mcp-import-named", ImportProjectTool.nameOf(location, found));
	}

	@Test
	void aDirectoryNotYetImportedIsNamedFromItsDotProject() throws Exception {
		Files.writeString(directory.resolve(".project"), """
				<?xml version="1.0" encoding="UTF-8"?>
				<projectDescription>
					<name>com.example.described</name>
					<comment></comment>
					<projects></projects>
					<buildSpec></buildSpec>
					<natures></natures>
				</projectDescription>
				""");

		assertEquals("com.example.described", ImportProjectTool.nameOf(directory.toFile(), null));
	}

	@Test
	void aDirectoryWithoutDotProjectHasNoNameUntilItIsImported() {
		assertNull(ImportProjectTool.nameOf(directory.toFile(), null));
	}
}
