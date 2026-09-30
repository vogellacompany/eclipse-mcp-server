package com.vogella.eclipse.mcp.jdt.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;

/**
 * Reads workspace files as text and picks headers out of a manifest.
 */
final class FileText {

	private FileText() {
	}

	/** The file's content in its own charset, or {@code null} when it cannot be read. */
	static String read(IFile file) {
		try (InputStream in = file.getContents(true)) {
			return new String(in.readAllBytes(), Charset.forName(file.getCharset()));
		} catch (CoreException | IOException | IllegalArgumentException e) {
			return null;
		}
	}

	/** The value of a manifest header, continuation lines joined, or {@code null} when absent or unreadable. */
	static String manifestHeader(IFile manifest, String name) {
		if (!manifest.exists()) {
			return null;
		}
		String content = read(manifest);
		if (content == null) {
			return null;
		}
		// continuation lines start with a single space
		for (String line : content.replace("\r\n", "\n").replace("\n ", "").split("\n")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			if (line.startsWith(name + ":")) { //$NON-NLS-1$
				return line.substring(name.length() + 1).trim();
			}
		}
		return null;
	}

	/** The Bundle-SymbolicName of a manifest without its directives, or {@code null}. */
	static String symbolicName(IFile manifest) {
		String header = manifestHeader(manifest, "Bundle-SymbolicName"); //$NON-NLS-1$
		return header == null ? null : header.split(";", 2)[0].trim(); //$NON-NLS-1$
	}

	/** The one-based line of an offset in {@code content}. */
	static int lineOf(String content, int offset) {
		int line = 1;
		for (int i = 0; i < offset && i < content.length(); i++) {
			if (content.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}
}
