package com.vogella.eclipse.mcp.ui.internal;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Notices a dialog that work on the UI thread opened and is still running inside.
 * <p>
 * A modal dialog runs a nested event loop, so a probe posted to the Display runs
 * while the work has not returned; work that is merely busy leaves the probe queued.
 */
public final class DialogWatch {

	/** A dialog seen open; {@code key} tells the same dialog on two probes from two dialogs. */
	public record Seen(Object key, String title, boolean nativeDialog) {
	}

	private static final long PROBE_MILLIS = 100;

	private volatile Display display;
	private volatile Set<Shell> before;
	private volatile Set<Long> nativeBefore;

	/** Records the dialogs already open; call it on the UI thread right before the work that may open one. */
	public void mark(Display on) {
		nativeBefore = NativeDialogs.handles(on);
		before = Set.of(on.getShells());
		display = on;
	}

	/**
	 * Waits for {@code pending} until the deadline and answers a new dialog once it has been seen
	 * on two probes in a row, or {@code null} when the work finished or the deadline passed first.
	 * It probes only once {@link #mark} has run, so the baseline always predates a probe.
	 */
	public Seen await(CompletableFuture<?> pending, long deadline) throws InterruptedException {
		CompletableFuture<Seen> probe = null;
		Object candidate = null;
		while (!pending.isDone()) {
			long left = deadline - System.currentTimeMillis();
			if (left <= 0) {
				return null;
			}
			try {
				pending.get(Math.min(left, PROBE_MILLIS), TimeUnit.MILLISECONDS);
				return null;
			} catch (ExecutionException e) {
				return null;
			} catch (TimeoutException e) {
				// still running, look for a dialog
			}
			Display on = display;
			if (on != null && !on.isDisposed() && (probe == null || probe.isDone())) {
				Seen found = probe == null ? null : probe.getNow(null);
				if (found != null && found.key().equals(candidate)) {
					return found;
				}
				candidate = found == null ? null : found.key();
				CompletableFuture<Seen> next = new CompletableFuture<>();
				try {
					on.asyncExec(() -> {
						// a shell disposed mid-probe must not stall the watch or fail a delivered action
						try {
							next.complete(newDialog(on));
						} catch (Throwable e) {
							next.complete(null);
						}
					});
				} catch (SWTException e) {
					return null;
				}
				probe = next;
			}
		}
		return null;
	}

	private Seen newDialog(Display on) {
		Shell shell = Shells.newDialog(on, before);
		if (shell != null) {
			return new Seen(shell, shell.getText(), false);
		}
		return NativeDialogs.listNew(on, nativeBefore).stream().findFirst()
				.map(dialog -> new Seen(Long.valueOf(dialog.handle()), dialog.title(), true)).orElse(null);
	}
}
