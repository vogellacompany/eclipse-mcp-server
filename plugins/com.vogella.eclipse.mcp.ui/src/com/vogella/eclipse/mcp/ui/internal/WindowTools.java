package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.WorkbenchException;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Opens and closes workbench windows.
 */
public final class WindowTools implements IMcpTool {

	private static final long WINDOW_TIMEOUT_SECONDS = 15;

	@Override
	public String getName() {
		return "eclipse_manage_window"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Opens or closes a workbench window, the way the Window > New Window entry and a window's close box do. CHANGES WHAT THE USER SEES, and writes nothing to the workspace. action 'open' opens one, optionally showing the perspective named by id or label; action 'close' closes one named by title or title substring, or the active window when 'window' is omitted. CLOSING THE LAST WINDOW IS REFUSED unconditionally: it would shut the IDE down and end this server with it, which nothing outside the machine could undo. A closing window whose editors have unsaved changes raises a save prompt, which is a modal dialog like any other, so the wait is capped and running out reports timedOut with a pointer to eclipse_dismiss_dialog. Both actions answer with every window that exists afterwards, whether it is the active one and its bounds."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["action"],
				  "properties": {
				    "action":      {"type":"string","enum":["open","close"],"description":"Open a new workbench window, or close one."},
				    "perspective": {"type":"string","description":"For open: perspective id or label, e.g. org.eclipse.jdt.ui.JavaPerspective or Java. Omit for this installation's default perspective."},
				    "window":      {"type":"string","description":"For close: window title or a substring of it. Omit for the active window."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String action = args.getString("action"); //$NON-NLS-1$
		if (action == null) {
			return McpToolResult.error("The argument 'action' is required, one of open or close."); //$NON-NLS-1$
		}
		if (!"open".equals(action) && !"close".equals(action)) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("Unknown action '%s', expected open or close.".formatted(action)); //$NON-NLS-1$
		}
		UiThread.TimedOutcome outcome = UiThread.timed(WINDOW_TIMEOUT_SECONDS,
				() -> "open".equals(action) ? open(args.getString("perspective")) : close(args.getString("window"))); //$NON-NLS-1$ //$NON-NLS-2$
		if (outcome.error() != null) {
			return McpToolResult.error(outcome.error());
		}
		if (outcome.timedOut()) {
			return McpToolResult.of(timedOut(action).toString());
		}
		return McpToolResult.of(outcome.value().toString());
	}

	private static JsonObject open(String perspectiveWanted) {
		String perspectiveId = null;
		if (perspectiveWanted != null) {
			List<IPerspectiveDescriptor> matches = PerspectiveTools.match(perspectiveWanted);
			if (matches.isEmpty()) {
				return refused("opened", //$NON-NLS-1$
						"No registered perspective matches '%s'. Use eclipse_list_perspectives." //$NON-NLS-1$
								.formatted(perspectiveWanted));
			}
			if (matches.size() > 1) {
				JsonArray candidates = new JsonArray();
				for (IPerspectiveDescriptor descriptor : matches) {
					candidates.add(PerspectiveTools.describe(descriptor));
				}
				return new JsonObject().put("opened", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "'%s' matches %d perspectives; name one of them exactly." //$NON-NLS-1$
								.formatted(perspectiveWanted, Integer.valueOf(matches.size())))
						.put("candidates", candidates).put("windows", windows()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			perspectiveId = matches.get(0).getId();
		} else {
			perspectiveId = PlatformUI.getWorkbench().getPerspectiveRegistry().getDefaultPerspective();
		}
		try {
			IWorkbenchWindow created = PlatformUI.getWorkbench().openWorkbenchWindow(perspectiveId,
					ResourcesPlugin.getWorkspace().getRoot());
			return new JsonObject().put("opened", Boolean.TRUE) //$NON-NLS-1$
					.put("perspective", perspectiveId) //$NON-NLS-1$
					.put("newWindowTitle", created == null || created.getShell() == null ? null //$NON-NLS-1$
							: created.getShell().getText())
					.put("windows", windows()); //$NON-NLS-1$
		} catch (WorkbenchException e) {
			return new JsonObject().put("opened", Boolean.FALSE) //$NON-NLS-1$
					.put("perspective", perspectiveId) //$NON-NLS-1$
					.put("reason", String.valueOf(e.getMessage())).put("windows", windows()); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private static JsonObject close(String wantedTitle) {
		IWorkbenchWindow[] all = PlatformUI.getWorkbench().getWorkbenchWindows();
		if (all.length <= 1) {
			// unconditional: closing the last window shuts the IDE down and ends the
			// server, and no flag can make that recoverable from the client side
			return new JsonObject().put("closed", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "Refused: this is the last workbench window, and closing it would shut the IDE down and end this server with it.") //$NON-NLS-1$ //$NON-NLS-2$
					.put("windows", windows()); //$NON-NLS-1$
		}
		IWorkbenchWindow target;
		if (wantedTitle == null) {
			target = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (target == null) {
				return new JsonObject().put("closed", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "There is no active window; pass 'window' with a title or a substring of one.") //$NON-NLS-1$ //$NON-NLS-2$
						.put("windows", windows()); //$NON-NLS-1$
			}
		} else {
			List<IWorkbenchWindow> matching = new ArrayList<>();
			for (IWorkbenchWindow window : all) {
				Shell shell = window.getShell();
				if (shell != null && wantedTitle.equals(shell.getText())) {
					matching.add(window);
				}
			}
			if (matching.isEmpty()) {
				String needle = wantedTitle.toLowerCase(Locale.ROOT);
				for (IWorkbenchWindow window : all) {
					Shell shell = window.getShell();
					if (shell != null && shell.getText().toLowerCase(Locale.ROOT).contains(needle)) {
						matching.add(window);
					}
				}
			}
			if (matching.isEmpty()) {
				JsonArray candidates = new JsonArray();
				for (IWorkbenchWindow window : all) {
					candidates.add(titles(window));
				}
				return new JsonObject().put("closed", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "No window matching '%s'.".formatted(wantedTitle)) //$NON-NLS-1$ //$NON-NLS-2$
						.put("candidates", candidates).put("windows", windows()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (matching.size() > 1) {
				JsonArray candidates = new JsonArray();
				for (IWorkbenchWindow window : matching) {
					candidates.add(titles(window));
				}
				return new JsonObject().put("closed", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "'%s' matches %d windows; name one of them exactly." //$NON-NLS-1$
								.formatted(wantedTitle, Integer.valueOf(matching.size())))
						.put("candidates", candidates).put("windows", windows()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			target = matching.get(0);
		}
		// captured before the close, because the shell is disposed by it
		String title = target.getShell() == null ? null : target.getShell().getText();
		boolean wasActive = target.equals(PlatformUI.getWorkbench().getActiveWorkbenchWindow());
		boolean closed = target.close();
		JsonObject result = new JsonObject().put("closed", Boolean.valueOf(closed)).put("title", title) //$NON-NLS-1$ //$NON-NLS-2$
				.put("wasActive", Boolean.valueOf(wasActive)).put("windows", windows()); //$NON-NLS-1$ //$NON-NLS-2$
		return closed ? result : result.put("reason", "The window refused to close, for example because a save prompt was cancelled."); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static JsonObject titles(IWorkbenchWindow window) {
		Shell shell = window.getShell();
		return new JsonObject().put("title", shell == null ? null : shell.getText()); //$NON-NLS-1$
	}

	private static JsonObject refused(String flag, String reason) {
		return new JsonObject().put(flag, Boolean.FALSE).put("reason", reason).put("windows", windows()); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static JsonObject timedOut(String action) {
		String note = "close".equals(action) //$NON-NLS-1$
				? "Closing had not finished within %d seconds, most likely because a save prompt for editors with unsaved changes in that window is holding the UI thread. Use eclipse_list_ui_targets to see it and eclipse_dismiss_dialog to answer it; the window may close once it is answered." //$NON-NLS-1$
				: "Opening had not finished within %d seconds, so nothing was reported."; //$NON-NLS-1$
		return new JsonObject().put("timedOut", Boolean.TRUE).put("windows", new JsonArray()) //$NON-NLS-1$ //$NON-NLS-2$
				.put("note", note.formatted(Long.valueOf(WINDOW_TIMEOUT_SECONDS))); //$NON-NLS-1$
	}

	private static JsonArray windows() {
		JsonArray array = new JsonArray();
		IWorkbenchWindow active = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			Shell shell = window.getShell();
			array.add(new JsonObject().put("title", shell == null ? null : shell.getText()) //$NON-NLS-1$
					.put("active", Boolean.valueOf(window.equals(active))) //$NON-NLS-1$
					.put("bounds", shell == null ? null : Overlays.describe(shell.getBounds()))); //$NON-NLS-1$
		}
		return array;
	}
}
