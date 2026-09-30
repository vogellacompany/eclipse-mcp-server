package com.vogella.eclipse.mcp.ui.internal;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.vogella.eclipse.mcp.core.ToolArguments;

/**
 * Picks a shell without depending on its title, which several shells share.
 * <p>
 * A content assist popup, a tooltip and the main window all report the same
 * empty or repeated title, so a substring match cannot tell them apart. This
 * resolves the {@code shell} argument against the order {@code eclipse_list_ui_targets}
 * prints, so an index or the bounds it shows addresses one exactly.
 */
final class Shells {

	private static final Pattern INDEX = Pattern.compile("#?(\\d+)"); //$NON-NLS-1$
	private static final Pattern BOUNDS = Pattern.compile("(-?\\d+),(-?\\d+)\\s+(\\d+)x(\\d+)"); //$NON-NLS-1$

	private Shells() {
	}

	/** The {@code shell} argument, else {@code shellTitle}, which is what {@link #select} resolves. */
	static String spec(ToolArguments args) {
		String shell = args.getString("shell"); //$NON-NLS-1$
		return shell != null ? shell : args.getString("shellTitle"); //$NON-NLS-1$
	}

	/** The shells in the order {@code eclipse_list_ui_targets} reports them. */
	static List<Shell> ordered(Display display) {
		return List.of(display.getShells());
	}

	/**
	 * The shell a {@code shell} argument names, or {@code null}. Accepts an index
	 * into the list ({@code "1"} or {@code "#1"}), the bounds as printed
	 * ({@code "151,334 402x255"}), the keyword {@code "popup"} for the topmost
	 * visible non-workbench shell, or a title substring. {@code null} or blank
	 * falls back to the modal dialog, then the active window.
	 */
	static Shell select(Display display, String spec) {
		if (spec == null || spec.isBlank()) {
			return active(display);
		}
		String value = spec.strip();
		if ("popup".equalsIgnoreCase(value)) { //$NON-NLS-1$
			return popup(display);
		}
		Matcher index = INDEX.matcher(value);
		if (index.matches()) {
			List<Shell> shells = ordered(display);
			int i = Integer.parseInt(index.group(1));
			return i >= 0 && i < shells.size() ? shells.get(i) : null;
		}
		Matcher bounds = BOUNDS.matcher(value);
		if (bounds.matches()) {
			int x = Integer.parseInt(bounds.group(1));
			int y = Integer.parseInt(bounds.group(2));
			int w = Integer.parseInt(bounds.group(3));
			int h = Integer.parseInt(bounds.group(4));
			for (Shell shell : ordered(display)) {
				var b = shell.getBounds();
				if (b.x == x && b.y == y && b.width == w && b.height == h) {
					return shell;
				}
			}
			return null;
		}
		for (Shell shell : ordered(display)) {
			if (shell.getText() != null && shell.getText().contains(value)) {
				return shell;
			}
		}
		return null;
	}

	/** The blocking modal dialog, else the active shell, else the workbench window. */
	static Shell active(Display display) {
		for (Shell shell : display.getShells()) {
			if (shell.isVisible() && isModal(shell)) {
				return shell;
			}
		}
		Shell activeShell = display.getActiveShell();
		if (activeShell != null) {
			return activeShell;
		}
		return Workbenches.activeWindowShell();
	}

	/** The topmost visible shell that is neither a workbench window nor modal. */
	private static Shell popup(Display display) {
		Shell withTable = null;
		Shell any = null;
		for (Shell shell : ordered(display)) {
			if (!shell.isVisible() || isWorkbench(shell) || isModal(shell)) {
				continue;
			}
			any = shell;
			if (firstControl(shell) instanceof org.eclipse.swt.widgets.Table) {
				withTable = shell;
			}
		}
		// a proposal popup carries a Table, which is the one a caller usually means;
		// fall back to the last visible non-workbench shell otherwise
		return withTable != null ? withTable : any;
	}

	static boolean isModal(Shell shell) {
		return (shell.getStyle() & (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL)) != 0;
	}

	static boolean isWorkbench(Shell shell) {
		return Workbenches.windowShells().contains(shell);
	}

	/** workbench, dialog or popup, which is what tells the proposal popup apart. */
	static String kind(Shell shell) {
		if (isWorkbench(shell)) {
			return "workbench"; //$NON-NLS-1$
		}
		return isModal(shell) ? "dialog" : "popup"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * The first control worth naming inside the shell, so a proposal Table, a
	 * StyledText or a Browser is recognisable without reading the whole tree.
	 */
	static Control firstControl(Composite parent) {
		for (Control child : parent.getChildren()) {
			if (isNotable(child)) {
				return child;
			}
			if (child instanceof Composite composite) {
				Control found = firstControl(composite);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static boolean isNotable(Control control) {
		return control instanceof org.eclipse.swt.widgets.Table || control instanceof org.eclipse.swt.widgets.Tree
				|| control instanceof StyledText || control instanceof org.eclipse.swt.browser.Browser
				|| control instanceof org.eclipse.swt.widgets.Text;
	}

	/** The class name of the first notable control, or {@code null}. */
	static String firstControlName(Shell shell) {
		Control control = firstControl(shell);
		return control == null ? null : control.getClass().getSimpleName();
	}
}
