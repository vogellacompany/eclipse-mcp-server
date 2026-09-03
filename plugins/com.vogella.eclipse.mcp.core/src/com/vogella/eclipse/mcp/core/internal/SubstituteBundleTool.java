package com.vogella.eclipse.mcp.core.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;

import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.core.FrameworkChanges;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Runs the IDE against a workspace project's bundle instead of the installed
 * one.
 */
public final class SubstituteBundleTool implements IMcpTool {

	/** Where the packed jars go, next to the installation rather than in it. */
	private static final String JARS = "mcp-substituted"; //$NON-NLS-1$

	/** What was replaced and with what, so a restore needs no knowledge from the caller. */
	private static final String RECORD = "mcp-substituted/substitutions.txt"; //$NON-NLS-1$

	private static final String BUNDLES_INFO = "org.eclipse.equinox.simpleconfigurator/bundles.info"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_substitute_bundle"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Makes the IDE run a workspace project's bundle in place of the installed one at the next restart, by packing the project and pointing this installation's bundles.info line at the packed jar. CHANGES THE INSTALLATION, not the workspace, and runs as a dry run unless dryRun is set to false; the dry run shows the exact line before and after. THIS IS THE ONLY WAY IN FOR MOST OF THE SDK: a hot install through eclipse_install_bundle is invisible to anything that reads the registry once at startup, the theme engine among them, and the dropins directory cannot replace a bundle that belongs to an installed feature, because a feature demands its bundles at an exact version, which covers nearly everything in an SDK. THE RISK IS REAL: a bundles.info that names a jar which is not there leaves the bundles that need it unresolvable, and if that bundle is one the framework itself needs, the IDE does not start and no tool here can reach it; short of that the IDE still runs and action repair points such a line back at the installed jar. NEVER DELETE A PACKED JAR BY HAND: action cleanup does it and re-reads bundles.info first, because this file is rewritten at every start and by other sessions, so a jar that looks unreferenced can be the one the next start loads. The original line is recorded, so action restore needs nothing from the caller, and action status reports what is substituted right now, checked against bundles.info rather than believed from the record, including a substitution another session made and, under referencingSubstitutedJars, every line that points at a packed jar even when nothing here recorded it, which is what stops somebody debugging an IDE that is not running what its plugins directory holds. THE VERSION FIELD IS WHAT MAKES IT TAKE EFFECT: simpleconfigurator matches a bundle on symbolic name plus version, so a line that keeps the installed version and only changes the path is read as a bundle already installed and the path is never looked at, which is a substitution that does nothing while every check on the file says it is in force. The version of the substituted jar is therefore written into the line. Ask action status for the 'running' field, which reports what the framework has actually loaded, its version, its state and the jar it came from, rather than what the file says, and believe that one. The line may also be rewritten at a restart, so everything here matches on the bundle name, the one stable field."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "action":  {"type":"string","enum":["substitute","restore","status","cleanup","repair"],"default":"status","description":"'substitute' packs the project and points bundles.info at it, 'restore' puts the recorded original lines back, 'status' only reports, 'cleanup' deletes the packed jars that nothing references any more, 'repair' points a line whose jar is missing back at the installed one."},
				    "jar":     {"type":"string","description":"Absolute path of a jar that is already built, used instead of 'project'. Its Bundle-SymbolicName and Bundle-Version are read from its own manifest rather than guessed from the file name, which for a Maven build matches neither. The jar is copied, so rebuilding it afterwards does not silently change what this IDE runs."},
				    "project": {"type":"string","description":"Plug-in project to pack, for substitute. Its output folder and the bin.includes of build.properties are what goes into the jar. CHECK WHICH CLONE IT IS: the answer reports packedFrom, because a workspace project can point at one clone of a repository while the change being measured lives in another, and then this packs a tree without it."},
				    "dryRun":  {"type":"boolean","default":true,"description":"Report the line that would change, and change nothing."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String action = args.getString("action", "status"); //$NON-NLS-1$ //$NON-NLS-2$
		Path configuration = configurationDirectory();
		if (configuration == null) {
			return McpToolResult.error(
					"This IDE has no writable configuration directory, so bundles.info cannot be read or changed."); //$NON-NLS-1$
		}
		Path bundlesInfo = configuration.resolve(BUNDLES_INFO);
		if (!Files.isRegularFile(bundlesInfo)) {
			// asking what is substituted is answerable even here, and the answer is
			// nothing; only changing something needs the file to exist
			if ("status".equals(action)) { //$NON-NLS-1$
				return McpToolResult.of(new JsonObject().put("substituted", new JsonArray()) //$NON-NLS-1$
						.put("count", Integer.valueOf(0)) //$NON-NLS-1$
						.put("note", //$NON-NLS-1$
								"This installation has no bundles.info at %s, so it is not managed by simpleconfigurator and nothing can be substituted in it." //$NON-NLS-1$
										.formatted(bundlesInfo))
						.toString());
			}
			return McpToolResult.error("No bundles.info at %s, so this installation is not managed by simpleconfigurator." //$NON-NLS-1$
					.formatted(bundlesInfo));
		}
		try {
			McpToolResult result = switch (action) {
			case "status" -> McpToolResult.of(status(configuration, bundlesInfo).toString()); //$NON-NLS-1$
			case "restore" -> restore(configuration, bundlesInfo, args.getBoolean("dryRun", true)); //$NON-NLS-1$ //$NON-NLS-2$
			case "cleanup" -> cleanup(configuration, bundlesInfo, args.getBoolean("dryRun", true)); //$NON-NLS-1$ //$NON-NLS-2$
			case "repair" -> repair(configuration, bundlesInfo, args.getBoolean("dryRun", true)); //$NON-NLS-1$ //$NON-NLS-2$
			case "substitute" -> substitute(configuration, bundlesInfo, args, monitor);
			default -> McpToolResult.error("'action' is 'substitute', 'restore', 'status', 'cleanup' or 'repair'."); //$NON-NLS-1$
			};
			// a changed bundles.info line is loaded under caches written for the old
			// jar, so the next restart discards them
			if (!result.isError() && !args.getBoolean("dryRun", true) //$NON-NLS-1$
					&& List.of("restore", "repair", "substitute").contains(action)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				FrameworkChanges.markLiveChange("eclipse_substitute_bundle " + action); //$NON-NLS-1$
			}
			return result;
		} catch (IOException e) {
			return McpToolResult.error("Could not work with the installation: " + e); //$NON-NLS-1$
		}
	}

