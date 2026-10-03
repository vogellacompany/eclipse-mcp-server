package com.vogella.eclipse.mcp.ui.internal;

import java.time.Instant;
import java.util.Map;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Shuts the IDE down, after answering.
 */
public final class ExitTool implements IMcpTool {

	private static final long UI_TIMEOUT_SECONDS = 10;

	/** Long enough for the HTTP response to be on the wire before the server dies with the IDE. */
	private static final int EXIT_DELAY_MILLIS = 2000;

	/**
	 * What the last attempt came to, when it did not take.
	 * <p>
	 * Same reason as {@code eclipse_restart}: the answer goes out before the
	 * workbench closes, so a veto is invisible unless it is kept for the next
	 * call. Unlike a restart there may be no next call, so this is logged as well.
	 */
	private static volatile JsonObject lastFailure;

	@Override
	public String getName() {
		return "eclipse_exit"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "SHUTS THE IDE DOWN. The process ends and this server ends with it, so nothing here can bring it back: starting it again is the caller's job, from outside, which is what makes this different from eclipse_restart. Use it to take down a throwaway IDE a harness started; do not use it on an IDE somebody is working in. THE ANSWER REPORTS WHAT WAS REQUESTED, NOT THE OUTCOME, because the server cannot outlive the shutdown it is reporting on: it answers first and closes a couple of seconds later, so a dropped connection right after a successful result is the expected outcome. Confirm the exit from outside, by watching the process or the port rather than by asking this server. Refuses when anything is unsaved or a modal dialog is open, naming which of the two fired, unless save or force is passed; the check covers every dirty part in every window, which is the set the platform itself would prompt for, not one page's editors. IWorkbench.close is a cancellable close that prompts for every dirty part, and a veto leaves the JVM up, so on an IDE nobody is watching an unguarded exit stalls in an invisible dialog rather than failing. force DISCARDS unsaved work outright, closing dirty editors without saving, so the platform has nothing left to prompt about. A blocking dialog is better cleared with eclipse_dismiss_dialog than forced past. Builds are cancelled and launches this server started are terminated first, the same way a restart clears them, because a launched JVM that outlives the IDE keeps its workspace lock with nobody left who knows where it came from. An exit the platform vetoes leaves the IDE running and is written to the Error Log, and reported as previousExitFailed by the next call if there is one."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "save":  {"type":"boolean","default":false,"description":"Save dirty editors first, then exit."},
				    "force": {"type":"boolean","default":false,"description":"Exit even with unsaved changes or an open modal dialog. DISCARDS that work: dirty editors are closed without saving and dirty views are marked clean, so the platform has nothing left to prompt about."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return McpToolResult.error(Workbenches.noIde());
		}
		ToolArguments args = ToolArguments.of(arguments);
		boolean save = args.getBoolean("save", false); //$NON-NLS-1$
		boolean force = args.getBoolean("force", false); //$NON-NLS-1$
		// a refusal must arrive as an error, or a client checking only isError reads "exiting: false" as a shutdown in progress
		UiThread.Outcome outcome = UiThread.run(UI_TIMEOUT_SECONDS, () -> prepare(save, force));
		if (outcome.error() != null) {
			return McpToolResult.error(outcome.error());
		}
		JsonObject result = outcome.value();
		return Boolean.TRUE.equals(result.remove("exiting")) ? McpToolResult.of(result.toString()) //$NON-NLS-1$
				: McpToolResult.error(result.toString());
	}

	private static JsonObject prepare(boolean save, boolean force) {
		RestartTool.CloseGuard guard = RestartTool.guard(save, force, "exiting", "exiting"); //$NON-NLS-1$ //$NON-NLS-2$
		if (guard.refusal() != null) {
			JsonObject refusal = guard.refusal().put("exiting", Boolean.FALSE); //$NON-NLS-1$
			addLastFailure(refusal);
			return refusal;
		}
		JsonObject discarded = guard.discarded();
		JsonObject cleared = RestartTool.clearTheWayForShutdown();
		Display display = Workbenches.display();
		lastFailure = null;
		display.timerExec(EXIT_DELAY_MILLIS, ExitTool::performExit);
		JsonObject result = new JsonObject().put("exiting", Boolean.TRUE) //$NON-NLS-1$
				.put("inMillis", Integer.valueOf(EXIT_DELAY_MILLIS)) //$NON-NLS-1$
				.put("cleared", cleared) //$NON-NLS-1$
				.put("discarded", discarded) //$NON-NLS-1$
				.put("savedEditors", Boolean.valueOf(save)) //$NON-NLS-1$
				.put("workspace", RestartTool.workspaceLocationForAnswer()) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"REQUESTED, NOT YET DONE: this answer is sent BEFORE the shutdown, so it reports what was asked for and cannot report the outcome. The server keeps answering for a couple of seconds, so a reachability check right after this succeeds against the process that is about to die; watch the process or the port from outside instead. THIS SERVER CANNOT START THE IDE AGAIN, so whatever launched it has to. An exit the platform refuses leaves the IDE up and is written to the Error Log."); //$NON-NLS-1$
		addLastFailure(result);
		return result;
	}

	/**
	 * Closes the workbench and reports what it came to.
	 * <p>
	 * {@code IWorkbench.close} is cancellable, so a listener veto leaves the JVM
	 * running and the boolean is the only signal. There is nobody left to answer
	 * afterwards, so the log is where this has to go.
	 */
	private static void performExit() {
		boolean closed = false;
		Throwable failure = null;
		try {
			closed = PlatformUI.getWorkbench().close();
		} catch (Throwable e) {
			failure = e;
		}
		if (closed) {
			return;
		}
		String reason = failure != null ? "the workbench threw while closing, which leaves it running" //$NON-NLS-1$
				: "the workbench refused to close, which a save prompt or a listener veto does; the IDE is still up"; //$NON-NLS-1$
		lastFailure = new JsonObject().put("at", Instant.now().toString()) //$NON-NLS-1$
				.put("reason", reason) //$NON-NLS-1$
				.put("cause", failure == null ? null : String.valueOf(failure)); //$NON-NLS-1$
		ILog.get().error("The exit did not take: " + reason, failure); //$NON-NLS-1$
	}

	private static void addLastFailure(JsonObject result) {
		JsonObject failure = lastFailure;
		if (failure != null) {
			result.put("previousExitFailed", failure) //$NON-NLS-1$
					.put("previousExitNote", //$NON-NLS-1$
							"An earlier exit from this tool did not take; the IDE stayed up. Its reason is above."); //$NON-NLS-1$
		}
	}
}
