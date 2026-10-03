package com.vogella.eclipse.mcp.server.internal;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.server.McpEndpoint;

/**
 * The discovery file under the bundle state location, so that clients do not have to be
 * configured with the port and the token by hand.
 */
public final class EndpointFile {

	private static final String FILE_NAME = "endpoint.json"; //$NON-NLS-1$

	private EndpointFile() {
	}

	/** The absolute path of the discovery file, whether or not it exists. */
	public static Path location() {
		return Platform.getStateLocation(FrameworkUtil.getBundle(EndpointFile.class)).append(FILE_NAME).toFile()
				.toPath();
	}

	public static void write(McpEndpoint endpoint) {
		Path path = location();
		// startedAt identifies this process, so a client can tell a restarted IDE from the old one still answering
		String json = new JsonObject().put("state", "listening") //$NON-NLS-1$ //$NON-NLS-2$
				.put("url", endpoint.url()).put("token", endpoint.token()) //$NON-NLS-1$ //$NON-NLS-2$
				.put("workspace", workspace()) //$NON-NLS-1$
				.put("startedAt", System.currentTimeMillis()).toString(); //$NON-NLS-1$
		try {
			PrivateFiles.write(path, json);
		} catch (IOException e) {
			ILog.get().error("Could not write the MCP endpoint file " + path, e); //$NON-NLS-1$
		}
	}

	/** Records that the server stopped instead of removing the file, so a failed self update leaves evidence. */
	public static void markStopped() {
		Path path = location();
		String json = new JsonObject().put("state", "stopped") //$NON-NLS-1$ //$NON-NLS-2$
				.put("stoppedAt", System.currentTimeMillis()) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"The MCP server is not listening. If this followed an update of the server itself, the update may have stopped this bundle without finishing; restarting Eclipse brings it back.") //$NON-NLS-1$
				.toString();
		try {
			PrivateFiles.write(path, json);
		} catch (IOException e) {
			ILog.get().warn("Could not write the MCP endpoint file " + path, e); //$NON-NLS-1$
		}
	}

	/** The workspace this server serves, so a client can tell which one it reached. */
	static String workspace() {
		var location = Platform.getInstanceLocation();
		URL url = location == null ? null : location.getURL();
		if (url == null) {
			return null;
		}
		try {
			return new File(url.toURI()).getAbsolutePath();
		} catch (URISyntaxException | IllegalArgumentException e) {
			return url.getPath();
		}
	}

}
