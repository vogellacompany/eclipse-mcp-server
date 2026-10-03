package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.basic.internal.SubstituteBundleTool;

/**
 * Packing a project must never leave the project. A CRLF build.properties
 * once left a bare backslash among the bin.includes, which on Windows resolves
 * to the drive root, and the pack walked C:\ until it hit the recycle bin.
 */
class SubstituteBundlePackTest {

	@TempDir
	Path temp;

	@Test
	void aCrlfBuildPropertiesPacksEveryEntryAndStaysInsideTheProject() throws Exception {
		Path project = plugin("bin.includes = plugin.properties,\\\r\n" //
				+ "               plugin.xml,\\\r\n" //
				+ "               icons/,\\\r\n" //
				+ "               .,\\\r\n" //
				+ "               META-INF/\r\n" //
				+ "source.. = src/\r\n" //
				+ "output.. = bin/\r\n");
		Path jar = temp.resolve("packed.jar");

		SubstituteBundleTool.Packed packed = SubstituteBundleTool.pack(project, jar);

		assertEquals(Set.of("META-INF/MANIFEST.MF", "a/B.class", "icons/x.png", "plugin.properties", "plugin.xml"),
				entries(jar));
		assertEquals(5, packed.entries());
		assertTrue(packed.unreadable().isEmpty());
	}

	@Test
	void anEntryOutsideTheProjectIsRefusedByName() throws Exception {
		Path project = plugin("bin.includes = META-INF/,../\n");
		Path jar = temp.resolve("packed.jar");

		IOException e = assertThrows(IOException.class, () -> SubstituteBundleTool.pack(project, jar));

		assertTrue(e.getMessage().contains("'../'"), e.getMessage());
		assertTrue(e.getMessage().contains(project.toString()), e.getMessage());
	}

	@Test
	void anUnreadableDirectoryIsReportedRatherThanEndingThePack() throws Exception {
		Path project = plugin("bin.includes = META-INF/,icons/\n");
		Path locked = Files.createDirectories(project.resolve("icons/locked"));
		Files.writeString(locked.resolve("hidden.png"), "x");
		try {
			Files.setPosixFilePermissions(locked, Set.<PosixFilePermission>of());
		} catch (UnsupportedOperationException e) {
			assumeTrue(false, "no POSIX permissions here");
		}
		assumeTrue(!Files.isReadable(locked), "permissions are not enforced for this user");
		Path jar = temp.resolve("packed.jar");
		try {
			SubstituteBundleTool.Packed packed = SubstituteBundleTool.pack(project, jar);

			assertEquals(List.of(locked.toString()), packed.unreadable());
			assertTrue(entries(jar).contains("icons/x.png"));
			assertFalse(entries(jar).contains("icons/locked/hidden.png"));
		} finally {
			Files.setPosixFilePermissions(locked, Set.of(PosixFilePermission.OWNER_READ,
					PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
		}
	}

	private Path plugin(String buildProperties) throws IOException {
		Path project = Files.createDirectories(temp.resolve("project"));
		Files.writeString(project.resolve("build.properties"), buildProperties);
		Files.writeString(project.resolve(".classpath"),
				"<classpath><classpathentry kind=\"src\" path=\"src\"/><classpathentry kind=\"output\" path=\"bin\"/></classpath>");
		Files.createDirectories(project.resolve("META-INF"));
		Files.writeString(project.resolve("META-INF/MANIFEST.MF"), "Bundle-SymbolicName: p\nBundle-Version: 1.0.0\n");
		Files.writeString(project.resolve("plugin.xml"), "<plugin/>");
		Files.writeString(project.resolve("plugin.properties"), "name=p");
		Files.createDirectories(project.resolve("icons"));
		Files.writeString(project.resolve("icons/x.png"), "png");
		Files.createDirectories(project.resolve("bin/a"));
		Files.writeString(project.resolve("bin/a/B.class"), "class");
		Files.createDirectories(project.resolve("src/a"));
		Files.writeString(project.resolve("src/a/B.java"), "class B {}");
		return project;
	}

	private static Set<String> entries(Path jar) throws IOException {
		try (JarFile file = new JarFile(jar.toFile())) {
			Set<String> names = new TreeSet<>();
			file.stream().forEach(entry -> names.add(entry.getName()));
			return names;
		}
	}
}
