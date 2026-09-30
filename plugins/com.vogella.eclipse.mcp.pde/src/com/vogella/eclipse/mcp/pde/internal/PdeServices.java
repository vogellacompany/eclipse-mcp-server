package com.vogella.eclipse.mcp.pde.internal;

import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Runs a tool body with one of PDE's OSGi services, releasing it afterwards.
 */
final class PdeServices {

	private PdeServices() {
	}

	/** What a tool does with the service. */
	@FunctionalInterface
	interface Body<S, X extends Exception> {
		McpToolResult apply(S service) throws X;
	}

	/** Answers with an error when the service is not offered, so that {@code body} never sees null. */
	static <S, X extends Exception> McpToolResult with(Class<S> type, String label, Body<S, X> body) throws X {
		// the bundle is lazily activated, so its own context only exists once it started
		BundleContext context = FrameworkUtil.getBundle(PdeServices.class).getBundleContext();
		if (context == null) {
			context = FrameworkUtil.getBundle(type).getBundleContext();
		}
		if (context == null) {
			return McpToolResult.error(
					"Neither this bundle nor PDE is active, so the %s cannot be reached.".formatted(label)); //$NON-NLS-1$
		}
		ServiceReference<S> reference = context.getServiceReference(type);
		S service = reference == null ? null : context.getService(reference);
		if (service == null) {
			return McpToolResult.error("PDE does not offer its %s in this IDE.".formatted(label)); //$NON-NLS-1$
		}
		try {
			return body.apply(service);
		} finally {
			context.ungetService(reference);
		}
	}
}
