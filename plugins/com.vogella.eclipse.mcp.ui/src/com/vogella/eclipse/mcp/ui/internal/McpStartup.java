package com.vogella.eclipse.mcp.ui.internal;

import org.eclipse.ui.IStartup;

/**
 * The IDE side of startup. The server itself is started by the declarative service of
 * the server bundle, which an application without this extension point also has.
 */
public class McpStartup implements IStartup {

	@Override
	public void earlyStartup() {
		// the UI bundle is the only one that can reach a browser, so it is the one
		// that teaches the core how to open a trace page
		BrowserOpener.install();
		// every startup, not only when the preference changes: a p2 update rewrites
		// config.ini and would otherwise drop the setting without saying so
		SplashBranding.reconcile();
	}
}
