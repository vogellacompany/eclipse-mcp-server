package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.FlameGraph;
import com.vogella.eclipse.mcp.core.IdleFrames;
import com.vogella.eclipse.mcp.core.ProductFamily;

/**
 * The flame graph page.
 * <p>
 * The page carries frame names that come from somebody else's code, so the two
 * escapes are what stop a class called {@code <script>} from being one, and the
 * embedded tree has to stay parseable JSON or the page draws nothing at all.
 */
class FlameGraphTest {

	@Test
	void stacksMergeIntoATreeWeightedFromTheRootDown() throws Exception {
		FlameGraph.Builder builder = FlameGraph.builder()
				.add(List.of("main", "run", "parse"), 3)
				.add(List.of("main", "run", "write"), 1)
				.add(List.of("main", "idle"), 6);

		Map<String, Object> tree = TestFixture.parse(json(builder));
		assertEquals(10, ((Number) tree.get("v")).intValue(), "the root carries every weight, got " + tree);
		assertEquals("all", tree.get("n"));

		Map<String, Object> main = onlyChild(tree);
		assertEquals(10, ((Number) main.get("v")).intValue());
		// widest first, so the expensive branch is drawn on the left
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> kids = (List<Map<String, Object>>) main.get("c");
		assertEquals("idle", kids.get(0).get("n"), "the heavier branch has to sort first, got " + kids);
		assertEquals("run", kids.get(1).get("n"));
	}

	@Test
	void selfWeightIsWhatDidNotGoToTheChildren() {
		FlameGraph.Builder builder = FlameGraph.builder()
				.add(List.of("a", "b"), 10)
				.add(List.of("a"), 4);

		// a took 14 in total and handed 10 to b, so 4 sits in a itself
		assertEquals(List.of("b", "a"), builder.topSelf(5).stream().map(FlameGraph.Builder.Ranked::frame).toList());
		assertEquals(10, builder.topSelf(5).get(0).weight());
		assertEquals(4, builder.topSelf(5).get(1).weight());
	}

	@Test
	void aFrameAppearingInSeveralBranchesIsCountedOnce() {
		FlameGraph.Builder builder = FlameGraph.builder()
				.add(List.of("x", "shared"), 2)
				.add(List.of("y", "shared"), 3);

		// otherwise recursion and shared helpers split one method across many rows
		FlameGraph.Builder.Ranked shared = builder.topTotal(10).stream()
				.filter(r -> r.frame().equals("shared")).findFirst().orElseThrow();
		assertEquals(5, shared.weight());
	}

	@Test
	void anArrowStackIsReversedIntoRootFirstOrder() {
		// the recording renders innermost first; a flame graph stacks outermost first
		assertEquals(List.of("Thread.run", "Server.handle", "Parser.parse"),
				FlameGraph.parseArrowStack("Parser.parse <- Server.handle <- Thread.run"));
	}

	@Test
	void frameNamesCannotBreakOutOfTheMarkupOrTheJson() {
		String hostile = "Evil</script><img src=x onerror=alert(1)>\"'";
		FlameGraph.Builder builder = FlameGraph.builder().add(List.of(hostile), 1);
		String page = FlameGraph.page(new FlameGraph.Spec("t", "s", "samples", builder, List.of(), null));

		assertFalse(page.contains("</script><img"), "the closing tag has to be broken up inside the data block");
		assertFalse(page.contains("onerror=alert(1)>\"'"), "the raw payload must not survive verbatim");
		assertTrue(page.contains("<\\/script>"), "the escape the script block needs is missing");
	}

	@Test
	void aTitleWithMarkupIsEscapedIntoTheDocument() {
		String page = FlameGraph.page(new FlameGraph.Spec("<b>title</b>", "sub & more", "samples",
				FlameGraph.builder().add(List.of("a"), 1), List.of(), null));

		assertTrue(page.contains("&lt;b&gt;title&lt;/b&gt;"), "the title was not escaped");
		assertTrue(page.contains("sub &amp; more"));
	}

	@Test
	void anEmptyProfileSaysSoInsteadOfDrawingNothing() {
		FlameGraph.Builder empty = FlameGraph.builder();
		assertTrue(empty.isEmpty());

		String page = FlameGraph.page(new FlameGraph.Spec("t", "s", "samples", empty, List.of(), null));
		assertTrue(page.contains("No stacks were recorded"), "an empty profile needs an explanation");
		assertFalse(page.contains("id=\"profile\""), "there is no tree to embed, so no data block should exist");
	}

	@Test
	void zeroAndNegativeWeightsAreIgnoredRatherThanBreakingTheLayout() {
		// a zero wide rectangle is not a drawing problem, it is a division by zero in
		// the script that lays the children out
		FlameGraph.Builder builder = FlameGraph.builder()
				.add(List.of("a"), 0)
				.add(List.of("b"), -5)
				.add(List.of("c"), 7);

		assertEquals(7, builder.total());
		assertEquals(1, builder.stacks());
	}

	@Test
	void bytesAreFormattedForAPersonToRead() {
		assertEquals("512 B", FlameGraph.bytes(512));
		assertEquals("1.0 KB", FlameGraph.bytes(1024));
		assertEquals("1.0 MB", FlameGraph.bytes(1024 * 1024));
		assertEquals("1.00 GB", FlameGraph.bytes(1024L * 1024 * 1024));
	}

