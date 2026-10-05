package com.vogella.eclipse.mcp.ui.internal;

import java.net.URL;

import org.eclipse.swt.program.Program;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.browser.IWorkbenchBrowserSupport;

import com.vogella.eclipse.mcp.core.TracePages;

/**
 * Opens a trace page in the machine's browser, through the workbench's browser support
 * first and SWT's {@code Program} when that fails.
 */
final class BrowserOpener implements TracePages.Opener {

	/** An id of our own, so repeated opens reuse one external browser rather than piling up. */
	private static final String BROWSER_ID = "com.vogella.eclipse.mcp.trace"; //$NON-NLS-1$

	@Override
	public String open(String url) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return Workbenches.noIde();
		}
		// asyncExec, never syncExec: the call arrives on a request thread and opening
		// a browser can block on the desktop, which must not take that thread with it
		UiThread.TimedOutcome outcome = UiThread.timed(10, () -> {
			launch(url);
			return null;
		});
		if (outcome.timedOut()) {
			// the launch was queued and may still happen, so this is not a failure
			return "The browser did not open within ten seconds. The URL is in this answer either way."; //$NON-NLS-1$
		}
		return outcome.error();
	}

	private static void launch(String url) {
		try {
			IWorkbenchBrowserSupport support = PlatformUI.getWorkbench().getBrowserSupport();
			support.createBrowser(IWorkbenchBrowserSupport.AS_EXTERNAL, BROWSER_ID, null, null)
					.openURL(new URL(url));
		} catch (Exception e) {
			// no configured browser, or none this platform can start that way
			if (!Program.launch(url)) {
				throw new IllegalStateException("No browser could be started for " + url, e); //$NON-NLS-1$
			}
		}
	}
}
