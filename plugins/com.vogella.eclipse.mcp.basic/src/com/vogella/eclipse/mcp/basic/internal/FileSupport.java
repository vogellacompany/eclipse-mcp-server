package com.vogella.eclipse.mcp.basic.internal;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.WorkspaceSync;

/**
 * Helpers shared by the tools that read or write a single workspace file.
 */
final class FileSupport {

	private FileSupport() {
	}

	/** Refreshes the file, or its nearest existing ancestor when it is not in the resource tree yet. */
	static void refresh(IFile file, IProgressMonitor monitor) throws CoreException {
		IResource target = file;
		while (target != null && !target.exists()) {
			target = target.getParent();
		}
		WorkspaceSync.refresh(target, monitor);
	}

	/** The folders between the project and the file that do not exist yet, outermost first. */
	static List<IFolder> missingParents(IFile file) {
		List<IFolder> missing = new ArrayList<>();
		IContainer parent = file.getParent();
		while (parent instanceof IFolder folder && !folder.exists()) {
			missing.add(0, folder);
			parent = folder.getParent();
		}
		return missing;
	}

	static boolean isBinary(byte[] bytes) {
		for (int i = 0; i < Math.min(bytes.length, 8000); i++) {
			if (bytes[i] == 0) {
				return true;
			}
		}
		return false;
	}

	/** The charset Eclipse has for the file, falling back to UTF-8 when it is unknown. */
	static String charset(IFile file) {
		try {
			String name = file.getCharset();
			return Charset.isSupported(name) ? name : "UTF-8"; //$NON-NLS-1$
		} catch (CoreException | IllegalArgumentException e) {
			return "UTF-8"; //$NON-NLS-1$
		}
	}
}
