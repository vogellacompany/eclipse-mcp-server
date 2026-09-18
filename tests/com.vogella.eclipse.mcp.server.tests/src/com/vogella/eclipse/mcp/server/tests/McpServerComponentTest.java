package com.vogella.eclipse.mcp.server.tests;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.server.McpServerService;

/**
 * Covers the declarative service that starts the server, which is what an application
 * without the IDE's {@code org.eclipse.ui.startup} extension point runs on.
 */
class McpServerComponentTest {

	private static final String DESCRIPTOR = "OSGI-INF/com.vogella.eclipse.mcp.server.internal.McpServerComponent.xml"; //$NON-NLS-1$

	/**
	 * The descriptor is generated at build time, so a build that lost the generation
	 * leaves a header pointing at nothing and a server that never starts, silently.
	 */
	@Test
	void theDescriptorIsInTheBundle() {
		Bundle server = FrameworkUtil.getBundle(McpServerService.class);
		assertTrue(DESCRIPTOR.equals(server.getHeaders().get("Service-Component")), //$NON-NLS-1$
				"The Service-Component header should name the generated descriptor"); //$NON-NLS-1$
		assertNotNull(server.getEntry(DESCRIPTOR), "The generated descriptor should be packaged"); //$NON-NLS-1$
	}

	/** The component is the only thing publishing the service, so this proves it activated. */
	@Test
	void theServiceIsPublished() {
		Bundle server = FrameworkUtil.getBundle(McpServerService.class);
		assertNotNull(server.getBundleContext().getServiceReference(McpServerService.class),
				"The declarative service should have published McpServerService"); //$NON-NLS-1$
	}
}
