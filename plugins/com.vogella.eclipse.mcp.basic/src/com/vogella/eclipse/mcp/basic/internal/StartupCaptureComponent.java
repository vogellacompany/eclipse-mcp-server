package com.vogella.eclipse.mcp.basic.internal;

import java.util.ArrayList;
import java.util.List;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.startlevel.FrameworkStartLevel;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

import com.vogella.eclipse.mcp.core.StartupTimeline;
import com.vogella.eclipse.mcp.core.StartupTimeline.Phase;

/**
 * Observes the framework's own startup events for {@link GetStartupTimingTool}, none of which leaves a record once
 * it has passed.
 * <p>
 * Runs nested in the framework's startup, so it refers to no class of another bundle beyond the OSGi API and core's
 * {@link StartupTimeline}, which holds no OSGi or Platform reference.
 */
@Component(immediate = true)
public final class StartupCaptureComponent {

	private static final String APPLICATION_HANDLE = "org.osgi.service.application.ApplicationHandle"; //$NON-NLS-1$

	private static final String APPLICATION_LAUNCHER = "org.eclipse.osgi.service.runnable.ApplicationLauncher"; //$NON-NLS-1$

	private static final String LOCATION = "org.eclipse.osgi.service.datalocation.Location"; //$NON-NLS-1$

	private static final String WORKSPACE_FILTER = "(&(objectClass=" + LOCATION //$NON-NLS-1$
			+ ")(type=osgi.instance.area)(url=*))"; //$NON-NLS-1$

	private static final String APPLICATION_CLAUSES = "(objectClass=" + APPLICATION_HANDLE + ")(objectClass=" //$NON-NLS-1$ //$NON-NLS-2$
			+ APPLICATION_LAUNCHER + ")"; //$NON-NLS-1$

	private static final String STATE = "application.state"; //$NON-NLS-1$

	private static final String RUNNING = "RUNNING"; //$NON-NLS-1$

	/** {@code EclipseStarter.DEFAULT_INITIAL_STARTLEVEL}, used when {@code osgi.startLevel} is not set. */
	private static final int DEFAULT_START_LEVEL = 6;

	private final StartupTimeline timeline;

	private BundleContext context;

	private final SynchronousBundleListener bundles = this::bundleChanged;

	private final ServiceListener services = this::serviceChanged;

	private final FrameworkListener framework = this::frameworkEvent;

	public StartupCaptureComponent() {
		this(StartupTimeline.getDefault());
	}

	/** Writes to the given timeline, so a test does not touch the one of this process. */
	public StartupCaptureComponent(StartupTimeline timeline) {
		this.timeline = timeline;
	}

	@Activate
	void activate(BundleContext bundleContext) throws InvalidSyntaxException {
		context = bundleContext;
		// listening before looking, so nothing falls between the two; an observation beats a missed note
		context.addBundleListener(bundles);
		context.addFrameworkListener(framework);
		context.addServiceListener(services, "(|" + WORKSPACE_FILTER + APPLICATION_CLAUSES + ")"); //$NON-NLS-1$ //$NON-NLS-2$

		List<String> active = new ArrayList<>();
		for (Bundle bundle : context.getBundles()) {
			if (bundle.getState() == Bundle.ACTIVE) {
				active.add(name(bundle));
			}
		}
		timeline.frameworkCaptureStarted(active);

		if (startLevelReached()) {
			timeline.missed(Phase.FRAMEWORK_READY, "The target start level had been reached when the listener started."); //$NON-NLS-1$
		}
		if (context.getServiceReferences(LOCATION, WORKSPACE_FILTER) != null) {
			timeline.missed(Phase.WORKSPACE_LOCATION_SET,
					"The workspace location was already set when the listener started, for example through -data, so no workspace chooser can follow."); //$NON-NLS-1$
		}
		if (context.getServiceReferences(APPLICATION_LAUNCHER, null) != null) {
			timeline.missed(Phase.APPLICATION_START, "The ApplicationLauncher service was already registered when the listener started, so the application had started or was about to."); //$NON-NLS-1$
		}
		ServiceReference<?>[] handles = context.getServiceReferences(APPLICATION_HANDLE, null);
		if (handles != null) {
			for (ServiceReference<?> handle : handles) {
				if (RUNNING.equals(handle.getProperty(STATE))) {
					timeline.missed(Phase.APPLICATION_RUNNING,
							"The application had reported itself running when the listener started."); //$NON-NLS-1$
				}
			}
		}
	}

