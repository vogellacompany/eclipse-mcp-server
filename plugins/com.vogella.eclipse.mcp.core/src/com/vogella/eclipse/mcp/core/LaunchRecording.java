package com.vogella.eclipse.mcp.core;

import java.nio.file.Path;

/**
 * Records a launched JVM with Java Flight Recorder, through the only channel
 * that reaches one.
 * <p>
 * The recording tools of this server work inside the IDE's own JVM, so they
 * cannot see a program the IDE launches: that is a separate process. Asking the
 * JVM to record itself from its command line does reach it, and costs no attach
 * mechanism and no external tool. The price is that the decision has to be made
 * before the launch and the file exists only once the program ends.
 */
public final class LaunchRecording {

	/** How a caller asks for one, and what each answer costs. */
	public static final String SCHEMA_PROPERTY = """
			{"type":"string","enum":["off","default","profile"],"default":"off","description":"Record the launched JVM with Java Flight Recorder. 'profile' includes allocation and execution samples at a few percent overhead, 'default' covers GC and threads at about one percent. The file is written when the program exits and is read with eclipse_stop_flight_recording by passing its path as 'file'. The IDE's own recording tools cannot see a launched process, which is what this is for."}"""; //$NON-NLS-1$

	private LaunchRecording() {
	}

	/** Whether this value asks for a recording at all. */
	public static boolean wanted(String settings) {
		return settings != null && !settings.isBlank() && !"off".equals(settings); //$NON-NLS-1$
	}

	/** A file to record into, named after what is being launched. */
	public static Path fileFor(String label) {
		String safe = label == null || label.isBlank() ? "launch" //$NON-NLS-1$
				: label.replaceAll("[^A-Za-z0-9._-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
		return Path.of(System.getProperty("java.io.tmpdir"), //$NON-NLS-1$
				"mcp-%s-%d.jfr".formatted(safe, Long.valueOf(System.nanoTime()))); //$NON-NLS-1$
	}

	/** The recording name, which is how anything outside the JVM addresses it. */
	public static final String NAME = "mcp"; //$NON-NLS-1$

	/**
	 * The VM argument that starts the recording.
	 * <p>
	 * {@code dumponexit} is the fallback rather than the plan. It writes the file
	 * when the program ends, and ending the program is the hard part of measuring
	 * one: a running application cannot be asked to exit from here, and killing it
	 * both loses the dump and spoils the next start, since the framework never
	 * persists its state. So the recording carries a name, which is what lets it
	 * be dumped from outside while the program keeps running, and a duration
	 * writes the file by itself at a point the caller chooses.
	 *
	 * @param seconds write the file after this long without ending the program, 0
	 *                to rely on the exit dump alone
	 */
	public static String vmArgument(String settings, Path file, int seconds) {
		String duration = seconds > 0 ? ",duration=%ds".formatted(Integer.valueOf(seconds)) : ""; //$NON-NLS-1$ //$NON-NLS-2$
		String argument = "-XX:StartFlightRecording=name=%s,settings=%s,dumponexit=true%s,filename=%s" //$NON-NLS-1$
				.formatted(NAME, settings, duration, file);
		// the launch keeps its VM arguments as one string and the platform splits it
		// on whitespace, so an unquoted path with a space in it arrives as two
		// arguments and the launch fails on the second. The temporary directory has
		// one on any Windows account whose name has one, which is most of them
		return argument.indexOf(' ') < 0 ? argument : '"' + argument + '"'; //$NON-NLS-1$
	}

	/** Appended rather than replaced, so a caller's own VM arguments survive. */
	public static String appendTo(String vmArguments, String argument) {
		return vmArguments == null || vmArguments.isBlank() ? argument : vmArguments + " " + argument; //$NON-NLS-1$
	}

	/** What the caller has to know to get anything out of it. */
	public static String note(Path file, int seconds) {
		if (seconds > 0) {
			return "The JVM records itself into %s and writes it after %d seconds WITHOUT ending the program, which is what makes a startup measurable: the application keeps running and the file is complete. THE RECORDING ENDS THERE: this is a duration, so it is one window and nothing is added afterwards, not even when the program exits, and the file keeps the timestamp of that dump. For a window later in the run, dump it from outside with 'jcmd <pid> JFR.dump name=%s filename=...', which leaves the recording running. Read the file with eclipse_stop_flight_recording passing file." //$NON-NLS-1$
					.formatted(file, Integer.valueOf(seconds), NAME);
		}
		return "The JVM records itself into %s and writes it when it EXITS, so the file is not there while the program runs and a program that is killed rather than ended leaves nothing. To measure something that must keep running, pass flightRecordingSeconds, or dump it from outside with 'jcmd <pid> JFR.dump name=%s filename=...', which leaves the process running. Read the file with eclipse_stop_flight_recording passing file." //$NON-NLS-1$
				.formatted(file, NAME);
	}
}
