package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.basic.internal.SharedBase;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.basic.internal.SubstituteBundleTool;

/**
 * When simpleconfigurator reads a user area's bundles.info rather than the shared installation's.
 */
class SharedBaseTest {

	private static final String HEADER = "#version=1";

	@TempDir
	Path directory;

	@Test
	void aChangedSharedFileMakesTheUserAreaStale() throws Exception {
		Path user = directory.resolve("user");
		Path base = writeBase(5_000L);
		writeTimestamp(user, "bundlesInfoTimestamp=4000\nextTimestamp=-1\n");
		assertTrue(SharedBase.state(user, base).stale());

		writeTimestamp(user, "bundlesInfoTimestamp=5000\nextTimestamp=-1\n");
		assertFalse(SharedBase.state(user, base).stale());
	}

	@Test
	void aRecordedFragmentTimeMakesTheUserAreaStaleWithoutFragments() throws Exception {
		Path user = directory.resolve("user");
		Path base = writeBase(5_000L);
		writeTimestamp(user, "bundlesInfoTimestamp=5000\nextTimestamp=1234\n");
		assertTrue(SharedBase.state(user, base).stale());
	}

	@Test
	void withoutARecordTheUserAreaIsRead() throws Exception {
		assertFalse(SharedBase.state(directory.resolve("user"), writeBase(5_000L)).stale());
	}

	@Test
	void linesAreComparedWholeLeavingOutTheNamedBundles() {
		List<String> base = List.of(HEADER, "a,1.0.0,plugins/a.jar,4,false", "b,1.0.0,plugins/b.jar,4,false",
				"c,1.0.0,plugins/c.jar,4,false");
		List<String> user = List.of(HEADER, "a,1.0.0,plugins/a.jar,4,false", "b,1.1.0,file:/tmp/b.jar,4,false",
				"c,1.0.0,plugins/c.jar,3,false", "extra,1.0.0,../user/extra.jar,4,false");
		assertEquals(List.of("c,1.0.0,plugins/c.jar,4,false"), SharedBase.linesNotIn(base, user, Set.of("b")));
		assertEquals(List.of("c,1.0.0,plugins/c.jar,3,false", "extra,1.0.0,../user/extra.jar,4,false"),
				SharedBase.linesNotIn(user, base, Set.of("b")));
		assertEquals(List.of(), SharedBase.linesNotIn(base, base, Set.of()));
		assertEquals(List.of(), SharedBase.linesNotIn(List.of("#other comment", "", "a,1.0.0,plugins/a.jar,4,false  "),
				base, Set.of()), "comments, blank lines and trailing blanks are not differences");
	}

	@Test
	void anUnreadableRecordCountsAsNone() throws Exception {
		Path user = directory.resolve("user");
		Path base = writeBase(5_000L);
		writeTimestamp(user, "bundlesInfoTimestamp=abc\n");
		assertFalse(SharedBase.state(user, base).stale());
	}

	@Test
	void identicalFilesApartFromTheSubstitutionAreMarkedCurrentOnlyWhenWritten() throws Exception {
		Path user = directory.resolve("user");
		Path base = writeBase(9_000L, "a,1.0.0,plugins/a.jar,4,false", "b,1.0.0,plugins/b.jar,4,false");
		writeTimestamp(user, "bundlesInfoTimestamp=1000\n");
		List<String> userLines = List.of(HEADER, "a,1.0.0,plugins/a.jar,4,false", "b,2.0.0,file:/tmp/b.jar,4,false");

		SubstituteBundleTool.Shared shared = SubstituteBundleTool.sharedInstall(user, base, userLines, Set.of("b"));
		assertFalse(shared.differs(), shared.json().toString());
		SubstituteBundleTool.markUserAreaCurrent(user, shared, false);
		assertTrue(SharedBase.state(user, base).stale(), "a dry run changes nothing");
		SubstituteBundleTool.markUserAreaCurrent(user, shared, true);
		assertFalse(SharedBase.state(user, base).stale());
	}

	@Test
	void anyOtherDifferenceIsReportedForAcceptSharedChanges() throws Exception {
		Path user = directory.resolve("user");
		Path base = writeBase(9_000L, "a,1.1.0,plugins/a.jar,4,false");
		writeTimestamp(user, "bundlesInfoTimestamp=1000\n");
		List<String> userLines = List.of(HEADER, "a,1.0.0,plugins/a.jar,4,false");

		SubstituteBundleTool.Shared shared = SubstituteBundleTool.sharedInstall(user, base, userLines, Set.of());
		assertTrue(shared.differs());
		assertTrue(shared.refusal().contains("a,1.1.0,plugins/a.jar,4,false"), shared.refusal());
		assertTrue(shared.refusal().contains("acceptSharedChanges"), shared.refusal());
		assertFalse(shared.json().toString().contains("\"onlyInSharedFile\": null"), shared.json().toString());
	}

