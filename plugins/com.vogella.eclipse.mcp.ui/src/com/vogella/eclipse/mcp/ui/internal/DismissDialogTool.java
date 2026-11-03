package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Closes a dialog that is blocking the IDE.
 */
public final class DismissDialogTool implements IMcpTool {

	private static final long UI_TIMEOUT_SECONDS = 10;

	@Override
	public String getName() {
		return "eclipse_dismiss_dialog"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Closes an open dialog, or presses one of its buttons. PRESSING A BUTTON DOES WHATEVER THAT BUTTON DOES, which may be destructive, so this reports the dialog and its buttons and changes nothing unless dryRun is set to false. With no button named it closes the dialog, which for a JFace dialog is the same as cancelling; that is the safe way to unblock an IDE waiting on a prompt nobody meant to answer. With neither shell nor shellTitle it closes the active modal dialog, or, when none is open, a transient popup such as the content assist proposal list, which has no title and cannot be matched by name, or else a non-modal dialog. Use eclipse_list_ui_targets first to see what is open. A workbench window is refused, since closing one is not dismissing a dialog."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "shell":      {"type":"string","description":"The dialog independent of title: 'popup', an index from eclipse_list_ui_targets, or its bounds. Wins over shellTitle."},
				    "shellTitle": {"type":"string","description":"Title of the dialog, or a substring. Omit both for the active modal dialog."},
				    "button":     {"type":"string","description":"Label of the button to press, mnemonics ignored, case insensitive. Omit to close the dialog, which cancels it."},
				    "dryRun":     {"type":"boolean","default":true,"description":"Report the dialog and its buttons without touching it."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (!Workbenches.running()) {
			return McpToolResult.error("There is no running workbench."); //$NON-NLS-1$
		}
		ToolArguments args = ToolArguments.of(arguments);
		String shellSpec = args.getString("shell"); //$NON-NLS-1$
		String shellTitle = args.getString("shellTitle"); //$NON-NLS-1$
		String button = args.getString("button"); //$NON-NLS-1$
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$

		// a modal runs a nested event loop, so queued runnables still execute
		return UiThread.call(UI_TIMEOUT_SECONDS,
				() -> dismiss(Workbenches.display(), shellSpec, shellTitle, button, dryRun));
	}

	/** Reports or closes the dialog that {@code shellSpec}, else {@code shellTitle}, else the state of the IDE names. */
	public static JsonObject dismiss(Display display, String shellSpec, String shellTitle, String button, boolean dryRun) {
		Shell shell = shellSpec != null ? Shells.select(display, shellSpec) : find(display, shellTitle);
		String named = shellSpec != null ? shellSpec : shellTitle;
		if (shell != null && !shell.isVisible()) {
			return new JsonObject().put("dismissed", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "'%s' is a hidden shell, not an open dialog. Indices can shift between calls, so read them again with eclipse_list_ui_targets.".formatted(named)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (shell == null) {
			return new JsonObject().put("dismissed", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", named == null ? "No modal dialog is open." //$NON-NLS-1$ //$NON-NLS-2$
							: "No dialog matching '%s' is open.".formatted(named)); //$NON-NLS-1$
		}
		if (Shells.isWorkbench(shell)) {
			return new JsonObject().put("dismissed", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "'%s' is a workbench window, not a dialog; use eclipse_manage_window or eclipse_exit for those.".formatted(named)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		JsonArray buttons = new JsonArray();
		collectButtons(shell, buttons);
		JsonObject result = new JsonObject().put("title", shell.getText()) //$NON-NLS-1$
				.put("modal", Shells.isModal(shell)) //$NON-NLS-1$
				.put("kind", Shells.kind(shell)) //$NON-NLS-1$
				.put("buttons", buttons); //$NON-NLS-1$
		if (dryRun) {
			return result.put("dismissed", Boolean.FALSE) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"Nothing was touched. Pass dryRun false, with a button to press it or without one to close the dialog."); //$NON-NLS-1$
		}
		if (button == null) {
			shell.close();
			return result.put("dismissed", Boolean.TRUE).put("action", "closed") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.put("shellClosed", Boolean.valueOf(shell.isDisposed())); //$NON-NLS-1$
		}
		Button target = findButton(shell, button);
		if (target == null) {
			return result.put("dismissed", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "No button labelled '%s' on this dialog.".formatted(button)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (!target.isEnabled() || !target.isVisible()) {
			return result.put("dismissed", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "The button '%s' is disabled or hidden, so a click would not reach it.".formatted(button)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		// read the label before pressing: the press may dispose the button along with the shell
		String pressed = label(target);
		press(target);
		return result.put("dismissed", Boolean.TRUE).put("action", "pressed " + pressed) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("shellClosed", Boolean.valueOf(shell.isDisposed())); //$NON-NLS-1$
	}

	/** Sends the selection event a real click sends to the dialog's listeners. */
	static void press(Button target) {
		if ((target.getStyle() & SWT.CHECK) != 0) {
			target.setSelection(!target.getSelection());
		}
		target.notifyListeners(SWT.Selection, new Event());
	}

	private static Shell find(Display display, String title) {
		if (title == null) {
			// a modal shell is what blocks the IDE, so it wins; the active shell is not guaranteed to be getShells()[0]
			for (Shell shell : display.getShells()) {
				if (shell.isVisible() && Shells.isModal(shell)) {
					return shell;
				}
			}
			// otherwise a transient popup such as content assist; its Table tells it from a tooltip
			for (Shell shell : display.getShells()) {
				if (shell.isVisible() && !Shells.isDialog(shell) && !Shells.isWorkbench(shell) && hasTable(shell)) {
					return shell;
				}
			}
			for (Shell shell : display.getShells()) {
				if (shell.isVisible() && Shells.isDialog(shell) && !Shells.isWorkbench(shell)) {
					return shell;
				}
			}
			return null;
		}
		for (Shell shell : display.getShells()) {
			if (shell.isVisible() && !Workbenches.windowShells().contains(shell) && shell.getText() != null
					&& shell.getText().contains(title)) {
				return shell;
			}
		}
		return null;
	}

	private static boolean hasTable(Composite parent) {
		for (Control child : parent.getChildren()) {
			if (child instanceof Table) {
				return true;
			}
			if (child instanceof Composite composite && hasTable(composite)) {
				return true;
			}
		}
		return false;
	}

	static void collectButtons(Composite parent, JsonArray into) {
		for (Control child : parent.getChildren()) {
			if (child instanceof Button candidate && !label(candidate).isEmpty()) {
				into.add(new JsonObject().put("label", label(candidate)) //$NON-NLS-1$
						.put("enabled", candidate.isEnabled()) //$NON-NLS-1$
						.put("kind", (candidate.getStyle() & SWT.CHECK) != 0 ? "checkbox" : "push")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			if (child instanceof Composite composite) {
				collectButtons(composite, into);
			}
		}
	}

	static Button findButton(Composite parent, String label) {
		for (Control child : parent.getChildren()) {
			if (child instanceof Button candidate && label(candidate).equalsIgnoreCase(label.trim())) {
				return candidate;
			}
			if (child instanceof Composite composite) {
				Button found = findButton(composite, label);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/** The visible label, with the mnemonic ampersand removed. */
	static String label(Button button) {
		String text = button.getText();
		return text == null ? "" : text.replace("&", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}
}
