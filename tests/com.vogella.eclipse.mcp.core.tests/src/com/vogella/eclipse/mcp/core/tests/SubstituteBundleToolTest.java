package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.basic.internal.SubstituteBundleTool;

/**
 * What the framework has actually loaded, which is the field a caller is meant
 * to trust over bundles.info.
 * <p>
 * It used to be unreadable: the answer came from this bundle's own
 * BundleContext, and this bundle declares no activator and no lazy activation,
 * so it never leaves RESOLVED and the context is null for the life of the IDE.
 * Every caller was told the server was still starting and to ask again.
 */
class SubstituteBundleToolTest {

	@Test
	void reportsWhatTheFrameworkHasLoadedRatherThanRefusingToLook() throws Exception {
		Map<String, Object> running = TestFixture
				.parse(SubstituteBundleTool.running("org.eclipse.core.runtime").toString());

		assertEquals(Boolean.TRUE, running.get("known"),
				"a bundle that is certainly there has to be readable, got " + running);
		assertNotNull(running.get("version"), "got " + running);
		assertNotNull(running.get("state"), "the state is what tells a resolved bundle from a broken one");
		assertEquals(Boolean.FALSE, running.get("isSubstitutedJar"),
				"nothing is substituted in the test IDE, got " + running);
	}

	@Test
	void aPackedLineThatLosesToAHigherVersionIsSupersededAndNothingElseIs() throws Exception {
		// what an update leaves behind: the framework installs the old packed copy,
		// resolves the higher one, and reported the unloaded copy as running
		Path installation = Files.createTempDirectory("mcp-superseded");
		Path configuration = Files.createDirectories(installation.resolve("configuration"));
		Files.createDirectories(installation.resolve("plugins"));
		Files.createDirectories(configuration.resolve("mcp-substituted"));
		Files.writeString(installation.resolve("plugins/a_2.0.0.jar"), "");
		Files.writeString(configuration.resolve("mcp-substituted/a_1.0.0.jar"), "");
		Files.writeString(configuration.resolve("mcp-substituted/b_2.0.0.jar"), "");
		Files.writeString(configuration.resolve("mcp-substituted/c_1.0.0.jar"), "");
		String stale = "a,1.0.0," + configuration.resolve("mcp-substituted/a_1.0.0.jar").toUri() + ",4,false";
		String winner = "a,2.0.0,plugins/a_2.0.0.jar,4,false";
		// b's packed jar is the higher version, so it is the one running
		String inForce = "b,2.0.0," + configuration.resolve("mcp-substituted/b_2.0.0.jar").toUri() + ",4,false";
		// c's higher line names a jar that is not there, so dropping the packed one would leave none
		String onlyCopy = "c,1.0.0," + configuration.resolve("mcp-substituted/c_1.0.0.jar").toUri() + ",4,false";
		try {
			Map<Integer, String> superseded = SubstituteBundleTool.superseded(configuration,
					List.of("#version=1", stale, winner, inForce, "b,1.0.0,plugins/b_1.0.0.jar,4,false", onlyCopy,
							"c,3.0.0,plugins/c_3.0.0.jar,4,false"));

			assertEquals(Map.of(Integer.valueOf(1), winner), superseded);
		} finally {
			try (var walk = Files.walk(installation)) {
				for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	@Test
	void repairDropsTheStaleCopyAndKeepsTheLiveSubstitutionAndItsRecord() throws Exception {
		// the layout that was found on a real installation: two packed copies of one
		// bundle, both in the five-slash form simpleconfigurator writes, each with its
		// own record. Matching records by name forgot the live one as well
		Path configuration = Files.createTempDirectory("mcp-repair").resolve("configuration");
		Path packed = Files.createDirectories(configuration.resolve("mcp-substituted"));
		Path live = Files.writeString(packed.resolve("ide_live.jar"), "");
		Path stale = Files.writeString(packed.resolve("ide_stale.jar"), "");
		String liveLine = "org.eclipse.ui.ide,3.24.0.qualifier," + fiveSlash(live) + ",4,false";
		String staleLine = "org.eclipse.ui.ide,3.23.200.v20260901-1520," + fiveSlash(stale) + ",4,false";
		try {
			Map<Integer, String> superseded = SubstituteBundleTool.superseded(configuration,
					List.of(liveLine, staleLine));
			assertEquals(Map.of(Integer.valueOf(1), liveLine), superseded);

			String[] liveRecord = { "org.eclipse.ui.ide", "org.eclipse.ui.ide,3.24.0.v1,plugins/ide.jar,4,false",
					"org.eclipse.ui.ide,3.24.0.qualifier," + live.toUri() + ",4,false" };
			String[] staleRecord = { "org.eclipse.ui.ide", "org.eclipse.ui.ide,3.23.200.v0,plugins/old.jar,4,false",
					"org.eclipse.ui.ide,3.23.200.v20260901-1520," + stale.toUri() + ",4,false" };
			List<String[]> kept = SubstituteBundleTool.recordsKept(configuration, List.of(liveRecord, staleRecord),
					List.of(staleLine));
			assertEquals(1, kept.size(), "only the stale substitution's record may go");
			assertEquals(liveRecord[2], kept.get(0)[2]);
		} finally {
			try (var walk = Files.walk(configuration.getParent())) {
				for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	@Test
	void restoringASubstitutionLayeredOnAnotherPutsBackTheShippedLine() throws Exception {
		Path configuration = Files.createTempDirectory("mcp-restore").resolve("configuration");
		installed(configuration, "a_1.0.0.jar", "a_2.0.0.jar", "b_1.0.0.jar");
		Path packed = Files.createDirectories(configuration.resolve("mcp-substituted"));
		String shipped = "a,1.0.0,plugins/a_1.0.0.jar,4,false";
		String first = "a,1.0.0," + packed.resolve("a_first.jar").toUri() + ",4,false";
		String second = "a,1.0.0," + packed.resolve("a_second.jar").toUri() + ",4,false";
		String other = "b,1.0.0," + packed.resolve("b.jar").toUri() + ",4,false";
		Path bundlesInfo = Files.writeString(configuration.resolve("bundles.info"),
				String.join("\n", "#version=1", second, other) + "\n");
		Files.writeString(packed.resolve("substitutions.txt"), "a\t" + shipped + "\t" + first + "\n" //
				+ "b\tb,1.0.0,plugins/b_1.0.0.jar,4,false\t" + other + "\n" //
				+ "a\t" + first + "\t" + second + "\n");
		try {
			Map<String, Object> answer = TestFixture
					.parse(SubstituteBundleTool.restore(configuration, bundlesInfo, false, "a").text());

			assertEquals(List.of("#version=1", shipped, other), Files.readAllLines(bundlesInfo), "got " + answer);
			assertEquals("b\tb,1.0.0,plugins/b_1.0.0.jar,4,false\t" + other + "\n",
					Files.readString(packed.resolve("substitutions.txt")), "only the other bundle's record may stay");
		} finally {
			try (var walk = Files.walk(configuration.getParent())) {
				for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	@Test
	void restoringAfterAnUpdateBetweenTwoSubstitutionsStopsAtTheUpdatedLine() throws Exception {
		Path configuration = Files.createTempDirectory("mcp-restore").resolve("configuration");
		installed(configuration, "a_1.0.0.jar", "a_2.0.0.jar", "b_1.0.0.jar");
		Path packed = Files.createDirectories(configuration.resolve("mcp-substituted"));
		String removedByUpdate = "a,1.0.0,plugins/a_1.0.0.jar,4,false";
		String updated = "a,2.0.0,plugins/a_2.0.0.jar,4,false";
		String first = "a,1.0.0," + packed.resolve("a_first.jar").toUri() + ",4,false";
		String second = "a,2.0.0," + packed.resolve("a_second.jar").toUri() + ",4,false";
		// the form simpleconfigurator rewrites the line into, not the one that was recorded
		Path bundlesInfo = Files.writeString(configuration.resolve("bundles.info"),
				String.join("\n", "#version=1", "a,2.0.0," + fiveSlash(packed.resolve("a_second.jar")) + ",4,false") + "\n");
		Files.writeString(packed.resolve("substitutions.txt"), "a\t" + removedByUpdate + "\t" + first + "\n" //
				+ "a\t" + updated + "\t" + second + "\n");
		try {
			Map<String, Object> answer = TestFixture
					.parse(SubstituteBundleTool.restore(configuration, bundlesInfo, false, "a").text());

			assertEquals(List.of("#version=1", updated), Files.readAllLines(bundlesInfo), "got " + answer);
		} finally {
			try (var walk = Files.walk(configuration.getParent())) {
				for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	@Test
	void restoringABundleThatIsAlreadyRestoredLeavesItAndItsRecordsAlone() throws Exception {
		Path configuration = Files.createTempDirectory("mcp-restore").resolve("configuration");
		installed(configuration, "a_1.0.0.jar", "a_2.0.0.jar", "b_1.0.0.jar");
		Path packed = Files.createDirectories(configuration.resolve("mcp-substituted"));
		String shipped = "a,1.0.0,plugins/a_1.0.0.jar,4,false";
		String first = "a,1.0.0," + packed.resolve("a_first.jar").toUri() + ",4,false";
		String second = "a,1.0.0," + packed.resolve("a_second.jar").toUri() + ",4,false";
		Path bundlesInfo = Files.writeString(configuration.resolve("bundles.info"),
				String.join("\n", "#version=1", shipped) + "\n");
		String records = "a\t" + shipped + "\t" + first + "\n" + "a\t" + first + "\t" + second + "\n";
		Files.writeString(packed.resolve("substitutions.txt"), records);
		try {
			SubstituteBundleTool.restore(configuration, bundlesInfo, false, "a");

			assertEquals(List.of("#version=1", shipped), Files.readAllLines(bundlesInfo), "a packed jar was brought back");
			assertEquals(records, Files.readString(packed.resolve("substitutions.txt")));
		} finally {
			try (var walk = Files.walk(configuration.getParent())) {
				for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	@Test
	void restoringEverythingKeepsTheRecordsOfABundleItLeftAloneAndChangesNothingOnADryRun() throws Exception {
		Path configuration = Files.createTempDirectory("mcp-restore").resolve("configuration");
		installed(configuration, "a_1.0.0.jar", "a_2.0.0.jar", "b_1.0.0.jar");
		Path packed = Files.createDirectories(configuration.resolve("mcp-substituted"));
		String shippedA = "a,1.0.0,plugins/a_1.0.0.jar,4,false";
		String shippedB = "b,1.0.0,plugins/b_1.0.0.jar,4,false";
		String jarA = "a,1.0.0," + packed.resolve("a.jar").toUri() + ",4,false";
		String jarB = "b,1.0.0," + packed.resolve("b.jar").toUri() + ",4,false";
		String content = String.join("\n", "#version=1", jarA, shippedB) + "\n";
		Path bundlesInfo = Files.writeString(configuration.resolve("bundles.info"), content);
		String records = "a\t" + shippedA + "\t" + jarA + "\n" + "b\t" + shippedB + "\t" + jarB + "\n";
		Files.writeString(packed.resolve("substitutions.txt"), records);
		try {
			SubstituteBundleTool.restore(configuration, bundlesInfo, true, null);
			assertEquals(content, Files.readString(bundlesInfo), "a dry run wrote");
			assertEquals(records, Files.readString(packed.resolve("substitutions.txt")));

			SubstituteBundleTool.restore(configuration, bundlesInfo, false, null);
			assertEquals(List.of("#version=1", shippedA, shippedB), Files.readAllLines(bundlesInfo));
			assertEquals("b\t" + shippedB + "\t" + jarB + "\n", Files.readString(packed.resolve("substitutions.txt")),
					"the record of the bundle that was left alone has to survive");
		} finally {
			try (var walk = Files.walk(configuration.getParent())) {
				for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	private static void installed(Path configuration, String... jars) throws java.io.IOException {
		Path plugins = Files.createDirectories(configuration.getParent().resolve("plugins"));
		for (String jar : jars) {
			Files.writeString(plugins.resolve(jar), "");
		}
	}

	@Test
	void restoringToAJarThatIsGoneWritesNothingAndKeepsTheRecords() throws Exception {
		Path configuration = Files.createTempDirectory("mcp-restore").resolve("configuration");
		Path packed = Files.createDirectories(configuration.resolve("mcp-substituted"));
		String shipped = "a,1.0.0,plugins/a_1.0.0.jar,4,false";
		String jar = "a,1.0.0," + packed.resolve("a.jar").toUri() + ",4,false";
		String content = String.join("\n", "#version=1", jar) + "\n";
		Path bundlesInfo = Files.writeString(configuration.resolve("bundles.info"), content);
		String records = "a\t" + shipped + "\t" + jar + "\n";
		Files.writeString(packed.resolve("substitutions.txt"), records);
		try {
			for (boolean dryRun : new boolean[] { true, false }) {
				String text = SubstituteBundleTool.restore(configuration, bundlesInfo, dryRun, "a").text();
				assertTrue(text.contains("jarMissing"), text);
				assertEquals(content, Files.readString(bundlesInfo), "a line naming a missing jar was written");
				assertEquals(records, Files.readString(packed.resolve("substitutions.txt")));
			}
		} finally {
			try (var walk = Files.walk(configuration.getParent())) {
				for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	/** The form simpleconfigurator rewrites a file line into. */
	private static String fiveSlash(Path path) {
		String slashed = path.toString().replace(java.io.File.separatorChar, '/');
		return "file://///" + (slashed.startsWith("/") ? slashed.substring(1) : slashed);
	}

	@Test
	void aBundleThatIsNotThereIsSaidToBeAbsentRatherThanUnreadable() throws Exception {
		Map<String, Object> running = TestFixture
				.parse(SubstituteBundleTool.running("com.example.no.such.bundle").toString());

		assertEquals(Boolean.FALSE, running.get("known"));
		assertTrue(String.valueOf(running.get("reason")).contains("no bundle called"),
				"got " + running.get("reason"));
	}
}
