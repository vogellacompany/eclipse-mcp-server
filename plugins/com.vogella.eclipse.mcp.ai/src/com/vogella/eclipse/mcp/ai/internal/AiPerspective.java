package com.vogella.eclipse.mcp.ai.internal;

import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/**
 * The AI perspective: the AI view and nothing else.
 */
public class AiPerspective implements IPerspectiveFactory {

	public static final String ID = "com.vogella.eclipse.mcp.ai.perspective"; //$NON-NLS-1$

	@Override
	public void createInitialLayout(IPageLayout layout) {
		String editorArea = layout.getEditorArea();
		layout.setEditorAreaVisible(false);
		layout.addStandaloneView(AiView.ID, false, IPageLayout.TOP, 0.95f, editorArea);
		layout.getViewLayout(AiView.ID).setCloseable(false);
		layout.getViewLayout(AiView.ID).setMoveable(false);
		layout.addShowViewShortcut(AiView.ID);
	}
}
