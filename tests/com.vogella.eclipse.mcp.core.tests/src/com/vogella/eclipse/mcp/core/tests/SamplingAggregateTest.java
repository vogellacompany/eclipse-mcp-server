package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.ui.internal.SamplingRegistry;
import com.vogella.eclipse.mcp.ui.internal.SamplingRegistry.Sample;

/**
 * How sampled stacks are merged into the answer of eclipse_stop_sampling.
 * <p>
 * Built from stacks written by hand, because the shapes that matter are the
 * ones a real session produces by accident: a stack deeper than maxDepth that
 * lost a different number of outer frames per sample, and a filter that leaves
 * every remaining sample sharing the same trunk.
 */
class SamplingAggregateTest {

	private static final int MAX_DEPTH = 6;

	/** A stack with the given frames, innermost first. */
	private static Sample sample(String... innermostFirst) {
		StackTraceElement[] stack = new StackTraceElement[innermostFirst.length];
		for (int i = 0; i < stack.length; i++) {
			stack[i] = new StackTraceElement("T", innermostFirst[i], null, -1);
		}
		return new Sample(1, "main", Thread.State.RUNNABLE, stack);
	}

	/**
	 * The full path is main > run > loop > dispatch > work > leaf, with a leaf side
	 * that grows and shrinks. Cut at MAX_DEPTH, the deeper samples lose main, then
	 * run as well, exactly the way ThreadInfo truncates.
	 */
	private static List<Sample> truncatedSamples() {
		List<Sample> samples = new ArrayList<>();
		// depth 6, nothing lost, but at exactly maxDepth it cannot be told apart
		// from a cut one and counts as truncated
		samples.add(sample("leaf", "work", "dispatch", "loop", "run", "main"));
		// depth 7, main lost
		samples.add(sample("deeper", "leaf", "work", "dispatch", "loop", "run"));
		// depth 8, main and run lost
		samples.add(sample("deepest", "deeper", "leaf", "work", "dispatch", "loop"));
		return samples;
	}

	@Test
	@SuppressWarnings("unchecked")
	void samplesCutAtMaxDepthMergeUnderOneRoot() throws Exception {
		JsonObject result = new JsonObject();

		SamplingRegistry.tree(truncatedSamples(), 1, MAX_DEPTH, result);
		Map<String, Object> parsed = TestFixture.parse(result.toString());

		List<Map<String, Object>> roots = (List<Map<String, Object>>) parsed.get("tree");
		assertEquals(1, roots.size(), "one path has to be one root, got " + roots);
		assertEquals("T.loop", roots.get(0).get("frame"), "rooted at the outermost frame every cut sample shares");
		assertEquals(Integer.valueOf(3), roots.get(0).get("samples"));
		assertEquals(Integer.valueOf(3), parsed.get("truncatedSamples"));
		assertTrue(String.valueOf(parsed.get("truncatedNote")).contains("T.loop"), "got " + parsed);
	}

	@Test
	@SuppressWarnings("unchecked")
	void aRunOfFramesWithTheSameCountIsFoldedIntoAChain() throws Exception {
		JsonObject result = new JsonObject();

		SamplingRegistry.tree(truncatedSamples(), 1, MAX_DEPTH, result);
		Map<String, Object> parsed = TestFixture.parse(result.toString());

		Map<String, Object> root = ((List<Map<String, Object>>) parsed.get("tree")).get(0);
		// loop > dispatch > work > leaf all carry every sample; only below leaf do
		// the samples part ways
		assertEquals(List.of("T.dispatch", "T.work", "T.leaf"), root.get("chain"), "got " + root);
		List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");
		assertEquals(1, children.size(), "got " + children);
		assertEquals("T.deeper", children.get(0).get("frame"));
		assertEquals(Integer.valueOf(2), children.get(0).get("samples"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void untruncatedSamplesAreLeftAlone() throws Exception {
		JsonObject result = new JsonObject();

		SamplingRegistry.tree(List.of(sample("a", "main"), sample("b", "main")), 1, MAX_DEPTH, result);
		Map<String, Object> parsed = TestFixture.parse(result.toString());

		List<Map<String, Object>> roots = (List<Map<String, Object>>) parsed.get("tree");
		assertEquals("T.main", roots.get(0).get("frame"));
		assertNull(parsed.get("truncatedSamples"), "nothing was cut, got " + parsed);
	}

	@Test
	@SuppressWarnings("unchecked")
	void framesOnEveryStackAreListedOnceRatherThanToppingPresence() throws Exception {
		JsonObject result = new JsonObject();

		SamplingRegistry.presence(truncatedSamples(), 10, result);
		Map<String, Object> parsed = TestFixture.parse(result.toString());

		List<Object> everywhere = (List<Object>) parsed.get("onEveryStack");
		assertTrue(everywhere.containsAll(List.of("T.leaf", "T.work", "T.dispatch", "T.loop")), "got " + everywhere);
		List<Map<String, Object>> top = (List<Map<String, Object>>) parsed.get("topByPresence");
		for (Map<String, Object> row : top) {
			assertFalse(everywhere.contains(row.get("frame")), "the trunk must not top the list, got " + top);
		}
		assertEquals("T.deeper", top.get(0).get("frame"), "got " + top);
	}

	@Test
	void poolThreadsAndRunningJobsShareOneBranch() {
		assertEquals("Worker-N", SamplingRegistry.threadGroup("Worker-19: Indexing"));
		assertEquals("pool-N", SamplingRegistry.threadGroup("pool-7"));
		assertEquals("main", SamplingRegistry.threadGroup("main"));
		assertEquals("unnamed", SamplingRegistry.threadGroup(null));
	}
}
