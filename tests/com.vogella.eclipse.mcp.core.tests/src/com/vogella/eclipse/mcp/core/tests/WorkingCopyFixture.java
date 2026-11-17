package com.vogella.eclipse.mcp.core.tests;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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

	void dispose() throws Exception {
		ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
		for (IPath path : connected) {
			manager.disconnect(path, LocationKind.IFILE, new NullProgressMonitor());
		}
		connected.clear();
		fixture.dispose();
	}
}
