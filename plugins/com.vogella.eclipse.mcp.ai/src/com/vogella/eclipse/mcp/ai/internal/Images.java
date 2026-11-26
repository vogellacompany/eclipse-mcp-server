package com.vogella.eclipse.mcp.ai.internal;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ResourceLocator;

/**
 * The icons of this bundle.
 */
final class Images {

	static final String NEW_SESSION = "icons/new_session.svg"; //$NON-NLS-1$
	static final String SIDEBAR = "icons/sidebar.svg"; //$NON-NLS-1$
	static final String SEARCH = "icons/search.svg"; //$NON-NLS-1$
	static final String STATUS_BUSY = "icons/status_busy.svg"; //$NON-NLS-1$
	static final String STATUS_IDLE = "icons/status_idle.svg"; //$NON-NLS-1$
	static final String STATUS_WAITING = "icons/status_waiting.svg"; //$NON-NLS-1$
	static final String STATUS_ENDED = "icons/status_ended.svg"; //$NON-NLS-1$
	static final String FOLDER = "icons/folder.svg"; //$NON-NLS-1$
	static final String FOLDER_DARK = "icons/folder_dark.svg"; //$NON-NLS-1$

	private Images() {
	}

	static ImageDescriptor descriptor(String path) {
		return ResourceLocator.imageDescriptorFromBundle(Images.class, path).orElseThrow();
	}
}
