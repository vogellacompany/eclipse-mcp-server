package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.eclipse.swt.SWT;

import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Pointer input for SWT's headless backend, which has no window system to post to: its
 * {@code RecordingInput} feeds the events to the display directly. Reached by name, so the bundle stays usable on
 * every other platform; every method runs on the UI thread and answers {@code null} or why it failed.
 */
public final class HeadlessInput {

	static final String METHOD = "headless"; //$NON-NLS-1$
	private static final String RECORDING_INPUT = "org.eclipse.swt.internal.headless.RecordingInput"; //$NON-NLS-1$

	private HeadlessInput() {
	}

	/** Whether SWT runs on its headless backend. */
	public static boolean applies() {
		return METHOD.equals(SWT.getPlatform());
	}

	/** Moves the pointer to a display point. */
	public static String mouseMove(int x, int y) {
		return invoke("mouseMove", new Class<?>[] { int.class, int.class }, Integer.valueOf(x), Integer.valueOf(y)); //$NON-NLS-1$
	}

	/** Presses or releases a button where the pointer is. */
	static String mouseButton(int button, boolean down) {
		return invoke("mouseButton", new Class<?>[] { int.class, boolean.class }, Integer.valueOf(button), //$NON-NLS-1$
				Boolean.valueOf(down));
	}

	/** Turns the wheel at the pointer, SWT's way round: positive lines scroll up, positive columns left. */
	static String wheel(boolean horizontal, int count) {
		return invoke(horizontal ? "horizontalWheel" : "wheel", new Class<?>[] { int.class }, Integer.valueOf(count)); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Waits on the display thread while timers run and paint; with {@code swt.headless.clock=controlled} it steps the
	 * display clock to each deadline instead of sleeping, so the recording shows the whole pause at little wall time.
	 */
	static String pause(long millis) {
		return invoke("pause", new Class<?>[] { long.class }, Long.valueOf(millis)); //$NON-NLS-1$
	}

	/**
	 * Runs {@link #pause} on the UI thread from a worker, answering {@code null} once done or why it could not, so the
	 * caller can fall back to sleeping.
	 */
	static String pauseFromWorker(long millis) {
		UiThread.Outcome outcome = UiThread.run(millis / 1000 + 2, () -> {
			String failure = pause(millis);
			return new JsonObject().put("failure", failure); //$NON-NLS-1$
		});
		if (outcome.error() != null) {
			return outcome.error();
		}
		Object failure = outcome.value().remove("failure"); //$NON-NLS-1$
		return failure == null ? null : failure.toString();
	}

	private static String invoke(String name, Class<?>[] types, Object... arguments) {
		try {
			Method method = Class.forName(RECORDING_INPUT, true, SWT.class.getClassLoader()).getMethod(name, types);
			method.invoke(null, arguments);
			return null;
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			return "the headless backend refused %s: %s".formatted(name, cause == null ? e : cause); //$NON-NLS-1$
		} catch (ReflectiveOperationException | LinkageError e) {
			return "this SWT has no %s.%s, which the headless backend needs for input".formatted(RECORDING_INPUT, //$NON-NLS-1$
					name);
		}
	}
}
