package com.vogella.eclipse.mcp.core;

import java.util.List;

/**
 * The frames a thread sits in while doing nothing, the one definition behind the
 * sampling and flight recording answers and the flame graph pages.
 * <p>
 * An entry is {@code Class.method} with a simple class name, or a bare {@code method}, and
 * matches a method starting with it, so {@code Object.wait} also covers {@code Object.wait0}.
 * {@code Display.sleep} covers SWT on every platform: its wait is {@code OS.WaitMessage} on
 * win32, {@code OS.Call} of the poll function on GTK and {@code NSRunLoop.runMode} on Cocoa.
 */
public final class IdleFrames {

	private static final List<String> FRAMES = List.of("Display.sleep", "OS.WaitMessage", "NSRunLoop.runMode", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"Unsafe.park", "LockSupport.park", "Object.wait", "Thread.sleep", "Net.accept", "Net.poll", "EPoll.wait", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
			"WEPoll.wait", "KQueue.poll", "WToolkit.eventLoop", "waitForEvents", "waitForReferencePendingList", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			"ReferenceQueue.remove", "SocketDispatcher.read"); //$NON-NLS-1$ //$NON-NLS-2$

	/** How far from the innermost frame a wait counts: below that a native callback is running real work. */
	public static final int STACK_REACH = 4;

	/** Busy below one part in this of what could have been busy is a profile with no finding. */
	private static final int QUIET_RATIO = 20;

	private IdleFrames() {
	}

	/** Whether a frame such as {@code pkg.Outer$Inner.method:12} is a wait. */
	public static boolean isIdle(String frame) {
		if (frame == null) {
			return false;
		}
		int colon = frame.lastIndexOf(':');
		String body = colon > 0 ? frame.substring(0, colon) : frame;
		int dot = body.lastIndexOf('.');
		String method = body.substring(dot + 1);
		String type = dot < 0 ? "" : body.substring(0, dot); //$NON-NLS-1$
		type = type.substring(Math.max(type.lastIndexOf('.'), type.lastIndexOf('$')) + 1);
		for (String entry : FRAMES) {
			int separator = entry.indexOf('.');
			if (separator < 0 ? method.startsWith(entry)
					: type.equals(entry.substring(0, separator)) && method.startsWith(entry.substring(separator + 1))) {
				return true;
			}
		}
		return false;
	}

	/** The wait a stack given innermost first is parked in, or null when it is working. */
	public static String idleFrame(List<String> framesInnermostFirst) {
		for (int i = 0; i < Math.min(STACK_REACH, framesInnermostFirst.size()); i++) {
			if (isIdle(framesInnermostFirst.get(i))) {
				return framesInnermostFirst.get(i);
			}
		}
		return null;
	}

	/**
	 * Whether so little ran that naming a hot frame would mislead. {@code opportunities} is what
	 * busy is measured against: ticks for a sampler that visits every thread, since a share of
	 * all stacks would call one saturated thread among seventy parked ones idle.
	 */
	public static boolean nothingBusy(long busy, long opportunities) {
		return opportunities > 0 && busy * QUIET_RATIO < opportunities;
	}

	/** {@code Class.method} of a frame, for a sentence a person reads. */
	public static String brief(String frame) {
		int colon = frame.lastIndexOf(':');
		String body = colon > 0 ? frame.substring(0, colon) : frame;
		int dot = body.lastIndexOf('.');
		return body.substring(dot <= 0 ? 0 : body.lastIndexOf('.', dot - 1) + 1);
	}
}
