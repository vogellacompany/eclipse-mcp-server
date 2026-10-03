package com.vogella.eclipse.mcp.debug.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchListener;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IThread;

import com.vogella.eclipse.mcp.core.LaunchAttributes;
import com.vogella.eclipse.mcp.core.LaunchPrompts;

/**
 * Owns the mapping from {@link ILaunch} to debug session id and the last suspend
 * event per target.
 * <p>
 * The launch listener is global, so launches started by hand get an id too.
 * Suspend events are only recorded and signalled on the debug event dispatcher thread, since
 * blocking it would stall every other debug event in the IDE.
 */
public final class DebugSessionRegistry {

	private static final DebugSessionRegistry INSTANCE = new DebugSessionRegistry();

	/**
	 * Terminated sessions stay listed this long so that a caller polling sees what
	 * happened, then are dropped.
	 */
	static final long KEEP_TERMINATED_MILLIS = 5 * 60_000L;

	private static final int MAX_SESSIONS = 50;

	private final AtomicLong ids = new AtomicLong();

	/** Insertion order, oldest first, so "the sessions" reads chronologically. */
	private final Map<String, Session> sessions = new LinkedHashMap<>();

	/** Sessions created ahead of their launch, matched by configuration name. */
	private final Map<String, Session> pending = new LinkedHashMap<>();

	private final List<Entry> signals = new ArrayList<>();

