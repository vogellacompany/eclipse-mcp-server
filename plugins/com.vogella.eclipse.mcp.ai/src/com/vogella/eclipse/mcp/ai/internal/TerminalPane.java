package com.vogella.eclipse.mcp.ai.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.terminal.connector.ITerminalConnector;
import org.eclipse.terminal.connector.InMemorySettingsStore;
import org.eclipse.terminal.connector.TerminalConnectorExtension;
import org.eclipse.terminal.connector.TerminalState;
import org.eclipse.terminal.connector.process.ProcessConnector;
import org.eclipse.terminal.connector.process.ProcessSettings;
import org.eclipse.terminal.control.ITerminalListener;
import org.eclipse.terminal.control.ITerminalViewControl;
import org.eclipse.terminal.control.TerminalTitleRequestor;
import org.eclipse.terminal.control.TerminalViewControlFactory;

/**
 * The terminal of one {@link TerminalSession}; disposing it ends the process.
 */
final class TerminalPane {

	private static final String PROCESS_CONNECTOR = "org.eclipse.terminal.connector.process.ProcessConnector"; //$NON-NLS-1$

	private final TerminalSession session;
	private final Composite composite;
	private ITerminalViewControl terminal;

	/** Creates the terminal and starts its process; {@code ended} runs on the UI thread once the process is gone. */
	TerminalPane(Composite parent, TerminalSession session, Runnable ended) {
		this.session = session;
		composite = new Composite(parent, SWT.NONE);
		FillLayout layout = new FillLayout();
		layout.marginWidth = 8;
		layout.marginHeight = 4;
		composite.setLayout(layout);
		ITerminalConnector connector;
		try {
			connector = connector(session);
		} catch (CoreException e) {
			ILog.of(TerminalPane.class).error("Cannot create the terminal", e); //$NON-NLS-1$
			new Label(composite, SWT.WRAP).setText(e.getStatus().getMessage());
			return;
		}
		ITerminalViewControl control = TerminalViewControlFactory.makeControl(new Listener(ended), composite,
				new ITerminalConnector[] { connector }, true);
		terminal = control;
		// the margin takes the terminal's own colour rather than the theme's
		composite.setBackground(control.getControl().getBackground());
		control.setCharset(StandardCharsets.UTF_8);
		control.setConnector(connector);
		control.connectTerminal();
		ProcessConnector process = connector.getAdapter(ProcessConnector.class);
		if (process != null && process.getProcess() != null && session.kind() == TerminalSession.Kind.CLAUDE) {
			session.setPid(process.getProcess().pid());
		}
		composite.addDisposeListener(event -> control.disposeTerminal());
	}

	private static ITerminalConnector connector(TerminalSession session) throws CoreException {
		List<String> command = session.command();
		ProcessSettings settings = new ProcessSettings();
		settings.setImage(Commands.quote(command.get(0)));
		settings.setArguments(Commands.join(command.subList(1, command.size())));
		settings.setWorkingDir(session.workingDirectory());
		settings.setEnvironment(Commands.environment(System.getenv()));
		settings.setMergeWithNativeEnvironment(false);
		settings.setLocalEcho(false);
		InMemorySettingsStore store = new InMemorySettingsStore();
		settings.save(store);
		ITerminalConnector connector = TerminalConnectorExtension.makeTerminalConnector(PROCESS_CONNECTOR);
		connector.setDefaultSettings();
		connector.load(store);
		return connector;
	}

	TerminalSession session() {
		return session;
	}

	Control control() {
		return composite;
	}

	void setFocus() {
		if (terminal != null && !terminal.isDisposed()) {
			terminal.setFocus();
		}
	}

	void dispose() {
		composite.dispose();
	}

	private final class Listener implements ITerminalListener {

		private final Runnable ended;
		private boolean connected;

		Listener(Runnable ended) {
			this.ended = ended;
		}

		@Override
		public void setState(TerminalState state) {
			if (state == TerminalState.CONNECTED) {
				connected = true;
			}
			if (state != TerminalState.CLOSED || !connected || session.ended()) {
				return;
			}
			session.setEnded();
			Display display = composite.getDisplay();
			if (!display.isDisposed()) {
				display.asyncExec(() -> {
					if (!composite.isDisposed()) {
						ended.run();
					}
				});
			}
			TerminalSessions.changed();
		}

		@Override
		public void setTerminalSelectionChanged() {
			// nothing offers the terminal selection
		}

		@Override
		public void setTerminalTitle(String title, TerminalTitleRequestor requestor) {
			// the session name from the sessions file is the better title
		}
	}
}
