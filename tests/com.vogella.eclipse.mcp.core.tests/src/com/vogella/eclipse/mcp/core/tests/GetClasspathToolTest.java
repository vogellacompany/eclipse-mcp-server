package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GetClasspathToolTest {

	private static final String TOOL = "eclipse_get_classpath";

	private static final String PROJECT = "mcp-classpath-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void reportsTheBoundJreOfTheContainer() throws Exception {
		fixture.createJavaProject(PROJECT);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT));

		boolean container = false;
		for (Object entry : (List<?>) result.get("rawEntries")) {
			Map<?, ?> map = (Map<?, ?>) entry;
			if ("container".equals(map.get("kind"))) {
				container = true;
				assertNotNull(map.get("boundJre"), "the JRE container should name its VM, got " + map);
			}
		}
		assertEquals(true, container, "got " + result);
	}

	@Test
	void capsTheRawEntriesAndSaysSo() throws Exception {
		fixture.createJavaProject(PROJECT);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "maxResults", 1));

		assertEquals(1, ((List<?>) result.get("rawEntries")).size(), "got " + result);
		assertEquals(Boolean.TRUE, result.get("rawTruncated"));
		assertEquals(Integer.valueOf(2), result.get("rawTotal"));
		assertEquals(Boolean.TRUE, result.get("truncated"));
	}
}
