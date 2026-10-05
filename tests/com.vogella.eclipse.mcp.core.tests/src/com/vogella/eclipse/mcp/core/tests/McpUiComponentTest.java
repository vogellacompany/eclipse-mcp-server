package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;

import com.vogella.eclipse.mcp.ui.internal.McpUiComponent;

/**
 * Covers the declarative service that registers the UI hooks, which replaced the
 * bundle activator and the {@code org.eclipse.ui.startup} extension.
 */
class McpUiComponentTest {

	private static final String DESCRIPTOR = "OSGI-INF/com.vogella.eclipse.mcp.ui.internal.McpUiComponent.xml"; //$NON-NLS-1$

	@Test
	void theDescriptorIsInTheBundleAndThereIsNoActivator() {
		Bundle ui = FrameworkUtil.getBundle(McpUiComponent.class);
		assertEquals(DESCRIPTOR, ui.getHeaders().get("Service-Component"), //$NON-NLS-1$
				"The Service-Component header should name the descriptor"); //$NON-NLS-1$
		assertNotNull(ui.getEntry(DESCRIPTOR), "The descriptor should be packaged"); //$NON-NLS-1$
		assertNull(ui.getHeaders().get("Bundle-Activator"), "The hooks are registered by the component"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	@Test
	void theDescriptorWiresTheLifecycleMethodsImmediately() throws IOException {
		Bundle ui = FrameworkUtil.getBundle(McpUiComponent.class);
		String xml;
		try (InputStream in = ui.getEntry(DESCRIPTOR).openStream()) {
			xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		assertTrue(xml.contains("activate=\"activate\""), "activate should be wired"); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(xml.contains("deactivate=\"deactivate\""), "deactivate should be wired"); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(xml.contains("immediate=\"true\""), "the component should be immediate"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	@Test
	void scrActivatesTheComponentOnceTheBundleStarts() throws BundleException, InterruptedException {
		Bundle ui = FrameworkUtil.getBundle(McpUiComponent.class);
		ui.start();
		// asks SCR rather than probing a hook, since other tests replace the hooks
		BundleContext context = ui.getBundleContext();
		ServiceReference<ServiceComponentRuntime> reference = context.getServiceReference(ServiceComponentRuntime.class);
		assertNotNull(reference, "SCR should be running"); //$NON-NLS-1$
		ServiceComponentRuntime scr = context.getService(reference);
		try {
			ComponentDescriptionDTO description = scr.getComponentDescriptionDTO(ui, McpUiComponent.class.getName());
			assertNotNull(description, "SCR should know the component"); //$NON-NLS-1$
			int state = -1;
			for (int i = 0; i < 100 && state != ComponentConfigurationDTO.ACTIVE; i++) {
				Collection<ComponentConfigurationDTO> configurations = scr.getComponentConfigurationDTOs(description);
				state = configurations.isEmpty() ? -1 : configurations.iterator().next().state;
				if (state != ComponentConfigurationDTO.ACTIVE) {
					Thread.sleep(100);
				}
			}
			assertEquals(ComponentConfigurationDTO.ACTIVE, state, "SCR should have activated the component"); //$NON-NLS-1$
		} finally {
			context.ungetService(reference);
		}
	}
}
