package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.IParameter;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.keys.IBindingService;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the commands the IDE defines, and runs one through the command and
 * handler framework.
 * <p>
 * Everything a menu entry, toolbar button or keybinding does goes through this
 * framework, so running a command by id is what makes the parts of the IDE that
 * exist only as a command reachable at all.
 */
public final class CommandTools {

	private static final long UI_TIMEOUT_SECONDS = 10;

	private static final int MAX_CANDIDATES = 20;

	private static final int RETURN_VALUE_LIMIT = 500;

	private static final String EXIT = "org.eclipse.ui.file.exit"; //$NON-NLS-1$

	private static final String RESTART_WORKBENCH = "org.eclipse.ui.file.restartWorkbench"; //$NON-NLS-1$

	private CommandTools() {
	}

	/** Every defined command, narrowed by filter, as far as it is safe to read. */
	public static final class ListCommands implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_list_commands"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Lists the workbench commands this IDE defines, with name, category, whether a handler is active for them right now, whether they are enabled, and their active keybinding formatted the way the menus show it. READ ONLY, and where eclipse_run_workbench_command gets its ids. PASS A FILTER: an IDE defines around two thousand commands, so an unfiltered answer is the first page of a wall of ids rather than something anyone can read. The filter is a case insensitive substring over id, name and category; handledOnly narrows to commands whose handler would act at the moment of the call, which is the shorter list worth reading before running something."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "filter":            {"type":"string","description":"Substring of id, name or category, case insensitive. Pass one: there are about two thousand commands."},
					    "handledOnly":       {"type":"boolean","default":false,"description":"Only commands that have an active handler right now."},
					    "includeParameters": {"type":"boolean","default":false,"description":"Report each parameter's id, name and whether it is optional."},
					    "maxResults":        {"type":"integer","default":100,"minimum":1,"maximum":500}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String filter = args.getString("filter"); //$NON-NLS-1$
			boolean handledOnly = args.getBoolean("handledOnly", false); //$NON-NLS-1$
			boolean includeParameters = args.getBoolean("includeParameters", false); //$NON-NLS-1$
			int maxResults = args.getInt("maxResults", 100, 1, 500); //$NON-NLS-1$
			UiThread.TimedOutcome outcome = UiThread.timed(UI_TIMEOUT_SECONDS,
					() -> collect(filter, handledOnly, includeParameters, maxResults));
			if (outcome.error() != null) {
				return McpToolResult.error(outcome.error());
			}
			if (outcome.timedOut()) {
				// enablement is evaluated against the workbench's evaluation context, so a
				// half read listing would mix states from different moments
				return McpToolResult.of(new JsonObject().put("commands", new JsonArray()) //$NON-NLS-1$
						.put("total", Integer.valueOf(0)).put("truncated", Boolean.FALSE) //$NON-NLS-1$ //$NON-NLS-2$
						.put("timedOut", Boolean.TRUE) //$NON-NLS-1$
						.put("note", "The Eclipse UI did not answer within %d seconds, so nothing was reported." //$NON-NLS-1$
								.formatted(Long.valueOf(UI_TIMEOUT_SECONDS)))
						.toString());
			}
			return McpToolResult.of(outcome.value().toString());
		}

		private static JsonObject collect(String filter, boolean handledOnly, boolean includeParameters,
				int maxResults) {
			ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
			IBindingService bindings = PlatformUI.getWorkbench().getService(IBindingService.class);
			String needle = filter == null ? null : filter.toLowerCase(Locale.ROOT);
			List<Candidate> matching = new ArrayList<>();
			for (Command command : commands.getDefinedCommands()) {
				String name = nameOf(command);
				if (name == null) {
					// a command undefined after being defined throws from everything it answers,
					// and skipping it beats failing the whole listing
					continue;
				}
				String category = categoryOf(command);
				if (needle != null && !(contains(command.getId(), needle) || contains(name, needle)
						|| contains(category, needle))) {
					continue;
				}
				boolean handled = command.isHandled();
				if (handledOnly && !handled) {
					continue;
				}
				matching.add(new Candidate(command, name, category, handled));
			}
			matching.sort((a, b) -> {
				int byName = String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
				return byName != 0 ? byName
						: String.CASE_INSENSITIVE_ORDER.compare(a.command().getId(), b.command().getId());
			});

			int total = matching.size();
			JsonArray reported = new JsonArray();
			// enablement and keybinding are the costly reads, so only the reported page pays for them
			for (Candidate candidate : matching.subList(0, Math.min(maxResults, total))) {
				Command command = candidate.command();
				reported.add(describe(new Row(command.getId(), candidate.name(), candidate.category(),
						descriptionOf(command), candidate.handled(), command.isEnabled(),
						bindings.getBestActiveBindingFormattedFor(command.getId()),
						includeParameters ? parametersOf(command) : null)));
			}
			return new JsonObject().put("commands", reported).put("total", Integer.valueOf(total)) //$NON-NLS-1$ //$NON-NLS-2$
					.put("truncated", Boolean.valueOf(total > reported.size())); //$NON-NLS-1$
		}

		private static JsonObject describe(Row row) {
			JsonObject json = new JsonObject().put("id", row.id()).put("name", row.name()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("category", row.category()).put("description", row.description()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("handled", Boolean.valueOf(row.handled())).put("enabled", Boolean.valueOf(row.enabled())) //$NON-NLS-1$ //$NON-NLS-2$
					.put("keybinding", row.keybinding()); //$NON-NLS-1$
			if (row.parameters() != null) {
				json.put("parameters", row.parameters()); //$NON-NLS-1$
			}
			return json;
		}
	}

	private record Candidate(Command command, String name, String category, boolean handled) {
	}

	private record Row(String id, String name, String category, String description, boolean handled, boolean enabled,
			String keybinding, JsonArray parameters) {
	}

	/** Runs one command through the framework its menu entries go through. */
	public static final class Run implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_run_workbench_command"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Runs a workbench command through Eclipse's command and handler framework, exactly as its menu entry, toolbar button or keybinding would. DOES WHATEVER THAT COMMAND DOES: the effect belongs to the command's handler and is unknown to this server, which neither knows in advance nor limits what it can be, and it can change files, preferences, perspectives or anything else the IDE can touch. This is how most of what an IDE can do becomes reachable here, because much of it exists only as a command with no other API; resolve ids with eclipse_list_commands first. Every answer reports handlerFinished and the outcome verb success, failure or notHandled, heard from the framework's own execution listener, so whether the handler actually ran is never a guess. A command no handler currently answers comes back as handled false with advice rather than an error, which usually means the part or selection it belongs to is not active; activate it with eclipse_set_part_state and try again. MANY HANDLERS OPEN A MODAL DIALOG, which holds the UI thread until somebody answers it, so the wait is capped and running out reports timedOut with the tools to see and answer the dialog; there timedOut true with handlerFinished false reads as a dialog still holding the handler inside execute, while handlerFinished true reads as a slow handler whose verdict already came back, and whatever the command went on to do afterwards is written to the Error Log rather than lost. Refuses org.eclipse.ui.file.exit, which ends the IDE and this server with it, and org.eclipse.ui.file.restartWorkbench, which eclipse_restart does in an orderly way."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "required": ["command"],
					  "properties": {
					    "command":        {"type":"string","description":"Command id, or the label a person reads in the menu. Use eclipse_list_commands."},
					    "parameters":     {"type":"object","additionalProperties":{"type":"string"},"description":"Parameter id to string value, for commands that take them."},
					    "dryRun":         {"type":"boolean","default":false,"description":"Resolve the command and report handled, enabled and its parameters without executing anything."},
				    "selection":      {"type":"array","items":{"type":"string"},"description":"Ask the enablement for THIS selection instead of the one the IDE currently has, as workspace paths ('/org.eclipse.compare') or project names ('g'). dryRun only. The command is evaluated against a context built from these elements and the IDE's selection is not touched, so an enablement can be tested for a selection no viewer here can even show, a closed project among open ones for instance. Reports enabledForSelection beside the ambient enabled."},
					    "timeoutSeconds": {"type":"integer","default":10,"minimum":1,"maximum":25,"description":"How long to wait for the handler before reporting a probable dialog."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String wanted = args.getString("command"); //$NON-NLS-1$
			if (wanted == null) {
				return McpToolResult.error("The argument 'command' is required."); //$NON-NLS-1$
			}
			String refusal = refusalFor(wanted);
			if (refusal != null) {
				return McpToolResult.error(refusal);
			}
			boolean dryRun = args.getBoolean("dryRun", false); //$NON-NLS-1$
			List<String> selection = new ArrayList<>();
			if (arguments.get("selection") instanceof List<?> given) { //$NON-NLS-1$
				given.forEach(value -> selection.add(String.valueOf(value)));
			}
			if (!selection.isEmpty() && !dryRun) {
				return McpToolResult.error(
						"'selection' only applies to a dryRun: it answers what the enablement would be, and executing a command against a selection the IDE does not have would run it on the wrong thing."); //$NON-NLS-1$
			}
			long timeoutSeconds = args.getInt("timeoutSeconds", 10, 1, 25); //$NON-NLS-1$
			Map<String, String> parameters = parameterMap(arguments.get("parameters")); //$NON-NLS-1$
			if (!PlatformUI.isWorkbenchRunning()) {
				return McpToolResult.error(Workbenches.noIde());
			}

			ExecutionRecorder recorder = new ExecutionRecorder();
			CompletableFuture<String> pending = new CompletableFuture<>();
			UiThread.exec(() -> {
				try {
					pending.complete(execute(wanted, parameters, dryRun, selection, recorder).toString());
				} catch (Throwable e) {
					// an Error must complete the future too, or the wait reports a dialog that is not there
					pending.completeExceptionally(e);
				}
			});
			try {
				return McpToolResult.of(pending.get(timeoutSeconds, TimeUnit.SECONDS));
			} catch (TimeoutException e) {
				// registered here so a completion between the timeout and this line is logged too
				pending.whenComplete((text, error) -> logLateCompletion(wanted, text, error));
				return McpToolResult.of(timedOut(wanted, timeoutSeconds, recorder).toString());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return McpToolResult.error("The request was interrupted."); //$NON-NLS-1$
			} catch (ExecutionException e) {
				Throwable cause = e.getCause() == null ? e : e.getCause();
				return McpToolResult.error("The command could not be run: " + cause); //$NON-NLS-1$
			}
		}

		/**
		 * Resolves and runs the command on the UI thread.
		 * <p>
		 * The three framework failures are answers rather than errors, because each one
		 * is the caller's next question: not handled asks for the right active part,
		 * not enabled for the right selection, and an execution exception carries the
		 * handler's own failure. An {@link ExecutionRecorder} hears the verdict the
		 * framework reports, which is what tells a dialog still holding the handler
		 * apart from one that already finished.
		 */
		private static JsonObject execute(String wanted, Map<String, String> parameters, boolean dryRun,
				List<String> selection, ExecutionRecorder recorder) {
			long started = System.nanoTime();
			ICommandService service = PlatformUI.getWorkbench().getService(ICommandService.class);
			List<Command> matches = match(service, wanted);
			if (matches.isEmpty()) {
				return recorder.reportInto(new JsonObject().put("executed", Boolean.FALSE).put("id", wanted) //$NON-NLS-1$ //$NON-NLS-2$
						.put("reason", "No command matches '%s'. Use eclipse_list_commands.".formatted(wanted))); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (matches.size() > 1) {
				return recorder.reportInto(new JsonObject().put("executed", Boolean.FALSE).put("id", wanted) //$NON-NLS-1$ //$NON-NLS-2$
						.put("reason", "'%s' matches %d commands; name one of them exactly." //$NON-NLS-1$
								.formatted(wanted, Integer.valueOf(matches.size())))
						.put("candidates", candidates(matches))); //$NON-NLS-1$
			}
			Command command = matches.get(0);

			String refusal = refusalFor(command.getId());
			if (refusal != null) {
				// a label can resolve to a refused command even though the raw argument got
				// past the guard before resolution
				return recorder.reportInto(base(command, started)).put("reason", refusal); //$NON-NLS-1$
			}

			command.addExecutionListener(recorder);
			try {
				JsonObject answer = base(command, started);
				answer.put("parameters", parametersOf(command)); //$NON-NLS-1$
				ParameterizedCommand parameterized = ParameterizedCommand.generateCommand(command, parameters);
				if (parameterized == null) {
					return recorder.reportInto(answer.put("reason", //$NON-NLS-1$
							"This command requires a value for %s; pass them under parameters." //$NON-NLS-1$
									.formatted(requiredParameters(command))));
				}
				if (dryRun) {
					if (!selection.isEmpty()) {
						answer.put("forSelection", enablementFor(command, selection)); //$NON-NLS-1$
					}
					return recorder.reportInto(answer.put("dryRun", Boolean.TRUE).put("note", "Nothing was executed.") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							.put("elapsedMillis", elapsed(started))); //$NON-NLS-1$
				}
				IHandlerService handlers = PlatformUI.getWorkbench().getService(IHandlerService.class);
				Object returnValue;
				try {
					returnValue = handlers.executeCommand(parameterized, null);
				} catch (NotHandledException e) {
					return recorder.reportInto(answer.put("handled", Boolean.FALSE) //$NON-NLS-1$
							.put("elapsedMillis", elapsed(started)) //$NON-NLS-1$
							.put("reason", //$NON-NLS-1$
									"No handler is active for this command right now. Most commands are handled only while a particular part is active or a particular selection exists; activate the part with eclipse_set_part_state and try again.")); //$NON-NLS-1$
				} catch (NotEnabledException e) {
					return recorder.reportInto(answer.put("enabled", Boolean.FALSE) //$NON-NLS-1$
							.put("elapsedMillis", elapsed(started)) //$NON-NLS-1$
							.put("reason", //$NON-NLS-1$
									"The command's handler is active but not enabled right now, which usually depends on the active part or selection; activate the part with eclipse_set_part_state and try again.")); //$NON-NLS-1$
				} catch (org.eclipse.core.commands.ExecutionException e) {
					Throwable cause = e.getCause() == null ? e : e.getCause();
					String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
					return recorder.reportInto(answer.put("executed", Boolean.FALSE) //$NON-NLS-1$
							.put("elapsedMillis", elapsed(started)) //$NON-NLS-1$
							.put("reason", "The command threw: " + message)); //$NON-NLS-1$ //$NON-NLS-2$
				} catch (NotDefinedException e) {
					return recorder.reportInto(answer.put("executed", Boolean.FALSE) //$NON-NLS-1$
							.put("elapsedMillis", elapsed(started)) //$NON-NLS-1$
							.put("reason", String.valueOf(e.getMessage()))); //$NON-NLS-1$
				}
				answer.put("executed", Boolean.TRUE); //$NON-NLS-1$
				if (returnValue != null) {
					answer.put("returnValue", cap(String.valueOf(returnValue))); //$NON-NLS-1$
				}
				return recorder.reportInto(answer.put("elapsedMillis", elapsed(started))); //$NON-NLS-1$
			} finally {
				command.removeExecutionListener(recorder);
			}
		}

		/**
		 * The command's enablement for a selection the IDE does not have.
		 * <p>
		 * The handler is asked against a context built for these elements, so the
		 * question can be put for a selection no viewer here shows: a lazily
		 * populated tree materialises only what is on screen, and a view can filter
		 * a closed project out entirely, which leaves clicking unable to express the
		 * very case an enablement test is about. The IDE's own selection is not
		 * touched, and the command's enablement is put back afterwards.
		 */
		private static JsonObject enablementFor(Command command, List<String> specs) {
			JsonArray unresolved = new JsonArray();
			List<Object> elements = new ArrayList<>();
			JsonArray described = new JsonArray();
			for (String spec : specs) {
				Object resolved = SelectionTools.resolveResource(spec);
				if (resolved == null) {
					unresolved.add(spec);
				} else {
					elements.add(resolved);
					described.add(SelectionTools.describe(resolved));
				}
			}
			JsonObject result = new JsonObject().put("requested", Integer.valueOf(specs.size())) //$NON-NLS-1$
					.put("resolved", Integer.valueOf(elements.size())) //$NON-NLS-1$
					.put("unresolved", unresolved) //$NON-NLS-1$
					.put("elements", described); //$NON-NLS-1$
			if (elements.isEmpty()) {
				return result.put("enabledForSelection", null) //$NON-NLS-1$
						.put("reason", "Nothing resolved, so no selection could be built."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			IHandlerService handlers = PlatformUI.getWorkbench().getService(IHandlerService.class);
			org.eclipse.jface.viewers.IStructuredSelection structured = new org.eclipse.jface.viewers.StructuredSelection(
					elements);
			org.eclipse.core.commands.IHandler handler = command.getHandler();
			result.put("handlerClass", handler == null ? null : handler.getClass().getName()); //$NON-NLS-1$
			// the platform's handler wrapper ignores the IEvaluationContext and reads the LIVE context,
			// so the selection is swapped in there and restored within the same UI turn
			org.eclipse.e4.core.contexts.IEclipseContext eclipseContext = eclipseContext();
			if (eclipseContext == null) {
				return result.put("enabledForSelection", null) //$NON-NLS-1$
						.put("reason", "No e4 context to evaluate against."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			String currentName = org.eclipse.ui.ISources.ACTIVE_CURRENT_SELECTION_NAME;
			String menuName = org.eclipse.ui.ISources.ACTIVE_MENU_SELECTION_NAME;
			Object previousCurrent = eclipseContext.getLocal(currentName);
			Object previousMenu = eclipseContext.getLocal(menuName);
			try {
				eclipseContext.set(currentName, structured);
				eclipseContext.set(menuName, structured);
				command.setEnabled(handlers.createContextSnapshot(true));
				result.put("enabledForSelection", Boolean.valueOf(command.isEnabled())) //$NON-NLS-1$
						.put("handled", Boolean.valueOf(command.isHandled())); //$NON-NLS-1$
			} catch (RuntimeException e) {
				result.put("enabledForSelection", null).put("reason", "The handler threw while being asked: " + e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			} finally {
				restoreVariable(eclipseContext, currentName, previousCurrent);
				restoreVariable(eclipseContext, menuName, previousMenu);
				// put the ambient enablement back, so a later question about the real
				// selection is not answered from this hypothetical one
				try {
					command.setEnabled(handlers.createContextSnapshot(true));
				} catch (RuntimeException e) {
					result.put("restoreFailed", String.valueOf(e)); //$NON-NLS-1$
				}
			}
			return result.put("note", //$NON-NLS-1$
					"This is the enablement for the selection above, evaluated without touching what the IDE has selected. 'enabled' elsewhere in this answer is the ambient one."); //$NON-NLS-1$
		}

		/** The context the handlers actually read, which is the active window's. */
		private static org.eclipse.e4.core.contexts.IEclipseContext eclipseContext() {
			org.eclipse.ui.IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window != null) {
				org.eclipse.e4.core.contexts.IEclipseContext fromWindow = window
						.getService(org.eclipse.e4.core.contexts.IEclipseContext.class);
				if (fromWindow != null) {
					return fromWindow;
				}
			}
			return PlatformUI.getWorkbench().getService(org.eclipse.e4.core.contexts.IEclipseContext.class);
		}

		private static void restoreVariable(org.eclipse.e4.core.contexts.IEclipseContext context, String name,
				Object previous) {
			if (previous == null) {
				context.remove(name);
			} else {
				context.set(name, previous);
			}
		}

		private static JsonObject base(Command command, long started) {
			return new JsonObject().put("executed", Boolean.FALSE) //$NON-NLS-1$
					.put("id", command.getId()).put("name", nameOf(command)) //$NON-NLS-1$ //$NON-NLS-2$
					.put("handled", Boolean.valueOf(command.isHandled())) //$NON-NLS-1$
					.put("enabled", Boolean.valueOf(command.isEnabled())) //$NON-NLS-1$
					.put("elapsedMillis", elapsed(started)); //$NON-NLS-1$
		}

		private static JsonArray candidates(List<Command> matches) {
			JsonArray candidates = new JsonArray();
			for (Command command : matches.subList(0, Math.min(MAX_CANDIDATES, matches.size()))) {
				candidates.add(new JsonObject().put("id", command.getId()).put("name", nameOf(command))); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return candidates;
		}

		private static String requiredParameters(Command command) {
			List<String> required = new ArrayList<>();
			try {
				for (IParameter parameter : parametersFor(command)) {
					if (!parameter.isOptional()) {
						required.add(parameter.getId());
					}
				}
			} catch (NotDefinedException e) {
				return "its required parameters"; //$NON-NLS-1$
			}
			return required.isEmpty() ? "its required parameters" : String.join(", ", required); //$NON-NLS-1$
		}

		private static JsonObject timedOut(String wanted, long timeoutSeconds, ExecutionRecorder recorder) {
			return recorder.reportInto(new JsonObject().put("executed", Boolean.FALSE).put("timedOut", Boolean.TRUE) //$NON-NLS-1$ //$NON-NLS-2$
					.put("id", wanted) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"The handler had not returned within %d seconds. With handlerFinished false this most likely means it opened a modal dialog and is holding the UI thread waiting for an answer; use eclipse_list_ui_targets to see the dialog and eclipse_dismiss_dialog to answer it. With handlerFinished true the handler already reached its verdict and was merely slow. If the command finishes after this answer, what it did is written to the Error Log rather than lost." //$NON-NLS-1$
									.formatted(Long.valueOf(timeoutSeconds))));
		}

		private static void logLateCompletion(String wanted, String answer, Throwable error) {
			String outcome = error != null ? "it failed with: " + error //$NON-NLS-1$
					: answer == null ? "no result" : "its answer was: " + cap(answer); //$NON-NLS-1$ //$NON-NLS-2$
			ILog.get().log(new Status(IStatus.INFO, McpUiPlugin.PLUGIN_ID,
					"eclipse_run_workbench_command: '%s' finished after its call had already timed out; the caller never saw this answer. %s" //$NON-NLS-1$
							.formatted(wanted, outcome)));
		}
	}

	/**
	 * Exact id, then exact name, then substring over id and name, stopping at the
	 * first step that finds anything, the way {@link ViewTools#match} orders it.
	 */
	static List<Command> match(ICommandService service, String wanted) {
		Command[] all = service.getDefinedCommands();
		for (Command command : all) {
			if (command.getId().equals(wanted)) {
				return List.of(command);
			}
		}
		List<Command> byName = new ArrayList<>();
		for (Command command : all) {
			String name = nameOf(command);
			if (name != null && wanted.equalsIgnoreCase(name)) {
				byName.add(command);
			}
		}
		if (!byName.isEmpty()) {
			return byName;
		}
		String needle = wanted.toLowerCase(Locale.ROOT);
		List<Command> partial = new ArrayList<>();
		for (Command command : all) {
			if (contains(command.getId(), needle) || contains(nameOf(command), needle)) {
				partial.add(command);
			}
		}
		return partial;
	}

	static String nameOf(Command command) {
		try {
			return command.getName();
		} catch (NotDefinedException e) {
			return null;
		}
	}

	private static String descriptionOf(Command command) {
		try {
			return command.getDescription();
		} catch (NotDefinedException e) {
			return null;
		}
	}

	private static String categoryOf(Command command) {
		try {
			return command.getCategory().getName();
		} catch (NotDefinedException e) {
			return null;
		}
	}

	/**
	 * The parameters of a command, empty for one that declares none.
	 * <p>
	 * {@code Command.getParameters()} returns null rather than an empty array in
	 * that case, and most workbench commands declare none: every toggle, every
	 * Expand All. Reading the length unguarded therefore failed for the majority
	 * of commands and worked for the parameterised few.
	 */
	public static IParameter[] parametersFor(Command command) throws NotDefinedException {
		IParameter[] declared = command.getParameters();
		return declared == null ? new IParameter[0] : declared;
	}

	private static JsonArray parametersOf(Command command) {
		JsonArray parameters = new JsonArray();
		try {
			for (IParameter parameter : parametersFor(command)) {
				parameters.add(new JsonObject().put("id", parameter.getId()).put("name", parameter.getName()) //$NON-NLS-1$ //$NON-NLS-2$
						.put("optional", Boolean.valueOf(parameter.isOptional()))); //$NON-NLS-1$
			}
		} catch (NotDefinedException e) {
			// an undefined command has no parameters to report
		}
		return parameters;
	}

	private static boolean contains(String text, String needle) {
		return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
	}

	private static Map<String, String> parameterMap(Object raw) {
		if (!(raw instanceof Map<?, ?> map)) {
			return Map.of();
		}
		Map<String, String> parameters = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			parameters.put(String.valueOf(entry.getKey()),
					entry.getValue() == null ? null : String.valueOf(entry.getValue()));
		}
		return parameters;
	}

	private static String refusalFor(String commandId) {
		if (EXIT.equals(commandId)) {
			return "Refused: %s ends the IDE, and this server with it, and nothing outside the machine could undo that.".formatted(EXIT); //$NON-NLS-1$
		}
		if (RESTART_WORKBENCH.equals(commandId)) {
			return "Refused: %s restarts the IDE. Use eclipse_restart instead, which shuts the server down in an orderly way and relaunches into the same workspace.".formatted(RESTART_WORKBENCH); //$NON-NLS-1$
		}
		return null;
	}

	private static String cap(String value) {
		return value.length() <= RETURN_VALUE_LIMIT ? value : value.substring(0, RETURN_VALUE_LIMIT) + "..."; //$NON-NLS-1$
	}

	private static Long elapsed(long started) {
		return Long.valueOf((System.nanoTime() - started) / 1_000_000);
	}
}
