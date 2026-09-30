package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Whether any open text editor's reconciler is still working.
 * <p>
 * This is the blind spot {@link UiSettle} otherwise documents and cannot close:
 * {@code AbstractReconciler} starts as a job and then hands its work to a plain
 * daemon thread, so neither the job manager nor a fence posted to the Display
 * knows it is running, and semantic highlighting lands after everything
 * observable has gone quiet.
 * <p>
 * JDT's own performance tests reach it the same way, in
 * {@code EditorTestHelper.joinReconciler}: reflect into
 * {@code SourceViewer.fReconciler}, then {@code AbstractReconciler.fWorker},
 * then poll {@code isDirty} and {@code isActive} on the package private worker,
 * and for a {@code JavaReconciler} also its {@code fIninitalProcessDone} flag,
 * whose spelling is a typo in JDT and has to be matched exactly. That an
 * upstream test suite does it this way is evidence the approach works, not that
 * it is supported: every name here is internal and can change in any release.
 * <p>
 * So every failure is answered as "cannot tell" rather than as "idle". A settle
 * that reported quiet because it could not find the field would be worse than
 * one that never looked.
 * <p>
 * The probe runs on the UI thread, so it has to be cheap and it has to be
 * bounded. Members are resolved once per class and cached, misses included,
 * because resolving them on every probe was measured holding the UI thread for
 * seconds at a time with large Java editors open. A probe that still runs past
 * its budget stops and counts the editors it did not reach as unreadable.
 */
public final class Reconcilers {

	private static final String ABSTRACT_RECONCILER = "org.eclipse.jface.text.reconciler.AbstractReconciler"; //$NON-NLS-1$

	private static final String SOURCE_VIEWER = "org.eclipse.jface.text.source.SourceViewer"; //$NON-NLS-1$

	private static final String JAVA_RECONCILER = "org.eclipse.jdt.internal.ui.javaeditor.JavaReconciler"; //$NON-NLS-1$

	/** The longest one probe may hold the UI thread before it stops looking. */
	static final long BUDGET_MILLIS = 100;

	private record Key(Class<?> type, String name) {
	}

	private static final Map<Key, Optional<Method>> METHODS = new ConcurrentHashMap<>();

	private static final Map<Key, Optional<Field>> FIELDS = new ConcurrentHashMap<>();

	private Reconcilers() {
	}

	/** What one editor's reconciler was found to be doing. */
	public enum Verdict {
		/** No reconciler, so not a text editor this applies to. */
		NONE, IDLE, BUSY,
		/** An internal that moved, or a read that failed: deliberately not idle. */
		UNREADABLE
	}

	/**
	 * What the reconcilers of the open editors are doing. MUST run on the UI thread.
	 *
	 * @param editorsChecked editors with a reconciler this could read
	 * @param busy of those, how many were working
	 * @param unreadable editors whose reconciler could not be read, counted as busy
	 * @param skipped editors the probe did not reach within its budget, counted as
	 *        busy
	 * @param probeMillis how long the probe held the UI thread
	 */
	record State(int editorsChecked, int busy, int unreadable, int skipped, long probeMillis) {

		boolean isBusy() {
			return busy > 0 || unreadable > 0 || skipped > 0;
		}

		JsonObject describe() {
			JsonObject json = new JsonObject().put("editorsChecked", Integer.valueOf(editorsChecked)) //$NON-NLS-1$
					.put("busy", Integer.valueOf(busy)) //$NON-NLS-1$
					.put("unreadable", Integer.valueOf(unreadable)) //$NON-NLS-1$
					.put("probeMillis", Long.valueOf(probeMillis)); //$NON-NLS-1$
			if (unreadable > 0) {
				json.put("note", //$NON-NLS-1$
						"Some editors' reconcilers could not be read, which happens when the internal fields this reaches by name have changed. Those are counted as busy rather than idle, so settling stays conservative; it can mean a settle that never succeeds rather than one that succeeds too early."); //$NON-NLS-1$
			}
			if (skipped > 0) {
				json.put("skipped", Integer.valueOf(skipped)); //$NON-NLS-1$
				json.put("skippedNote", //$NON-NLS-1$
						"The probe runs on the UI thread and stopped after %d ms without reaching every editor. The editors it did not reach count as busy. A probe this slow is itself a sign the UI thread is under load." //$NON-NLS-1$
								.formatted(Long.valueOf(BUDGET_MILLIS)));
			}
			return json;
		}
	}

