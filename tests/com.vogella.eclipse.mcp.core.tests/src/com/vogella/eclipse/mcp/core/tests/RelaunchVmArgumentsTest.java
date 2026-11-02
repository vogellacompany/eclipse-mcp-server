package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.ui.internal.RelaunchVmArguments;
import com.vogella.eclipse.mcp.ui.internal.RestartTool;

/**
 * The JVM arguments of one relaunch, checked against a real JVM but never a real restart.
 */
class RelaunchVmArgumentsTest {

	private static RelaunchVmArguments recording(Map<String, Object> options) {
		return RelaunchVmArguments.of(Map.of("flightRecording", options));
	}

	private static void refuses(String expected, Map<String, Object> arguments) {
		IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
				() -> RelaunchVmArguments.of(arguments));
		assertTrue(refusal.getMessage().contains(expected), refusal.getMessage());
	}

	@Test
	void nothingAskedForAddsNothing() {
		RelaunchVmArguments nothing = RelaunchVmArguments.of(Map.of("splash", Boolean.FALSE));

		assertTrue(nothing.isEmpty());
		assertEquals("", nothing.exitData());
	}

	@Test
	void anEmptyObjectRecordsAStartupWithDefaults() {
		String argument = recording(Map.of()).vmArguments().get(0);

		assertTrue(argument.startsWith("-XX:StartFlightRecording="), argument);
		assertTrue(argument.contains("settings=profile"), argument);
		assertTrue(argument.contains("duration=60s"), "the file has to appear without ending the IDE: " + argument);
		assertTrue(argument.contains("mcp-startup-"), argument);
	}

	@Test
	void theExitDataKeepsThePathInOneLineWithoutQuotes(@TempDir Path dir) {
		// exit data is split on newlines, so a quote here would reach the JVM literally
		Path file = dir.resolve("with space").resolve("startup.jfr");

		RelaunchVmArguments arguments = recording(Map.of("file", file.toString(), "eventSettings",
				List.of("jdk.ExecutionSample#period=1ms"), "seconds", Integer.valueOf(30)));

		String expected = "-XX:StartFlightRecording=name=mcp,settings=profile,dumponexit=true,duration=30s,filename=" + file
				+ ",jdk.ExecutionSample#period=1ms";
		assertEquals("--launcher.appendVmargs\n-vmargs\n" + expected + "\n-XX:FlightRecorderOptions:stackdepth=256\n",
				arguments.exitData());
	}

	@Test
	void theVmArgumentsComeLastSoTheWorkspaceIsNotPassedToTheJvm() {
		String data = System.getProperty(RestartTool.EXIT_DATA_PROPERTY);
		String code = System.getProperty(RestartTool.EXIT_CODE_PROPERTY);
		try {
			System.setProperty(RestartTool.EXIT_DATA_PROPERTY, "-clean\n");

			RestartTool.relaunchInto("/tmp/my ws",
					RelaunchVmArguments.of(Map.of("vmArgs", List.of("-Dfoo=a b", "-Xmx2g"))));

			assertEquals("-clean\n-data\n/tmp/my ws\n--launcher.appendVmargs\n-vmargs\n-Dfoo=a b\n-Xmx2g\n",
					System.getProperty(RestartTool.EXIT_DATA_PROPERTY));
			// 24: the code that makes the launcher read the exit data at all
			assertEquals("24", System.getProperty(RestartTool.EXIT_CODE_PROPERTY));
		} finally {
			restore(RestartTool.EXIT_DATA_PROPERTY, data);
			restore(RestartTool.EXIT_CODE_PROPERTY, code);
		}
	}

	@Test
	void whatTheOptionCannotCarryIsRefused(@TempDir Path dir) throws Exception {
		Path existing = Files.createFile(dir.resolve("old.jfr"));

		refuses("absolute", Map.of("flightRecording", Map.of("file", "relative.jfr")));
		refuses("already exists", Map.of("flightRecording", Map.of("file", existing.toString())));
		// the JVM splits its option on commas and would write to the part in front of one
		refuses("comma", Map.of("flightRecording", Map.of("file", dir.resolve("a,b.jfr").toString())));
		refuses("event setting", Map.of("flightRecording", Map.of("eventSettings", List.of("period=1ms"))));
		refuses("event setting",
				Map.of("flightRecording", Map.of("eventSettings", List.of("jdk.ExecutionSample#period=1ms,x=y"))));
		refuses("object", Map.of("flightRecording", Boolean.TRUE));
		refuses("one JVM argument", Map.of("vmArgs", List.of("value")));
		refuses("one JVM argument", Map.of("vmArgs", List.of("-Dfoo=a\n-Dbar=b")));
		refuses("adds -vmargs itself", Map.of("vmArgs", List.of("-VMARGS")));
		refuses("array", Map.of("vmArgs", "-Xmx2g"));
	}

	@Test
	void theJvmAcceptsThePathWithASpaceAndTheCheckLeavesNoFile(@TempDir Path dir) {
		Path file = dir.resolve("not there yet").resolve("startup.jfr");
		RelaunchVmArguments arguments = recording(
				Map.of("file", file.toString(), "eventSettings", List.of("jdk.ExecutionSample#period=1ms")));

		assertNull(arguments.jvmRefusal());

		assertTrue(Files.isDirectory(file.getParent()), "a missing directory stops the JVM, so it is created");
		// an old file would pass for the new recording
		assertFalse(Files.exists(file));
	}

	@Test
	void theArgumentRecordsIntoThatFileOnARealJvm(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("with space").resolve("startup.jfr");
		RelaunchVmArguments arguments = recording(Map.of("file", file.toString()));
		Files.createDirectories(file.getParent());
		List<String> command = new ArrayList<>(List.of(java()));
		command.addAll(arguments.vmArguments());
		command.add("-version");

		Process process = new ProcessBuilder(command).redirectErrorStream(true)
				.redirectOutput(dir.resolve("output.txt").toFile()).start();

		assertEquals(0, process.waitFor());
		assertTrue(Files.size(file) > 0, "nothing recorded into " + file);
	}

	@Test
	void aFlagTheJvmDoesNotKnowIsRefusedInsteadOfLeavingTheLauncherDialog() {
		String refusal = RelaunchVmArguments.of(Map.of("vmArgs", List.of("-XX:+NoSuchFlagAtAll"))).jvmRefusal();

		assertNotNull(refusal);
		assertTrue(refusal.contains("NoSuchFlagAtAll"), refusal);
	}

	@Test
	void anEventTheJvmDoesNotKnowIsRefusedBecauseItOnlyWarns() {
		String refusal = recording(Map.of("eventSettings", List.of("jdk.NoSuchEvent#period=1ms"))).jvmRefusal();

		assertNotNull(refusal);
		assertTrue(refusal.contains("jdk.NoSuchEvent"), refusal);
	}

	@Test
	void settingsTheJvmCannotFindAreRefused() {
		String refusal = recording(Map.of("settings", "no-such-settings")).jvmRefusal();

		assertNotNull(refusal);
		assertTrue(refusal.contains("no-such-settings"), refusal);
	}

	@Test
	void theAnswerSaysWhereWhenAndForHowLong(@TempDir Path dir) {
		Path file = dir.resolve("startup.jfr");
		JsonObject answer = new JsonObject();

		recording(Map.of("file", file.toString(), "seconds", Integer.valueOf(30))).addTo(answer);

		String text = answer.toString();
		assertTrue(text.contains("startup.jfr"), text);
		assertTrue(text.contains("REQUESTED, NOT OBSERVED"), text);
		assertTrue(text.contains("EMPTY until"), "the file exists long before it can be read: " + text);
		assertTrue(text.contains("WITHOUT ending the IDE"), text);
		assertTrue(text.contains("eclipse_stop_flight_recording"), text);
		assertTrue(text.contains("THIS RELAUNCH ONLY"), text);
		assertTrue(text.contains("exit code 23"), "what does repeat the arguments has to be named: " + text);
	}

	@Test
	void recordingUntilExitSaysSo() {
		JsonObject answer = new JsonObject();

		recording(Map.of("seconds", Integer.valueOf(0))).addTo(answer);

		assertTrue(answer.toString().contains("EXITS"), answer.toString());
		assertFalse(answer.toString().contains("duration=0"), answer.toString());
	}

	@Test
	void aDirectoryThatCannotBeCreatedIsRefusedBecauseItWouldStopTheRealJvm(@TempDir Path dir) throws Exception {
		Path blocker = Files.createFile(dir.resolve("a file"));

		String refusal = recording(Map.of("file", blocker.resolve("below").resolve("startup.jfr").toString())).jvmRefusal();

		assertNotNull(refusal);
		assertTrue(refusal.startsWith("Refused"), refusal);
		assertTrue(Files.isRegularFile(blocker));
	}

	@Test
	void noJavaToTryWithSkipsTheCheck(@TempDir Path dir) {
		RelaunchVmArguments arguments = RelaunchVmArguments.of(Map.of("vmArgs", List.of("-Xmx2g")));
		String javaHome = System.getProperty("java.home");
		try {
			System.setProperty("java.home", dir.toString());

			assertNull(arguments.jvmRefusal());
		} finally {
			restore("java.home", javaHome);
		}
		JsonObject answer = new JsonObject();
		arguments.addTo(answer);
		assertTrue(answer.toString().contains("skipped"), answer.toString());
	}

	@Test
	void aFileThatAppearedInTheMeantimeIsRefusedAndKept(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("startup.jfr");
		RelaunchVmArguments arguments = recording(Map.of("file", file.toString()));
		Files.writeString(file, "somebody else's");

		String refusal = arguments.jvmRefusal();

		assertNotNull(refusal);
		assertTrue(refusal.contains("already exists"), refusal);
		assertEquals("somebody else's", Files.readString(file));
	}

	@Test
	void argumentsThatEndOrReplaceTheProgramAreRefusedByName() {
		for (String argument : List.of("-version", "--version", "-fullversion", "--full-version", "-help", "-h", "-?",
				"--help", "-X", "--help-extra", "-Xinternalversion", "--list-modules", "--list-modules=java.base", "-d",
				"--describe-module", "--describe-module=java.base", "--dry-run", "--validate-modules", "-Xlog:help",
				"-Xshare:dump", "-XX:+PrintFlagsInitial", "-agentlib:jdwp=help")) {
			refuses("makes the JVM exit", Map.of("vmArgs", List.of(argument)));
		}
		for (String argument : List.of("-jar", "-jar=other.jar", "-m", "--module", "--module=a/b.C", "--source=25")) {
			refuses("names what the JVM runs", Map.of("vmArgs", List.of(argument)));
		}
		for (String argument : List.of("-cp", "-classpath", "--class-path", "--class-path=/tmp", "-Djava.class.path=/tmp")) {
			refuses("replaces the class path", Map.of("vmArgs", List.of(argument)));
		}
	}

	@Test
	void argumentsThatKeepTheProgramRunningStayAllowed() {
		List<String> allowed = List.of("-showversion", "--show-version", "-Xmx2g", "-Dversion=1", "-Dd=1", "-XshowSettings:vm",
				"-XX:+PrintFlagsFinal", "--add-opens=java.base/java.lang=ALL-UNNAMED");

		RelaunchVmArguments arguments = RelaunchVmArguments.of(Map.of("vmArgs", allowed));

		assertEquals(allowed, arguments.vmArguments());
		assertNull(arguments.jvmRefusal());
	}

	@Test
	void theCheckClaimsOnlyThatTheJvmStarts() {
		RelaunchVmArguments arguments = RelaunchVmArguments.of(Map.of("vmArgs", List.of("-Xmx2g")));
		assertNull(arguments.jvmRefusal());

		JsonObject answer = new JsonObject();
		arguments.addTo(answer);

		assertTrue(answer.toString().contains("not that Eclipse comes up"), answer.toString());
	}

	@Test
	void theStringFormOfTheOtherToolsIsAccepted() {
		RelaunchVmArguments off = RelaunchVmArguments.of(Map.of("flightRecording", "off"));
		assertTrue(off.isEmpty());
		assertEquals("", off.exitData());

		for (String settings : List.of("default", "profile")) {
			RelaunchVmArguments arguments = RelaunchVmArguments.of(Map.of("flightRecording", settings));
			String argument = arguments.vmArguments().get(0);
			assertTrue(argument.contains("settings=" + settings), argument);
			assertTrue(argument.contains("duration=60s"), argument);
			assertNull(arguments.jvmRefusal());
		}

		for (String other : List.of("on", "OFF", "", "/tmp/my.jfc")) {
			refuses("'off', 'default' and 'profile'", Map.of("flightRecording", other));
		}
	}

	@Test
	void theStackDepthIsItsOwnArgumentWithADefaultForBothForms() {
		String deep = "-XX:FlightRecorderOptions:stackdepth=256";
		assertTrue(recording(Map.of()).vmArguments().contains(deep));
		assertTrue(RelaunchVmArguments.of(Map.of("flightRecording", "profile")).vmArguments().contains(deep));

		RelaunchVmArguments arguments = recording(Map.of("stackDepth", Integer.valueOf(2048)));
		assertTrue(arguments.vmArguments().contains("-XX:FlightRecorderOptions:stackdepth=2048"));
		assertNull(arguments.jvmRefusal());

		JsonObject answer = new JsonObject();
		arguments.addTo(answer);
		assertTrue(answer.toString().contains("\"stackDepth\": 2048"), answer.toString());
	}

	@Test
	void aStackDepthTheJvmWouldSilentlyClampIsRefused() {
		for (Object depth : List.of(Integer.valueOf(0), Integer.valueOf(-1), Integer.valueOf(2049), Double.valueOf(1.5), "deep")) {
			refuses("'stackDepth'", Map.of("flightRecording", Map.of("stackDepth", depth)));
		}
	}

	@Test
	void flightRecorderOptionsInVmArgsWouldReplaceTheDepthSoTheyAreRefusedWithARecording() {
		for (String argument : List.of("-XX:FlightRecorderOptions:memorysize=32m", "-XX:FlightRecorderOptions=stackdepth=100")) {
			refuses("keeps only the last", Map.of("flightRecording", "profile", "vmArgs", List.of(argument)));
			assertEquals(List.of(argument), RelaunchVmArguments.of(Map.of("vmArgs", List.of(argument))).vmArguments());
		}
	}

	@Test
	void secondsOutOfRangeAreRefusedRatherThanClamped() {
		for (Object seconds : List.of(Integer.valueOf(-1), Integer.valueOf(86401), Long.valueOf(4294967356L),
				Double.valueOf(1.5), "soon", Boolean.TRUE)) {
			refuses("'seconds'", Map.of("flightRecording", Map.of("seconds", seconds)));
		}
		assertTrue(recording(Map.of("seconds", Integer.valueOf(86400))).vmArguments().get(0).contains("duration=86400s"));
	}

	@Test
	void aBlankFileIsRefusedRatherThanBecomingATemporaryOne() {
		refuses("blank", Map.of("flightRecording", Map.of("file", "")));
		refuses("blank", Map.of("flightRecording", Map.of("file", "   ")));
	}

	@Test
	void aFileWithSurroundingWhitespaceIsRefusedRatherThanBecomingAnotherPath(@TempDir Path dir) {
		String file = dir.resolve("startup.jfr").toString();

		refuses("whitespace", Map.of("flightRecording", Map.of("file", file + " ")));
		refuses("whitespace", Map.of("flightRecording", Map.of("file", " " + file)));
		assertTrue(recording(Map.of("file", file)).vmArguments().get(0).contains("filename=" + file));
	}

	@Test
	void theProbeKeepsOnlyLogOptionsThatWriteNoFile() {
		List<String> dropped = List.of("-Xlog:gc:file=/tmp/a.log", "-Xlog:gc:/tmp/a.log", "-Xlog:gc:C:\\a.log", "-Xlog::a.log",
				"-Xlog:gc:stdout.log", "-Xlog:gc:file=/tmp/a.log::filecount=0", "-Xloggc:/tmp/a.log", "-XX:LogFile=/tmp/a.log",
				"-XX:+LogCompilation", "-XX:+LogVMOutput", "-XX:ArchiveClassesAtExit=/tmp/a.jsa", "-XX:AOTCacheOutput=/tmp/a.aot",
				"-XX:AOTConfiguration=/tmp/a.conf", "-XX:+PerfDataSaveToFile", "-XX:PerfDataSaveFile=/tmp/a.hsperf");
		List<String> kept = List.of("-Xlog","-Xlog:disable", "-Xlog:gc", "-Xlog:gc:stdout", "-Xlog:gc:stderr:uptime", "-Xlog:gc::uptime",
				"-XX:+UnlockDiagnosticVMOptions", "-XX:-LogVMOutput");

		List<String> all = new ArrayList<>(dropped);
		all.addAll(kept);
		assertEquals(kept, RelaunchVmArguments.probeArguments(String.join("\n", all)));
	}

	/** Every form that replaced an existing file on a real JVM. */
	private static List<String> logFileOptions(Path log) {
		return List.of("-Xlog:gc:file=" + log, "-Xlog:gc:file=" + log + "::filecount=0", "-Xlog:gc:" + log, "-Xloggc:" + log,
				"-XX:LogFile=" + log, "-XX:+LogVMOutput");
	}

	private static void assertOnlyTheLogIsThere(Path dir, Path log) throws Exception {
		assertEquals("the log of the running IDE", Files.readString(log));
		try (var files = Files.list(dir)) {
			assertEquals(List.of(log), files.toList(), "a rotation leaves a sibling");
		}
	}

	@Test
	void aRealJvmLeavesTheLogFilesOfThePropertyAlone(@TempDir Path dir) throws Exception {
		Path log = Files.writeString(dir.resolve("gc.log"), "the log of the running IDE");
		List<String> shaped = new ArrayList<>(logFileOptions(log));
		shaped.add(0, "-XX:+UnlockDiagnosticVMOptions");
		RelaunchVmArguments arguments = RelaunchVmArguments.of(Map.of("vmArgs", List.of("-Xmx64m")));

		assertNull(arguments.jvmRefusal(String.join("\n", shaped)));

		assertOnlyTheLogIsThere(dir, log);
		JsonObject answer = new JsonObject();
		arguments.addTo(answer);
		assertFalse(answer.toString().contains("gc.log"), answer.toString());
		assertFalse(answer.toString().contains("NOT TRIED"), answer.toString());
	}

	@Test
	void aRequestedLogFileIsNotProbedAndTheCheckSaysSo(@TempDir Path dir) throws Exception {
		Path log = Files.writeString(dir.resolve("gc.log"), "the log of the running IDE");
		List<String> requested = new ArrayList<>(logFileOptions(log));
		requested.add(0, "-XX:+UnlockDiagnosticVMOptions");
		RelaunchVmArguments arguments = RelaunchVmArguments.of(Map.of("vmArgs", requested));

		assertNull(arguments.jvmRefusal(null));

		assertOnlyTheLogIsThere(dir, log);
		JsonObject answer = new JsonObject();
		arguments.addTo(answer);
		assertTrue(answer.toString().contains("NOT TRIED"), answer.toString());
		for (String option : logFileOptions(log)) {
			assertTrue(arguments.exitData().contains(option + "\n"), "the relaunch still gets it: " + option);
		}
	}

	@Test
	void whatIsLeftOutDoesNotHideARefusalOfTheRest(@TempDir Path dir) throws Exception {
		Path log = Files.writeString(dir.resolve("gc.log"), "the log of the running IDE");
		String refusal = RelaunchVmArguments.of(Map.of("vmArgs", List.of("-Xlog:gc:file=" + log, "-XX:+NoSuchFlagAtAll"))).jvmRefusal(null);

		assertNotNull(refusal);
		assertTrue(refusal.contains("NoSuchFlagAtAll"), refusal);
		assertTrue(refusal.contains("leaving out"), refusal);
		assertOnlyTheLogIsThere(dir, log);
	}

	@Test
	void aRecordingInVmArgsIsNotProbedSinceNothingWouldDeleteItsFile(@TempDir Path dir) throws Exception {
		Path kept = Files.writeString(dir.resolve("kept.jfr"), "a recording of the running IDE");
		RelaunchVmArguments arguments = RelaunchVmArguments
				.of(Map.of("vmArgs", List.of("-XX:StartFlightRecording=filename=" + kept)));

		assertNull(arguments.jvmRefusal(null));

		assertEquals("a recording of the running IDE", Files.readString(kept));
		JsonObject answer = new JsonObject();
		arguments.addTo(answer);
		assertTrue(answer.toString().contains("NOT TRIED"), answer.toString());
	}

	@Test
	void aCallWithTooLittleTimeLeftRefusesWithoutStartingAJvm(@TempDir Path dir) {
		Path file = dir.resolve("deeper/startup.jfr");
		RelaunchVmArguments arguments = RelaunchVmArguments
				.of(Map.of("flightRecording", Map.of("file", file.toString())));
		CallBudget.enter(4);
		try {
			String refusal = arguments.jvmRefusal(null);
			assertTrue(refusal.contains("too little of this call is left"), refusal);
		} finally {
			CallBudget.leave();
		}
		assertFalse(Files.exists(file.getParent()), "nothing was tried, so nothing was created");
	}

	@Test
	void aRequestedAgentIsNotProbedAndTheCheckSaysSo(@TempDir Path dir) {
		// started at all, the first would listen on a port and wait for a debugger until the probe times out
		for (String agent : List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0",
				"-agentpath:" + dir.resolve("none.so"), "-javaagent:" + dir.resolve("none.jar"), "-Xrunnosuchlibrary")) {
			RelaunchVmArguments arguments = RelaunchVmArguments.of(Map.of("vmArgs", List.of(agent, "-Xmx64m")));

			assertNull(arguments.jvmRefusal(null), agent);

			JsonObject answer = new JsonObject();
			arguments.addTo(answer);
			assertTrue(answer.toString().contains("NOT TRIED: [" + agent.replace("\\", "\\\\") + "]"), answer.toString());
			assertTrue(arguments.exitData().contains(agent + "\n"), "the relaunch still gets it: " + agent);
		}
	}

	@Test
	void aRecordingIsStillProbedAndNotReportedAsLeftOut() {
		RelaunchVmArguments arguments = recording(Map.of());

		assertNull(arguments.jvmRefusal(null));

		JsonObject answer = new JsonObject();
		arguments.addTo(answer);
		assertFalse(answer.toString().contains("NOT TRIED"), answer.toString());
	}

	@Test
	void developmentModeNamesTheLaunchConfiguration(@TempDir Path dir) {
		assumeTrue(Platform.inDevelopmentMode(), "only a development mode IDE refuses this");

		McpToolResult refusal = RestartTool.checkVmArguments(RelaunchVmArguments.of(Map.of("vmArgs", List.of("-Xmx2g"))),
				dir.toString());

		assertNotNull(refusal);
		assertTrue(refusal.text().contains("launch configuration's VM arguments"), refusal.text());
	}

	@Test
	void whetherJvmArgumentsCanBeTriedAtAll() {
		String workspace = "/some/workspace";
		String location = "/current/workspace";

		for (String asked : Arrays.asList(workspace, null)) {
			for (String current : Arrays.asList(location, null)) {
				String refusal = RestartTool.vmArgumentsRefusal(true, asked, current);
				assertNotNull(refusal);
				assertTrue(refusal.contains("development mode"), "development mode comes first: " + refusal);
			}
		}
		assertNull(RestartTool.vmArgumentsRefusal(false, workspace, location));
		assertNull(RestartTool.vmArgumentsRefusal(false, workspace, null), "a workspace passed is enough to relaunch into");
		assertNull(RestartTool.vmArgumentsRefusal(false, null, location));
		String none = RestartTool.vmArgumentsRefusal(false, null, null);
		assertNotNull(none);
		assertTrue(none.contains("no workspace"), none);
	}

	@Test
	void jvmArgumentsAlwaysGoIntoTheExitDataSinceRestartTrueWouldPutDataBehindThem() {
		String target = "/target";
		String current = "/current";

		assertNull(RestartTool.exitDataWorkspace(null, false, current), "the plain case is restart(true)");
		assertEquals(current, RestartTool.exitDataWorkspace(null, true, current));
		assertEquals(target, RestartTool.exitDataWorkspace(target, false, current));
		assertEquals(target, RestartTool.exitDataWorkspace(target, true, current));
	}

	@Test
	void theProbeKeepsTheArgumentsOfTheIdeAndDropsWhatBelongsToItsOwnRun() {
		String shaped = String.join("\n", "-Dosgi.requiredJavaVersion=21", "-Xms256m", "-Xmx2048m", "--add-modules=ALL-SYSTEM",
				"--add-opens", "java.base/java.lang=ALL-UNNAMED", "-XX:StartFlightRecording=name=mcp,filename=/tmp/a b.jfr",
				"-XX:FlightRecorderOptions:stackdepth=256", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y",
				"-agentpath:/lib/a.so", "-javaagent:/lib/a.jar", "-Xrunjdwp:suspend=y", "-Dcom.sun.management.jmxremote.port=9010", "-cp", "/some/classes", "-classpath",
				"/more/classes", "--class-path=/other/classes", "-Dafter=1");
		List<String> kept = List.of("-Dosgi.requiredJavaVersion=21", "-Xms256m", "-Xmx2048m", "--add-modules=ALL-SYSTEM", "--add-opens",
				"java.base/java.lang=ALL-UNNAMED", "-Dafter=1");
		String launcher = "/opt/my eclipse/plugins/org.eclipse.equinox.launcher_1.7.0.jar";

		// exec launching ends in -jar and the jar, JNI launching in -Djava.class.path, each with the launcher's closing newline
		assertEquals(kept, RelaunchVmArguments.probeArguments(shaped + "\n-jar\n" + launcher + "\n"));
		assertEquals(kept, RelaunchVmArguments.probeArguments(shaped + "\n-Djava.class.path=" + launcher + "\n"));
		assertEquals(kept, RelaunchVmArguments.probeArguments((shaped + "\n-jar\n" + launcher + "\n").replace("\n", "\r\n")));
		assertEquals(List.of("-Xms1m"), RelaunchVmArguments.probeArguments("-Xms1m\n-jar\n" + launcher + "\n-Dbehind=1\n"));
	}

	@Test
	void nothingInThePropertyMeansTheRequestedArgumentsAlone() {
		for (String nothing : Arrays.asList(null, "", "\n", "\r\n", "-jar\n/some.jar\n")) {
			assertEquals(List.of(), RelaunchVmArguments.probeArguments(nothing));
			RelaunchVmArguments arguments = RelaunchVmArguments.of(Map.of("vmArgs", List.of("-Xmx64m")));
			assertNull(arguments.jvmRefusal(nothing));
			JsonObject answer = new JsonObject();
			arguments.addTo(answer);
			assertTrue(answer.toString().contains("these arguments alone"), answer.toString());
		}
	}

	@Test
	void aCombinationRefusedOnlyWithTheArgumentsOfTheIniIsRefused() {
		RelaunchVmArguments smallHeap = RelaunchVmArguments.of(Map.of("vmArgs", List.of("-Xmx64m")));
		assertNull(smallHeap.jvmRefusal(null));
		String heap = smallHeap.jvmRefusal("-Xms128m\n");
		assertNotNull(heap);
		assertTrue(heap.contains("Initial heap size set to a larger value than the maximum heap size"), heap);
		assertTrue(heap.contains("-Xms128m"), "the refusal has to name what it was tried with: " + heap);

		RelaunchVmArguments collector = RelaunchVmArguments.of(Map.of("vmArgs", List.of("-XX:+UseSerialGC")));
		assertNull(collector.jvmRefusal(null));
		String collectors = collector.jvmRefusal("-XX:+UseG1GC\n");
		assertNotNull(collectors);
		assertTrue(collectors.contains("collector"), collectors);
	}

	@Test
	void aRealJvmRunsNeitherTheLauncherNorTheAgentsNorTheRecordingOfTheProperty(@TempDir Path dir) throws Exception {
		Path earlier = dir.resolve("earlier.jfr");
		Files.writeString(earlier, "the recording of the running IDE");
		String shaped = String.join("\n", "-Xms32m", "-XX:StartFlightRecording=filename=" + earlier,
				"-XX:FlightRecorderOptions:stackdepth=not-a-number",
				"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0", "-javaagent:" + dir.resolve("none.jar"),
				"-jar", dir.resolve("none.jar").toString(), "");
		RelaunchVmArguments arguments = RelaunchVmArguments.of(Map.of("vmArgs", List.of("-Xmx64m")));

		assertNull(arguments.jvmRefusal(shaped));

		assertEquals("the recording of the running IDE", Files.readString(earlier));
		JsonObject answer = new JsonObject();
		arguments.addTo(answer);
		assertTrue(answer.toString().contains("-Xms32m"), answer.toString());
		assertTrue(answer.toString().contains("not that Eclipse comes up"), answer.toString());
		assertFalse(answer.toString().contains("these arguments alone"), answer.toString());
		assertFalse(answer.toString().contains("none.jar"), answer.toString());
	}

	@Test
	@SuppressWarnings("unchecked")
	void theDescriptionAndSchemaOfferBothFormsTheLimitsAndTheTypes() throws Exception {
		var tool = TestFixture.tool("eclipse_restart");
		assertTrue(tool.getDescription().contains("THIS ONE RELAUNCH"), tool.getDescription());
		assertTrue(tool.getDescription().contains("development mode"), tool.getDescription());
		assertTrue(tool.getInputSchema().contains("jdk.ExecutionSample#period=1ms"), tool.getInputSchema());

		Map<String, Object> properties = (Map<String, Object>) TestFixture.parse(tool.getInputSchema()).get("properties");
		Map<String, Object> vmArgs = (Map<String, Object>) properties.get("vmArgs");
		assertEquals("array", vmArgs.get("type"));
		assertEquals("string", ((Map<String, Object>) vmArgs.get("items")).get("type"));

		Map<String, Object> recording = (Map<String, Object>) properties.get("flightRecording");
		assertEquals(List.of("object", "string"), recording.get("type"));
		Map<String, Object> options = (Map<String, Object>) recording.get("properties");
		assertEquals("string", ((Map<String, Object>) options.get("settings")).get("type"));
		assertEquals("string", ((Map<String, Object>) options.get("file")).get("type"));
		assertEquals("array", ((Map<String, Object>) options.get("eventSettings")).get("type"));
		Map<String, Object> seconds = (Map<String, Object>) options.get("seconds");
		assertEquals("integer", seconds.get("type"));
		assertEquals(86400, ((Number) seconds.get("maximum")).intValue());
		Map<String, Object> depth = (Map<String, Object>) options.get("stackDepth");
		assertEquals("integer", depth.get("type"));
		assertEquals(2048, ((Number) depth.get("maximum")).intValue());
		assertEquals(256, ((Number) depth.get("default")).intValue());
	}

	private static String java() {
		return Path.of(System.getProperty("java.home"), "bin", FileLocations.isWindows() ? "java.exe" : "java")
				.toString();
	}

	private static void restore(String property, String value) {
		if (value == null) {
			System.clearProperty(property);
		} else {
			System.setProperty(property, value);
		}
	}
}