	/**
	 * What is substituted right now, checked against the file rather than believed
	 * from the record.
	 * <p>
	 * The two can disagree, and that disagreement is what made a restore fail
	 * silently once: simpleconfigurator rewrites bundles.info at every start, and
	 * it normalises what it writes, taking the version from the jar's own manifest
	 * and making the path relative to the installation. The recorded line is then
	 * no longer in the file even though the substitution is still in force.
	 */
	private static JsonObject status(Path configuration, Path bundlesInfo) throws IOException {
		List<String[]> records = records(configuration);
		List<String> lines = Files.isRegularFile(bundlesInfo)
				? Files.readAllLines(bundlesInfo, StandardCharsets.UTF_8)
				: List.of();
		JsonArray active = new JsonArray();
		int stillSubstituted = 0;
		for (String[] record : records) {
			String current = lineFor(lines, record[0]);
			String state = current == null ? "missing" //$NON-NLS-1$
					: current.equals(record[1]) ? "restored" : "substituted"; //$NON-NLS-1$ //$NON-NLS-2$
			if ("substituted".equals(state)) { //$NON-NLS-1$
				stillSubstituted++;
			}
			JsonObject entry = new JsonObject().put("bundle", record[0]) //$NON-NLS-1$
					.put("state", state) //$NON-NLS-1$
					.put("originalLine", record[1]) //$NON-NLS-1$
					.put("recordedLine", record[2]) //$NON-NLS-1$
					.put("currentLine", current) //$NON-NLS-1$
					.put("running", running(record[0])); //$NON-NLS-1$
			if (current != null && !current.equals(record[2]) && "substituted".equals(state)) { //$NON-NLS-1$
				entry.put("rewritten", //$NON-NLS-1$
						"The line differs from what was written: simpleconfigurator rewrote it at a restart, normalising the version from the jar's manifest and the path relative to the installation. It is still the substituted jar."); //$NON-NLS-1$
			}
			active.add(entry);
		}
		// every line that points into the jar directory, whatever the record says:
		// a line left behind by an older substitution has no record of its own, and
		// it was invisible here while it decided what the IDE would load
		JsonArray referencing = new JsonArray();
		for (String line : lines) {
			if (!line.contains(JARS) || line.startsWith("#")) { //$NON-NLS-1$
				continue;
			}
			String bundle = line.substring(0, Math.max(0, line.indexOf(',')));
			boolean recorded = false;
			for (String[] record : records) {
				recorded |= record[0].equals(bundle);
			}
			Path jar = jarOf(configuration, line);
			referencing.add(new JsonObject().put("bundle", bundle) //$NON-NLS-1$
					.put("line", line) //$NON-NLS-1$
					.put("recorded", Boolean.valueOf(recorded)) //$NON-NLS-1$
					.put("jarExists", Boolean.valueOf(jar != null && Files.isRegularFile(jar))) //$NON-NLS-1$
					// this is the case where the question matters most: nothing here
					// recorded the substitution, so the file is the only other evidence
					// and it is exactly the evidence that can be stale
					.put("running", running(bundle))); //$NON-NLS-1$
		}
		return new JsonObject().put("substituted", active) //$NON-NLS-1$
				.put("count", Integer.valueOf(active.size())) //$NON-NLS-1$
				.put("stillInForce", Integer.valueOf(stillSubstituted)) //$NON-NLS-1$
				.put("referencingSubstitutedJars", referencing) //$NON-NLS-1$
				.put("note", note(stillSubstituted, referencing)); //$NON-NLS-1$
	}

