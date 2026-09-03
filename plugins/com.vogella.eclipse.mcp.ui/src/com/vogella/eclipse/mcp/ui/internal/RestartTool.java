package com.vogella.eclipse.mcp.ui.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.FileLocations;
import com.vogella.eclipse.mcp.core.FrameworkChanges;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.LaunchAttributes;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.server.McpPreferences;

/**
 * Restarts the IDE, after answering.
 */
public final class RestartTool implements IMcpTool {

	private static final long UI_TIMEOUT_SECONDS = 10;

	/** Long enough for the HTTP response to be on the wire before the server dies with the IDE. */
	private static final int RESTART_DELAY_MILLIS = 2000;

	/** Where the workbench reads the arguments it hands the launcher for the next start. */
	public static final String EXIT_DATA_PROPERTY = "eclipse.exitdata"; //$NON-NLS-1$

	public static final String NO_SPLASH = "-nosplash"; //$NON-NLS-1$

	public static final String CLEAN = "-clean"; //$NON-NLS-1$

	/** Decides whether the launcher reads the exit data at all. */
	public static final String EXIT_CODE_PROPERTY = "eclipse.exitcode"; //$NON-NLS-1$

	/** Set by the launcher on a process that came up from a relaunch. */
	private static final String OLD_USER_ARGS = "--launcher.oldUserArgsStart"; //$NON-NLS-1$

	/**
	 * What the last restart attempt came to, when it did not take.
	 * <p>
	 * The answer has to be sent before the workbench closes, so success cannot be
	 * reported from the outcome. A restart that is vetoed therefore leaves this
	 * behind, and the next call carries it, rather than the failure being visible
	 * only as an IDE that is still running.
	 */
	private static volatile JsonObject lastFailure;

