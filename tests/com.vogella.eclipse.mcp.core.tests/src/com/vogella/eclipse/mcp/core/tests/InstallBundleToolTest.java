package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Drives {@code eclipse_install_bundle} against the real framework the tests run in.
 */
class InstallBundleToolTest {

	private static final String TOOL = "eclipse_install_bundle";

	/** Deliberately outside this server's own name space, so no self guard fires. */
	private static final String SYMBOL = "org.mcp.tests.installable";

	private static final String OWN = "com.vogella.eclipse.mcp.core";

	/** A real extension point of the test runtime, so the contribution has somewhere to land. */
	private static final String POINT = "org.eclipse.core.runtime.products";

	private static final String PLUGIN_XML = """
			<?xml version="1.0" encoding="UTF-8"?>
			<plugin>
			   <extension point="%s">
			      <product name="mcp install test one" application="org.mcp.tests.no.application"/>
			   </extension>
			   <extension point="%s">
			      <product name="mcp install test two" application="org.mcp.tests.no.application"/>
			   </extension>
			</plugin>
			""".formatted(POINT, POINT);

	@TempDir
	Path temp;

	private BundleContext context = FrameworkUtil.getBundle(InstallBundleToolTest.class).getBundleContext();

	@BeforeEach
	@AfterEach
	void uninstallLeftovers() {
		for (Bundle bundle : context.getBundles()) {
			if (SYMBOL.equals(bundle.getSymbolicName())) {
				try {
					bundle.uninstall();
				} catch (Exception e) {
					throw new AssertionError("Could not clean up " + bundle, e);
				}
			}
		}
	}

	@Test
	void installsThenUpdatesAndStartsAMinimalBundle() throws Exception {
		Map<String, Object> installed = TestFixture.callAndParse(TOOL,
				Map.of("jar", jar(SYMBOL, "1.0.0").toString(), "dryRun", Boolean.FALSE));

		assertEquals("installed", installed.get("outcome"), installed.toString());
		assertNull(installed.get("previousVersion"));
		assertEquals("1.0.0", installed.get("version"));
		assertEquals(Boolean.TRUE, installed.get("resolved"));
		assertEquals("ACTIVE", installed.get("state"));
		Bundle bundle = findInstalled();
		assertNotNull(bundle, "the test bundle should be in the framework");
		assertEquals(Bundle.ACTIVE, bundle.getState());
		assertFalse((Boolean) installed.get("truncated"));

		Map<String, Object> updated = TestFixture.callAndParse(TOOL,
				Map.of("jar", jar(SYMBOL, "2.0.0").toString(), "dryRun", Boolean.FALSE));

		assertEquals("updated", updated.get("outcome"), updated.toString());
		assertEquals("1.0.0", updated.get("previousVersion"));
		assertEquals("2.0.0", updated.get("version"));
		assertEquals("ACTIVE", updated.get("state"));
		assertEquals(1, countInstalled(), "an update replaces the bundle rather than adding a second copy");
		assertEquals("2.0.0", findInstalled().getVersion().toString());

		Map<String, Object> extensions = extensionsOf(updated);
		assertNotNull(extensions, "a hot answer reports what the registry attributes");
		assertEquals(Boolean.FALSE, extensions.get("pluginXmlInJar"), extensions.toString());
		assertEquals(Integer.valueOf(0), extensions.get("total"));
	}

	@Test
	void aJarRebuiltInPlaceWithTheSameVersionIsUpdatedNotRefused() throws Exception {
		Path built = jar(SYMBOL, "1.0.0");
		TestFixture.callAndParse(TOOL, Map.of("jar", built.toString(), "dryRun", Boolean.FALSE));

		Path rebuilt = jar(SYMBOL, "1.0.0", PLUGIN_XML);
		Files.setLastModifiedTime(rebuilt,
				java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
		McpToolResult result = TestFixture.call(TOOL, Map.of("jar", rebuilt.toString(), "dryRun", Boolean.FALSE));

		assertFalse(result.isError(), result.text());
		assertEquals(built, rebuilt, "the point of the test is that the path is the same");
	}

	@Test
	void reportsWhatTheRegistryMadeOfAPluginXmlContribution() throws Exception {
		Path themed = jar(SYMBOL, "1.0.0", PLUGIN_XML);

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("jar", themed.toString(), "dryRun", Boolean.FALSE));

		assertEquals(Boolean.TRUE, result.get("resolved"), result.toString());
		Map<String, Object> extensions = extensionsOf(result);
		assertEquals(Boolean.TRUE, extensions.get("pluginXmlInJar"), extensions.toString());
		assertEquals(Integer.valueOf(1), extensions.get("total"), extensions.toString());
		assertEquals(Boolean.FALSE, extensions.get("truncated"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> points = (List<Map<String, Object>>) extensions.get("points");
		assertEquals(1, points.size(), extensions.toString());
		assertEquals(POINT, points.get(0).get("extensionPoint"));
		assertEquals(Integer.valueOf(2), points.get(0).get("count"),
				"two extension elements under one point count as two extensions");
	}

	@Test
	void aDryRunChangesNothing() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("jar", jar(SYMBOL, "1.0.0").toString()));

		assertEquals(Boolean.TRUE, result.get("dryRun"));
		assertEquals("installed", result.get("wouldBe"), result.toString());
		assertNull(result.get("previousVersion"));
		assertEquals(Integer.valueOf(0), result.get("total"), result.toString());
		assertEquals(0, countInstalled(), "a dry run must not install anything");
	}

