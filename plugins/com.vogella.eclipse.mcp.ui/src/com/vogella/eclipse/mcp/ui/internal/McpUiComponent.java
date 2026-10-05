package com.vogella.eclipse.mcp.ui.internal;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

import com.vogella.eclipse.mcp.core.LogClearedHandlers;
import com.vogella.eclipse.mcp.core.TracePages;
import com.vogella.eclipse.mcp.core.UiDispatch;

/**
 * Registers the UI side of the core hooks when the framework starts, so neither a
 * bundle activator nor the IDE's {@code org.eclipse.ui.startup} extension is needed.
 */
@Component(immediate = true)
public final class McpUiComponent {

	/** Bundle id, for statuses logged from this bundle. */
	public static final String PLUGIN_ID = "com.vogella.eclipse.mcp.ui"; //$NON-NLS-1$

	private final ErrorLogRefresh errorLogRefresh = new ErrorLogRefresh();

	private final BrowserOpener browserOpener = new BrowserOpener();

	@Activate
	void activate() {
		// only hooks are set here, nothing that reaches Platform or the workbench:
		// this can run before either exists
		LogClearedHandlers.set(errorLogRefresh);
		UiDispatch.set(UiThread.EXECUTOR);
		TracePages.setOpener(browserOpener);
	}

	@Deactivate
	void deactivate() {
		LogClearedHandlers.unset(errorLogRefresh);
		UiDispatch.unset(UiThread.EXECUTOR);
		TracePages.unsetOpener(browserOpener);
		// a hidden window has no menu to bring it back, so the plug-in going away
		// must not be the moment the IDE becomes unrecoverable
		VisibilityTool.restoreIfHidden();
		// the same reasoning for an ad-hoc stylesheet: it can leave the IDE unreadable
		CssStyling.dropIfApplied();
	}
}
