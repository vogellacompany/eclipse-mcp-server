package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.internal.PlatformFilters;
import com.vogella.eclipse.mcp.core.internal.PlatformFilters.Verdict;

/**
 * Whether a project's bundle can run here: the manifest header first, the name only as a fallback.
 */
class PlatformFiltersTest {

	private static final String FOREIGN_WS = "gtk".equals(Platform.getWS()) ? "win32" : "gtk";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	private IProject withHeader(String name, String header) throws Exception {
		IProject project = fixture.createProject(name);
		IFolder folder = project.getFolder("META-INF");
		folder.create(false, true, new NullProgressMonitor());
		String content = "Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: %s\n%s\n".formatted(name,
				header);
		project.getFile("META-INF/MANIFEST.MF").create(
				new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), false, new NullProgressMonitor());
		return project;
	}

	@Test
	void aForeignHeaderIsAMismatchAndSaysItCameFromTheHeader() throws Exception {
		Verdict verdict = PlatformFilters
				.evaluate(withHeader("mcp-pf-foreign", "Eclipse-PlatformFilter: (osgi.ws=%s)".formatted(FOREIGN_WS)));

		assertTrue(verdict.mismatch(), verdict.reason());
		assertTrue(verdict.reason().contains("Eclipse-PlatformFilter does not match"), verdict.reason());
	}

	@Test
	void aHeaderForThisPlatformMatchesEvenWhenTheNameSaysOtherwise() throws Exception {
		String name = "mcp-pf." + FOREIGN_WS + ".named";
		Verdict verdict = PlatformFilters
				.evaluate(withHeader(name, "Eclipse-PlatformFilter: (osgi.ws=%s)".formatted(Platform.getWS())));

		assertFalse(verdict.mismatch(), verdict.reason());
		assertTrue(verdict.reason().contains("matches"), verdict.reason());
	}

	@Test
	void aCompoundFilterIsEvaluated() throws Exception {
		String filter = "(&(osgi.ws=%s)(osgi.os=%s))".formatted(Platform.getWS(), Platform.getOS());

		assertFalse(PlatformFilters.evaluate(withHeader("mcp-pf-compound", "Eclipse-PlatformFilter: " + filter)).mismatch());
	}

	@Test
	void anUnparsableHeaderLeavesTheProjectAlone() throws Exception {
		Verdict verdict = PlatformFilters.evaluate(withHeader("mcp-pf-broken", "Eclipse-PlatformFilter: (osgi.ws="));

		assertFalse(verdict.mismatch(), verdict.reason());
		assertTrue(verdict.reason().contains("could not be parsed"), verdict.reason());
	}

	@Test
	void withoutAHeaderAForeignTokenInTheNameIsAHeuristicMismatch() throws Exception {
		Verdict verdict = PlatformFilters.evaluate(fixture.createProject("mcp.pf.fragment." + FOREIGN_WS));

		assertTrue(verdict.mismatch(), verdict.reason());
		assertTrue(verdict.reason().contains("heuristic"), verdict.reason());
	}

	@Test
	void aNameCarryingThisPlatformsTokenToo() throws Exception {
		Verdict verdict = PlatformFilters
				.evaluate(fixture.createProject("mcp.pf." + FOREIGN_WS + "." + Platform.getWS()));

		assertFalse(verdict.mismatch(), verdict.reason());
	}

	@Test
	void aPlainNameWithoutAHeaderIsLeftAlone() throws Exception {
		Verdict verdict = PlatformFilters.evaluate(fixture.createProject("mcp-pf-plain"));

		assertFalse(verdict.mismatch(), verdict.reason());
	}
}