	@Test
	void refusesAJarWithoutASymbolicName() throws Exception {
		Path notABundle = jar(null, "1.0.0");

		McpToolResult result = TestFixture.call(TOOL,
				Map.of("jar", notABundle.toString(), "dryRun", Boolean.FALSE));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains(notABundle.getFileName().toString()), result.text());
		assertTrue(result.text().contains("Bundle-SymbolicName"), result.text());
		assertEquals(0, countInstalled());
	}

	@Test
	void refusesAMissingPathAndAFileThatIsNotAJar() throws Exception {
		Path missing = temp.resolve("does-not-exist.jar");
		McpToolResult missingResult = TestFixture.call(TOOL, Map.of("jar", missing.toString()));

		assertTrue(missingResult.isError(), missingResult.text());
		assertTrue(missingResult.text().contains("does-not-exist.jar"), missingResult.text());

		Path textFile = temp.resolve("text.jar");
		Files.writeString(textFile, "this is not a zip archive");

		McpToolResult notAJar = TestFixture.call(TOOL, Map.of("jar", textFile.toString()));

		assertTrue(notAJar.isError(), notAJar.text());
		assertEquals(0, countInstalled());
	}

	@Test
	void refusesToTouchItsOwnBundlesByDefault() throws Exception {
		Bundle core = bundleOf(OWN);
		assertNotNull(core, "this server's own core bundle should be installed in the test IDE");
		String versionBefore = core.getVersion().toString();
		Path patchedCore = jar(OWN, "9.9.9");

		McpToolResult refused = TestFixture.call(TOOL, Map.of("jar", patchedCore.toString()));

		assertTrue(refused.isError(), refused.text());
		assertTrue(refused.text().contains(OWN), refused.text());
		assertTrue(refused.text().contains("allowSelf"), refused.text());
		assertEquals(versionBefore, core.getVersion().toString(), "nothing may be changed by the refusal");
	}

	@Test
	void dropinsDryRunPlansACopyWithoutWritingOne() throws Exception {
		Path jar = jar(SYMBOL, "1.0.0");

		Map<String, Object> planned = TestFixture.callAndParse(TOOL,
				Map.of("jar", jar.toString(), "mode", "dropins"));

		assertEquals("copied", planned.get("wouldBe"), planned.toString());
		assertEquals(Boolean.TRUE, planned.get("survivesRestart"));
		assertEquals(Boolean.TRUE, planned.get("needsRestart"));
		assertFalse(planned.containsKey("outcome"), "a dry run reports no outcome");
		Path target = Path.of(String.valueOf(planned.get("target")));
		assertFalse(Files.exists(target), "a dry run must not copy the jar");
		if (Boolean.FALSE.equals(planned.get("dropinsExisted"))) {
			assertFalse(Files.exists(target.getParent()), "a dry run must not create the dropins directory");
		}
	}

	private Bundle bundleOf(String symbolicName) {
		for (Bundle bundle : context.getBundles()) {
			if (symbolicName.equals(bundle.getSymbolicName())) {
				return bundle;
			}
		}
		return null;
	}

	private Bundle findInstalled() {
		return bundleOf(SYMBOL);
	}

	private int countInstalled() {
		int count = 0;
		for (Bundle bundle : context.getBundles()) {
			if (SYMBOL.equals(bundle.getSymbolicName())) {
				count++;
			}
		}
		return count;
	}

	/** Builds a minimal bundle jar whose manifest carries only what the argument names need. */
	private Path jar(String symbol, String version) throws IOException {
		return jar(symbol, version, null);
	}

	/** Builds a bundle jar that optionally embeds a plugin.xml, in one stream so the zip stays valid. */
	private Path jar(String symbol, String version, String pluginXml) throws IOException {
		Manifest manifest = new Manifest();
		Attributes main = manifest.getMainAttributes();
		main.putValue("Manifest-Version", "1.0");
		main.putValue("Bundle-ManifestVersion", "2");
		if (symbol != null) {
			// the extension registry reads plugin.xml from singleton bundles only
			main.putValue("Bundle-SymbolicName", symbol + ";singleton:=true");
		}
		main.putValue("Bundle-Version", version);
		Path path = temp.resolve(("bundle-" + version).replaceAll("[^A-Za-z0-9.-]", "_") + ".jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
			if (pluginXml != null) {
				out.putNextEntry(new JarEntry("plugin.xml"));
				out.write(pluginXml.getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
			out.flush();
		}
		return path;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> extensionsOf(Map<String, Object> result) {
		return (Map<String, Object>) result.get("extensions");
	}
}
