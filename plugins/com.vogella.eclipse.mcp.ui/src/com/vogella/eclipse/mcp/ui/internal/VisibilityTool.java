package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Takes the IDE off the screen and brings it back.
 * <p>
 * An IDE used as a backend does not need to be looked at, but it does need to
 * keep running: the workbench event loop belongs to the display, not to the
 * window, so an invisible window still builds, still searches and still answers
 * every other tool here.
 * <p>
 * Hiding a window is easy to make unrecoverable, and that is the whole risk of
 * this tool. There is no menu to bring it back with, so the way back is this
 * tool, and the way back from the server being gone is that the plug-in restores
 * every window it hid when it stops.
 */
public final class VisibilityTool implements IMcpTool {

	private static final long UI_TIMEOUT_SECONDS = 10;

	private static final long ACTIVATION_WAIT_MILLIS = 1000;

	private static volatile boolean hiddenByUs;

	@Override
	public String getName() {
		return "eclipse_set_ide_visibility"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Hides the Eclipse window or brings it back, for using the IDE as a backend rather than as something to look at. The IDE keeps running while hidden: builds, searches, tests and every other tool here work unchanged, because the event loop belongs to the display and not to the window. CHANGES WHAT THE USER SEES, in the one way they cannot undo from the IDE itself, since a hidden window has no menu and no taskbar entry. Two modes: 'hidden' removes it from the screen and the taskbar entirely, 'minimized' leaves it reachable by hand and is the safer choice when a person is at the machine. Calling this with visible true restores it, and so does stopping the plug-in, so a hidden IDE is never stranded. While hidden, dialogs are still raised and are still invisible; eclipse_list_ui_targets and eclipse_dismiss_dialog remain the way to see and answer them."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["visible"],
				  "properties": {
				    "visible": {"type":"boolean","description":"False takes the IDE off the screen, true brings it back, unminimizes it and ASKS for focus. The window system may refuse the focus part, so the answer reports 'foreground' for what actually happened rather than promising it, and 'foregroundMethod' for how it was asked: on Windows a plain request is refused to a process that is not already in front, so the raise falls back to attaching to the foreground thread's input queue."},
				    "mode":    {"type":"string","enum":["hidden","minimized"],"default":"hidden","description":"How to take it off the screen. Ignored when visible is true."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		if (arguments == null || !arguments.containsKey("visible")) { //$NON-NLS-1$
			return McpToolResult.error("The argument 'visible' is required."); //$NON-NLS-1$
		}
		boolean visible = args.getBoolean("visible", true); //$NON-NLS-1$
		String mode = args.getString("mode", "hidden"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!"hidden".equals(mode) && !"minimized".equals(mode)) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("Unknown mode '%s', expected 'hidden' or 'minimized'.".formatted(mode)); //$NON-NLS-1$
		}
		List<JsonObject> unconfirmed = new ArrayList<>();
		UiThread.Outcome outcome = UiThread.run(UI_TIMEOUT_SECONDS, () -> apply(visible, mode, unconfirmed));
		if (outcome.error() != null) {
			return McpToolResult.error(outcome.error());
		}
		if (!unconfirmed.isEmpty() && !UiThread.onUiThread() && awaitActivation(monitor)) {
			for (JsonObject entry : unconfirmed) {
				entry.put("foreground", Boolean.TRUE); //$NON-NLS-1$
				entry.remove("foregroundNote"); //$NON-NLS-1$
			}
		}
		return McpToolResult.of(outcome.value().toString());
	}

	/** Waits briefly for the window system to answer the raise, since activation arrives as a later event. */
	private static boolean awaitActivation(IProgressMonitor monitor) {
		long deadline = System.currentTimeMillis() + ACTIVATION_WAIT_MILLIS;
		while (System.currentTimeMillis() < deadline && (monitor == null || !monitor.isCanceled())) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
			UiThread.Outcome active = UiThread.run(UI_TIMEOUT_SECONDS,
					() -> NativeForeground.isForeground(Workbenches.display()) ? new JsonObject() : null);
			if (active.value() != null) {
				return true;
			}
		}
		return false;
	}

