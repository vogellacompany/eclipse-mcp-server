package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.json.Json;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The writer every tool answer goes through.
 * <p>
 * Nothing here needs a workspace, and everything here is load bearing: a value
 * this writer spells wrongly reaches the client as a payload it cannot parse at
 * all, so the failure is the whole answer rather than one field of it.
 */
class JsonTest {

	@Test
	void nonFiniteDoublesAreWrittenAsNull() throws Exception {
		// a ratio whose denominator came out zero used to be appended verbatim, and
		// NaN is not a JSON token, so one unlucky division cost the entire answer
		JsonObject json = new JsonObject().put("nan", Double.valueOf(Double.NaN))
				.put("positive", Double.valueOf(Double.POSITIVE_INFINITY))
				.put("negative", Double.valueOf(Double.NEGATIVE_INFINITY))
				.put("float", Float.valueOf(Float.NaN))
				.put("ordinary", Double.valueOf(0.5));

		Map<String, Object> parsed = TestFixture.parse(json.toString());
		assertNull(parsed.get("nan"), "got " + json);
		assertNull(parsed.get("positive"));
		assertNull(parsed.get("negative"));
		assertNull(parsed.get("float"));
		assertEquals(0.5, ((Number) parsed.get("ordinary")).doubleValue(), 0.0);
	}

	@Test
	void aJavaListIsWrittenAsAnArray() throws Exception {
		String written = new JsonObject().put("notes", List.of("a", "b"))
				.put("rules", List.of(new JsonObject().put("selector", "Label"))).toString();

		Map<String, Object> parsed = TestFixture.parse(written);
		assertEquals(List.of("a", "b"), parsed.get("notes"), written);
		assertEquals(List.of(Map.of("selector", "Label")), parsed.get("rules"), written);
	}

	@Test
	void aJavaMapIsWrittenAsAnObject() throws Exception {
		String written = new JsonObject().put("counts", Map.of("errors", 2)).toString();

		assertEquals(Map.of("errors", 2), TestFixture.parse(written).get("counts"), written);
	}

	@Test
	void exactNumbersTooLargeForADoubleStayThemselves() throws Exception {
		// the non finite check asks the value for its double, and a BigInteger past
		// the double range answers Infinity while still being a number JSON can spell
		BigInteger huge = BigInteger.TEN.pow(400);
		String written = new JsonObject().put("huge", huge).put("exact", new BigDecimal("1.5")).toString();

		assertTrue(written.contains(huge.toString()), "got " + written);
		Map<String, Object> parsed = TestFixture.parse(written);
		assertEquals(1.5, ((Number) parsed.get("exact")).doubleValue(), 0.0);
	}

	@Test
	void controlCharactersAndQuotesSurviveARoundTrip() throws Exception {
		// tool answers carry file content and exception messages, so a stray quote or
		// newline in somebody else's data must not end the string early
		String awkward = "a\"b\\c\nd\te\rfg";

		Map<String, Object> parsed = TestFixture.parse(new JsonObject().put("text", awkward).toString());
		assertEquals(awkward, parsed.get("text"));
	}

	@Test
	void emptyObjectsAndArraysAreStillValidJson() throws Exception {
		String written = new JsonObject().put("object", new JsonObject()).put("array", new JsonArray()).toString();

		Map<String, Object> parsed = TestFixture.parse(written);
		assertEquals(Map.of(), parsed.get("object"), "got " + written);
		assertEquals(List.of(), parsed.get("array"));
	}

	@Test
	void nestedStructuresKeepTheirShapeAndOrder() throws Exception {
		JsonArray rows = new JsonArray().add(new JsonObject().put("name", "first").put("count", Integer.valueOf(1)))
				.add(new JsonObject().put("name", "second").put("count", Integer.valueOf(2)));
		JsonObject json = new JsonObject().put("rows", rows).put("truncated", Boolean.FALSE).put("missing", null);

		Map<String, Object> parsed = TestFixture.parse(json.toString());
		assertEquals(Boolean.FALSE, parsed.get("truncated"));
		assertNull(parsed.get("missing"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> read = (List<Map<String, Object>>) parsed.get("rows");
		assertEquals(2, read.size(), "got " + json);
		assertEquals("first", read.get(0).get("name"));
		assertEquals("second", read.get(1).get("name"));
	}

	@Test
	void aBareValueIsWrittenOnItsOwn() {
		assertEquals("null", Json.write(null));
		assertEquals("true", Json.write(Boolean.TRUE));
		assertEquals("\"text\"", Json.write("text"));
	}

	@Test
	void theReaderRoundTripsWhatTheWriterProduces() {
		// the git tools read GitHub's answers through this, so a nested object, an
		// array, an escaped string and a null have to come back as they went in
		String written = new JsonObject().put("name", "quote \" and \\ and \n and \u00e9")
				.put("nested", new JsonObject().put("count", Integer.valueOf(3)).put("ratio", Double.valueOf(0.25)))
				.put("list", new JsonArray().add("a").add(Boolean.TRUE).add(null)).put("missing", null).toString();

		@SuppressWarnings("unchecked")
		Map<String, Object> parsed = (Map<String, Object>) Json.parse(written);

		assertEquals("quote \" and \\ and \n and \u00e9", parsed.get("name"));
		@SuppressWarnings("unchecked")
		Map<String, Object> nested = (Map<String, Object>) parsed.get("nested");
		assertEquals(Long.valueOf(3), nested.get("count"));
		assertEquals(Double.valueOf(0.25), nested.get("ratio"));
		assertEquals(java.util.Arrays.asList("a", Boolean.TRUE, null), parsed.get("list"));
		assertTrue(parsed.containsKey("missing") && parsed.get("missing") == null, "got " + parsed);
	}

	@Test
	void theReaderRejectsWhatIsNotJson() {
		// a truncated or trailing answer must fail loudly rather than yield a partial
		// object that a caller then reads a wrong branch name out of
		for (String broken : List.of("{\"a\":1", "{\"a\":1} x", "[1,]", "{\"a\" 1}", "", "\"open", "tru")) {
			assertThrows(IllegalArgumentException.class, () -> Json.parse(broken), broken);
		}
		assertEquals("\u00e9", Json.parse("\"\\u00e9\""));
	}
}
