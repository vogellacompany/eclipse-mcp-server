package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.p2.internal.CappedList;

/** The cap the p2 update lists report through, kept testable without provisioning. */
class CappedListTest {

	@Test
	void capsTheListedEntriesButKeepsTheFullCount() {
		CappedList capped = CappedList.cap(entries(5), 2);

		assertEquals(2, capped.listed().size());
		assertEquals(5, capped.total());
		assertTrue(capped.truncated());
	}

	@Test
	void keepsTheFirstEntriesInOrder() {
		CappedList capped = CappedList.cap(entries(5), 2);
		String json = capped.listed().toString();

		assertTrue(json.contains("unit-0"));
		assertTrue(json.contains("unit-1"));
		assertFalse(json.contains("unit-4"));
	}

	@Test
	void aCapAboveTheCountIsNotTruncated() {
		CappedList capped = CappedList.cap(entries(3), 200);

		assertEquals(3, capped.listed().size());
		assertEquals(3, capped.total());
		assertFalse(capped.truncated());
	}

	@Test
	void anEmptyListReportsZeroAndIsNotTruncated() {
		CappedList capped = CappedList.cap(List.of(), 200);

		assertEquals(0, capped.listed().size());
		assertEquals(0, capped.total());
		assertFalse(capped.truncated());
	}

	@Test
	void bothUpdateToolsDeclareMaxResults() throws Exception {
		for (String name : List.of("eclipse_check_for_updates", "eclipse_update")) {
			Map<String, Object> schema = TestFixture.parse(TestFixture.tool(name).getInputSchema());
			Map<String, Object> max = property(schema, "maxResults");

			assertEquals("integer", max.get("type"));
			assertEquals(200, ((Number) max.get("default")).intValue());
			assertEquals(1, ((Number) max.get("minimum")).intValue());
		}
	}

	@Test
	void theUpdateCapSaysItNeverLimitsWhatIsInstalled() throws Exception {
		Map<String, Object> schema = TestFixture.parse(TestFixture.tool("eclipse_update").getInputSchema());
		Map<String, Object> max = property(schema, "maxResults");
		String description = String.valueOf(max.get("description"));

		assertTrue(description.toLowerCase().contains("list"), description);
		assertTrue(description.toLowerCase().contains("install"), description);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> property(Map<String, Object> schema, String name) {
		Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
		return (Map<String, Object>) properties.get(name);
	}

	private static List<JsonObject> entries(int count) {
		List<JsonObject> entries = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			entries.add(new JsonObject().put("unit", "unit-" + i));
		}
		return entries;
	}
}
