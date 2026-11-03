package com.vogella.eclipse.mcp.ui.internal;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.core.runtime.ILog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Watches a workbench close for a dialog the product raises inside it, such as
 * an exit confirmation from {@code WorkbenchAdvisor.preShutdown}.
 * <p>
 * Such a dialog runs a nested event loop, so these probes still fire. With
 * {@code force} its Yes or OK button is pressed; otherwise the dialog is reported
 * as holding the close, since the answer to the caller has already gone out.
 */
public final class CloseWatch {

	private static final int PROBE_MILLIS = 500;

	private final Display display;
	private final Set<Shell> seen;
	private final boolean force;
	private final String action;
	private final Consumer<JsonObject> onHeld;
	private final JsonArray answered = new JsonArray();
	private Shell candidate;
	private Shell pressed;
	private boolean stopped;

	private CloseWatch(Display display, boolean force, String action, Consumer<JsonObject> onHeld) {
		this.display = display;
		this.seen = new HashSet<>(List.of(display.getShells()));
		this.force = force;
		this.action = action;
		this.onHeld = onHeld;
	}

	/** Starts watching for dialogs that are not open yet, on the UI thread. */
	public static CloseWatch start(Display display, boolean force, String action, Consumer<JsonObject> onHeld) {
		CloseWatch watch = new CloseWatch(display, force, action, onHeld);
		display.timerExec(PROBE_MILLIS, watch::probe);
		return watch;
	}

	public void stop() {
		stopped = true;
	}

	/** The dialogs this watch answered, as title and button. */
	public JsonArray answered() {
		return answered;
	}

	private void probe() {
		if (stopped || display.isDisposed()) {
			return;
		}
		display.timerExec(PROBE_MILLIS, this::probe);
		// a press the dialog survives, such as a refused validation, leaves it holding the close
		if (pressed != null && !pressed.isDisposed() && pressed.isVisible()) {
			reportHeld(pressed);
		}
		pressed = null;
		Shell found = Shells.newDialog(display, seen);
		// seen on two probes in a row, so a dialog that closes itself is not reported
		if (found != null && found == candidate) {
			seen.add(found);
			handle(found);
			found = null;
		}
		candidate = found;
	}

	private void handle(Shell dialog) {
		String title = dialog.getText();
		Button affirmative = force ? affirmative(dialog) : null;
		if (affirmative != null) {
			String label = DismissDialogTool.label(affirmative);
			answered.add(new JsonObject().put("dialog", title).put("pressed", label)); //$NON-NLS-1$ //$NON-NLS-2$
			ILog.get().info("eclipse_%s force answered the dialog '%s' with %s.".formatted(action, title, label)); //$NON-NLS-1$
			DismissDialogTool.press(affirmative);
			pressed = dialog;
			return;
		}
		reportHeld(dialog);
	}

	private void reportHeld(Shell dialog) {
		String title = dialog.getText();
		JsonArray buttons = new JsonArray();
		DismissDialogTool.collectButtons(dialog, buttons);
		String reason = "The dialog '%s' opened during the %s and is holding it, so the IDE stays up until it is answered; read it with eclipse_list_ui_targets and answer it with eclipse_dismiss_dialog." //$NON-NLS-1$
				.formatted(title, action);
		onHeld.accept(new JsonObject().put("at", Instant.now().toString()) //$NON-NLS-1$
				.put("reason", reason) //$NON-NLS-1$
				.put("heldByDialog", title) //$NON-NLS-1$
				.put("buttons", buttons)); //$NON-NLS-1$
		ILog.get().error(reason);
	}

	/** The enabled Yes or OK button, by JFace's own localized labels, or {@code null}. */
	private static Button affirmative(Shell dialog) {
		for (String label : List.of(IDialogConstants.YES_LABEL, IDialogConstants.OK_LABEL)) {
			Button button = DismissDialogTool.findButton(dialog, label.replace("&", "")); //$NON-NLS-1$ //$NON-NLS-2$
			if (button != null && button.isEnabled() && button.isVisible()) {
				return button;
			}
		}
		return null;
	}
}
