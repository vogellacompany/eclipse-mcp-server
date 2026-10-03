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

	/** Writes through a temporary file and an atomic move, so a second IDE never reads a missing token. */
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
	 * Replaces a file's ACL with one entry granting its owner everything, the {@code rw-------} equivalent.
	 * It is set on the temporary file because the ACL travels through the atomic move.
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
