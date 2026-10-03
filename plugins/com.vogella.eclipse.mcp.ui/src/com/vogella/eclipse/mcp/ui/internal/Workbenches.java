package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.IWorkbench;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * The running workbench, whether it is the IDE's 3.x workbench or a pure E4 application's.
 * <p>
 * {@link PlatformUI} only knows the 3.x workbench, so an E4 RCP application has a Display, windows and parts while
 * {@code PlatformUI.isWorkbenchRunning()} answers false. The E4 workbench registers itself as an OSGi service, and
 * its application model is what the SWT and model tools need.
 */
public final class Workbenches {

	/** The refusal of a tool that needs the IDE's workbench when only an E4 one is running. */
	static final String NEEDS_IDE = "This tool needs the Eclipse IDE workbench (org.eclipse.ui), and this application runs a pure E4 workbench. The widget, screenshot, keyboard, menu, dialog and part tools work there; the ones built on editors, views, perspectives and workbench commands do not."; //$NON-NLS-1$

	private Workbenches() {
	}

	/** Whether the IDE's 3.x workbench is running. */
	static boolean ide() {
		return PlatformUI.isWorkbenchRunning();
	}

	/** Whether any workbench with a Display is running. */
	public static boolean running() {
		return display() != null;
	}

	private static volatile Display e4Display;

	/** The workbench's Display, or null when none is running. */
	public static Display display() {
		if (ide()) {
			return PlatformUI.getWorkbench().getDisplay();
		}
		Display known = e4Display;
		if (known != null && !known.isDisposed()) {
			return known;
		}
		for (Shell shell : e4WindowShells()) {
			if (!shell.isDisposed()) {
				e4Display = shell.getDisplay();
				return e4Display;
			}
		}
		return null;
	}

	/** Why nothing ran: no workbench at all, or only one that is not the IDE's. */
	public static String noIde() {
		return running() ? NEEDS_IDE : UiThread.NO_WORKBENCH;
	}

	/** The failure message for an exception, naming the missing IDE workbench rather than printing its exception. */
	static String describe(Throwable e) {
		if (e instanceof IllegalStateException && fromPlatformUi(e) && !ide() && running()) {
			return NEEDS_IDE;
		}
		return "The request failed: " + e; //$NON-NLS-1$
	}

	/** Whether PlatformUI threw it, since a tool's own IllegalStateException carries its own explanation. */
	private static boolean fromPlatformUi(Throwable e) {
		for (StackTraceElement frame : e.getStackTrace()) {
			if (PlatformUI.class.getName().equals(frame.getClassName())) {
				return true;
			}
		}
		return false;
	}

	/** The shells of the workbench windows, the IDE's or the E4 application's. */
	static List<Shell> windowShells() {
		if (ide()) {
			List<Shell> shells = new ArrayList<>();
			for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
				shells.add(window.getShell());
			}
			return shells;
		}
		return e4WindowShells();
	}

	/** The active workbench window's shell, or null. */
	static Shell activeWindowShell() {
		if (ide()) {
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			return window == null ? null : window.getShell();
		}
		MApplication application = e4Application();
		if (application != null && application.getSelectedElement() != null
				&& application.getSelectedElement().getWidget() instanceof Shell shell && !shell.isDisposed()) {
			return shell;
		}
		List<Shell> shells = e4WindowShells();
		return shells.isEmpty() ? null : shells.get(0);
	}

	/** The rendered parts of an E4 application model, empty under the IDE's workbench. */
	static List<MPart> e4Parts() {
		MApplication application = ide() ? null : e4Application();
		if (application == null) {
			return List.of();
		}
		EModelService models = application.getContext().get(EModelService.class);
		if (models == null) {
			return List.of();
		}
		List<MPart> parts = new ArrayList<>();
		for (MPart part : models.findElements(application, null, MPart.class)) {
			if (part.getWidget() instanceof Control) {
				parts.add(part);
			}
		}
		return parts;
	}

	/** The control of the E4 part with this element id, or null. */
	static Control e4Part(String elementId) {
		for (MPart part : e4Parts()) {
			if (elementId.equals(part.getElementId()) && part.getWidget() instanceof Control control
					&& !control.isDisposed()) {
				return control;
			}
		}
		return null;
	}

	private static List<Shell> e4WindowShells() {
		MApplication application = e4Application();
		List<Shell> shells = new ArrayList<>();
		if (application != null) {
			for (MWindow window : application.getChildren()) {
				collect(window, shells);
			}
		}
		return shells;
	}

	private static void collect(MWindow window, List<Shell> shells) {
		if (window.getWidget() instanceof Shell shell && !shell.isDisposed()) {
			shells.add(shell);
		}
		for (MWindow child : window.getWindows()) {
			collect(child, shells);
		}
	}

	private static MApplication e4Application() {
		BundleContext context = FrameworkUtil.getBundle(Workbenches.class).getBundleContext();
		if (context == null) {
			return null;
		}
		ServiceReference<IWorkbench> reference = context.getServiceReference(IWorkbench.class);
		if (reference == null) {
			return null;
		}
		IWorkbench workbench = context.getService(reference);
		try {
			return workbench == null ? null : workbench.getApplication();
		} finally {
			context.ungetService(reference);
		}
	}
}
