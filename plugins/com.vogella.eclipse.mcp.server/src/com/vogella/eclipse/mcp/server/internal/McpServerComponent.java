package com.vogella.eclipse.mcp.server.internal;

import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.osgi.service.datalocation.Location;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

import com.vogella.eclipse.mcp.server.McpServerLifecycle;
import com.vogella.eclipse.mcp.server.McpServerService;

/**
 * Publishes {@link McpServerService} and starts it with the framework, so that an
 * application without the IDE's {@code org.eclipse.ui.startup} extension point, an
 * RCP application above all, gets a server from the same preferences.
 */
@Component(immediate = true)
public final class McpServerComponent {

	/**
	 * Never read, and not dead code: the tool list is built once when the server starts,
	 * from the extension registry, so a component that activated before the registry
	 * exists would serve a short list for the rest of the session.
	 */
	@Reference
	IExtensionRegistry registry;

	/**
	 * Never read either: the preferences live in instance scope, which throws until the
	 * workspace is chosen, and Equinox adds {@code url} to this service only once it is.
	 */
	@Reference(target = "(&(type=osgi.instance.area)(url=*))")
	Location instanceLocation;

	private ServiceRegistration<McpServerService> registration;

	@Activate
	void activate(BundleContext context) {
		// declaring the service through DS would publish an instance of its own, while
		// every caller in and outside this bundle goes through the singleton
		registration = context.registerService(McpServerService.class, McpServerService.getInstance(), null);
		// no preference read here: this can run inside the content type manager's own
		// activation, and loading Platform then leaves it null for the whole session
		McpServerLifecycle.reconcile();
	}

	@Deactivate
	void deactivate() {
		if (registration != null) {
			registration.unregister();
			registration = null;
		}
		McpServerService.getInstance().stop();
	}
}
