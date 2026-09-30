package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.cert.Certificate;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.UIServices;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.p2.internal.HeadlessTrust;

/**
 * How p2's trust prompts are answered without a dialog, and that the IDE's own dialogs always come back.
 */
class HeadlessTrustTest {

	private static final class FakeAgent implements IProvisioningAgent {

		private final Map<String, Object> services = new HashMap<>();

		@Override
		public Object getService(String name) {
			return services.get(name);
		}

		@Override
		public void registerService(String name, Object service) {
			services.put(name, service);
		}

		@Override
		public void unregisterService(String name, Object service) {
			services.remove(name, service);
		}

		@Override
		public void stop() {
		}
	}

	private static final class IdeDialogs extends UIServices {
		@Override
		public AuthenticationInfo getUsernamePassword(String location) {
			return null;
		}

		@Override
		public AuthenticationInfo getUsernamePassword(String location, AuthenticationInfo previous) {
			return null;
		}

		@Override
		public TrustInfo getTrustInfo(Certificate[][] untrustedChains, String[] unsignedDetail) {
			return null;
		}
	}

	@Test
	void refusingUnsignedContentRecordsThePromptAndTrustsNothing() {
		HeadlessTrust trust = new HeadlessTrust(false);

		UIServices.TrustInfo answer = trust.getTrustInfo(null, new String[] { "a.jar" });

		assertFalse(answer.trustUnsignedContent());
		assertFalse(answer.persistTrust());
		assertFalse(answer.trustAlways());
		assertTrue(trust.prompted());
		assertTrue(trust.prompts().get(0).startsWith("REFUSED, unsigned"), trust.prompts().toString());
	}

	@Test
	void trustingUnsignedContentNeverPersistsIt() {
		HeadlessTrust trust = new HeadlessTrust(true);

		UIServices.TrustInfo answer = trust.getTrustInfo(null, new String[] { "a.jar" });

		assertTrue(answer.trustUnsignedContent());
		assertFalse(answer.persistTrust());
		assertFalse(answer.trustAlways());
	}

	@Test
	void theListOfPromptsIsCappedButEveryPromptIsCounted() {
		HeadlessTrust trust = new HeadlessTrust(true);
		String[] details = new String[100];
		java.util.Arrays.fill(details, "artifact.jar");

		trust.getTrustInfo(null, details);

		assertTrue(trust.promptCount() == 100 && trust.prompts().size() == 25, trust.promptCount() + " / " + trust.prompts().size());
	}

	@Test
	void overlappingOperationsLeaveTheIdesDialogsInPlace() {
		FakeAgent agent = new FakeAgent();
		IdeDialogs ide = new IdeDialogs();
		agent.registerService(UIServices.SERVICE_NAME, ide);
		HeadlessTrust first = new HeadlessTrust(true);
		HeadlessTrust second = new HeadlessTrust(false);

		HeadlessTrust.install(agent, first);
		HeadlessTrust.install(agent, second);
		HeadlessTrust.restore(agent, first);
		assertSame(second, agent.getService(UIServices.SERVICE_NAME), "a finished earlier operation must not remove a later one's trust");
		HeadlessTrust.restore(agent, second);

		assertSame(ide, agent.getService(UIServices.SERVICE_NAME));
	}

	@Test
	void aLaterOperationEndingFirstHandsThePromptsBackToTheEarlierOne() {
		FakeAgent agent = new FakeAgent();
		IdeDialogs ide = new IdeDialogs();
		agent.registerService(UIServices.SERVICE_NAME, ide);
		HeadlessTrust first = new HeadlessTrust(true);
		HeadlessTrust second = new HeadlessTrust(false);

		HeadlessTrust.install(agent, first);
		HeadlessTrust.install(agent, second);
		HeadlessTrust.restore(agent, second);
		assertSame(first, agent.getService(UIServices.SERVICE_NAME), "a running operation must never be handed to the IDE's dialogs");
		HeadlessTrust.restore(agent, first);

		assertSame(ide, agent.getService(UIServices.SERVICE_NAME));
	}
}