	private static String note(int stillSubstituted, JsonArray referencing) {
		if (referencing.size() > stillSubstituted) {
			return "READ referencingSubstitutedJars, NOT the count: bundles.info points at a packed jar for a bundle this record knows nothing about, which is what an older substitution leaves behind. Whoever wrote that line, the IDE loads it at the next start, and deleting the jar without changing the line is what leaves an IDE that cannot resolve the bundle. action repair puts such a line back."; //$NON-NLS-1$
		}
		if (stillSubstituted == 0) {
			return "No substitution is in force; this IDE runs what its plugins directory holds. A record with state 'restored' is history and can be forgotten, and action cleanup deletes the jars that go with it."; //$NON-NLS-1$
		}
		return "These bundles are NOT the installed ones, checked against bundles.info rather than taken from the record. The record survives restarts and sessions, so this is also what another session's substitution looks like. action restore puts them back, and it takes a restart either way."; //$NON-NLS-1$
	}

	/**
	 * The jar a bundles.info line names, or null when it names none.
	 * <p>
	 * A path is either a file URI or relative to the installation, which is the
	 * directory above the configuration area. Public for the test bundle, which
	 * cannot see a package-private method across bundles.
	 */
	public static Path jarOf(Path configuration, String line) {
		String[] fields = line.split(","); //$NON-NLS-1$
		if (fields.length < 3) {
			return null;
		}
		String path = fields[2];
		try {
			if (path.startsWith("file:")) { //$NON-NLS-1$
				return FileLocations.pathOf(path);
			}
			Path installation = configuration.getParent();
			return installation == null ? null : installation.resolve(path);
		} catch (IllegalArgumentException | FileSystemNotFoundException e) {
			return null;
		}
	}

	/**
	 * What the framework actually loaded for this bundle, which is a different
	 * question from what the file says.
	 * <p>
	 * A line can name the substituted jar while the running IDE holds the original
	 * open, and then every check against bundles.info agrees that the substitution
	 * is in force and every measurement taken is of the unchanged code. Only the
	 * live bundle's own location settles it.
	 */
	public static JsonObject running(String symbolicName) {
		// Platform.getBundle rather than our own BundleContext: this bundle declares
		// no activator and no lazy activation, so it never leaves RESOLVED and its
		// context is null for the life of the IDE. The old code read that as "the
		// server is still starting" and told every caller to ask again in a moment,
		// which never came, so the one field meant to be trusted over the file was
		// permanently unavailable
		org.osgi.framework.Bundle bundle = org.eclipse.core.runtime.Platform.getBundle(symbolicName);
		if (bundle == null) {
			return new JsonObject().put("known", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", //$NON-NLS-1$
							"The framework has no bundle called '%s' at all, so nothing of that name is running, substituted or otherwise." //$NON-NLS-1$
									.formatted(symbolicName));
		}
		String location = bundle.getLocation();
		boolean substituted = location != null && location.contains(JARS);
		String state = stateOf(bundle.getState());
		return new JsonObject().put("known", Boolean.TRUE) //$NON-NLS-1$
				.put("version", String.valueOf(bundle.getVersion())) //$NON-NLS-1$
				.put("state", state) //$NON-NLS-1$
				.put("location", location) //$NON-NLS-1$
				.put("isSubstitutedJar", Boolean.valueOf(substituted)) //$NON-NLS-1$
				.put("note", substituted //$NON-NLS-1$
						? "The framework holds the substituted jar, so this IDE really is running it." //$NON-NLS-1$
						: "THE FRAMEWORK HOLDS THE ORIGINAL. Whatever bundles.info says, this IDE is running the installed bundle, so anything measured here describes unchanged code. A restart is needed, and if one has already happened the substitution did not take.") //$NON-NLS-1$
				.put("stateNote", "RESOLVED means the bundle is wired and will run when something needs it, which is the ordinary state for a lazily activated bundle and says nothing against the substitution; what matters here is the version and the location."); //$NON-NLS-1$
	}

	/** The framework's state constants, which are a bit field of powers of two. */
	private static String stateOf(int state) {
		return switch (state) {
		case org.osgi.framework.Bundle.UNINSTALLED -> "UNINSTALLED"; //$NON-NLS-1$
		case org.osgi.framework.Bundle.INSTALLED -> "INSTALLED"; //$NON-NLS-1$
		case org.osgi.framework.Bundle.RESOLVED -> "RESOLVED"; //$NON-NLS-1$
		case org.osgi.framework.Bundle.STARTING -> "STARTING"; //$NON-NLS-1$
		case org.osgi.framework.Bundle.STOPPING -> "STOPPING"; //$NON-NLS-1$
		case org.osgi.framework.Bundle.ACTIVE -> "ACTIVE"; //$NON-NLS-1$
		default -> String.valueOf(state);
		};
	}

	/** The line for a bundle, found by its name, which is the one stable field. */
	private static String lineFor(List<String> lines, String bundle) {
		for (String line : lines) {
			if (line.startsWith(bundle + ",")) { //$NON-NLS-1$
				return line;
			}
		}
		return null;
	}