	static State inspect() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		if (page == null) {
			return new State(0, 0, 0, 0, 0);
		}
		long started = System.nanoTime();
		int checked = 0;
		int busy = 0;
		int unreadable = 0;
		int skipped = 0;
		for (IEditorReference reference : page.getEditorReferences()) {
			if (elapsedMillis(started) > BUDGET_MILLIS) {
				skipped++;
				continue;
			}
			// getPart(false): restoring an editor to ask whether it is reconciling
			// would be a change made by a read, and a closed editor reconciles nothing
			IWorkbenchPart part = reference.getPart(false);
			if (part == null) {
				continue;
			}
			switch (verdict(part)) {
			case NONE -> {
				// not a text editor
			}
			case IDLE -> checked++;
			case BUSY -> {
				checked++;
				busy++;
			}
			case UNREADABLE -> unreadable++;
			}
		}
		return new State(checked, busy, unreadable, skipped, elapsedMillis(started));
	}

	private static long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}

	/** What one editor's reconciler is doing, {@code UNREADABLE} when an internal this reads has moved. */
	public static Verdict verdict(Object editor) {
		try {
			Method getViewer = method(editor, "getSourceViewer"); //$NON-NLS-1$
			Object viewer = getViewer == null ? null : getViewer.invoke(editor);
			if (viewer == null || !isAssignable(viewer, SOURCE_VIEWER)) {
				return Verdict.NONE;
			}
			// past this point a missing member means the internals moved, not "no reconciler"
			Object reconciler = field(viewer, "fReconciler"); //$NON-NLS-1$
			if (reconciler == null || !isAssignable(reconciler, ABSTRACT_RECONCILER)) {
				return Verdict.NONE;
			}
			Object worker = field(reconciler, "fWorker"); //$NON-NLS-1$
			if (worker == null) {
				return Verdict.NONE;
			}
			Object dirty = invoke(worker, "isDirty"); //$NON-NLS-1$
			Object active = invoke(worker, "isActive"); //$NON-NLS-1$
			if (!(dirty instanceof Boolean) || !(active instanceof Boolean)) {
				// the worker no longer answers the two questions this is built on
				return Verdict.UNREADABLE;
			}
			if (Boolean.TRUE.equals(dirty) || Boolean.TRUE.equals(active)) {
				return Verdict.BUSY;
			}
			if (isAssignable(reconciler, JAVA_RECONCILER)) {
				Object done = field(reconciler, "fIninitalProcessDone"); //$NON-NLS-1$
				// the typo is JDT's; matching it is the point
				if (!(done instanceof Boolean flag)) {
					return Verdict.UNREADABLE;
				}
				if (!flag.booleanValue()) {
					return Verdict.BUSY;
				}
			}
			return Verdict.IDLE;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return Verdict.UNREADABLE;
		}
	}

	private static boolean isAssignable(Object candidate, String className) {
		for (Class<?> type = candidate.getClass(); type != null; type = type.getSuperclass()) {
			if (className.equals(type.getName())) {
				return true;
			}
		}
		return false;
	}

	/** A field anywhere up the hierarchy, since every one of these is declared privately. */
	private static Object field(Object owner, String name) throws ReflectiveOperationException {
		Field field = FIELDS.computeIfAbsent(new Key(owner.getClass(), name), Reconcilers::findField)
				.orElseThrow(() -> new NoSuchFieldException(name));
		return field.get(owner);
	}

	private static Object invoke(Object owner, String name) throws ReflectiveOperationException {
		Method method = method(owner, name);
		if (method == null) {
			throw new NoSuchMethodException(name);
		}
		return method.invoke(owner);
	}

	private static Method method(Object owner, String name) {
		return METHODS.computeIfAbsent(new Key(owner.getClass(), name), Reconcilers::findMethod).orElse(null);
	}

	private static Optional<Field> findField(Key key) {
		for (Class<?> type = key.type(); type != null; type = type.getSuperclass()) {
			try {
				Field field = type.getDeclaredField(key.name());
				field.setAccessible(true);
				return Optional.of(field);
			} catch (NoSuchFieldException e) {
				continue;
			} catch (RuntimeException e) {
				return Optional.empty();
			}
		}
		return Optional.empty();
	}

	private static Optional<Method> findMethod(Key key) {
		for (Class<?> type = key.type(); type != null; type = type.getSuperclass()) {
			try {
				Method method = type.getDeclaredMethod(key.name());
				method.setAccessible(true);
				return Optional.of(method);
			} catch (NoSuchMethodException e) {
				continue;
			} catch (RuntimeException e) {
				return Optional.empty();
			}
		}
		return Optional.empty();
	}
}
