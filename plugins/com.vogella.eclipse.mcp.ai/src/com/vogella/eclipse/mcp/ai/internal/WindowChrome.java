package com.vogella.eclipse.mcp.ai.internal;

import java.util.List;
import java.util.Map;

import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.SideValue;
import org.eclipse.e4.ui.model.application.ui.basic.MTrimBar;
import org.eclipse.e4.ui.model.application.ui.basic.MTrimElement;
import org.eclipse.e4.ui.model.application.ui.basic.MTrimmedWindow;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.ui.IWorkbenchWindow;

/**
 * Hides the main toolbar while the AI perspective is active, after moving the perspective switcher into the bottom
 * trim so another perspective stays one click away. What was changed is kept in the window's persisted state, so a
 * workbench closed in the AI perspective is restored the next time the perspective is left.
 */
final class WindowChrome {

	private static final String SWITCHER_ID = "PerspectiveSwitcher"; //$NON-NLS-1$
	private static final String MOVED_FROM = "com.vogella.eclipse.mcp.ai.switcherMovedFrom"; //$NON-NLS-1$
	private static final String TOOLBAR_HIDDEN = "com.vogella.eclipse.mcp.ai.toolbarHidden"; //$NON-NLS-1$

	private WindowChrome() {
	}

	static void enterAi(IWorkbenchWindow workbenchWindow) {
		MTrimmedWindow window = window(workbenchWindow);
		EModelService service = workbenchWindow.getService(EModelService.class);
		if (window == null || service == null) {
			return;
		}
		Map<String, String> state = window.getPersistedState();
		MTrimBar top = service.getTrim(window, SideValue.TOP);
		MTrimBar bottom = service.getTrim(window, SideValue.BOTTOM);
		MTrimElement switcher = switcher(service, window);
		if (switcher != null && switcher.getParent() != null && !bottom.getChildren().contains(switcher)
				&& !state.containsKey(MOVED_FROM)) {
			MElementContainer<MUIElement> parent = switcher.getParent();
			state.put(MOVED_FROM, parent.getElementId() + '@' + parent.getChildren().indexOf(switcher));
			move(switcher, bottom, -1);
		}
		// the workbench shows the toolbar again at startup, so a restart in this perspective hides it anew
		if (top.isVisible()) {
			state.putIfAbsent(TOOLBAR_HIDDEN, Boolean.TRUE.toString());
			top.setVisible(false);
		}
	}

	static void leaveAi(IWorkbenchWindow workbenchWindow) {
		MTrimmedWindow window = window(workbenchWindow);
		EModelService service = workbenchWindow.getService(EModelService.class);
		if (window == null || service == null) {
			return;
		}
		Map<String, String> state = window.getPersistedState();
		if (state.remove(TOOLBAR_HIDDEN) != null) {
			service.getTrim(window, SideValue.TOP).setVisible(true);
		}
		String movedFrom = state.remove(MOVED_FROM);
		MTrimElement switcher = switcher(service, window);
		if (movedFrom == null || switcher == null) {
			return;
		}
		int at = movedFrom.lastIndexOf('@');
		MUIElement parent = service.find(movedFrom.substring(0, Math.max(at, 0)), window);
		MTrimBar target = parent instanceof MTrimBar trimBar ? trimBar : service.getTrim(window, SideValue.TOP);
		move(switcher, target, at < 0 ? -1 : parseIndex(movedFrom.substring(at + 1)));
	}

	private static MTrimmedWindow window(IWorkbenchWindow workbenchWindow) {
		return workbenchWindow.getService(MWindow.class) instanceof MTrimmedWindow trimmed ? trimmed : null;
	}

	private static MTrimElement switcher(EModelService service, MWindow window) {
		return service.find(SWITCHER_ID, window) instanceof MTrimElement element ? element : null;
	}

	private static int parseIndex(String text) {
		try {
			return Integer.parseInt(text);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** Re-renders the switcher in its new trim bar, which is how it picks up that bar's orientation. */
	private static void move(MTrimElement switcher, MTrimBar target, int index) {
		if (target.getChildren().contains(switcher)) {
			return;
		}
		boolean toBeRendered = switcher.isToBeRendered();
		switcher.setToBeRendered(false);
		List<MTrimElement> children = target.getChildren();
		if (index < 0 || index > children.size()) {
			children.add(switcher);
		} else {
			children.add(index, switcher);
		}
		target.setToBeRendered(true);
		switcher.setToBeRendered(toBeRendered);
	}
}