	private static McpToolResult restore(Path configuration, Path bundlesInfo, boolean dryRun) throws IOException {
		List<String[]> records = records(configuration);
		if (records.isEmpty()) {
			return McpToolResult.of(new JsonObject().put("restored", Integer.valueOf(0)) //$NON-NLS-1$
					.put("note", "Nothing was substituted, so there is nothing to put back.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		List<String> lines = new ArrayList<>(Files.readAllLines(bundlesInfo, StandardCharsets.UTF_8));
		JsonArray done = new JsonArray();
		JsonArray missed = new JsonArray();
		for (String[] record : records) {
			int index = -1;
			for (int i = 0; i < lines.size(); i++) {
				// by bundle name, not by the whole line: simpleconfigurator rewrites
				// the version and the path form at every start, so the line written
				// here is not the line found later
				if (lines.get(i).startsWith(record[0] + ",")) { //$NON-NLS-1$
					index = i;
					break;
				}
			}
			if (index < 0) {
				missed.add(new JsonObject().put("bundle", record[0]) //$NON-NLS-1$
						.put("reason", "bundles.info has no line for it at all.")); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			String current = lines.get(index);
			if (current.equals(record[1])) {
				missed.add(new JsonObject().put("bundle", record[0]) //$NON-NLS-1$
						.put("reason", "It already holds the original line; nothing to undo.")); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			if (!dryRun) {
				lines.set(index, record[1]);
			}
			// a substitution layered on another records the jar it replaced, which is
			// itself a packed one, so restoring lands on that rather than on the
			// shipped bundle. That is right, and invisible unless it is said
			boolean ontoSubstituted = record[1].contains(JARS);
			done.add(new JsonObject().put("bundle", record[0]) //$NON-NLS-1$
					.put("was", current) //$NON-NLS-1$
					.put("line", record[1]) //$NON-NLS-1$
					.put("restoredToSubstitutedJar", Boolean.valueOf(ontoSubstituted)) //$NON-NLS-1$
					.put("restoredToNote", ontoSubstituted //$NON-NLS-1$
							? "This puts back an EARLIER SUBSTITUTED jar, not the shipped one: a substitution was layered on another, and this record's original is that other packed jar. Restore again to go one layer further back, and check action status." //$NON-NLS-1$
							: null));
		}
		JsonArray stillReferenced = new JsonArray();
		if (!dryRun && done.size() > 0) {
			Files.write(bundlesInfo, lines, StandardCharsets.UTF_8);
			Files.deleteIfExists(configuration.resolve(RECORD));
			// read the file back rather than trust what was just written: another
			// session writes this file too, and a restore that looks clean while a
			// line still points at a packed jar is how a deleted jar takes the IDE
			// down
			for (String line : Files.readAllLines(bundlesInfo, StandardCharsets.UTF_8)) {
				if (line.contains(JARS) && !line.startsWith("#")) { //$NON-NLS-1$
					stillReferenced.add(line);
				}
			}
		}
		JsonObject result = new JsonObject().put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("restored", done) //$NON-NLS-1$
				.put("stillReferenced", stillReferenced) //$NON-NLS-1$
				.put("restoredCount", Integer.valueOf(done.size())); //$NON-NLS-1$
		if (missed.size() > 0) {
			result.put("notRestored", missed); //$NON-NLS-1$
		}
		if (done.size() == 0) {
			// saying "done" over an empty list is worse than an error: a caller reads
			// the note, stops looking, and keeps an IDE that is not what it thinks
			return McpToolResult.error(result
					.put("note", //$NON-NLS-1$
							"NOTHING WAS PUT BACK. Every recorded substitution is either already restored or has no line in bundles.info, so the file was not written. Check action status and the notRestored entries before assuming this IDE runs its installed bundles.") //$NON-NLS-1$
					.toString());
		}
		return McpToolResult.of(result.put("restartRequired", Boolean.TRUE) //$NON-NLS-1$
				.put("note", dryRun ? "Nothing was changed. Pass dryRun false to put these lines back." //$NON-NLS-1$
						: stillReferenced.size() > 0
								? "The recorded lines are back, BUT bundles.info still points at a packed jar for something else, listed under stillReferenced. Do not delete anything under mcp-substituted until that is gone; action repair puts such a line back." //$NON-NLS-1$
								: "The installed bundles are back in bundles.info; restart with eclipse_restart for the IDE to run them. Delete the packed jars with action cleanup, which checks the file again first, rather than by hand: this file is written by simpleconfigurator and by other sessions, so what is unreferenced now may not be in a minute.") //$NON-NLS-1$
				.toString());
	}

	/**
	 * Deletes the packed jars nothing points at any more.
	 * <p>
	 * The check happens here rather than in the caller's head: the file is written
	 * by simpleconfigurator at every start and by any other session, so a jar that
	 * was unreferenced a minute ago may be the one the IDE is about to load, and
	 * deleting it leaves a bundle that cannot resolve.
	 */
	private static McpToolResult cleanup(Path configuration, Path bundlesInfo, boolean dryRun) throws IOException {
		Path directory = configuration.resolve(JARS);
		if (!Files.isDirectory(directory)) {
			return McpToolResult.of(new JsonObject().put("deleted", new JsonArray()) //$NON-NLS-1$
					.put("note", "There is no %s directory, so no packed jar was ever left here.".formatted(directory)) //$NON-NLS-1$ //$NON-NLS-2$
					.toString());
		}
		List<String> lines = Files.readAllLines(bundlesInfo, StandardCharsets.UTF_8);
		JsonArray deleted = new JsonArray();
		JsonArray kept = new JsonArray();
		try (var jars = Files.list(directory)) {
			for (Path jar : jars.filter(Files::isRegularFile).toList()) {
				if (jar.getFileName().toString().equals("substitutions.txt")) { //$NON-NLS-1$
					continue;
				}
				String referencedBy = null;
				for (String line : lines) {
					Path named = jarOf(configuration, line);
					if (named != null && named.toAbsolutePath().normalize().equals(jar.toAbsolutePath().normalize())) {
						referencedBy = line;
						break;
					}
				}
				if (referencedBy != null) {
					kept.add(new JsonObject().put("jar", jar.toString()) //$NON-NLS-1$
							.put("referencedBy", referencedBy)); //$NON-NLS-1$
					continue;
				}
				if (!dryRun) {
					Files.delete(jar);
				}
				deleted.add(jar.toString());
			}
		}
		return McpToolResult.of(new JsonObject().put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("deleted", deleted) //$NON-NLS-1$
				.put("kept", kept) //$NON-NLS-1$
				.put("note", kept.size() > 0 //$NON-NLS-1$
						? "The jars under 'kept' are named by a line in bundles.info as it reads right now and were left alone; deleting one is what leaves an IDE that cannot resolve that bundle. Put its line back with action restore or action repair first." //$NON-NLS-1$
						: dryRun ? "Nothing was deleted. Pass dryRun false to remove these; the check is made again at that point." //$NON-NLS-1$
								: "Nothing in bundles.info pointed at these, so they were deleted.") //$NON-NLS-1$
				.toString());
	}

	/**
	 * Points a line whose jar is gone back at an installed one.
	 * <p>
	 * An IDE in this state may still be running, because one unresolvable bundle
	 * does not stop the framework, and then the repair can be made from inside it.
	 */
	private static McpToolResult repair(Path configuration, Path bundlesInfo, boolean dryRun) throws IOException {
		List<String[]> records = records(configuration);
		List<String> lines = new ArrayList<>(Files.readAllLines(bundlesInfo, StandardCharsets.UTF_8));
		Path installation = configuration.getParent();
		JsonArray repaired = new JsonArray();
		JsonArray broken = new JsonArray();
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			String[] fields = line.split(","); //$NON-NLS-1$
			if (fields.length < 5 || line.startsWith("#")) { //$NON-NLS-1$
				continue;
			}
			Path jar = jarOf(configuration, line);
			if (jar == null || Files.exists(jar)) {
				continue;
			}
			String replacement = null;
			for (String[] record : records) {
				if (record[0].equals(fields[0])) {
					replacement = record[1];
				}
			}
			if (replacement == null && installation != null) {
				// no record for it, so fall back on the convention: the installed jar
				// carries the bundle's own name and version
				Path installed = installation.resolve("plugins/%s_%s.jar".formatted(fields[0], fields[1])); //$NON-NLS-1$
				if (Files.isRegularFile(installed)) {
					replacement = "%s,%s,plugins/%s_%s.jar,%s,%s".formatted(fields[0], fields[1], fields[0], fields[1], //$NON-NLS-1$
							fields[3], fields[4]);
				}
			}
			if (replacement == null) {
				broken.add(new JsonObject().put("bundle", fields[0]) //$NON-NLS-1$
						.put("line", line) //$NON-NLS-1$
						.put("missing", String.valueOf(jar)) //$NON-NLS-1$
						.put("reason", //$NON-NLS-1$
								"No record of an original line, and no plugins/%s_%s.jar to fall back on. This one needs a human." //$NON-NLS-1$
										.formatted(fields[0], fields[1])));
				continue;
			}
			if (!dryRun) {
				lines.set(i, replacement);
			}
			repaired.add(new JsonObject().put("bundle", fields[0]) //$NON-NLS-1$
					.put("was", line) //$NON-NLS-1$
					.put("missing", String.valueOf(jar)) //$NON-NLS-1$
					.put("line", replacement)); //$NON-NLS-1$
		}
		if (!dryRun && repaired.size() > 0) {
			Files.write(bundlesInfo, lines, StandardCharsets.UTF_8);
		}
		JsonObject result = new JsonObject().put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("repaired", repaired) //$NON-NLS-1$
				.put("repairedCount", Integer.valueOf(repaired.size())); //$NON-NLS-1$
		if (broken.size() > 0) {
			result.put("notRepaired", broken); //$NON-NLS-1$
		}
		return McpToolResult.of(result
				.put("restartRequired", Boolean.valueOf(repaired.size() > 0)) //$NON-NLS-1$
				.put("note", repaired.size() == 0 //$NON-NLS-1$
						? "Every line in bundles.info names a file that exists, so there is nothing to repair." //$NON-NLS-1$
						: dryRun ? "Nothing was changed. Pass dryRun false to write these lines back." //$NON-NLS-1$
								: "The lines are back on files that exist; restart for the IDE to load them. Until then the bundles they name stay unresolved, which shows up as NoClassDefFoundError from anything that needed them.") //$NON-NLS-1$
				.toString());
	}

	private static McpToolResult substitute(Path configuration, Path bundlesInfo, ToolArguments args,
			IProgressMonitor monitor) throws IOException {
		if (args.getString("jar") != null) { //$NON-NLS-1$
			return substituteJar(configuration, bundlesInfo, args);
		}
		String projectName = args.getString("project"); //$NON-NLS-1$
		if (projectName == null) {
			return McpToolResult.error("Name what to substitute with: 'project' to pack a workspace project, or 'jar' for one that is already built."); //$NON-NLS-1$
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.isAccessible() || project.getLocation() == null) {
			return McpToolResult.error("No open project named '%s' in this workspace.".formatted(projectName)); //$NON-NLS-1$
		}
		Path projectPath = project.getLocation().toFile().toPath();
		Path manifest = projectPath.resolve("META-INF/MANIFEST.MF"); //$NON-NLS-1$
		if (!Files.isRegularFile(manifest)) {
			return McpToolResult.error("'%s' has no META-INF/MANIFEST.MF, so it is not a plug-in project." //$NON-NLS-1$
					.formatted(projectName));
		}
		String symbolicName = symbolicName(manifest);
		if (symbolicName == null) {
			return McpToolResult.error("Could not read Bundle-SymbolicName from %s.".formatted(manifest)); //$NON-NLS-1$
		}
		String packedVersion = header(manifest, "Bundle-Version"); //$NON-NLS-1$
		if (packedVersion == null) {
			return McpToolResult.error("Could not read Bundle-Version from %s.".formatted(manifest)); //$NON-NLS-1$
		}

		List<String> lines = new ArrayList<>(Files.readAllLines(bundlesInfo, StandardCharsets.UTF_8));
		int index = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith(symbolicName + ",")) { //$NON-NLS-1$
				index = i;
				break;
			}
		}
		if (index < 0) {
			return McpToolResult.error(
					"bundles.info has no line for '%s', so this installation does not run that bundle and there is nothing to substitute." //$NON-NLS-1$
							.formatted(symbolicName));
		}
		String original = lines.get(index);
		String[] fields = original.split(","); //$NON-NLS-1$
		if (fields.length < 5) {
			return McpToolResult.error("The bundles.info line for '%s' is not in the expected five field form: %s" //$NON-NLS-1$
					.formatted(symbolicName, original));
		}
		Path jars = configuration.resolve(JARS);
		Path jar = jars.resolve("%s_%d.jar".formatted(symbolicName, Long.valueOf(System.currentTimeMillis()))); //$NON-NLS-1$
		// the packed bundle's own version, for the reason given in substituteJar
		String substituted = "%s,%s,%s,%s,%s".formatted(fields[0], packedVersion, jar.toUri(), fields[3], //$NON-NLS-1$
				fields[4]);

		JsonObject result = new JsonObject().put("bundle", symbolicName) //$NON-NLS-1$
				.put("project", projectName) //$NON-NLS-1$
				// the path, not just the name: a workspace can hold a project from one
				// clone while the patch being measured sits in another, and then this
				// packs the wrong tree and everything afterwards is measured wrongly
				.put("packedFrom", projectPath.toString()) //$NON-NLS-1$
				.put("originalLine", original) //$NON-NLS-1$
				.put("substitutedLine", substituted) //$NON-NLS-1$
				.put("jar", jar.toString()); //$NON-NLS-1$
		if (args.getBoolean("dryRun", true)) { //$NON-NLS-1$
			return McpToolResult.of(result.put("dryRun", Boolean.TRUE) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"Nothing was changed and no jar was packed. Pass dryRun false to carry it out; it needs a restart afterwards, and an unresolvable jar leaves an IDE that does not start.") //$NON-NLS-1$
					.toString());
		}

		Files.createDirectories(jars);
		Packed packed;
		try {
			packed = pack(projectPath, jar);
		} catch (IOException e) {
			Files.deleteIfExists(jar);
			return McpToolResult.error("Packing project '%s' from %s failed, and bundles.info was not changed: %s" //$NON-NLS-1$
					.formatted(projectName, projectPath, e.getMessage()));
		}
		lines.set(index, substituted);
		Files.write(bundlesInfo, lines, StandardCharsets.UTF_8);
		record(configuration, symbolicName, original, substituted);
		JsonArray unreadable = new JsonArray();
		packed.unreadable().forEach(unreadable::add);
		return McpToolResult.of(result.put("dryRun", Boolean.FALSE) //$NON-NLS-1$
				.put("entries", Integer.valueOf(packed.entries())) //$NON-NLS-1$
				.put("unreadable", unreadable) //$NON-NLS-1$
				.put("restartRequired", Boolean.TRUE) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						(packed.unreadable().isEmpty() ? "" //$NON-NLS-1$
								: "THE JAR IS INCOMPLETE: the directories under unreadable could not be read and nothing below them was packed. ") //$NON-NLS-1$
								+ "Restart with eclipse_restart for the IDE to run this jar. Until then it still runs the installed bundle. The original line is recorded, so action restore puts it back without you keeping it, and action status reports the substitution to any session that asks.") //$NON-NLS-1$
				.toString());
	}

