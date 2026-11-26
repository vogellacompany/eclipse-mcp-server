package com.vogella.eclipse.mcp.ai.internal;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.core.runtime.ILog;

/**
 * Watches the sessions folder and reports the live sessions whenever they change. A rescan also runs every few
 * seconds, since a session that is killed leaves its file behind.
 */
public final class SessionWatcher {

	private static final long RESCAN_MILLIS = 5000;
	/** Claude Code writes a file in several steps, so events are collected this long before a rescan. */
	private static final long SETTLE_MILLIS = 150;

	private final SessionDirectory directory;
	private final Consumer<List<ClaudeSession>> listener;
	private volatile boolean stopped;
	private Thread thread;
	private WatchService watchService;
	private List<ClaudeSession> last;

	public SessionWatcher(SessionDirectory directory, Consumer<List<ClaudeSession>> listener) {
		this.directory = directory;
		this.listener = listener;
	}

	public synchronized void start() {
		if (thread != null) {
			return;
		}
		thread = new Thread(this::run, "Claude Code session watcher"); //$NON-NLS-1$
		thread.setDaemon(true);
		thread.start();
	}

	public synchronized void stop() {
		stopped = true;
		if (watchService != null) {
			try {
				watchService.close();
			} catch (IOException e) {
				// closing only wakes the thread
			}
		}
		if (thread != null) {
			thread.interrupt();
		}
	}

	private void run() {
		while (!stopped) {
			publish();
			WatchService service = register();
			try {
				while (!stopped) {
					WatchKey key = service == null ? null : service.poll(RESCAN_MILLIS, TimeUnit.MILLISECONDS);
					if (key != null) {
						Thread.sleep(SETTLE_MILLIS);
						key.pollEvents();
						if (!key.reset()) {
							break;
						}
					} else if (service == null) {
						Thread.sleep(RESCAN_MILLIS);
						if (Files.isDirectory(directory.directory())) {
							break;
						}
					}
					publish();
				}
			} catch (InterruptedException | ClosedWatchServiceException e) {
				return;
			} finally {
				closeQuietly(service);
			}
		}
	}

	private WatchService register() {
		if (!Files.isDirectory(directory.directory())) {
			return null;
		}
		try {
			WatchService service = directory.directory().getFileSystem().newWatchService();
			directory.directory().register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
			synchronized (this) {
				watchService = service;
			}
			return service;
		} catch (IOException e) {
			ILog.of(SessionWatcher.class).warn("Cannot watch " + directory.directory() + ", rescanning instead", e); //$NON-NLS-1$ //$NON-NLS-2$
			return null;
		}
	}

	private void publish() {
		List<ClaudeSession> sessions = directory.read();
		if (Objects.equals(sessions, last)) {
			return;
		}
		last = sessions;
		try {
			listener.accept(sessions);
		} catch (RuntimeException e) {
			ILog.of(SessionWatcher.class).error("Session listener failed", e); //$NON-NLS-1$
		}
	}

	private static void closeQuietly(WatchService service) {
		if (service == null) {
			return;
		}
		try {
			service.close();
		} catch (IOException e) {
			// nothing left to release
		}
	}
}
