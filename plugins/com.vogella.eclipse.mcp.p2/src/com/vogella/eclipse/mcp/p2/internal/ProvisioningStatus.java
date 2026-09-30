package com.vogella.eclipse.mcp.p2.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IStatus;

/**
 * Turns the status a provisioning job ends with into what the client is told.
 */
public final class ProvisioningStatus {

	/** Enough of a status tree to say what happened, without the whole of it. */
	private static final int MAX_LINES = 40;

	private ProvisioningStatus() {
	}

	/**
	 * The reported state for a finished job.
	 * <p>
	 * Only ERROR is a failure. p2 reports an operation that completed but had
	 * something to say as INFO or WARNING, so treating every non-OK status as
	 * failed reports a finished install as a failure.
	 */
	public static String stateOf(IStatus status) {
		int severity = status == null ? IStatus.OK : status.getSeverity();
		if (severity == IStatus.CANCEL) {
			return "cancelled"; //$NON-NLS-1$
		}
		return severity >= IStatus.ERROR ? "failed" : "done"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * The text of a status, taking the children when it has none of its own, or null when there is none.
	 * <p>
	 * A p2 operation answers with a MultiStatus whose own message is empty, so
	 * reporting {@code getMessage()} alone says nothing about what happened.
	 */
	public static String describe(IStatus status) {
		if (status == null) {
			return null;
		}
		Set<String> lines = new LinkedHashSet<>();
		collect(status, lines);
		if (lines.isEmpty()) {
			return null;
		}
		// a resolution failure carries a status per unit it could not satisfy, which
		// came to 122,510 characters in one report and blew the caller's limit. The
		// first lines are the ones that say what happened
		int kept = Math.min(lines.size(), MAX_LINES);
		String text = String.join("\n", List.copyOf(lines).subList(0, kept)); //$NON-NLS-1$
		if (kept < lines.size()) {
			text += "\n... and %d more line(s), left out because a status tree of this size is not readable." //$NON-NLS-1$
					.formatted(Integer.valueOf(lines.size() - kept));
		}
		return text;
	}

	private static void collect(IStatus status, Set<String> lines) {
		String message = status.getMessage();
		if (message != null && !message.isBlank()) {
			lines.add(message);
		}
		for (IStatus child : status.getChildren()) {
			collect(child, lines);
		}
	}
}