	@Override
	public String getName() {
		return "eclipse_restart"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Restarts the Eclipse IDE, into the same workspace or into another one, which is what makes an installed or updated feature active. The answer names the workspace it will return to and the one it is leaving, so a caller that switches can find its way back. PASS workspace TO SWITCH: an absolute path, created when it is not there, which is how a measurement gets a workspace of its own instead of sharing the one somebody works in. The IDE that comes up is this same installation with this same server in it, reachable at the same port with the same token, but everything workspace-shaped is different: other projects, other preferences, a build from nothing, and the local history and the element tree of the old workspace stay behind. WHETHER THE SERVER RUNS IS ITSELF A WORKSPACE PREFERENCE, so it is written into the target workspace before the relaunch, which the answer reports under server; without that the IDE comes up in a workspace that has never had the server switched on and nothing can reach it. The discovery file is per workspace too, so a client that reads it has to read the new one; the answer names its absolute path under endpointFile, for the workspace being restarted into, so it does not have to be constructed. THE CONNECTION WILL DROP BY DESIGN: this tool answers first and restarts a couple of seconds later, so a dropped connection right after a successful result is the expected outcome and not a failure. THE ANSWER REPORTS WHAT WAS REQUESTED, NOT THE OUTCOME, because the server dies with the IDE and cannot report its own success: verify with startedAt in the discovery file. A restart the platform vetoes, which a save prompt or a listener can do, leaves the IDE running and is reported as previousRestartFailed by the next call and in the Error Log, rather than being silently indistinguishable from success. Reconnect with the same bearer token, which survives restarts and updates. Refuses when anything is unsaved or a modal dialog is open, naming which of the two fired, unless save or force is passed; the check covers every dirty part in every window, which is the set the platform itself would prompt for. force DISCARDS that work outright, closing dirty editors without saving, because leaving it to the platform means a prompt on an IDE nobody is looking at and a restart that never happens. A blocking dialog is better cleared with eclipse_dismiss_dialog than forced past. It works independently of eclipse_update, so a half applied update can still be recovered by restarting. Pass splash false to come back without the splash screen: this appends -nosplash to the arguments the workbench hands the launcher, the same channel it uses to pass the workspace, and splashSuppressed reports whether that argument was added, not what the launcher then did with it."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "save":  {"type":"boolean","default":false,"description":"Save dirty editors first, then restart."},
				    "force":  {"type":"boolean","default":false,"description":"Restart even with unsaved changes or an open modal dialog. DISCARDS that work: dirty editors are closed without saving and dirty views are marked clean, so the platform has nothing left to prompt about."},
				    "splash": {"type":"boolean","default":true,"description":"False comes back without the splash screen. The argument is added to the relaunch arguments; whether the launcher honours it is not something this server can observe."},
				    "clean":  {"type":"boolean","description":"Relaunch with -clean, which discards the registry and resolver caches. Defaults to true when this session hot installed or substituted a bundle, because the caches written at shutdown then describe the wrong contributions and the editors restored at the next start fail with InvalidRegistryObjectException; false otherwise. The answer reports clean and cleanReason."},
				    "workspace": {"type":"string","description":"Absolute path of the workspace to start into. Omit to come back into the current one. The directory is created when it does not exist, because a path that is not there opens the workspace chooser and waits for a person. A workspace another IDE has open cannot be taken over, and that is only visible once the relaunch has happened."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return McpToolResult.error("There is no running workbench to restart."); //$NON-NLS-1$
		}
		ToolArguments args = ToolArguments.of(arguments);
		boolean save = args.getBoolean("save", false); //$NON-NLS-1$
		boolean force = args.getBoolean("force", false); //$NON-NLS-1$
		boolean splash = args.getBoolean("splash", true); //$NON-NLS-1$
		boolean clean = args.getBoolean("clean", FrameworkChanges.any()); //$NON-NLS-1$
		String workspace = args.getString("workspace"); //$NON-NLS-1$
		if (workspace != null) {
			McpToolResult refusal = checkWorkspace(workspace);
			if (refusal != null) {
				return refusal;
			}
		}

		CompletableFuture<JsonObject> pending = new CompletableFuture<>();
		PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
			try {
				pending.complete(prepare(save, force, splash, workspace, clean, args.has("clean"))); //$NON-NLS-1$
			} catch (RuntimeException e) {
				pending.completeExceptionally(e);
			}
		});
		try {
			JsonObject result = pending.get(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return Boolean.TRUE.equals(result.remove("restarting")) //$NON-NLS-1$
					? McpToolResult.of(result.toString())
					: McpToolResult.error(result.toString());
		} catch (TimeoutException e) {
			pending.cancel(false);
			return McpToolResult.error("The Eclipse UI is busy, try again."); //$NON-NLS-1$
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return McpToolResult.error("The request was interrupted."); //$NON-NLS-1$
		} catch (ExecutionException e) {
			return McpToolResult.error("Could not restart: " + (e.getCause() == null ? e : e.getCause()));
		}
	}

	/**
	 * Why the launcher could not relaunch this process, or {@code null}.
	 * <p>
	 * {@code Workbench.restart} answers a refusal with {@code informNoRestart()},
	 * a modal dialog, which on an IDE nobody is looking at hangs the close inside
	 * an invisible prompt. Testing the same condition here turns that into an
	 * answer.
	 */
	public static String cannotRestartReason() {
		String commands = System.getProperty("eclipse.commands", ""); //$NON-NLS-1$
		if (contains(commands, "--launcher.noRestart")) { //$NON-NLS-1$
			return "This IDE was started with --launcher.noRestart, so the launcher will not bring it back up. Restart it by hand."; //$NON-NLS-1$
		}
		if (System.getProperty("eclipse.launcher") == null) { //$NON-NLS-1$
			return "This JVM was not started by the Eclipse launcher, so nothing outside it can relaunch it: the process would go down and stay down. Start the IDE through its launcher, or restart it by hand."; //$NON-NLS-1$
		}
		return null;
	}

	/** Whether this process itself came up from a relaunch, which the launcher records. */
	public static boolean cameFromARelaunch() {
		return contains(System.getProperty("eclipse.commands", ""), OLD_USER_ARGS); //$NON-NLS-1$
	}

	/**
	 * Records a restart that did not happen, so the next call can say so.
	 * <p>
	 * Logged as well: the answer that promised the restart is already gone by the
	 * time this runs, and an IDE that is still up with no trace of why is exactly
	 * the silent failure this tool used to produce.
	 */
	private static void recordFailure(String reason, Throwable cause) {
		JsonObject failure = new JsonObject().put("at", Instant.now().toString()) //$NON-NLS-1$
				.put("reason", reason) //$NON-NLS-1$
				.put("cause", cause == null ? null : String.valueOf(cause)); //$NON-NLS-1$
		lastFailure = failure;
		ILog.get().error("The restart did not take: " + reason, cause); //$NON-NLS-1$
	}

	/** Adds what a previous attempt came to, when one failed. */
	private static void addLastFailure(JsonObject result) {
		JsonObject failure = lastFailure;
		if (failure != null) {
			result.put("previousRestartFailed", failure) //$NON-NLS-1$
					.put("previousRestartNote", //$NON-NLS-1$
							"An earlier restart from this tool did not take; the IDE stayed up. Its reason is above."); //$NON-NLS-1$
		}
	}

	/**
	 * The parts the platform would prompt about, which is more than this tool used
	 * to look at.
	 * <p>
	 * {@code Workbench.saveAllParts} asks the model for every dirty part in every
	 * window, dirty views included, while a check over the active page's editors
	 * sees one window's editors. The difference is not cosmetic: a part this side
	 * did not count cleared the guard and then stalled the close in a prompt
	 * nobody could see.
	 */
	private static List<MPart> dirtyParts() {
		List<MPart> parts = new ArrayList<>();
		try {
			EPartService service = PlatformUI.getWorkbench().getService(EPartService.class);
			if (service != null) {
				parts.addAll(service.getDirtyParts());
			}
		} catch (RuntimeException | LinkageError e) {
			// the editor scan below is the fallback, and reporting nothing at all
			// would be worse than reporting the part of the truth that is reachable
		}
		return parts;
	}

	/** The dirty editors of every window, for the names a caller can act on. */
	private static JsonArray dirtyEditorTitles() {
		JsonArray titles = new JsonArray();
		for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			for (IWorkbenchPage page : window.getPages()) {
				for (IEditorReference reference : page.getEditorReferences()) {
					if (reference.isDirty()) {
						titles.add(reference.getTitle());
					}
				}
			}
		}
		return titles;
	}

	/**
	 * Adds {@code -nosplash} to the arguments the workbench hands the launcher.
	 * <p>
	 * Reports whether the argument is in place, which is all this side can know:
	 * the splash is painted by the native launcher before the JVM exists, so
	 * whether it honours the relaunch arguments cannot be observed from here.
	 */
	public static boolean appendNoSplash() {
		return appendArgument(NO_SPLASH);
	}

	/** Adds one argument to the relaunch arguments, once. */
	public static boolean appendArgument(String argument) {
		try {
			String existing = System.getProperty(EXIT_DATA_PROPERTY, ""); //$NON-NLS-1$
			if (contains(existing, argument)) {
				return true;
			}
			System.setProperty(EXIT_DATA_PROPERTY, existing + argument + "\n"); //$NON-NLS-1$
			return true;
		} catch (RuntimeException e) {
			// a restart that happens without the argument beats one that does not happen
			return false;
		}
	}

	private static String cleanReason(boolean explicit) {
		if (explicit) {
			return "clean was passed."; //$NON-NLS-1$
		}
		return "This session changed what the framework runs (" + String.join("; ", FrameworkChanges.since()) //$NON-NLS-1$ //$NON-NLS-2$
				+ "), so the registry and resolver caches are discarded rather than trusted; pass clean false to keep them."; //$NON-NLS-1$
	}

	/** The arguments are newline separated, so a substring match would hit -nosplashfoo. */
	public static boolean contains(String arguments, String argument) {
		for (String line : arguments.split("\n")) { //$NON-NLS-1$
			if (argument.equals(line.trim())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the IDE can be sent to this workspace at all.
	 *
	 * @return the refusal, or null when it can
	 */
	public static McpToolResult checkWorkspace(String workspace) {
		Path path = pathOf(workspace);
		if (path == null) {
			return McpToolResult.error("'%s' is not a usable path.".formatted(workspace)); //$NON-NLS-1$
		}
		if (!path.isAbsolute()) {
			return McpToolResult.error(
					"'workspace' has to be an absolute path; the launcher resolves a relative one against a working directory nobody here knows."); //$NON-NLS-1$
		}
		if (Files.exists(path) && !Files.isDirectory(path)) {
			return McpToolResult.error("'%s' exists and is not a directory.".formatted(path)); //$NON-NLS-1$
		}
		if (org.eclipse.core.runtime.Platform.inDevelopmentMode()) {
			// the workbench forces a plain restart in development mode, so the
			// relaunch arguments are dropped and the switch would silently not happen
			return McpToolResult.error(
					"This IDE runs in development mode, where the platform keeps the command line of the launch it came from, so it cannot be sent to another workspace. Restart it by hand with -data '%s'." //$NON-NLS-1$
							.formatted(path));
		}
		try {
			Files.createDirectories(path);
		} catch (IOException e) {
			return McpToolResult.error("Could not create '%s': %s".formatted(path, e.getMessage())); //$NON-NLS-1$
		}
		return null;
	}

	/**
	 * Switches the server on in the workspace the IDE is going to.
	 * <p>
	 * Whether the server runs is an instance preference, so it belongs to the
	 * workspace and not to the installation: an untouched workspace has no server
	 * in it, and the IDE would come up unreachable with nothing left to ask why.
	 * The bearer token is not copied because it does not have to be, living under
	 * the user location rather than in a workspace.
	 */
	public static JsonObject carryTheServerOver(Path workspace) {
		Path settings = settingsFile(workspace);
		JsonObject json = new JsonObject().put("preferences", settings.toString()) //$NON-NLS-1$
				.put("endpointFile", endpointFile(workspace).toString()); //$NON-NLS-1$
		if (Files.exists(settings)) {
			return json.put("carriedOver", Boolean.FALSE) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"That workspace has settings of its own for the server and they were left alone, so it comes up with whatever they say."); //$NON-NLS-1$
		}
		var node = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode(McpPreferences.QUALIFIER);
		StringBuilder content = new StringBuilder("eclipse.preferences.version=1\n"); //$NON-NLS-1$
		content.append(McpPreferences.KEY_ENABLED).append('=')
				.append(node.getBoolean(McpPreferences.KEY_ENABLED, McpPreferences.DEFAULT_ENABLED)).append('\n');
		for (String key : new String[] { McpPreferences.KEY_PORT, McpPreferences.KEY_CALL_TIMEOUT_SECONDS }) {
			String value = node.get(key, null);
			if (value != null) {
				content.append(key).append('=').append(value).append('\n');
			}
		}
		try {
			Files.createDirectories(settings.getParent());
			Files.writeString(settings, content.toString());
		} catch (IOException e) {
			return json.put("carriedOver", Boolean.FALSE) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"The server settings could not be written to that workspace (%s), so the IDE will come up there WITHOUT this server and has to be switched on by hand in Preferences > General > MCP Server." //$NON-NLS-1$
									.formatted(e.getMessage()));
		}
		return json.put("carriedOver", Boolean.TRUE) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"The server is switched on for that workspace with the same port. The bearer token is the same, it belongs to the user rather than to a workspace, but the discovery file is per workspace and the new one is written under its .metadata."); //$NON-NLS-1$
	}

	/**
	 * The discovery file of a workspace, whether or not it exists yet.
	 * <p>
	 * Named in the answer because a client that has to read the new one after a
	 * restart cannot construct this path from anything else the answer says, and
	 * telling it the file is "under .metadata" is not a path.
	 */
	public static Path endpointFile(Path workspace) {
		return workspace.resolve(".metadata/.plugins") //$NON-NLS-1$
				.resolve(McpPreferences.QUALIFIER).resolve("endpoint.json"); //$NON-NLS-1$
	}

	/** Where the instance scope keeps the server's preferences inside a workspace. */
	public static Path settingsFile(Path workspace) {
		return workspace.resolve(".metadata/.plugins/org.eclipse.core.runtime/.settings") //$NON-NLS-1$
				.resolve(McpPreferences.QUALIFIER + ".prefs"); //$NON-NLS-1$
	}

	/**
	 * A filesystem path from what a caller passed, a file URI included.
	 * <p>
	 * The URI form is what this tool used to report as the workspace, so a caller
	 * that hands back what it was given would otherwise be refused.
	 */
	public static Path pathOf(String workspace) {
		// through FileLocations rather than URI.getPath(): that form hands back
		// "/C:/ws" for a Windows workspace, which is not a path at all, and leaves
		// %20 wherever the URL was encoded
		return FileLocations.pathOf(workspace);
	}

	/**
	 * Points the relaunch at another workspace.
	 * <p>
	 * This is what {@code restart(true)} does for the current workspace: the
	 * launcher reads the arguments out of the exit data, and the exit code decides
	 * whether they are used at all, so both have to be set together.
	 */
	private static void relaunchInto(String workspace) {
		String existing = System.getProperty(EXIT_DATA_PROPERTY, ""); //$NON-NLS-1$
		System.setProperty(EXIT_DATA_PROPERTY, existing + "-data\n" + workspace + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
		// the code that makes the launcher read the arguments above; a plain restart
		// ignores them and comes back where it was
		System.setProperty(EXIT_CODE_PROPERTY, org.eclipse.equinox.app.IApplication.EXIT_RELAUNCH.toString());
	}

	private static JsonObject prepare(boolean save, boolean force, boolean splash, String workspace, boolean clean, boolean cleanExplicit) {
		String cannot = cannotRestartReason();
		if (cannot != null) {
			return new JsonObject().put("restarting", Boolean.FALSE).put("reason", "Refused: " + cannot); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		CloseGuard guard = guard(save, force, "restarting", "restarting"); //$NON-NLS-1$ //$NON-NLS-2$
		if (guard.refusal() != null) {
			JsonObject refusal = guard.refusal().put("restarting", Boolean.FALSE); //$NON-NLS-1$
			addLastFailure(refusal);
			return refusal;
		}
		JsonObject discarded = guard.discarded();
		// before the restart, and deliberately not by waiting for either of them. A
		// build has nothing worth saving across a restart, and a launched JVM that
		// outlives the IDE keeps its workspace lock with nobody left who knows where
		// it came from
		JsonObject cleared = clearTheWay();
		// kept so a restart that does not take can put them back: they are global
		// state, and an attempt that leaves -data behind makes the next one append a
		// second one
		String previousExitData = System.getProperty(EXIT_DATA_PROPERTY);
		String previousExitCode = System.getProperty(EXIT_CODE_PROPERTY);
		// before the restart is scheduled: Workbench.buildCommandLine reads this
		// property and appends the workspace to whatever is already in it, so adding
		// the argument here is the same channel the platform uses for -data
		boolean splashSuppressed = splash ? false : appendNoSplash();
		boolean cleaning = clean && appendArgument(CLEAN);
		String target = workspace == null ? null : String.valueOf(pathOf(workspace));
		JsonObject server = null;
		if (target != null) {
			server = carryTheServerOver(Path.of(target));
			relaunchInto(target);
		}
		// answer first, restart after: the server dies with the IDE, so restarting
		// inside the call gives the caller a dropped connection instead of a result
		Display display = PlatformUI.getWorkbench().getDisplay();
		// restart(true), not restart(): the no argument form relaunches without -data,
		// so the IDE comes back up asking for a workspace and waits for a human.
		// With a workspace of our own the arguments are already set, and restart(true)
		// would append the current one after it
		boolean current = workspace == null;
		lastFailure = null;
		display.timerExec(RESTART_DELAY_MILLIS, () -> performRestart(current, previousExitData, previousExitCode));
		JsonObject result = new JsonObject().put("restarting", Boolean.TRUE) //$NON-NLS-1$
				.put("inMillis", RESTART_DELAY_MILLIS) //$NON-NLS-1$
				.put("cleared", cleared) //$NON-NLS-1$
				.put("discarded", discarded) //$NON-NLS-1$
				.put("splashSuppressed", Boolean.valueOf(splashSuppressed)) //$NON-NLS-1$
				.put("clean", Boolean.valueOf(cleaning)) //$NON-NLS-1$
				.put("cleanReason", cleaning ? cleanReason(cleanExplicit) : null) //$NON-NLS-1$
				.put("workspace", target == null ? workspaceLocation() : target) //$NON-NLS-1$
				.put("previousWorkspace", workspaceLocation()) //$NON-NLS-1$
				.put("workspaceChanged", Boolean.valueOf(workspace != null)) //$NON-NLS-1$
				.put("server", server) //$NON-NLS-1$
				.put("endpointFile", endpointFile(Path.of(target == null ? workspaceLocation() : target)).toString()) //$NON-NLS-1$
				.put("savedEditors", save) //$NON-NLS-1$
				.put("verifyRestartWith", //$NON-NLS-1$
						"Read startedAt from endpointFile above before and after; it changes only when a new server process comes up.") //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"REQUESTED, NOT YET DONE: this answer is sent BEFORE the restart, so it reports what was asked for and cannot report the outcome. The old server keeps answering for a couple of seconds, so a reachability check right after this succeeds against the process that is about to die; compare startedAt in the discovery file instead. A restart the platform refuses leaves the IDE up and is reported as previousRestartFailed by the next call to this tool, and in the Error Log. Reconnect with the same bearer token, which survives restarts and updates. The IDE is relaunched into the workspace named above; if it comes back asking which workspace to use, the relaunch lost its arguments and a human has to answer the chooser." //$NON-NLS-1$
								+ (workspace == null ? "" //$NON-NLS-1$
										: " THIS IS A DIFFERENT WORKSPACE: check what came up before measuring anything in it, because a workspace another IDE holds open is refused by the lock and the chooser opens instead. previousWorkspace is the way back.")); //$NON-NLS-1$
		addLastFailure(result);
		return result;
	}

	/**
	 * What the save-or-discard step did, and why the close must not go ahead.
	 *
	 * @param refusal  the answer to return instead of closing, or {@code null}
	 * @param discarded what {@code force} threw away, or {@code null}
	 */
	record CloseGuard(JsonObject refusal, JsonObject discarded) {
	}

	/**
	 * Saves or discards unsaved work, then decides whether the close can proceed.
	 * <p>
	 * Shared by {@code eclipse_restart} and {@code eclipse_exit} because both end
	 * in the same cancellable close: {@code Workbench.saveAllParts} prompts for
	 * every dirty part in every window, and a veto leaves the JVM up, so both have
	 * to leave nothing to prompt about rather than hoping the close is quiet.
	 *
	 * @param discardVerb what the unsaved work would be lost to, for the refusal
	 * @param underVerb   what would happen under an open dialog, for the refusal
	 */
	static CloseGuard guard(boolean save, boolean force, String discardVerb, String underVerb) {
		JsonArray modal = new JsonArray();
		for (Shell shell : PlatformUI.getWorkbench().getDisplay().getShells()) {
			boolean isModal = (shell.getStyle()
					& (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL)) != 0;
			if (isModal && shell.isVisible()) {
				modal.add(shell.getText());
			}
		}
		JsonObject discarded = null;
		if (save) {
			saveEverything();
		} else if (force) {
			discarded = discardEverything();
		}
		JsonArray dirty = dirtyEditorTitles();
		List<MPart> dirtyModelParts = dirtyParts();
		if (!force && (dirty.size() > 0 || !dirtyModelParts.isEmpty() || modal.size() > 0)) {
			// compose from whichever guard actually fired: naming unsaved work when the
			// blocker is a dialog sends the caller to save, which changes nothing
			StringBuilder reason = new StringBuilder("Refused: "); //$NON-NLS-1$
			if (dirty.size() > 0 || !dirtyModelParts.isEmpty()) {
				reason.append("there are unsaved changes, which %s would discard. Pass save to save them first, or force to discard them." //$NON-NLS-1$
						.formatted(discardVerb));
			}
			if (modal.size() > 0) {
				if (dirty.size() > 0 || !dirtyModelParts.isEmpty()) {
					reason.append(' ');
				}
				reason.append("a modal dialog is open, and %s under one loses whatever is in it. Close it with eclipse_dismiss_dialog, or pass force." //$NON-NLS-1$
						.formatted(underVerb));
			}
			return new CloseGuard(new JsonObject().put("dirtyEditors", dirty) //$NON-NLS-1$
					.put("dirtyParts", names(dirtyModelParts)) //$NON-NLS-1$
					.put("openModalDialogs", modal) //$NON-NLS-1$
					.put("reason", reason.toString()), discarded); //$NON-NLS-1$
		}
		return new CloseGuard(null, discarded);
	}

	/** Cancels builds and terminates this server's launches, for a restart or an exit. */
	static JsonObject clearTheWayForShutdown() {
		return clearTheWay();
	}

	/** The workspace path for an answer, or {@code null} when there is none. */
	static String workspaceLocationForAnswer() {
		return workspaceLocation();
	}

	/** The part names for an answer, since an MPart's toString is not one. */
	private static JsonArray names(List<MPart> parts) {
		JsonArray names = new JsonArray();
		for (MPart part : parts) {
			names.add(part.getLabel() == null ? part.getElementId() : part.getLabel());
		}
		return names;
	}

	/** Saves every dirty part in every window, which is the set the close would prompt for. */
	private static void saveEverything() {
		for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			for (IWorkbenchPage page : window.getPages()) {
				page.saveAllEditors(false);
			}
		}
		try {
			EPartService service = PlatformUI.getWorkbench().getService(EPartService.class);
			if (service != null) {
				for (MPart part : new ArrayList<>(service.getDirtyParts())) {
					service.savePart(part, false);
				}
			}
		} catch (RuntimeException | LinkageError e) {
			// what could not be saved stays dirty and the guard below reports it
		}
	}

	/**
	 * Throws away every unsaved change, so the platform's close has nothing to
	 * prompt about.
	 * <p>
	 * Editors are closed without saving, which is what discarding means for them.
	 * A dirty view cannot be closed the same way, so its dirty flag is cleared in
	 * the model instead; both are what {@code force} promises.
	 */
	private static JsonObject discardEverything() {
		JsonArray editors = new JsonArray();
		for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			for (IWorkbenchPage page : window.getPages()) {
				List<IEditorReference> dirty = new ArrayList<>();
				for (IEditorReference reference : page.getEditorReferences()) {
					if (reference.isDirty()) {
						dirty.add(reference);
						editors.add(reference.getTitle());
					}
				}
				if (!dirty.isEmpty()) {
					page.closeEditors(dirty.toArray(new IEditorReference[0]), false);
				}
			}
		}
		JsonArray parts = new JsonArray();
		for (MPart part : dirtyParts()) {
			parts.add(part.getLabel() == null ? part.getElementId() : part.getLabel());
			part.setDirty(false);
		}
		return new JsonObject().put("editorsClosed", editors).put("partsMarkedClean", parts) //$NON-NLS-1$ //$NON-NLS-2$
				.put("note", //$NON-NLS-1$
						"force discards unsaved work rather than letting the platform prompt for it, because a prompt on an IDE nobody is looking at stalls the close and the restart never happens."); //$NON-NLS-1$
	}

	/**
	 * Runs the restart and reports what it came to.
	 * <p>
	 * {@code Workbench.restart} routes to a cancellable close, so a prompt or a
	 * listener can veto it and the JVM stays up; the boolean says which happened
	 * and used to be discarded. On a refusal the exit data is put back, because it
	 * was set for a relaunch that is not going to happen.
	 */
	private static void performRestart(boolean current, String previousExitData, String previousExitCode) {
		boolean restarted = false;
		Throwable failure = null;
		try {
			restarted = PlatformUI.getWorkbench().restart(current);
		} catch (Throwable e) {
			failure = e;
		}
		if (restarted) {
			return;
		}
		restore(EXIT_DATA_PROPERTY, previousExitData);
		restore(EXIT_CODE_PROPERTY, previousExitCode);
		recordFailure(failure != null
				? "the workbench threw while closing, which leaves it running" //$NON-NLS-1$
				: "the workbench refused to close, which a save prompt or a listener veto does; the IDE is still up", //$NON-NLS-1$
				failure);
	}

	private static void restore(String property, String value) {
		if (value == null) {
			System.clearProperty(property);
		} else {
			System.setProperty(property, value);
		}
	}

	/**
	 * Cancels what is building and ends the launches this server started.
	 * <p>
	 * Neither is waited for beyond a short bound. A build interrupted by a restart
	 * costs another build and nothing else, so waiting minutes for one would be
	 * pure delay. A launched JVM is the opposite case: it survives the restart,
	 * keeps holding the workspace lock and the ports it took, and the next launch
	 * then fails inside its own process with a dialog no tool here can reach,
	 * hours after anybody could connect it to a restart.
	 */
	private static JsonObject clearTheWay() {
		JsonObject cleared = new JsonObject();
		org.eclipse.core.runtime.jobs.IJobManager jobs = org.eclipse.core.runtime.jobs.Job.getJobManager();
		int builds = jobs.find(org.eclipse.core.resources.ResourcesPlugin.FAMILY_MANUAL_BUILD).length
				+ jobs.find(org.eclipse.core.resources.ResourcesPlugin.FAMILY_AUTO_BUILD).length;
		if (builds > 0) {
			jobs.cancel(org.eclipse.core.resources.ResourcesPlugin.FAMILY_MANUAL_BUILD);
			jobs.cancel(org.eclipse.core.resources.ResourcesPlugin.FAMILY_AUTO_BUILD);
			cleared.put("buildsCancelled", Integer.valueOf(builds)) //$NON-NLS-1$
					.put("buildNote", //$NON-NLS-1$
							"Cancelled rather than waited for: a build has no state worth carrying across a restart, and the workspace rebuilds afterwards."); //$NON-NLS-1$
		}
		JsonArray terminated = new JsonArray();
		JsonArray leftRunning = new JsonArray();
		for (org.eclipse.debug.core.ILaunch launch : org.eclipse.debug.core.DebugPlugin.getDefault()
				.getLaunchManager().getLaunches()) {
			if (!startedByMcp(launch) || launch.isTerminated() || !launch.canTerminate()) {
				continue;
			}
			try {
				launch.terminate();
				terminated.add(nameOf(launch));
			} catch (org.eclipse.core.runtime.CoreException e) {
				leftRunning.add(nameOf(launch));
			}
		}
		if (terminated.size() > 0 || leftRunning.size() > 0) {
			cleared.put("launchesTerminated", terminated) //$NON-NLS-1$
					.put("launchNote", //$NON-NLS-1$
							"Only launches this server started. A launch the person at the IDE started is left alone, and it will outlive the restart."); //$NON-NLS-1$
		}
		if (leftRunning.size() > 0) {
			cleared.put("launchesLeftRunning", leftRunning) //$NON-NLS-1$
					.put("launchWarning", //$NON-NLS-1$
							"These refused to terminate and will survive the restart, still holding whatever workspace or port they took."); //$NON-NLS-1$
		}
		return cleared;
	}

	private static boolean startedByMcp(org.eclipse.debug.core.ILaunch launch) {
		try {
			return launch.getLaunchConfiguration() != null && launch.getLaunchConfiguration()
					.getAttribute(LaunchAttributes.STARTED_BY_MCP, false);
		} catch (org.eclipse.core.runtime.CoreException e) {
			return false;
		}
	}

	private static String nameOf(org.eclipse.debug.core.ILaunch launch) {
		return launch.getLaunchConfiguration() == null ? "unnamed" : launch.getLaunchConfiguration().getName(); //$NON-NLS-1$
	}

	/**
	 * The workspace the IDE is in, as a path rather than a URL.
	 * <p>
	 * A path is what the workspace argument takes, so what this reports can be
	 * handed straight back to get here again.
	 */
	private static String workspaceLocation() {
		var location = org.eclipse.core.runtime.Platform.getInstanceLocation();
		if (location == null || location.getURL() == null) {
			return null;
		}
		Path path = pathOf(location.getURL().toString());
		return path == null ? location.getURL().toString() : path.toString();
	}
}