	/**
	 * Substitutes a jar that somebody else already built.
	 * <p>
	 * The identity comes from the jar's own manifest, never from its file name: a
	 * Maven build is called artifact-version-SNAPSHOT.jar and matches neither the
	 * symbolic name nor the OSGi version, so guessing from the name would point
	 * bundles.info at the wrong line or at none.
	 */
	private static McpToolResult substituteJar(Path configuration, Path bundlesInfo, ToolArguments args)
			throws IOException {
		Path source = Path.of(args.getString("jar")); //$NON-NLS-1$
		if (!Files.isRegularFile(source)) {
			return McpToolResult.error("There is no file at '%s'.".formatted(source)); //$NON-NLS-1$
		}
		String symbolicName;
		String version;
		try (JarFile jarFile = new JarFile(source.toFile())) {
			Manifest manifest = jarFile.getManifest();
			if (manifest == null) {
				return McpToolResult.error("'%s' has no manifest, so it is not an OSGi bundle.".formatted(source)); //$NON-NLS-1$
			}
			String declared = manifest.getMainAttributes().getValue("Bundle-SymbolicName"); //$NON-NLS-1$
			version = manifest.getMainAttributes().getValue("Bundle-Version"); //$NON-NLS-1$
			symbolicName = declared == null ? null : declared.split(";")[0].strip(); //$NON-NLS-1$
		}
		if (symbolicName == null) {
			return McpToolResult.error("'%s' declares no Bundle-SymbolicName.".formatted(source)); //$NON-NLS-1$
		}

		List<String> lines = new ArrayList<>(Files.readAllLines(bundlesInfo, StandardCharsets.UTF_8));
		int index = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith(symbolicName + ",")) { //$NON-NLS-1$
				index = i;
				break;
			}
		}
		if (index < 0) {
			return McpToolResult.error(
					"bundles.info has no line for '%s', which is what %s declares, so this installation does not run that bundle." //$NON-NLS-1$
							.formatted(symbolicName, source.getFileName()));
		}
		String original = lines.get(index);
		String[] fields = original.split(","); //$NON-NLS-1$
		if (fields.length < 5) {
			return McpToolResult.error("The bundles.info line for '%s' is not in the expected five field form: %s" //$NON-NLS-1$
					.formatted(symbolicName, original));
		}
		Path jars = configuration.resolve(JARS);
		Path copy = jars.resolve("%s_%d.jar".formatted(symbolicName, Long.valueOf(System.currentTimeMillis()))); //$NON-NLS-1$
		// the version of the SUBSTITUTED jar, not the one the old line carried:
		// simpleconfigurator matches a bundle on symbolic name plus version, so a
		// line whose version is unchanged is taken for one already installed and its
		// path is never looked at. The substitution then does nothing at all while
		// every check on the file says it is in force
		String substituted = "%s,%s,%s,%s,%s".formatted(fields[0], version, copy.toUri(), fields[3], fields[4]); //$NON-NLS-1$

		JsonObject result = new JsonObject().put("bundle", symbolicName) //$NON-NLS-1$
				.put("jar", source.toString()) //$NON-NLS-1$
				.put("jarVersion", version) //$NON-NLS-1$
				.put("originalLine", original) //$NON-NLS-1$
				.put("substitutedLine", substituted); //$NON-NLS-1$
		if (args.getBoolean("dryRun", true)) { //$NON-NLS-1$
			return McpToolResult.of(result.put("dryRun", Boolean.TRUE) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"Nothing was changed and nothing was copied. Pass dryRun false to carry it out; it needs a restart afterwards, and an unresolvable jar leaves an IDE that does not start.") //$NON-NLS-1$
					.toString());
		}
		Files.createDirectories(jars);
		// copied rather than referenced in place: a later rebuild of the source jar
		// would otherwise change what this IDE runs without anybody saying so
		Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
		lines.set(index, substituted);
		Files.write(bundlesInfo, lines, StandardCharsets.UTF_8);
		record(configuration, symbolicName, original, substituted);
		return McpToolResult.of(result.put("dryRun", Boolean.FALSE) //$NON-NLS-1$
				.put("copiedTo", copy.toString()) //$NON-NLS-1$
				.put("restartRequired", Boolean.TRUE) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"Restart with eclipse_restart for the IDE to run this jar. The copy is what is referenced, so rebuilding the source jar changes nothing until this is called again. action restore puts the original line back.") //$NON-NLS-1$
				.toString());
	}

	/** What packing produced: the entries written and the directories it could not read. */
	public record Packed(int entries, List<String> unreadable) {
	}

	/**
	 * Packs the project the way PDE would: the compiled output, plus what
	 * build.properties lists under bin.includes, which is where the manifest, the
	 * plugin.xml and any css or icons live. Every root it walks has to lie inside
	 * the project; an entry that resolves anywhere else is refused by name.
	 */
	public static Packed pack(Path projectPath, Path jar) throws IOException {
		Path project = projectPath.toAbsolutePath().normalize();
		List<String> includes = binIncludes(project);
		List<String> unreadable = new ArrayList<>();
		int written = 0;
		try (OutputStream stream = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(stream)) {
			Path output = inside(project, outputFolder(project), "the output folder"); //$NON-NLS-1$
			if (Files.isDirectory(output)) {
				written += copyTree(output, output, out, unreadable);
			}
			for (String include : includes) {
				if (".".equals(include)) { //$NON-NLS-1$
					continue;
				}
				Path source = inside(project, project.resolve(include), "bin.includes entry '" + include + "'"); //$NON-NLS-1$ //$NON-NLS-2$
				if (Files.isDirectory(source)) {
					written += copyTree(project, source, out, unreadable);
				} else if (Files.isRegularFile(source)) {
					written += copyOne(project, source, out);
				}
			}
		}
		return new Packed(written, List.copyOf(unreadable));
	}

	/**
	 * Refuses a pack root outside the project. A stray value, a backslash left
	 * by a continuation line say, resolves to the drive root on Windows, and
	 * walking that from here is how a pack once failed on the recycle bin.
	 */
	private static Path inside(Path project, Path candidate, String what) throws IOException {
		Path resolved = candidate.toAbsolutePath().normalize();
		if (!resolved.startsWith(project)) {
			throw new IOException("%s resolves to %s, which is outside the project at %s".formatted(what, resolved, //$NON-NLS-1$
					project));
		}
		return resolved;
	}

	/**
	 * Walks one tree into the jar. A directory that cannot be read is recorded
	 * and skipped rather than ending the pack, so the answer can say what is
	 * missing instead of naming a path with no relation to the request.
	 */
	private static int copyTree(Path base, Path directory, JarOutputStream out, List<String> unreadable)
			throws IOException {
		int[] written = { 0 };
		Files.walkFileTree(directory, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (attrs.isRegularFile()) {
					written[0] += copyOne(base, file, out);
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException e) {
				unreadable.add(file.toString());
				return FileVisitResult.CONTINUE;
			}
		});
		return written[0];
	}

	private static int copyOne(Path base, Path file, JarOutputStream out) {
		String name = base.relativize(file).toString().replace('\\', '/');
		try {
			out.putNextEntry(new ZipEntry(name));
			Files.copy(file, out);
			out.closeEntry();
			return 1;
		} catch (IOException e) {
			// a duplicate entry is the usual cause, when bin.includes names something the
			// output folder already carries; the first one written wins
			return 0;
		}
	}

	/**
	 * Read through Properties, which is what PDE does: it joins a continuation
	 * line whatever its line ending. A hand parser that only knew LF left a
	 * bare backslash behind on a CRLF checkout, and lost every entry after it.
	 */
	private static List<String> binIncludes(Path projectPath) throws IOException {
		Path path = projectPath.resolve("build.properties"); //$NON-NLS-1$
		if (!Files.isRegularFile(path)) {
			return List.of("META-INF/", "plugin.xml"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(path)) {
			properties.load(in);
		}
		String value = properties.getProperty("bin.includes"); //$NON-NLS-1$
		if (value == null) {
			return List.of("META-INF/", "plugin.xml"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		List<String> values = new ArrayList<>();
		for (String entry : value.split(",")) { //$NON-NLS-1$
			if (!entry.isBlank()) {
				values.add(entry.strip());
			}
		}
		return values;
	}

	/**
	 * The compiled output, read from .classpath rather than through the Java model,
	 * so this bundle keeps no dependency on JDT for one path.
	 */
	private static Path outputFolder(Path projectPath) {
		Path classpath = projectPath.resolve(".classpath"); //$NON-NLS-1$
		if (Files.isRegularFile(classpath)) {
			try {
				Matcher matcher = Pattern
						.compile("kind=\"output\"\\s+path=\"([^\"]+)\"") //$NON-NLS-1$
						.matcher(Files.readString(classpath));
				if (matcher.find()) {
					return projectPath.resolve(matcher.group(1));
				}
			} catch (IOException | RuntimeException e) {
				// an unreadable .classpath is no reason to give up on the conventional name
			}
		}
		return projectPath.resolve("bin"); //$NON-NLS-1$
	}

	private static String symbolicName(Path manifest) throws IOException {
		String value = header(manifest, "Bundle-SymbolicName"); //$NON-NLS-1$
		return value == null ? null : value.split(";")[0].strip(); //$NON-NLS-1$
	}

	private static String header(Path manifest, String name) throws IOException {
		for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
			if (line.startsWith(name + ":")) { //$NON-NLS-1$
				return line.substring(name.length() + 1).strip();
			}
		}
		return null;
	}

	private static List<String[]> records(Path configuration) throws IOException {
		Path record = configuration.resolve(RECORD);
		if (!Files.isRegularFile(record)) {
			return List.of();
		}
		List<String[]> records = new ArrayList<>();
		for (String line : Files.readAllLines(record, StandardCharsets.UTF_8)) {
			String[] parts = line.split("\t", 3); //$NON-NLS-1$
			if (parts.length == 3) {
				records.add(parts);
			}
		}
		return records;
	}

	private static void record(Path configuration, String bundle, String original, String substituted)
			throws IOException {
		Path record = configuration.resolve(RECORD);
		Files.createDirectories(record.getParent());
		String line = "%s\t%s\t%s%n".formatted(bundle, original, substituted); //$NON-NLS-1$
		Files.writeString(record, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
	}

	private static Path configurationDirectory() {
		var location = Platform.getConfigurationLocation();
		if (location == null || location.getURL() == null) {
			return null;
		}
		return FileLocations.pathOf(location.getURL());
	}
}