	private static JsonObject apply(boolean visible, String mode, List<JsonObject> unconfirmed) {
		JsonArray windows = new JsonArray();
		for (Shell shell : Workbenches.windowShells()) {
			if (shell == null || shell.isDisposed()) {
				continue;
			}
			JsonObject entry = new JsonObject().put("title", shell.getText()) //$NON-NLS-1$
					.put("wasVisible", Boolean.valueOf(shell.isVisible())) //$NON-NLS-1$
					.put("wasMinimized", Boolean.valueOf(shell.getMinimized())); //$NON-NLS-1$
			if (visible) {
				shell.setVisible(true);
				shell.setMinimized(false);
				shell.forceActive();
			} else if ("minimized".equals(mode)) { //$NON-NLS-1$
				shell.setMinimized(true);
			} else {
				// minimized first would leave a taskbar entry behind on some window
				// managers, which is exactly the state this mode is asked to remove
				shell.setMinimized(false);
				shell.setVisible(false);
			}
			entry.put("visible", Boolean.valueOf(shell.isVisible())) //$NON-NLS-1$
					.put("minimized", Boolean.valueOf(shell.getMinimized())); //$NON-NLS-1$
			if (visible) {
				// forceActive is a REQUEST that Windows and most window managers refuse, so the call returning says nothing; report what happened
				boolean active = NativeForeground.isForeground(shell.getDisplay());
				String method = "forceActive"; //$NON-NLS-1$
				String nativeRefusal = null;
				if (!active && NativeForeground.isSupported()) {
					nativeRefusal = NativeForeground.raise(shell);
					active = NativeForeground.isForeground(shell.getDisplay());
					if (nativeRefusal == null) {
						method = "attachThreadInput"; //$NON-NLS-1$
					}
				}
				entry.put("foreground", Boolean.valueOf(active)) //$NON-NLS-1$
						.put("foregroundMethod", method); //$NON-NLS-1$
				if (!active) {
					unconfirmed.add(entry);
					entry.put("foregroundNote", nativeRefusal != null //$NON-NLS-1$
							? "Focus was requested, the window system did not grant it, and the native raise that works around the Windows foreground lock could not run: %s. The window is visible and unminimized, but something else is still in front, so a screen read would photograph that instead. eclipse_screenshot reports the same state as 'foreground'." //$NON-NLS-1$
									.formatted(nativeRefusal)
							: "Focus was requested and the window system did not grant it, which it is entitled to do: most window managers refuse the foreground to a process that does not already own it, and on Windows the input-queue attachment that works around it can be refused as well. The window is visible and unminimized, but something else is still in front, so a screen read would photograph that instead. eclipse_screenshot reports the same state as 'foreground'."); //$NON-NLS-1$
				}
			}
			windows.add(entry);
		}
		hiddenByUs = !visible;
		JsonObject result = new JsonObject().put("visible", Boolean.valueOf(visible)) //$NON-NLS-1$
				.put("mode", visible ? null : mode) //$NON-NLS-1$
				.put("windows", windows); //$NON-NLS-1$
		if (windows.size() == 0) {
			return result.put("note", "There is no workbench window to hide or show."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (!visible) {
			result.put("note", //$NON-NLS-1$
					"The IDE is still running and every other tool still works. Call this again with visible true to bring it back; there is no way to do that from the IDE itself while it is hidden. The plug-in also restores the window if it is stopped, so the IDE cannot be left invisible with no server to answer."); //$NON-NLS-1$
		}
		return result;
	}

	/**
	 * Puts the window back before the plug-in goes away, so that disabling or
	 * uninstalling the server cannot leave an IDE nobody can see and nothing can
	 * restore.
	 */
	static void restoreIfHidden() {
		if (!hiddenByUs || !Workbenches.running()) {
			return;
		}
		Display display = Workbenches.display();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.syncExec(() -> {
			for (Shell shell : Workbenches.windowShells()) {
				if (shell != null && !shell.isDisposed()) {
					shell.setVisible(true);
					shell.setMinimized(false);
				}
			}
		});
		hiddenByUs = false;
	}
}
