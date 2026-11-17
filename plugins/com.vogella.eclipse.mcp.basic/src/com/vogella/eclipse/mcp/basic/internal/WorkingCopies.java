package com.vogella.eclipse.mcp.basic.internal;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.UiDispatch;

/**
 * The working copy of a file: the shared text file buffer that an editor, a compare view or a
 * refactoring has connected, whose unsaved content the disk does not have yet.
 */
final class WorkingCopies {

	/**
	 * The text of a working copy, whether it differs from what is saved, and whether the
	 * buffer still matches the file it was loaded from.
	 */
	record Snapshot(String text, boolean dirty, boolean inSync) {
	}

	private WorkingCopies() {
	}

	/** The buffer somebody has connected for the file, or {@code null}. */
	static ITextFileBuffer find(IFile file) {
		ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
		return manager == null ? null : manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
	}

	/**
	 * The file's working copy, or {@code null} when nobody has one. The document belongs to the
	 * UI thread while an editor shows it, so it is read there, and a UI thread that does not get
	 * to it in time throws {@link java.util.concurrent.TimeoutException}.
	 */
	static Snapshot read(IFile file) throws Exception {
		ITextFileBuffer buffer = find(file);
		if (buffer == null) {
			return null;
		}
		return UiDispatch.call(
				() -> new Snapshot(buffer.getDocument().get(), buffer.isDirty(), buffer.isSynchronized()),
				CallBudget.uiWaitSeconds(0));
	}
}
