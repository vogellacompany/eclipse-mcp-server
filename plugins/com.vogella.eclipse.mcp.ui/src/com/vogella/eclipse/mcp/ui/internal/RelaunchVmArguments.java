package com.vogella.eclipse.mcp.ui.internal;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.core.LaunchRecording;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The JVM arguments of one relaunch of the IDE: a flight recording, other VM arguments, or both.
 */
public final class RelaunchVmArguments {

	/** Without it the launcher lets these replace the VM arguments of eclipse.ini instead of following them. */
	private static final String APPEND_VMARGS = "--launcher.appendVmargs"; //$NON-NLS-1$

	private static final String VMARGS = "-vmargs"; //$NON-NLS-1$

	private static final String DEFAULT_SETTINGS = "profile"; //$NON-NLS-1$

	/** Exit with 0 and never reach the main class, so a trial with -version cannot tell. */
	private static final Set<String> ENDS_THE_JVM = Set.of("-version", "--version", "-fullversion", "--full-version", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"-help", "-h", "-?", "--help", "-X", "--help-extra", "-Xinternalversion", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
			"--list-modules", "-d", "--describe-module", "--dry-run", "--validate-modules", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			"-Xlog:help", "-Xshare:dump", "-XX:+PrintFlagsInitial", "-agentlib:jdwp=help"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	/** Name what the JVM runs, which the launcher's own -jar behind them has to decide. */
	private static final Set<String> REPLACES_THE_PROGRAM = Set.of("-jar", "-m", "--module", "--source"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	/** Long enough for a whole startup, and the file stays empty until then. */
	private static final int DEFAULT_SECONDS = 60;

	private static final int MAX_SECONDS = 24 * 60 * 60;

	/** Long enough for a startup, where the 64 of JFR truncates most stacks and loses the frames that name the application. */
	private static final int DEFAULT_STACK_DEPTH = 256;

	/** The JVM silently takes anything above this as this, and anything below 1 as 1. */
	private static final int MAX_STACK_DEPTH = 2048;

	private static final String FLIGHT_RECORDER_OPTIONS = "-XX:FlightRecorderOptions"; //$NON-NLS-1$

	private static final String START_FLIGHT_RECORDING = "-XX:StartFlightRecording"; //$NON-NLS-1$

	private static final int CHECK_SECONDS = 10;

	/** What the check leaves of the call for the UI step that schedules the restart. */
	private static final int FINAL_STEP_SECONDS = 2;

	/** The launcher's JVM arguments for this process, one per line, its own launcher jar or class path among them. */
	private static final String IDE_VM_ARGUMENTS = "eclipse.vmargs"; //$NON-NLS-1$

	/** Take a value in the next argument unless written with an equals sign. */
	private static final Set<String> CLASS_PATH = Set.of("-cp", "-classpath", "--class-path", "-Djava.class.path"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	/** Belong to the running IDE: a repeat overwrites its recording, and JMX binds a port the running IDE holds. */
	private static final List<String> NOT_FOR_A_PROBE = List.of(START_FLIGHT_RECORDING, FLIGHT_RECORDER_OPTIONS,
			"-Dcom.sun.management."); //$NON-NLS-1$

	/** Can suspend the probe, listen on a port or act on their own. */
	private static final List<String> AGENTS = List.of("-agentlib:", "-agentpath:", "-javaagent:", "-Xrun"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	/** Replace their file when the JVM starts or exits or, without -XX:LogFile, leave a hotspot_pid log behind. */
	private static final List<String> WRITES_A_FILE = List.of("-Xloggc:", "-XX:LogFile", "-XX:+LogCompilation", "-XX:+LogVMOutput", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"-XX:ArchiveClassesAtExit", "-XX:AOTCacheOutput", "-XX:AOTConfiguration", "-XX:PerfDataSaveFile", "-XX:+PerfDataSaveToFile"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

	/** An -Xlog output other than the console, which the JVM rotates or truncates on start. */
	private static final Pattern XLOG_TO_FILE = Pattern.compile("-Xlog:[^:]*:(?!:|$|std(?:out|err)(?::|$)).*"); //$NON-NLS-1$

	private static final Pattern EVENT_SETTING = Pattern.compile("\\+?[\\w.$]+#\\w+=[^,\"\\r\\n]+"); //$NON-NLS-1$

	private static final Pattern JFR_WARNING = Pattern.compile("(?m)^.*\\[warning\\s*\\]\\[jfr.*$"); //$NON-NLS-1$

	private final List<String> vmArguments;

	private final Path file;

	private final JsonObject recording;

	private String jvmCheck = "not run"; //$NON-NLS-1$

	private RelaunchVmArguments(List<String> vmArguments, Path file, JsonObject recording) {
		this.vmArguments = vmArguments;
		this.file = file;
		this.recording = recording;
	}

	/**
	 * What the tool arguments ask for.
	 *
	 * @throws IllegalArgumentException with the refusal for the caller
	 */
	public static RelaunchVmArguments of(Map<String, Object> arguments) {
		List<String> all = new ArrayList<>();
		Path file = null;
		JsonObject recording = null;
		Map<String, Object> requested = recordingOptions(arguments == null ? null : arguments.get("flightRecording")); //$NON-NLS-1$
		if (requested != null) {
			ToolArguments options = ToolArguments.of(requested);
			String settings = plain("settings", options.getString("settings", DEFAULT_SETTINGS)); //$NON-NLS-1$ //$NON-NLS-2$
			int seconds = wholeNumber("seconds", requested.get("seconds"), DEFAULT_SECONDS, 0, MAX_SECONDS); //$NON-NLS-1$ //$NON-NLS-2$
			int stackDepth = wholeNumber("stackDepth", requested.get("stackDepth"), DEFAULT_STACK_DEPTH, 1, MAX_STACK_DEPTH); //$NON-NLS-1$ //$NON-NLS-2$
			if (requested.get("file") != null && options.getString("file") == null) { //$NON-NLS-1$ //$NON-NLS-2$
				throw new IllegalArgumentException("'file' is blank. Leave it out for a new temporary file."); //$NON-NLS-1$
			}
			file = recordingFile(options.getText("file")); //$NON-NLS-1$
			List<String> eventSettings = eventSettings(requested.get("eventSettings")); //$NON-NLS-1$
			String argument = LaunchRecording.unquotedVmArgument(settings, file, seconds, eventSettings);
			all.add(argument);
			all.add(FLIGHT_RECORDER_OPTIONS + ":stackdepth=" + stackDepth); //$NON-NLS-1$
			recording = new JsonObject().put("file", file.toString()) //$NON-NLS-1$
					.put("settings", settings) //$NON-NLS-1$
					.put("seconds", Integer.valueOf(seconds)) //$NON-NLS-1$
					.put("stackDepth", Integer.valueOf(stackDepth)) //$NON-NLS-1$
					.put("eventSettings", json(eventSettings)) //$NON-NLS-1$
					.put("readWith", "eclipse_stop_flight_recording with file set to this path") //$NON-NLS-1$ //$NON-NLS-2$
					.put("note", note(file, seconds)); //$NON-NLS-1$
		}
		List<String> vmArgs = vmArgs(arguments == null ? null : arguments.get("vmArgs")); //$NON-NLS-1$
		if (recording != null) {
			for (String argument : vmArgs) {
				if (argument.startsWith(FLIGHT_RECORDER_OPTIONS)) {
					throw new IllegalArgumentException(
							"'%s' cannot go with flightRecording: the JVM keeps only the last %s, so it would replace the stack depth of the recording and put it back to 64. Set the depth with stackDepth in flightRecording." //$NON-NLS-1$
									.formatted(argument, FLIGHT_RECORDER_OPTIONS));
				}
			}
		}
		all.addAll(vmArgs);
		return new RelaunchVmArguments(List.copyOf(all), file, recording);
	}

	public boolean isEmpty() {
		return vmArguments.isEmpty();
	}

	/** The arguments as the JVM has to receive them, one element per argument. */
	public List<String> vmArguments() {
		return vmArguments;
	}

	/**
	 * The lines for the exit data, which must come last.
	 * <p>
	 * The launcher takes everything after {@code -vmargs} for the JVM, so an argument
	 * added behind these, as {@code Workbench.restart(true)} adds {@code -data}, makes
	 * the JVM refuse to start.
	 */
	public String exitData() {
		if (vmArguments.isEmpty()) {
			return ""; //$NON-NLS-1$
		}
		return APPEND_VMARGS + '\n' + VMARGS + '\n' + String.join("\n", vmArguments) + '\n'; //$NON-NLS-1$
	}

	/**
	 * Why the JVM would not start with these arguments, or {@code null}.
	 * <p>
	 * A refused argument takes the IDE down behind the launcher's error dialog, with no
	 * server left to report it, and a recording that only warns would come back without
	 * what was asked for. So the arguments are tried on a throwaway JVM first, behind this
	 * IDE's own as in the relaunch, since some combinations are refused only together.
	 */
	public String jvmRefusal() {
		return jvmRefusal(System.getProperty(IDE_VM_ARGUMENTS));
	}

	/** {@link #jvmRefusal()} with the value of {@code eclipse.vmargs} passed in. */
	public String jvmRefusal(String ideVmArguments) {
		// the check below deletes this file, so it has to be the one the check wrote
		if (file != null && isThere(file)) {
			return "Refused: " + alreadyThere(file); //$NON-NLS-1$
		}
		int checkSeconds = CallBudget.boundedWaitSeconds(CHECK_SECONDS + FINAL_STEP_SECONDS) - FINAL_STEP_SECONDS;
		if (checkSeconds < 1) {
			return "Refused: too little of this call is left to try these arguments on a JVM, usually because the UI thread was busy. The IDE keeps running; call again."; //$NON-NLS-1$
		}
		Path java = Path.of(System.getProperty("java.home"), "bin", FileLocations.isWindows() ? "java.exe" : "java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		Path output = null;
		try {
			// the recording's JVM ends when it cannot write the file, trial or no trial
			if (file != null) {
				Files.createDirectories(file.getParent());
			}
			if (!Files.isExecutable(java)) {
				jvmCheck = "skipped: no " + java; //$NON-NLS-1$
				return null;
			}
			output = Files.createTempFile("mcp-jvm-check", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
			List<String> command = new ArrayList<>();
			command.add(java.toString());
			List<String> own = probeArguments(ideVmArguments);
			command.addAll(own);
			List<String> probed = vmArguments.stream().filter(this::isProbed).toList();
			List<String> notTried = vmArguments.stream().filter(argument -> !isProbed(argument)).toList();
			command.addAll(probed);
			String tried = (own.isEmpty() ? "these arguments alone" : "these arguments behind this IDE's own " + own) //$NON-NLS-1$ //$NON-NLS-2$
					+ (notTried.isEmpty() ? "" : ", leaving out " + notTried); //$NON-NLS-1$ //$NON-NLS-2$
			command.add("-version"); //$NON-NLS-1$
			Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile())
					.start();
			if (!process.waitFor(checkSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
				if (checkSeconds < CHECK_SECONDS) {
					return "Refused: a JVM with %s did not get through -version in the %d seconds left of this call, which says little about the arguments. The IDE keeps running; call again." //$NON-NLS-1$
							.formatted(tried, Integer.valueOf(checkSeconds));
				}
				return "Refused: a JVM with %s did not get through -version within %d seconds, so the IDE would not come back either: %s" //$NON-NLS-1$
						.formatted(tried, Integer.valueOf(CHECK_SECONDS), probed);
			}
			String text = new String(Files.readAllBytes(output), Charset.defaultCharset()).trim();
			if (process.exitValue() != 0) {
				return "Refused: the JVM does not start with %s, so the relaunch would end in the launcher's error dialog with no server left. %s said: %s" //$NON-NLS-1$
						.formatted(tried, java, text);
			}
			var warning = JFR_WARNING.matcher(text);
			if (warning.find()) {
				return "Refused: the JVM would start but not record what was asked for: " + warning.group().trim(); //$NON-NLS-1$
			}
			String scope = own.isEmpty()
					? "these arguments alone, since eclipse.vmargs holds no JVM arguments of this IDE to try them with, so a conflict with those of eclipse.ini would not show" //$NON-NLS-1$
					: "these arguments behind this IDE's own from eclipse.vmargs, %s, which leaves out its launcher jar, class path, recording, agents and log files and can hold vmArgs of an earlier relaunch" //$NON-NLS-1$
							.formatted(own);
			jvmCheck = "a JVM from %s, this IDE's JVM, got through -version with %s. That shows the JVM starts with them, not that Eclipse comes up." //$NON-NLS-1$
					.formatted(java, scope);
			if (!notTried.isEmpty()) {
				jvmCheck += " NOT TRIED: %s, since an agent can wait for a debugger or listen on a port and an option that writes a file replaces it. A mistake in them shows only in the relaunch, as the launcher's error dialog, and an agent with suspend=y makes the new IDE wait for its debugger." //$NON-NLS-1$
						.formatted(notTried);
			}
			return null;
		} catch (IOException e) {
			return "Refused: these arguments could not be tried on a JVM, and what stops the trial, such as a directory that cannot be created, can stop the relaunch and lose the IDE: " //$NON-NLS-1$
					+ e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "Refused: interrupted while checking the JVM arguments."; //$NON-NLS-1$
		} finally {
			delete(output);
			delete(file);
		}
	}

	/**
	 * What a probe can repeat of a value of {@code eclipse.vmargs}: without the launcher jar
	 * and all behind it, which would run Eclipse, a class path, a recording, an agent or a log file.
	 */
	public static List<String> probeArguments(String ideVmArguments) {
		List<String> kept = new ArrayList<>();
		if (ideVmArguments == null) {
			return kept;
		}
		String[] lines = ideVmArguments.split("\\R"); //$NON-NLS-1$
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int equals = line.indexOf('=');
			String name = equals < 0 ? line : line.substring(0, equals);
			if (REPLACES_THE_PROGRAM.contains(name)) {
				break;
			}
			if (CLASS_PATH.contains(name)) {
				i += equals < 0 ? 1 : 0;
			} else if (!line.isBlank() && !hasSideEffects(line) && NOT_FOR_A_PROBE.stream().noneMatch(line::startsWith)) {
				kept.add(line);
			}
		}
		return kept;
	}

	/** Whether the probe repeats this argument: the requested recording's file is deleted afterwards, one from vmArgs would stay. */
	private boolean isProbed(String argument) {
		boolean ownRecording = file != null && argument.equals(vmArguments.get(0));
		return !hasSideEffects(argument) && (ownRecording || !argument.startsWith(START_FLIGHT_RECORDING));
	}

	/** Whether a probe with this argument would act beyond itself, whoever asked for the argument. */
	private static boolean hasSideEffects(String argument) {
		return AGENTS.stream().anyMatch(argument::startsWith) || WRITES_A_FILE.stream().anyMatch(argument::startsWith)
				|| XLOG_TO_FILE.matcher(argument).matches();
	}

	/** Adds what was requested to the answer of the restart. */
	public void addTo(JsonObject result) {
		result.put("vmArguments", json(vmArguments)) //$NON-NLS-1$
				.put("jvmCheck", jvmCheck) //$NON-NLS-1$
				.put("flightRecording", recording) //$NON-NLS-1$
				.put("vmArgumentsScope", //$NON-NLS-1$
						"THIS RELAUNCH ONLY, and added to the VM arguments of eclipse.ini and of the original command line rather than replacing them. A later eclipse_restart or File > Restart rebuilds its command line from the original one and comes back without them. The exception is a restart that repeats the last command line, exit code 23: IWorkbench.restart() without an argument, which p2 uses under RESTART_POLICY_FORCE. That JVM gets them again, and a recording then overwrites its file. Starting the IDE by hand always comes up without them."); //$NON-NLS-1$
	}

	/** The options of the object form, those the string form stands for, or {@code null} for no recording. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> recordingOptions(Object requested) {
		if (requested == null || "off".equals(requested)) { //$NON-NLS-1$
			return null;
		}
		if ("default".equals(requested) || DEFAULT_SETTINGS.equals(requested)) { //$NON-NLS-1$
			return Map.of("settings", requested); //$NON-NLS-1$
		}
		if (requested instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		throw new IllegalArgumentException(
				"'flightRecording' has to be an object, or one of the strings 'off', 'default' and 'profile', got '%s'." //$NON-NLS-1$
						.formatted(requested));
	}

	private static int wholeNumber(String name, Object value, int fallback, int min, int max) {
		if (value == null) {
			return fallback;
		}
		try {
			int number = new BigDecimal(String.valueOf(value).trim()).intValueExact();
			if (number >= min && number <= max) {
				return number;
			}
		} catch (ArithmeticException | NumberFormatException e) {
			// refused below like any other value out of range
		}
		throw new IllegalArgumentException("'%s' has to be a whole number from %d to %d, got '%s'." //$NON-NLS-1$
				.formatted(name, Integer.valueOf(min), Integer.valueOf(max), value));
	}

	private static String note(Path file, int seconds) {
		String when = seconds > 0
				? "written %d seconds after it started, WITHOUT ending the IDE, and the recording ends there; an IDE that exits earlier writes it at exit" //$NON-NLS-1$
						.formatted(Integer.valueOf(seconds))
				: "written when the IDE EXITS, and an IDE that is killed leaves it empty; 'jcmd <pid> JFR.dump name=%s filename=...' copies it out earlier" //$NON-NLS-1$
						.formatted(LaunchRecording.NAME);
		return "REQUESTED, NOT OBSERVED: the new JVM starts recording during its own initialization, before any Eclipse code runs, into %s. The file exists from that moment but stays EMPTY until it is %s. No file at all means the relaunch did not take these arguments. After reconnecting, read it with eclipse_stop_flight_recording passing file; nothing is stopped by that." //$NON-NLS-1$
				.formatted(file, when);
	}

	private static Path recordingFile(String value) {
		// FileLocations.pathOf strips, which would record into another path than the one named
		if (value != null && !value.equals(value.strip())) {
			throw new IllegalArgumentException("'file' starts or ends with whitespace: '%s'.".formatted(value)); //$NON-NLS-1$
		}
		Path path = value == null ? LaunchRecording.fileFor("startup").toAbsolutePath() : RestartTool.pathOf(value); //$NON-NLS-1$
		plain("file", path == null ? value : path.toString()); //$NON-NLS-1$
		if (path == null || !path.isAbsolute()) {
			throw new IllegalArgumentException("'file' has to be an absolute path, got '%s'.".formatted(value)); //$NON-NLS-1$
		}
		if (isThere(path)) {
			// the JVM would overwrite it, and until then an old recording passes for the new one
			throw new IllegalArgumentException(alreadyThere(path));
		}
		return path;
	}

	private static boolean isThere(Path path) {
		return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
	}

	private static String alreadyThere(Path path) {
		return "'%s' already exists. Name another file or delete it first.".formatted(path); //$NON-NLS-1$
	}

	/** A value inside the JVM's comma separated option, where those characters mean something else. */
	private static String plain(String name, String value) {
		if (value.matches("(?s).*[,\"\\r\\n].*")) { //$NON-NLS-1$
			throw new IllegalArgumentException(
					"'%s' cannot contain a comma, a double quote or a line break: '%s'.".formatted(name, value)); //$NON-NLS-1$
		}
		return value;
	}

	private static List<String> eventSettings(Object value) {
		List<String> settings = strings("eventSettings", value); //$NON-NLS-1$
		for (String setting : settings) {
			if (!EVENT_SETTING.matcher(setting).matches()) {
				throw new IllegalArgumentException(
						"'%s' is not an event setting; the form is jdk.ExecutionSample#period=1ms.".formatted(setting)); //$NON-NLS-1$
			}
		}
		return settings;
	}

	private static List<String> vmArgs(Object value) {
		List<String> arguments = strings("vmArgs", value); //$NON-NLS-1$
		for (String argument : arguments) {
			if (!argument.startsWith("-") || argument.matches("(?s).*[\\r\\n].*")) { //$NON-NLS-1$ //$NON-NLS-2$
				throw new IllegalArgumentException(
						"'%s' is not one JVM argument: each item starts with a dash, has no line break, and a value goes with its option, as in --add-opens=a/b=c." //$NON-NLS-1$
								.formatted(argument));
			}
			if (VMARGS.equals(argument.toLowerCase(Locale.ROOT))) {
				throw new IllegalArgumentException("'vmArgs' holds JVM arguments and adds -vmargs itself."); //$NON-NLS-1$
			}
			int equals = argument.indexOf('=');
			String name = equals < 0 ? argument : argument.substring(0, equals);
			if (ENDS_THE_JVM.contains(argument) || ENDS_THE_JVM.contains(name)) {
				throw new IllegalArgumentException(
						"'%s' makes the JVM exit before it runs anything, so the relaunch would never start Eclipse and no server would come back. The trial on a throwaway JVM cannot see that, because it exits normally there too." //$NON-NLS-1$
								.formatted(argument));
			}
			if (CLASS_PATH.contains(name)) {
				throw new IllegalArgumentException(
						"'%s' replaces the class path that holds the launcher, so the JVM would not find Eclipse and no server would come back. The trial on a throwaway JVM cannot see that." //$NON-NLS-1$
								.formatted(argument));
			}
			if (REPLACES_THE_PROGRAM.contains(name)) {
				throw new IllegalArgumentException(
						"'%s' names what the JVM runs, and the relaunch has to run Eclipse, so no server would come back." //$NON-NLS-1$
								.formatted(argument));
			}
		}
		return arguments;
	}

	private static List<String> strings(String name, Object value) {
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list)) {
			throw new IllegalArgumentException("'%s' has to be an array of strings.".formatted(name)); //$NON-NLS-1$
		}
		List<String> strings = new ArrayList<>();
		for (Object item : list) {
			strings.add(String.valueOf(item));
		}
		return strings;
	}

	private static JsonArray json(List<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	private static void delete(Path path) {
		if (path != null) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException e) {
				// a leftover temporary file costs nothing, and a recording overwrites its own
			}
		}
	}
}
