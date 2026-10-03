package com.vogella.eclipse.mcp.ui.internal;

import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.LogClearedHandlers;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Empties an open Error Log view after {@code eclipse_clear_log} deleted the
 * file it is showing.
 */
final class ErrorLogRefresh implements LogClearedHandlers.Handler {

	private static final String VIEW_ID = "org.eclipse.ui.views.LogView"; //$NON-NLS-1$

	/**
	 * The view's delete action calls this after deleting the file, so the tool ends in the toolbar button's state.
	 * Reflective because {@code LogView} is exported only to PDE, and a missing method must degrade into a note.
	 */
	private static final String CLEAR_METHOD = "handleClear"; //$NON-NLS-1$

	private static final long UI_TIMEOUT_SECONDS = 10;

	@Override
	public JsonObject logCleared() {
		UiThread.Outcome outcome = UiThread.run(UI_TIMEOUT_SECONDS, ErrorLogRefresh::clearOpenViews);
		return outcome.error() == null ? outcome.value()
				: new JsonObject().put("updated", Boolean.FALSE).put("reason", outcome.error()); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static JsonObject clearOpenViews() {
		int cleared = 0;
		JsonArray problems = new JsonArray();
		for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			for (IWorkbenchPage page : window.getPages()) {
				for (IViewReference reference : page.getViewReferences()) {
					if (!VIEW_ID.equals(reference.getId())) {
						continue;
					}
					// getView(false): a view whose control has never been created holds
					// nothing to clear and will read the file when it is created
					IViewPart view = reference.getView(false);
					if (view == null) {
						continue;
					}
					try {
						view.getClass().getMethod(CLEAR_METHOD).invoke(view);
						cleared++;
					} catch (ReflectiveOperationException | RuntimeException e) {
						problems.add(String.valueOf(e));
					}
				}
			}
		}
		JsonObject result = new JsonObject().put("updated", Boolean.valueOf(cleared > 0)) //$NON-NLS-1$
				.put("viewsCleared", Integer.valueOf(cleared)); //$NON-NLS-1$
		if (problems.size() > 0) {
			result.put("couldNotClear", problems).put("note", //$NON-NLS-1$ //$NON-NLS-2$
					"The Error Log view is open but did not empty, so it is still showing entries that no longer exist. Close and reopen it."); //$NON-NLS-1$
		} else if (cleared == 0) {
			result.put("note", "The Error Log view is not open, so there was nothing to update."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return result;
	}
}