	@Test
	void theEmbeddedTreeIsTheOneTheScriptReads() throws Exception {
		String page = FlameGraph.page(new FlameGraph.Spec("t", "s", "bytes",
				FlameGraph.builder().add(List.of("a", "b"), 5), List.of(), null));

		int start = page.indexOf("<script id=\"profile\" type=\"application/json\">");
		assertTrue(start > 0, "the data block is missing");
		start = page.indexOf('>', start) + 1;
		String json = page.substring(start, page.indexOf("</script>", start));
		Map<String, Object> tree = TestFixture.parse(json);
		assertEquals(5, ((Number) tree.get("v")).intValue(), "got " + json);
	}

	@Test
	void theServerSaysWhatIsIdleAndThePageDoesNotDecide() throws Exception {
		FlameGraph.Builder builder = FlameGraph.builder()
				.add(List.of("trunk", "Display.sleep"), 3, true)
				.add(List.of("trunk", "work"), 1, false);

		Map<String, Object> trunk = onlyChild(TestFixture.parse(json(builder)));
		assertEquals(3, ((Number) trunk.get("i")).intValue(), "only part of the trunk waits, got " + trunk);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> kids = (List<Map<String, Object>>) trunk.get("c");
		assertEquals(3, ((Number) kids.get(0).get("i")).intValue());
		assertFalse(kids.get(1).containsKey("i"), "work is not idle, got " + kids);

		String page = FlameGraph.page(new FlameGraph.Spec("t", "s", "samples", builder, List.of(), null));
		assertFalse(page.contains("WToolkit"), "the page's script must not carry its own idle list");
	}

	@Test
	void aSaturatedThreadAmongParkedOnesIsBusy() {
		// one thread on every tick out of seventy stacks per tick is one percent of the stacks
		assertFalse(IdleFrames.nothingBusy(100, 100));
		assertTrue(IdleFrames.nothingBusy(2, 150));
		assertTrue(IdleFrames.nothingBusy(0, 150));
		assertFalse(IdleFrames.nothingBusy(0, 0), "without anything to measure against there is no verdict");
	}

	@Test
	void aQuietProfileIsSaidOnThePageAndEvenWithoutAGraph() {
		FlameGraph.Builder crumbs = FlameGraph.builder().add(List.of("a"), 1).quiet("Nothing was busy.");
		assertTrue(FlameGraph.page(new FlameGraph.Spec("t", "s", "samples", crumbs, List.of(), null))
				.contains("data-quiet=\"Nothing was busy.\""));

		String empty = FlameGraph.page(new FlameGraph.Spec("t", "s", "samples",
				FlameGraph.builder().quiet("Nothing was busy."), List.of(), null));
		assertTrue(empty.contains("Nothing was busy."));
		assertFalse(empty.contains("No stacks were recorded"), "the samples were taken and were idle");
	}

	@Test
	void theWarningsAndTheProductReachThePageEscaped() {
		ProductFamily family = new ProductFamily("A \"B\"", List.of("org.a."), "productPrefixes");
		String page = FlameGraph.page(new FlameGraph.Spec("t", "s", "samples", FlameGraph.builder().add(List.of("a"), 1),
				List.of(), null, List.of(), false, List.of("the clock <drifted>"), family));

		assertTrue(page.contains("the clock &lt;drifted&gt;"));
		assertTrue(page.contains("data-product=\"{"), "the script reads the family from here");
		assertFalse(page.contains("A \"B\""), "a label must not break out of the attribute");
		assertTrue(page.contains("id=\"only\""), "the reader has to be able to filter to the family");
		assertFalse(FlameGraph.page(new FlameGraph.Spec("t", "s", "samples", FlameGraph.builder().add(List.of("a"), 1),
				List.of(), null)).contains("id=\"only\""), "there is nothing to filter to without a family");
	}

	@Test
	void onlyAPageThatAsksRanksByCaller() {
		FlameGraph.Builder builder = FlameGraph.builder().add(List.of("a"), 1);

		assertTrue(FlameGraph.page(new FlameGraph.Spec("t", "s", "bytes", builder, List.of(), null, List.of(), true))
				.contains("data-rank=\"caller\""));
		assertFalse(FlameGraph.page(new FlameGraph.Spec("t", "s", "samples", builder, List.of(), null))
				.contains("data-rank=\"caller\""));
	}

	@Test
	void pooledThreadsShareOneBranch() {
		assertEquals("Worker-N", FlameGraph.threadBranch("Worker-19: Indexing"));
		assertEquals("Start Level", FlameGraph.threadBranch("Start Level: Equinox Container: 49d75ff2"));
		assertEquals("main", FlameGraph.threadBranch("main"));
		assertEquals("unnamed", FlameGraph.threadBranch(null));
	}

	private static String json(FlameGraph.Builder builder) {
		String page = FlameGraph.page(new FlameGraph.Spec("t", "s", "samples", builder, List.of(), null));
		int start = page.indexOf('>', page.indexOf("<script id=\"profile\"")) + 1;
		return page.substring(start, page.indexOf("</script>", start));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> onlyChild(Map<String, Object> node) {
		List<Map<String, Object>> kids = (List<Map<String, Object>>) node.get("c");
		assertEquals(1, kids.size(), "expected exactly one child, got " + kids);
		return kids.get(0);
	}
}