	@Deactivate
	void deactivate() {
		context.removeBundleListener(bundles);
		context.removeFrameworkListener(framework);
		context.removeServiceListener(services);
	}

	private void bundleChanged(BundleEvent event) {
		Bundle bundle = event.getBundle();
		switch (event.getType()) {
		case BundleEvent.STARTING -> timeline.bundleStarting(bundle.getBundleId(), name(bundle),
				bundle.getVersion().toString());
		case BundleEvent.STARTED -> timeline.bundleStarted(bundle.getBundleId(), name(bundle));
		case BundleEvent.STOPPED -> timeline.bundleStopped(bundle.getBundleId());
		default -> {
			// lazy activation and the rest say nothing about an activator's time
		}
		}
	}

	/** A bundle without a symbolic name is legal for a version 1 manifest. */
	private static String name(Bundle bundle) {
		String name = bundle.getSymbolicName();
		return name != null ? name : "bundle " + bundle.getBundleId(); //$NON-NLS-1$
	}

	private void frameworkEvent(FrameworkEvent event) {
		if ((event.getType() == FrameworkEvent.STARTLEVEL_CHANGED || event.getType() == FrameworkEvent.STARTED)
				&& startLevelReached()) {
			timeline.mark(Phase.FRAMEWORK_READY,
					"FrameworkEvent " + (event.getType() == FrameworkEvent.STARTED ? "STARTED" : "STARTLEVEL_CHANGED") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							+ " at start level " + targetStartLevel() //$NON-NLS-1$
							+ ", when the bundles of that level have been started; delivered asynchronously, so a moment after the fact."); //$NON-NLS-1$
		}
	}

	/** Records the phase that an event of the Location, ApplicationLauncher or ApplicationHandle service stands for. */
	public void serviceChanged(ServiceEvent event) {
		if (event.getType() == ServiceEvent.UNREGISTERING) {
			return;
		}
		ServiceReference<?> reference = event.getServiceReference();
		if (isService(reference, LOCATION)) {
			timeline.mark(Phase.WORKSPACE_LOCATION_SET,
					"Service event on the instance Location gaining its url; a workspace chooser, if one was shown, closed just before this."); //$NON-NLS-1$
			return;
		}
		// the default application's handle is registered long before it runs, so its registration says nothing
		if (event.getType() == ServiceEvent.REGISTERED && isService(reference, APPLICATION_LAUNCHER)) {
			timeline.mark(Phase.APPLICATION_START,
					"Registration of the ApplicationLauncher service, which EclipseStarter does right before it runs the application on the main thread."); //$NON-NLS-1$
		}
		if (RUNNING.equals(reference.getProperty(STATE))) {
			timeline.mark(Phase.APPLICATION_RUNNING, "The ApplicationHandle of " //$NON-NLS-1$
					+ reference.getProperty("application.descriptor") //$NON-NLS-1$
					+ " reaching RUNNING, which IApplicationContext.applicationRunning sets right before the splash is taken down."); //$NON-NLS-1$
		}
	}

	private static boolean isService(ServiceReference<?> reference, String objectClass) {
		return List.of((String[]) reference.getProperty(Constants.OBJECTCLASS)).contains(objectClass);
	}

	private boolean startLevelReached() {
		FrameworkStartLevel level = context.getBundle(0).adapt(FrameworkStartLevel.class);
		return level != null && level.getStartLevel() >= targetStartLevel();
	}

	private int targetStartLevel() {
		String level = context.getProperty("osgi.startLevel"); //$NON-NLS-1$
		if (level == null) {
			level = context.getProperty("org.osgi.framework.startlevel.beginning"); //$NON-NLS-1$
		}
		try {
			return level == null ? DEFAULT_START_LEVEL : Integer.parseInt(level.trim());
		} catch (NumberFormatException e) {
			return DEFAULT_START_LEVEL;
		}
	}
}
