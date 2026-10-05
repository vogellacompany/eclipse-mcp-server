package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.PartAddress;
import com.vogella.eclipse.mcp.ui.internal.PartAddress.Candidate;
import com.vogella.eclipse.mcp.ui.internal.PartAddress.Query;

/** Parsing and matching of the {@code part} argument, which needs no workbench. */
class PartAddressTest {

	private static final String E4 = "org.eclipse.e4.tools.emf.editor3x.e4wbm";

	private static Candidate<String> editor(String name, String path, boolean active, boolean visible) {
		return new Candidate<>(name, E4, name, path, active, visible);
	}

	@Test
	void splitsIdAndQualifier() {
		assertEquals(new Query("a.b", null), PartAddress.parse("a.b"));
		assertEquals(new Query("a.b", "/p/x.e4xmi"), PartAddress.parse("a.b@/p/x.e4xmi"));
		assertEquals(new Query(null, "/p/x.e4xmi"), PartAddress.parse("/p/x.e4xmi"));
	}

	@Test
	void prefersActiveThenVisibleThenFirst() {
		Candidate<String> hidden = editor("fragment.e4xmi", "/test/fragment.e4xmi", false, false);
		Candidate<String> visible = editor("McpTest.e4xmi", "/test/McpTest.e4xmi", false, true);
		Candidate<String> active = editor("Other.e4xmi", "/test/Other.e4xmi", true, false);
		Query plain = PartAddress.parse(E4);
		assertEquals("McpTest.e4xmi", PartAddress.choose(List.of(hidden, visible), plain).ref());
		assertEquals("Other.e4xmi", PartAddress.choose(List.of(hidden, visible, active), plain).ref());
		assertEquals("fragment.e4xmi",
				PartAddress.choose(List.of(hidden, editor("b", null, false, false)), plain).ref());
		assertNull(PartAddress.choose(List.of(hidden), PartAddress.parse("other.id")));
	}

	@Test
	void qualifiedByPathOrTitle() {
		Candidate<String> hidden = editor("fragment.e4xmi", "/test/fragment.e4xmi", false, false);
		Candidate<String> visible = editor("McpTest.e4xmi", "/test/McpTest.e4xmi", false, true);
		List<Candidate<String>> all = List.of(hidden, visible);
		assertEquals("fragment.e4xmi", PartAddress.choose(all, PartAddress.parse(E4 + "@/test/fragment.e4xmi")).ref());
		assertEquals("fragment.e4xmi", PartAddress.choose(all, PartAddress.parse(E4 + "@fragment.e4xmi")).ref());
		assertEquals("fragment.e4xmi", PartAddress.choose(all, PartAddress.parse("/test/fragment.e4xmi")).ref());
		assertNull(PartAddress.choose(all, PartAddress.parse(E4 + "@/TEST/fragment.e4xmi")));
	}

	@Test
	void addressesOnlyAmbiguousIdsAndRefusalListsThem() {
		Candidate<String> one = editor("a.e4xmi", "/p/a.e4xmi", false, true);
		Candidate<String> two = editor("b.e4xmi", "/p/b.e4xmi", false, false);
		Candidate<String> view = new Candidate<>("v", "some.view", "View", null, false, true);
		List<Candidate<?>> all = List.of(one, two, view);
		assertEquals(E4 + "@/p/a.e4xmi", PartAddress.address(one, all));
		assertEquals("some.view", PartAddress.address(view, all));
		String refusal = PartAddress.refusal(E4 + "@x", all);
		assertTrue(refusal.contains(E4 + "@/p/b.e4xmi") && refusal.contains("some.view"), refusal);
	}
}
