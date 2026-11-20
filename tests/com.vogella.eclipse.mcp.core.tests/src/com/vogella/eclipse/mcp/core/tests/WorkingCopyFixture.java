package com.vogella.eclipse.mcp.core.tests;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;

/**
 * A test project whose files can be given a working copy, the way an open editor does, and
 * which disconnects those again before the project goes.
 */
final class WorkingCopyFixture {

	private final TestFixture fixture = new TestFixture();

	private final String projectName;

	private final List<IPath> connected = new ArrayList<>();

	WorkingCopyFixture(String projectName) {
		this.projectName = projectName;
	}

	IFile write(String name, String content) throws Exception {
		IProject project = fixture.createProject(projectName);
		IFile file = project.getFile(name);
		file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());
		return file;
	}

	ITextFileBuffer connect(IFile file) throws Exception {
		ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
		manager.connect(file.getFullPath(), LocationKind.IFILE, new NullProgressMonitor());
		connected.add(file.getFullPath());
		return manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
	}

	/**
	 * Changes the file behind the workspace's back, as a shell would, and moves its modification
	 * time on: a write within the same timestamp tick as the previous one looks unchanged to a
	 * refresh, which then reloads nothing.
	 */
	static void writeOutside(IFile file, String content) throws Exception {
		Path path = file.getLocation().toPath();
		Files.writeString(path, content, StandardCharsets.UTF_8);
		Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 2000));
	}

	void dispose() throws Exception {
		ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
		for (IPath path : connected) {
			manager.disconnect(path, LocationKind.IFILE, new NullProgressMonitor());
		}
		connected.clear();
		fixture.dispose();
	}
}
