package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GetProjectDependenciesToolTest {

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	@SuppressWarnings("unchecked")
	void followsReferencesInBothDirections() throws Exception {
		IProject a = fixture.createProject("mcp-deps-a");
		IProject b = fixture.createProject("mcp-deps-b");
		IProject c = fixture.createProject("mcp-deps-c");
		reference(a, b);
		reference(b, c);

		Map<String, Object> direct = TestFixture.callAndParse("eclipse_get_project_dependencies",
				Map.of("project", "mcp-deps-a", "direction", "references"));
		Map<String, Object> transitive = TestFixture.callAndParse("eclipse_get_project_dependencies",
				Map.of("project", "mcp-deps-a", "direction", "references", "transitive", Boolean.TRUE));
		Map<String, Object> dependents = TestFixture.callAndParse("eclipse_get_project_dependencies",
				Map.of("project", "mcp-deps-c", "direction", "referencedBy", "transitive", Boolean.TRUE));

		assertEquals(List.of("mcp-deps-b"), first(direct).get("references"), "got " + direct);
		assertEquals(List.of("mcp-deps-b", "mcp-deps-c"), first(transitive).get("references"), "got " + transitive);
		assertEquals(List.of("mcp-deps-a", "mcp-deps-b"), first(dependents).get("referencedBy"), "got " + dependents);
	}

	@Test
	@SuppressWarnings("unchecked")
	void aClosedReferencedProjectDoesNotFailATransitiveWalk() throws Exception {
		IProject a = fixture.createProject("mcp-deps-open");
		IProject closed = fixture.createProject("mcp-deps-closed");
		reference(a, closed);
		closed.close(new NullProgressMonitor());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_project_dependencies",
				Map.of("project", "mcp-deps-open", "direction", "references", "transitive", Boolean.TRUE));

		assertEquals(List.of("mcp-deps-closed"), first(result).get("references"), "got " + result);
	}

	@Test
	void rejectsAnUnknownProject() throws Exception {
		assertTrue(TestFixture.call("eclipse_get_project_dependencies", Map.of("project", "mcp-deps-missing"))
				.isError());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> first(Map<String, Object> result) {
		return ((List<Map<String, Object>>) result.get("projects")).get(0);
	}

	private static void reference(IProject from, IProject to) throws Exception {
		IProjectDescription description = from.getDescription();
		description.setReferencedProjects(new IProject[] { to });
		from.setDescription(description, new NullProgressMonitor());
	}
}
