package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
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
public final class PrivateFiles {

	private static final String OWNER_ONLY = "rw-------"; //$NON-NLS-1$

	private PrivateFiles() {
	}

	/** Writes through a temporary file and an atomic move, so a second IDE never reads a missing token. */
	public static void write(Path path, String content) throws IOException {
		Path temporary = temporaryWith(path, content);
		try {
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException e) {
			deleteKeeping(temporary, e);
			throw e;
		}
	}

	/**
	 * Writes the file only if it does not exist yet, and says whether this call created it.
	 * Two IDEs starting at once both see no token, and a replacing write would leave the
	 * first one serving a token the file no longer holds.
	 */
	public static boolean create(Path path, String content) throws IOException {
		Path temporary = temporaryWith(path, content);
		try {
			try {
				// a hard link fails atomically when the name is taken, which a move does not
				Files.createLink(path, temporary);
			} catch (UnsupportedOperationException e) {
				Files.move(temporary, path);
			}
			return true;
		} catch (FileAlreadyExistsException e) {
			return false;
		} finally {
			// the link leaves the temporary name behind, and a failure to remove it must not hide the answer
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException e) {
				ILog.get().warn("Could not remove the temporary file %s".formatted(temporary), e); //$NON-NLS-1$
			}
		}
	}

	private static Path temporaryWith(Path path, String content) throws IOException {
		Path parent = path.toAbsolutePath().getParent();
		Files.createDirectories(parent);
		String prefix = path.getFileName() + "."; //$NON-NLS-1$
		Path temporary;
		try {
			temporary = Files.createTempFile(parent, prefix, ".tmp", //$NON-NLS-1$
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)));
		} catch (UnsupportedOperationException e) {
			// no POSIX permissions here, which is Windows, where the access rights are
			// an ACL and the file is created before one can be put on it
			temporary = Files.createTempFile(parent, prefix, ".tmp"); //$NON-NLS-1$
			restrictToOwner(temporary);
		}
		try {
			Files.writeString(temporary, content, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			deleteKeeping(temporary, e);
			throw e;
		}
		return temporary;
	}

	/** Removes a temporary file without letting a failure to do so replace the one being reported. */
	private static void deleteKeeping(Path temporary, Exception failure) {
		try {
			Files.deleteIfExists(temporary);
		} catch (IOException e) {
			failure.addSuppressed(e);
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