	@Test
	void acceptSharedChangesDecidesWhetherADifferingUserAreaIsMarked() throws Exception {
		Path user = directory.resolve("user");
		Path base = writeBase(9_000L, "a,1.1.0,plugins/a.jar,4,false");
		writeTimestamp(user, "bundlesInfoTimestamp=1000\n");
		List<String> userLines = List.of(HEADER, "a,1.0.0,plugins/a.jar,4,false");

		JsonObject dry = new JsonObject();
		assertNull(SubstituteBundleTool.prepareShared(user, base, userLines, "b", false, dry, true).refusal());
		assertTrue(dry.toString().contains("refusedWithoutAcceptSharedChanges"), dry.toString());

		McpToolResult refused = SubstituteBundleTool
				.prepareShared(user, base, userLines, "b", false, new JsonObject(), false).refusal();
		assertTrue(refused != null && refused.isError(), String.valueOf(refused));
		assertTrue(SharedBase.state(user, base).stale(), "a refusal writes nothing");

		SubstituteBundleTool.Prepared accepted = SubstituteBundleTool.prepareShared(user, base, userLines, "b", true,
				new JsonObject(), false);
		assertNull(accepted.refusal());
		assertTrue(SharedBase.state(user, base).stale(), "the caller marks only once the substitution cannot fail");
		SubstituteBundleTool.markUserAreaCurrent(user, accepted.shared(), true);
		assertFalse(SharedBase.state(user, base).stale(), "acceptSharedChanges lets the user area win");
	}

	@Test
	void onlySubstitutionsStillInForceAreLeftOut() throws Exception {
		Path user = directory.resolve("user");
		Path record = user.resolve("mcp-substituted/substitutions.txt");
		Files.createDirectories(record.getParent());
		Files.writeString(record, "a\ta,1.0.0,plugins/a.jar,4,false\ta,2.0.0,file:/x/a.jar,4,false\n"
				+ "b\tb,1.0.0,plugins/b.jar,4,false\tb,2.0.0,file:/x/b.jar,4,false\n");
		List<String> lines = List.of("a,2.0.0,file:/x/a.jar,4,false", "b,1.0.0,plugins/b.jar,4,false");
		assertEquals(Set.of("a", "c"), SubstituteBundleTool.substitutedNames(user, lines, "c"),
				"b was restored, so a later shared update of it must still count");
	}

	@Test
	void aUserAreaThatIsReadIsNotCompared() throws Exception {
		Path user = directory.resolve("user");
		Path base = writeBase(9_000L, "a,1.1.0,plugins/a.jar,4,false");
		writeTimestamp(user, "bundlesInfoTimestamp=9000\n");
		SubstituteBundleTool.Shared shared = SubstituteBundleTool.sharedInstall(user, base,
				List.of("a,1.0.0,plugins/a.jar,4,false"), Set.of());
		assertFalse(shared.differs());
		assertTrue(shared.json().toString().contains("\"onlyInSharedFile\": null"), shared.json().toString());
		assertTrue(shared.json().toString().contains("\"onlyInUserArea\": null"), shared.json().toString());
		SubstituteBundleTool.markUserAreaCurrent(user, shared, true);
		assertFalse(shared.json().toString().contains("baseTimestampUpdated"), "nothing to mark");
	}

	@Test
	void markingCurrentWritesBothTimesAndKeepsTheRest() throws Exception {
		Path user = directory.resolve("user");
		Path base = writeBase(7_000L);
		writeTimestamp(user, "bundlesInfoTimestamp=1\nextTimestamp=42\nother=kept\n");
		SharedBase.markCurrent(user, base);

		Properties written = new Properties();
		try (InputStream in = Files.newInputStream(user.resolve(SharedBase.TIMESTAMP_FILE))) {
			written.load(in);
		}
		assertEquals("7000", written.getProperty("bundlesInfoTimestamp"));
		assertEquals("-1", written.getProperty("extTimestamp"), "without p2.fragments simpleconfigurator compares -1");
		assertEquals("kept", written.getProperty("other"));
		assertFalse(SharedBase.state(user, base).stale());
	}

	private Path writeBase(long millis, String... lines) throws Exception {
		Path base = directory.resolve("base/org.eclipse.equinox.simpleconfigurator/bundles.info");
		Files.createDirectories(base.getParent());
		Files.writeString(base, HEADER + "\n" + String.join("\n", lines) + "\n");
		Files.setLastModifiedTime(base, FileTime.fromMillis(millis));
		return base;
	}

	private static void writeTimestamp(Path user, String content) throws Exception {
		Path file = user.resolve(SharedBase.TIMESTAMP_FILE);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}
}
