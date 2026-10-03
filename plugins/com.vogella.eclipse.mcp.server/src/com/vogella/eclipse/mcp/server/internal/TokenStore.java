package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.UserScope;
import org.osgi.framework.FrameworkUtil;

/**
 * The bearer token, kept in the user area so that it survives IDE restarts and is the
 * same for every workspace this user opens.
 */
public final class TokenStore {

	private static final String FILE_NAME = "token"; //$NON-NLS-1$

	private static final String BUNDLE_NAME = "com.vogella.eclipse.mcp.server"; //$NON-NLS-1$

	/** Puts the token somewhere else, which is how a test instance stays out of the real one. */
	private static final String DIRECTORY_PROPERTY = "com.vogella.eclipse.mcp.tokenDirectory"; //$NON-NLS-1$

	private TokenStore() {
	}

	/**
	 * The token file under the user scope location, {@code ~/.eclipse}.
	 * A workspace scoped token would give every workspace a different secret behind one port,
	 * and it is a plain file because preference files are world readable.
	 */
	static Path location() {
		// otherwise a test regenerating the token replaces the one the developer's own IDE serves
		String override = System.getProperty(DIRECTORY_PROPERTY);
		if (override != null && !override.isBlank()) {
			return Path.of(override.strip()).resolve(FILE_NAME);
		}
		IPath area = UserScope.INSTANCE.getLocation();
		Path root = area == null ? Path.of(System.getProperty("user.home"), ".eclipse") //$NON-NLS-1$ //$NON-NLS-2$
				: Path.of(area.toOSString());
		return root.resolve(BUNDLE_NAME).resolve(FILE_NAME);
	}

	/** Where the token used to live, one per workspace. */
	private static Path workspaceLocation() {
		return Platform.getStateLocation(FrameworkUtil.getBundle(TokenStore.class)).append(FILE_NAME).toFile().toPath();
	}

	/** The stored token, generating and storing one on first use. */
	public static synchronized String get() {
		Path path = location();
		String token = read(path);
		if (token != null) {
			return token;
		}
		// an IDE that already had a workspace token keeps it, so that a client
		// registered before this moved to user scope is not silently orphaned
		Path workspaceToken = workspaceLocation();
		String inherited = read(workspaceToken);
		if (inherited != null) {
			String adopted = store(inherited, path);
			retire(workspaceToken);
			return adopted;
		}
		return regenerate();
	}

	/** Replaces the stored token and returns the new one. */
	public static synchronized String regenerate() {
		return store(UUID.randomUUID().toString(), location());
	}

	/** Renames the adopted workspace token to {@code token.migrated}, so it no longer looks like current state. */
	private static void retire(Path path) {
		try {
			Files.move(path, path.resolveSibling(FILE_NAME + ".migrated"), //$NON-NLS-1$
					StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			ILog.get().warn("Could not rename the migrated workspace token %s".formatted(path), e); //$NON-NLS-1$
		}
	}

	private static String read(Path path) {
		try {
			if (Files.exists(path)) {
				String token = Files.readString(path, StandardCharsets.UTF_8).strip();
				if (!token.isEmpty()) {
					return token;
				}
			}
		} catch (IOException e) {
			ILog.get().warn("Could not read the MCP token from %s, generating a new one".formatted(path), e); //$NON-NLS-1$
		}
		return null;
	}

	private static String store(String token, Path path) {
		try {
			PrivateFiles.write(path, token);
		} catch (IOException e) {
			ILog.get().error(
					"Could not store the MCP token in %s, it will change again on the next restart".formatted(path), e); //$NON-NLS-1$
		}
		return token;
	}
}
