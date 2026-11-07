package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;

import org.eclipse.core.runtime.ILog;

/**
 * Stores the last successfully bound MCP server port alongside the endpoint file.
 */
public final class LastPortFile {

	private static final String FILE_NAME = "port.properties"; //$NON-NLS-1$

	private static final String LAST_PORT = "lastPort"; //$NON-NLS-1$

	private LastPortFile() {
	}

	/** Reads the previous port, or returns {@code -1} when the file has no valid port. */
	public static int read() {
		Path path;
		try {
			path = EndpointFile.location().resolveSibling(FILE_NAME);
		} catch (RuntimeException e) {
			ILog.get().warn("Could not locate the saved MCP port file", e); //$NON-NLS-1$
			return -1;
		}
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (NoSuchFileException e) {
			return -1;
		} catch (IOException | RuntimeException e) {
			ILog.get().warn("Could not read the saved MCP port from " + path, e); //$NON-NLS-1$
			return -1;
		}

		String value = properties.getProperty(LAST_PORT);
		if (value != null) {
			try {
				int port = Integer.parseInt(value);
				if (port > 0 && port <= 65_535) {
					return port;
				}
			} catch (NumberFormatException e) {
				// Ignore invalid data and let the operating system choose a port.
			}
		}
		ILog.get().warn("Ignoring an invalid saved MCP port in " + path); //$NON-NLS-1$
		return -1;
	}

	/** Saves the successfully bound port without making a file error fatal to startup. */
	public static void write(int port) {
		try {
			Path path = EndpointFile.location().resolveSibling(FILE_NAME);
			PrivateFiles.write(path, LAST_PORT + "=" + port + System.lineSeparator()); //$NON-NLS-1$
		} catch (IOException | RuntimeException e) {
			ILog.get().warn("Could not save the last MCP port", e); //$NON-NLS-1$
		}
	}
}
