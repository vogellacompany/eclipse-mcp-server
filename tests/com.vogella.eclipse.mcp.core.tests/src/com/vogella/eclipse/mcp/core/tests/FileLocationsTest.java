package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.FileLocations;

/**
 * The conversion every {@code Location} in the framework needs, and the two
 * shapes that used to break it off Linux: a Windows drive letter and a path with
 * a space in it.
 */
class FileLocationsTest {

	@Test
	void aFileUrlRoundTripsOnEveryWindowSystem() throws Exception {
		Path directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("mcp-locations");

		assertEquals(directory, FileLocations.pathOf(directory.toUri().toString()));
		assertEquals(directory, FileLocations.pathOf(directory.toUri().toURL().toString()));
	}

	@Test
	void aPlainPathIsTakenAsItIs() {
		Path directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();

		assertEquals(directory, FileLocations.pathOf(directory.toString()));
	}

	@Test
	void anEncodedSpaceIsDecoded() {
		// an installation under "Program Files" is the ordinary case of this, and
		// url.getPath() used to hand the %20 straight through into a path
		Path directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("with space");

		assertEquals(directory, FileLocations.pathOf(directory.toUri().toString()));
	}

	@Test
	void anUnencodedSpaceIsStillAPath() {
		// Equinox does not always encode what it puts in a Location URL, and then the
		// value is not a URI at all, so URI.create throws rather than answering
		Path directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("with space");
		String raw = "file:" + directory.toString().replace(java.io.File.separatorChar, '/');

		assertEquals(directory, FileLocations.pathOf(FileLocations.isWindows() ? "file:/" + raw.substring(5) : raw));
	}

	@Test
	void nothingIsNothing() {
		assertNull(FileLocations.pathOf((String) null));
		assertNull(FileLocations.pathOf("  "));
		assertNull(FileLocations.pathOf((java.net.URL) null));
	}
}
