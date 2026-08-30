package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

import org.eclipse.core.runtime.ILog;

/**
 * Writes the files that carry the bearer token, readable by their owner only.
 */
final class PrivateFiles {

	private static final String OWNER_ONLY = "rw-------"; //$NON-NLS-1$

	private PrivateFiles() {
	}

	/**
	 * Writes through a temporary file and moves it into place.
	 * <p>
	 * Truncating in place leaves a window in which the file does not exist, and a
	 * second IDE starting at that moment reads no token and mints a new one, which
	 * silently invalidates every client of the first. A move is atomic, so a reader
	 * sees either the old content or the new one.
	 */
	static void write(Path path, String content) throws IOException {
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp"); //$NON-NLS-1$
		Files.deleteIfExists(temporary);
		try {
			Files.createFile(temporary,
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)));
		} catch (UnsupportedOperationException e) {
			// no POSIX permissions here, which is Windows, where the access rights are
			// an ACL and the file is created before one can be put on it
			Files.createFile(temporary);
			restrictToOwner(temporary);
		}
		Files.writeString(temporary, content, StandardCharsets.UTF_8);
		try {
			Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Replaces a file's ACL with one entry granting its owner everything, which is
	 * what {@code rw-------} means on a filesystem that has no POSIX permissions.
	 * <p>
	 * Without this the token file inherits the directory's rights on Windows. That
	 * is usually the user profile and usually narrow enough, but "usually" is not a
	 * property to give a secret, and the failure is silent: a world readable token
	 * looks exactly like a private one. The ACL travels with the file through the
	 * atomic move, so it is set on the temporary file rather than afterwards.
	 * <p>
	 * A filesystem without ACLs either is not reached here, since it would have had
	 * POSIX permissions, or cannot express the restriction at all, and a token the
	 * server cannot write is worse than one whose file it could not narrow.
	 */
	private static void restrictToOwner(Path path) {
		AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
		if (acl == null) {
			return;
		}
		try {
			UserPrincipal owner = acl.getOwner();
			acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
					.setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
		} catch (IOException | RuntimeException e) {
			ILog.get().warn("Could not restrict %s to its owner, so it keeps the access rights of its directory" //$NON-NLS-1$
					.formatted(path), e);
		}
	}
}
