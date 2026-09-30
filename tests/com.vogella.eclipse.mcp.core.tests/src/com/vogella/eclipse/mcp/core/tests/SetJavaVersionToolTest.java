package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SetJavaVersionToolTest {

	private static final String TOOL = "eclipse_set_java_version";

	private static final String PROJECT = "mcp-java-version-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void aDryRunReportsTheChangeAndWritesNothing() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		project.setOption(JavaCore.COMPILER_SOURCE, "17");
		project.setOption(JavaCore.COMPILER_COMPLIANCE, "17");
		project.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "17");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("version", "21", "project", PROJECT));

		Map<?, ?> compliance = (Map<?, ?>) result.get("compliance");
		assertEquals(Boolean.TRUE, compliance.get("changed"), "got " + result);
		assertEquals("17", project.getOption(JavaCore.COMPILER_SOURCE, false));
	}

	@Test
	void applyingItTwiceReportsNothingToChangeTheSecondTime() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		project.setOption(JavaCore.COMPILER_SOURCE, "17");
		project.setOption(JavaCore.COMPILER_COMPLIANCE, "17");
		project.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "17");

		TestFixture.callAndParse(TOOL, Map.of("version", "21", "project", PROJECT, "dryRun", Boolean.FALSE));
		assertEquals("21", project.getOption(JavaCore.COMPILER_SOURCE, false));
		assertNotEquals("17", project.getOption(JavaCore.COMPILER_COMPLIANCE, false));

		Map<String, Object> again = TestFixture.callAndParse(TOOL,
				Map.of("version", "21", "project", PROJECT, "dryRun", Boolean.FALSE));
		assertEquals(Boolean.FALSE, ((Map<?, ?>) again.get("compliance")).get("changed"), "got " + again);
	}

	@Test
	void anUnknownVersionIsRefusedInsteadOfReportedAsAlreadySet() throws Exception {
		fixture.createJavaProject(PROJECT);

		TestFixture.assertRefused(TestFixture.call(TOOL, Map.of("version", "eleventy", "project", PROJECT)),
				"not a Java version");
	}
}
