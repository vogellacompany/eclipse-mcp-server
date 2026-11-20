package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.Launch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.debug.internal.DebugSessionRegistry;

/**
 * Overlapping launches of one configuration must not overwrite each other's pending session.
 */
class DebugSessionRegistryTest {

	private final DebugSessionRegistry registry = DebugSessionRegistry.getInstance();

	private final List<DebugSessionRegistry.Session> created = new ArrayList<>();

	@AfterEach
	void resolveCreated() {
		for (DebugSessionRegistry.Session session : created) {
			registry.fail(session, "test cleanup");
		}
	}

	private DebugSessionRegistry.Session prepare(String name) {
		DebugSessionRegistry.Session session = registry.prepare(name);
		if (session != null) {
			created.add(session);
		}
		return session;
	}

	@Test
	void aSecondPrepareIsRefusedUntilTheFirstAttaches() {
		String name = "mcp overlapping attach " + System.nanoTime();
		DebugSessionRegistry.Session first = prepare(name);
		assertNotNull(first);

		assertNull(prepare(name), "a launch of this configuration is already starting");

		registry.attachLaunched(first, new Launch(null, ILaunchManager.DEBUG_MODE, null));
		assertNotNull(prepare(name), "the name is free again once the first launch attached");
	}

	@Test
	void aSecondPrepareIsAcceptedAgainAfterTheFirstFailed() {
		String name = "mcp overlapping fail " + System.nanoTime();
		DebugSessionRegistry.Session first = prepare(name);
		assertNotNull(first);

		assertNull(prepare(name), "a launch of this configuration is already starting");

		registry.fail(first, "the VM was not found");
		assertNotNull(prepare(name), "the name is free again once the first launch failed");
	}
}