	private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "MCP debug auto-terminate"); //$NON-NLS-1$
		thread.setDaemon(true);
		return thread;
	});

	private boolean listening;

	private ILaunchListener launchListener;

	private IDebugEventSetListener eventListener;

	public static DebugSessionRegistry getInstance() {
		return INSTANCE;
	}

	private DebugSessionRegistry() {
	}

	/** One debug session: a launch running under this IDE's debugger. */
	public static final class Session {

		private final String id;

		private final boolean startedByMcp;

		private final String expectedConfigName;

		private final CountDownLatch registered = new CountDownLatch(1);

		private volatile ILaunch launch;

		private volatile long terminatedAt;

		private volatile IThread suspendedThread;

		private volatile IBreakpoint suspendBreakpoint;

		private volatile String failure;

		private ScheduledFuture<?> autoTerminate;
		private volatile boolean quietHeld;

		Session(String id, boolean startedByMcp, String expectedConfigName) {
			this.id = id;
			this.startedByMcp = startedByMcp;
			this.expectedConfigName = expectedConfigName;
		}

		public String id() {
			return id;
		}

		public boolean startedByMcp() {
			return startedByMcp;
		}

		public ILaunch launch() {
			return launch;
		}

		String expectedConfigName() {
			return expectedConfigName;
		}

		public boolean registered() {
			return registered.getCount() == 0;
		}

		boolean awaitRegistration(long seconds) throws InterruptedException {
			return registered.await(seconds, TimeUnit.SECONDS);
		}

		void failed(String message) {
			failure = message;
			if (terminatedAt == 0) {
				terminatedAt = System.currentTimeMillis();
			}
			cancelAutoTerminate();
			releaseQuiet();
			registered.countDown();
		}

		/**
		 * Holds the launch prompts quiet for as long as this session runs.
		 * <p>
		 * The suspend settings this silences are consulted while the program runs,
		 * not while it starts, so putting them back when the launch call returns
		 * would restore them before the exception that would trip them.
		 */
		void holdQuiet() {
			if (!quietHeld) {
				quietHeld = true;
				LaunchPrompts.quiet();
			}
		}

		private void releaseQuiet() {
			if (quietHeld) {
				quietHeld = false;
				LaunchPrompts.release();
			}
		}

		String failure() {
			return failure;
		}

		void attach(ILaunch value) {
			if (launch == null) {
				launch = value;
				registered.countDown();
				terminated();
			}
		}

		public boolean terminated() {
			ILaunch current = launch;
			if (terminatedAt == 0 && current != null && current.isTerminated()) {
				terminatedAt = System.currentTimeMillis();
				cancelAutoTerminate();
				// the one transition point, so the settings go back exactly once
				releaseQuiet();
			}
			return terminatedAt > 0 || (current != null && current.isTerminated());
		}

		long terminatedAt() {
			return terminatedAt;
		}

		/**
		 * Whether anything of this session is stopped: a suspended target, or any
		 * suspended thread of a resumed one.
		 */
		public boolean suspended() {
			ILaunch current = launch;
			if (current == null || terminated()) {
				return false;
			}
			for (var target : current.getDebugTargets()) {
				if (target.isSuspended()) {
					return true;
				}
				try {
					for (IThread thread : target.getThreads()) {
						if (thread.isSuspended()) {
							return true;
						}
					}
				} catch (CoreException e) {
					// a target whose threads cannot be read answers not suspended
				}
			}
			return false;
		}

		IThread suspendedThread() {
			return suspendedThread;
		}

		IBreakpoint suspendBreakpoint() {
			return suspendBreakpoint;
		}

		synchronized void cancelAutoTerminate() {
			ScheduledFuture<?> future = autoTerminate;
			if (future != null) {
				future.cancel(false);
				autoTerminate = null;
			}
		}

		void setAutoTerminate(ScheduledFuture<?> future) {
			autoTerminate = future;
		}
	}

	/** A wait for the next suspend event of one session, or of any session. */
	public static final class SuspendSignal {

		private final CountDownLatch latch = new CountDownLatch(1);

		private final DebugSessionRegistry owner = DebugSessionRegistry.getInstance();

		void fire() {
			latch.countDown();
		}

		/** Waits for the event and forgets the signal either way, so an expired wait leaves nothing behind. */
		public boolean await(long seconds) throws InterruptedException {
			try {
				return latch.await(seconds, TimeUnit.SECONDS);
			} finally {
				owner.forget(this);
			}
		}
	}

	private record Entry(Session session, SuspendSignal signal) {
	}

	/**
	 * Creates the session a tool is about to launch, before the job runs, so that no
	 * launch event can arrive unassigned.
	 */
	public synchronized Session prepare(String configName) {
		listen();
		prune();
		String id = "debug-" + ids.incrementAndGet(); //$NON-NLS-1$
		Session session = new Session(id, true, configName);
		sessions.put(id, session);
		pending.put(configName, session);
		return session;
	}

	public synchronized Session find(String id) {
		prune();
		return sessions.get(id);
	}

	/** The known ids, oldest first, for refusals that name what exists. */
	public synchronized List<String> ids() {
		prune();
		return List.copyOf(sessions.keySet());
	}

	/** Every session, oldest first. */
	public synchronized List<Session> all() {
		prune();
		return List.copyOf(sessions.values());
	}

	/**
	 * The single live session, or {@code null} when there is none or more than one;
	 * a caller facing several names them out of {@link #all()} in a refusal.
	 */
	public synchronized Session singleLive() {
		prune();
		Session found = null;
		for (Session session : sessions.values()) {
			if (!session.terminated()) {
				if (found != null) {
					return null;
				}
				found = session;
			}
		}
		return found;
	}

	/** Whether anything, anywhere, is currently stopped at a breakpoint or a step. */
	public boolean anythingSuspended() {
		// queried outside the lock: JDI calls can be slow and the event dispatcher needs the lock
		for (Session session : all()) {
			if (!session.terminated() && session.suspended()) {
				return true;
			}
		}
		return false;
	}

	/** How many sessions are not terminated. */
	public synchronized int liveCount() {
		int count = 0;
		for (Session session : sessions.values()) {
			if (!session.terminated()) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Signals the next suspend event of {@code session}, or of any session when it
	 * is {@code null}. Registering before acting is what makes the wait race free.
	 */
	public synchronized SuspendSignal onNextSuspend(Session session) {
		listen();
		SuspendSignal signal = new SuspendSignal();
		signals.add(new Entry(session, signal));
		return signal;
	}

	private synchronized void forget(SuspendSignal signal) {
		signals.removeIf(entry -> entry.signal() == signal);
	}

	/** Terminates an MCP-started session once its idle time is up; never another. */
	void scheduleAutoTerminate(Session session, int seconds) {
		session.setAutoTerminate(watchdog.schedule(() -> terminateQuietly(session), seconds, TimeUnit.SECONDS));
	}

	/** Terminates quietly; a launch that cannot be terminated is reported, not thrown. */
	static boolean terminateQuietly(Session session) {
		ILaunch launchValue = session.launch();
		if (launchValue == null || !launchValue.canTerminate() || launchValue.isTerminated()) {
			return false;
		}
		try {
			launchValue.terminate();
		} catch (CoreException e) {
			session.failed("Termination failed: " + e.getMessage()); //$NON-NLS-1$
			return false;
		}
		session.terminated();
		return true;
	}

	/**
	 * Terminates a session and waits for the process to actually be gone.
	 * <p>
	 * {@code ILaunch.terminate} returns once it has asked, and the JVM still holds its workspace
	 * lock for a moment, so reporting it early makes the next launch hit a "Workspace in use" dialog.
	 *
	 * @return whether the process had really ended within the wait
	 */
	static boolean terminateAndWait(Session session, int seconds) {
		terminateQuietly(session);
		ILaunch launchValue = session.launch();
		if (launchValue == null) {
			return true;
		}
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < deadline) {
			if (allGone(launchValue)) {
				return true;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return allGone(launchValue);
			}
		}
		return allGone(launchValue);
	}

	/** The launch and every process of it, since the launch can outlive its processes and the reverse. */
	private static boolean allGone(ILaunch launchValue) {
		if (!launchValue.isTerminated()) {
			return false;
		}
		for (org.eclipse.debug.core.model.IProcess process : launchValue.getProcesses()) {
			if (!process.isTerminated()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The sessions this server started for a launch configuration that are still
	 * running. A launch the person at the IDE started is never included: it is
	 * theirs, and terminating it under them would be the worse surprise.
	 */
	public synchronized List<Session> liveStartedByMcp(String configName) {
		List<Session> found = new ArrayList<>();
		for (Session session : sessions.values()) {
			if (session.startedByMcp() && !session.terminated() && configName != null
					&& configName.equals(nameOf(session))) {
				found.add(session);
			}
		}
		return found;
	}

	private static String nameOf(Session session) {
		ILaunch launchValue = session.launch();
		if (launchValue != null && launchValue.getLaunchConfiguration() != null) {
			return launchValue.getLaunchConfiguration().getName();
		}
		return session.expectedConfigName();
	}

	/** Terminates the MCP-started sessions still running, on bundle stop. */
	public synchronized void shutdown() {
		watchdog.shutdownNow();
		if (listening && DebugPlugin.getDefault() != null) {
			DebugPlugin.getDefault().getLaunchManager().removeLaunchListener(launchListener);
			DebugPlugin.getDefault().removeDebugEventListener(eventListener);
			listening = false;
		}
		for (Session session : sessions.values()) {
			if (session.startedByMcp()) {
				terminateQuietly(session);
			}
		}
	}

	private synchronized void listen() {
		if (listening) {
			return;
		}
		launchListener = new ILaunchListener() {
			@Override
			public void launchAdded(ILaunch launchValue) {
				adopt(launchValue);
			}

			@Override
			public void launchChanged(ILaunch launchValue) {
				markTerminated(launchValue);
			}

			@Override
			public void launchRemoved(ILaunch launchValue) {
				markTerminated(launchValue);
			}
		};
		DebugPlugin.getDefault().getLaunchManager().addLaunchListener(launchListener);
		eventListener = DebugSessionRegistry::handleDebugEvents;
		DebugPlugin.getDefault().addDebugEventListener(eventListener);
		listening = true;
	}

	/** Attaches a launch the launch job got back, for one that produced no launch event. */
	void attachLaunched(Session session, ILaunch launched) {
		synchronized (this) {
			if (session.expectedConfigName() != null) {
				pending.remove(session.expectedConfigName(), session);
			}
		}
		session.attach(launched);
	}

	/** Assigns an id to every debug launch in the IDE, ours or not. */
	private void adopt(ILaunch launchValue) {
		if (!"debug".equals(launchValue.getLaunchMode()) && launchValue.getDebugTargets().length == 0) { //$NON-NLS-1$
			return;
		}
		ILaunchConfiguration configuration = launchValue.getLaunchConfiguration();
		String name = configuration == null ? null : configuration.getName();
		Session session;
		synchronized (this) {
			session = name == null ? null : pending.remove(name);
			if (session == null) {
				String id = "debug-" + ids.incrementAndGet(); //$NON-NLS-1$
				// a test run launched with debug true is ours as well, and it reaches
				// the registry through the launch manager rather than through prepare
				session = new Session(id, startedByMcp(launchValue), name);
				sessions.put(id, session);
			}
		}
		session.attach(launchValue);
	}

	/** Whether the configuration carries this server's marker. */
	private static boolean startedByMcp(ILaunch launchValue) {
		try {
			var configuration = launchValue.getLaunchConfiguration();
			return configuration != null && configuration
					.getAttribute(LaunchAttributes.STARTED_BY_MCP, false);
		} catch (org.eclipse.core.runtime.CoreException | RuntimeException e) {
			return false;
		}
	}

	private void markTerminated(ILaunch launchValue) {
		Session session = byLaunch(launchValue);
		if (session != null) {
			session.terminated();
		}
	}

	private static void handleDebugEvents(DebugEvent[] events) {
		for (DebugEvent event : events) {
			handleDebugEvent(event);
		}
	}

	private static void handleDebugEvent(DebugEvent event) {
		Object source = event.getSource();
		int kind = event.getKind();
		if (kind == DebugEvent.TERMINATE) {
			if (source instanceof IDebugElement element) {
				getInstance().markTerminated(element.getLaunch());
			}
			return;
		}
		if (kind != DebugEvent.SUSPEND) {
			return;
		}
		IThread thread = source instanceof IThread value ? value : null;
		ILaunch launchValue = source instanceof ILaunch value ? value
				: source instanceof IDebugElement element ? element.getLaunch() : null;
		if (launchValue == null) {
			return;
		}
		Session session = getInstance().byLaunch(launchValue);
		if (session == null) {
			return;
		}
		// record and signal only: this runs on the debug event dispatcher thread, and
		// anything slow here stalls event processing for every other debug target
		session.suspendedThread = thread;
		session.suspendBreakpoint = event.getData() instanceof IBreakpoint breakpoint ? breakpoint : null;
		getInstance().fire(session);
	}

	private synchronized void fire(Session session) {
		Iterator<Entry> iterator = signals.iterator();
		while (iterator.hasNext()) {
			Entry entry = iterator.next();
			if (entry.session() == null || entry.session() == session) {
				entry.signal().fire();
				iterator.remove();
			}
		}
	}

	private synchronized Session byLaunch(ILaunch launchValue) {
		for (Session session : sessions.values()) {
			if (session.launch() == launchValue) {
				return session;
			}
		}
		return null;
	}

	private synchronized void prune() {
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<String, Session>> iterator = sessions.entrySet().iterator();
		while (iterator.hasNext()) {
			Session session = iterator.next().getValue();
			if (session.terminatedAt() > 0 && now - session.terminatedAt() > KEEP_TERMINATED_MILLIS) {
				iterator.remove();
				if (session.expectedConfigName() != null) {
					pending.remove(session.expectedConfigName());
				}
			}
		}
		while (sessions.size() > MAX_SESSIONS && dropOldestTerminated()) {
			// keep only the cap's worth
		}
	}

	private synchronized boolean dropOldestTerminated() {
		for (Map.Entry<String, Session> entry : sessions.entrySet()) {
			if (entry.getValue().terminatedAt() > 0) {
				sessions.remove(entry.getKey());
				return true;
			}
		}
		return false;
	}
}
