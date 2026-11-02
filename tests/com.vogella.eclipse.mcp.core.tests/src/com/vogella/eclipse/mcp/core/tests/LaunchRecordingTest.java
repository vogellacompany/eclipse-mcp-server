package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.LaunchRecording;

/**
 * The VM argument that records a launched program.
 * <p>
 * It is the only way this server reaches another process: everything else it
 * records runs inside the IDE's own JVM.
 */
class LaunchRecordingTest {

	/** Written with the platform's separator, which is a backslash on Windows. */
	private static final Path FILE = Path.of("/tmp/run.jfr");

	@Test
	void theArgumentDumpsOnExit() {
		String argument = LaunchRecording.vmArgument("profile", FILE, 0);

		assertTrue(argument.startsWith("-XX:StartFlightRecording="), argument);
		assertTrue(argument.contains("settings=profile"), argument);
		assertTrue(argument.contains("filename=" + FILE), argument);
		// without this a program that ends normally takes the recording with it and
		// there is nothing left to read, which is the whole feature
		assertTrue(argument.contains("dumponexit=true"), argument);
		// the name is what lets the recording be dumped from outside while the
		// program keeps running, which is the only way to measure a startup
		assertTrue(argument.contains("name=" + LaunchRecording.NAME), argument);
		assertFalse(argument.contains("duration"), "no duration was asked for: " + argument);
	}

	@Test
	void aDurationWritesTheFileWithoutEndingTheProgram() {
		String argument = LaunchRecording.vmArgument("profile", FILE, 90);

		assertTrue(argument.contains("duration=90s"), argument);
		assertTrue(argument.contains("dumponexit=true"), "the exit dump stays as the fallback: " + argument);
	}

	@Test
	void onlyTheFormThatGetsSplitOnWhitespaceIsQuoted() {
		Path spaced = Path.of("/tmp/with space/run.jfr");

		String quoted = LaunchRecording.vmArgument("profile", spaced, 0);
		assertTrue(quoted.startsWith("\"-XX:") && quoted.endsWith("\""), quoted);

		String unquoted = LaunchRecording.unquotedVmArgument("profile", spaced, 0, List.of("jdk.ExecutionSample#period=1ms"));
		assertTrue(unquoted.startsWith("-XX:"), unquoted);
		assertTrue(unquoted.endsWith("filename=" + spaced + ",jdk.ExecutionSample#period=1ms"), unquoted);
	}

	@Test
	void theCallersOwnVmArgumentsSurvive() {
		assertEquals("-Xmx2g -XX:StartFlightRecording=x",
				LaunchRecording.appendTo("-Xmx2g", "-XX:StartFlightRecording=x"));
		assertEquals("-XX:StartFlightRecording=x", LaunchRecording.appendTo(null, "-XX:StartFlightRecording=x"));
		assertEquals("-XX:StartFlightRecording=x", LaunchRecording.appendTo("   ", "-XX:StartFlightRecording=x"));
	}

	@Test
	void offAndAbsentBothMeanNoRecording() {
		assertFalse(LaunchRecording.wanted("off"));
		assertFalse(LaunchRecording.wanted(null));
		assertFalse(LaunchRecording.wanted(""));
		assertTrue(LaunchRecording.wanted("profile"));
		assertTrue(LaunchRecording.wanted("default"));
	}

	@Test
	void theFileNameCarriesNothingAShellWouldReadAsSyntax() {
		String name = LaunchRecording.fileFor("com.example.Main test #1").getFileName().toString();

		assertTrue(name.endsWith(".jfr"), name);
		assertFalse(name.contains(" "), name);
		assertFalse(name.contains("#"), name);
		assertTrue(name.contains("com.example.Main"), name);
	}

	@Test
	void twoLaunchesDoNotWriteToTheSameFile() {
		assertFalse(LaunchRecording.fileFor("Main").equals(LaunchRecording.fileFor("Main")));
	}

	@Test
	void theNoteSaysWhenTheFileAppears() {
		String note = LaunchRecording.note(FILE, 0);

		assertTrue(note.contains(FILE.toString()), note);
		assertTrue(note.contains("EXITS"), note);
		assertTrue(note.contains("EMPTY"), "the file is there but holds nothing while the program runs: " + note);
		assertFalse(note.contains("is not there"), note);
		assertTrue(note.contains("eclipse_stop_flight_recording"), note);
		assertTrue(note.contains("jcmd"), "the way out for a program that must keep running: " + note);

		String timed = LaunchRecording.note(FILE, 90);
		assertTrue(timed.contains("WITHOUT ending"), timed);
	}
}
