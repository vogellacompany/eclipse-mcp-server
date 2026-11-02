package com.vogella.eclipse.mcp.ui.internal;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.UIEvents;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

import com.vogella.eclipse.mcp.core.StartupTimeline;
import com.vogella.eclipse.mcp.core.StartupTimeline.Phase;

/**
 * Observes the workbench coming up, for the startup timing tool.
 * <p>
 * Registers on the event bus only and first touches SWT inside a window's own event on the UI thread: loading SWT from
 * another thread during startup races {@code Device}'s static initializer, which loads {@code Display}, and can hang
 * the IDE. That is why nothing here can see a shell opened before the first {@code MWindow} is rendered.
 */
public final class StartupUiCapture implements EventHandler {

	private static final String WORKBENCH_SERVICE = "org.eclipse.e4.ui.workbench.IWorkbench"; //$NON-NLS-1$

	private final StartupTimeline timeline;

	private final AtomicReference<ServiceRegistration<EventHandler>> registration = new AtomicReference<>();

	private volatile WindowWatch watch;

	public StartupUiCapture(StartupTimeline timeline) {
		this.timeline = timeline;
	}

	/** Starts listening, and records whether a workbench was already up, in which case its early events have passed. */
	public void start(BundleContext context) {
		boolean workbenchAlreadyThere = context.getServiceReference(WORKBENCH_SERVICE) != null;
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put(EventConstants.EVENT_TOPIC,
				new String[] { UIEvents.UIElement.TOPIC_WIDGET, UIEvents.UILifeCycle.APP_STARTUP_COMPLETE });
		registration.set(context.registerService(EventHandler.class, this, properties));
		timeline.uiCaptureStarted(workbenchAlreadyThere);
	}

	void stop() {
		ServiceRegistration<EventHandler> current = registration.getAndSet(null);
		if (current != null) {
			current.unregister();
		}
	}

	/** Stops listening and ends a live watch, whatever startup has reached, so nothing stays on the Display. */
	public void deactivate() {
		stop();
		WindowWatch current = watch;
		if (current != null) {
			current.endOnUiThread();
		}
	}

	@Override
	public void handleEvent(org.osgi.service.event.Event event) {
		if (UIEvents.UILifeCycle.APP_STARTUP_COMPLETE.equals(event.getTopic())) {
			timeline.mark(Phase.WORKBENCH_STARTUP_COMPLETE,
					"UIEvents.UILifeCycle.APP_STARTUP_COMPLETE, which the rendering engine posts after applicationRunning; delivered asynchronously, so a moment after the fact, and before early startup extensions have run."); //$NON-NLS-1$
			stop();
			// a shell that stays hidden or minimized never paints; without a watch this reaches no SWT class
			WindowWatch current = watch;
			if (current != null) {
				current.endIfNotShowing();
			}
			return;
		}
		if (event.getProperty(UIEvents.EventTags.ELEMENT) instanceof MWindow
				&& event.getProperty(UIEvents.EventTags.NEW_VALUE) instanceof Shell shell
				&& shell.getDisplay().getThread() == Thread.currentThread() && timeline.mark(Phase.FIRST_SHELL,
						"The widget event of the first MWindow, when the renderer has created its Shell. The native splash and a workspace chooser come before this and are not seen.")) { //$NON-NLS-1$
			watch = new WindowWatch(shell);
			watch.start();
		}
	}

	/** Waits for the window's first Show and then its first Paint. */
	private final class WindowWatch implements Listener {

		private final Shell shell;

		private final Display display;

		WindowWatch(Shell shell) {
			this.shell = shell;
			display = shell.getDisplay();
		}

		void start() {
			shell.addListener(SWT.Show, this);
			shell.addListener(SWT.Dispose, this);
		}

		@Override
		public void handleEvent(Event event) {
			switch (event.type) {
			case SWT.Show -> {
				timeline.mark(Phase.MAIN_WINDOW_VISIBLE,
						"SWT.Show on the first MWindow's Shell, which SWT sends right before it maps the native window."); //$NON-NLS-1$
				shell.removeListener(SWT.Show, this);
				shell.getDisplay().addFilter(SWT.Paint, this);
			}
			case SWT.Paint -> {
				if (event.widget instanceof Control control && !control.isDisposed() && control.getShell() == shell) {
					timeline.mark(Phase.MAIN_WINDOW_FIRST_PAINT,
							"The first SWT.Paint of a control in the first MWindow's Shell, the earliest moment it has pixels of its own on screen."); //$NON-NLS-1$
					end();
				}
			}
			default -> end();
			}
		}

		void endIfNotShowing() {
			onUiThread(() -> {
				if (!shell.isDisposed() && (!shell.isVisible() || shell.getMinimized())) {
					end();
				}
			});
		}

		void endOnUiThread() {
			onUiThread(this::end);
		}

		private void onUiThread(Runnable runnable) {
			try {
				if (!display.isDisposed()) {
					display.asyncExec(runnable);
				}
			} catch (SWTException e) {
				// disposed in between, which took the shell and the filter with it
			}
		}

		private void end() {
			watch = null;
			if (!display.isDisposed()) {
				display.removeFilter(SWT.Paint, this);
			}
			if (!shell.isDisposed()) {
				shell.removeListener(SWT.Show, this);
				shell.removeListener(SWT.Dispose, this);
			}
		}
	}
}
