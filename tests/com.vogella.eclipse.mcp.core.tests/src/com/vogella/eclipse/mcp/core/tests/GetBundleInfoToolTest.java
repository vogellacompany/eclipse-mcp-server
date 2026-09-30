package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GetBundleInfoToolTest {

	private static final String TOOL = "eclipse_get_bundle_info";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsAWorkspaceBundleWithItsExportsAndHonoursMaxResults() throws Exception {
		createPlugin("mcp.bundleinfo.alpha");
		createPlugin("mcp.bundleinfo.beta");

		Map<String, Object> all = TestFixture.callAndParse(TOOL, Map.of("namePattern", "mcp.bundleinfo.*"));
		assertEquals(Integer.valueOf(2), all.get("total"));
		Map<String, Object> capped = TestFixture.callAndParse(TOOL,
				Map.of("namePattern", "mcp.bundleinfo.*", "maxResults", Integer.valueOf(1)));
		assertEquals(Boolean.TRUE, capped.get("truncated"));
		assertEquals(1, ((List<Object>) capped.get("bundles")).size());

		Map<String, Object> one = TestFixture.callAndParse(TOOL, Map.of("symbolicName", "mcp.bundleinfo.alpha"));
		Map<String, Object> bundle = ((List<Map<String, Object>>) one.get("bundles")).get(0);
		assertEquals("mcp.bundleinfo.alpha", bundle.get("symbolicName"));
		assertTrue(bundle.get("exportPackage").toString().contains("mcp.bundleinfo.alpha.api"), bundle.toString());
	}

	private void createPlugin(String name) throws Exception {
		IProject project = fixture.createJavaProject(name).getProject();
		IProjectDescription description = project.getDescription();
		description.setNatureIds(new String[] { JavaCore.NATURE_ID, "org.eclipse.pde.PluginNature" });
		project.setDescription(description, new NullProgressMonitor());
		IFolder folder = project.getFolder("META-INF");
		folder.create(false, true, new NullProgressMonitor());
		String content = """
				Manifest-Version: 1.0
				Bundle-ManifestVersion: 2
				Bundle-Name: Info Test
				Bundle-SymbolicName: %s
				Bundle-Version: 1.0.0.qualifier
				Export-Package: %s.api
				""".formatted(name, name);
		project.getFile("META-INF/MANIFEST.MF").create(
				new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), false, new NullProgressMonitor());
		TestFixture.build(project);
	}
}
