package com.vogella.eclipse.mcp.p2.internal;

import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bouncycastle.openpgp.PGPPublicKey;

import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.UIServices;

/**
 * Answers p2's trust and credential prompts without a human.
 * <p>
 * The IDE's own {@code UIServices} raises a modal dialog that hangs an unattended job
 * like a slow download. Unsigned content is accepted by default, since nobody is there
 * to click and a client can add sites through eclipse_add_repository anyway; whatever was
 * accepted is reported.
 */
public final class HeadlessTrust extends UIServices {

	private final boolean trustUnsigned;

	/** An SDK install asks about hundreds of artifacts; the list is evidence, not a manifest. */
	private static final int MAX_PROMPTS = 25;

	private final List<String> prompts = new ArrayList<>();

	private int promptCount;

	private volatile boolean prompted;

	/** The IDE's own services, per agent, while at least one operation has its trust installed. */
	private static final Map<IProvisioningAgent, Object> ORIGINALS = new HashMap<>();

	/** The installed trusts per agent, the last one registered. */
	private static final Map<IProvisioningAgent, Deque<HeadlessTrust>> ACTIVE = new HashMap<>();

	public HeadlessTrust(boolean trustUnsigned) {
		this.trustUnsigned = trustUnsigned;
	}

	public boolean prompted() {
		return prompted;
	}

	/** What p2 asked about, whether it was then trusted or refused, capped. */
	public synchronized List<String> prompts() {
		return List.copyOf(prompts);
	}

	/** How many it asked about in total, which is usually more than the list holds. */
	public synchronized int promptCount() {
		return promptCount;
	}

	private void record(String prompt) {
		promptCount++;
		if (prompts.size() < MAX_PROMPTS) {
			prompts.add(prompt);
		}
	}

	@Override
	public TrustInfo getTrustInfo(Certificate[][] untrustedChains, String[] unsignedDetail) {
		return trust(untrustedChains, List.of(), unsignedDetail);
	}

	/**
	 * The overload p2 actually reaches for a signed artifact.
	 * <p>
	 * The default on {@link UIServices} drops the PGP keys and calls the pair above, which
	 * cancelled installs from locally built repositories, so it must be overridden.
	 */
	@Override
	public TrustInfo getTrustInfo(Certificate[][] untrustedChains, Collection<PGPPublicKey> untrustedKeys,
			String[] unsignedDetail) {
		return trust(untrustedChains, untrustedKeys, unsignedDetail);
	}

	private TrustInfo trust(Certificate[][] untrustedChains, Collection<PGPPublicKey> untrustedKeys,
			String[] unsignedDetail) {
		prompted = true;
		List<Certificate> certificates = new ArrayList<>();
		if (untrustedChains != null) {
			for (Certificate[] chain : untrustedChains) {
				if (chain != null && chain.length > 0) {
					// the leaf is what signed the artifact; trusting the whole chain
					// would accept everything the issuer ever signed
					certificates.add(chain[0]);
				}
			}
		}
		Set<PGPPublicKey> keys = new LinkedHashSet<>(untrustedKeys == null ? List.of() : untrustedKeys);
		synchronized (this) {
			if (unsignedDetail != null) {
				for (String detail : unsignedDetail) {
					record(trustUnsigned ? "unsigned: " + detail : "REFUSED, unsigned: " + detail);
				}
			}
			for (Certificate certificate : certificates) {
				record((trustUnsigned ? "certificate: " : "REFUSED, certificate: ") + describe(certificate));
			}
			for (PGPPublicKey key : keys) {
				record((trustUnsigned ? "PGP key: " : "REFUSED, PGP key: ") + Long.toHexString(key.getKeyID()));
			}
		}
		if (!trustUnsigned) {
			// refuse everything, which is what the caller asked for
			return new TrustInfo(List.of(), List.of(), false, false);
		}
		// trust exactly what was presented, for this operation only. persistTrust
		// stays false so nothing reaches the IDE's permanent trust store, and
		// trustAlways is never returned because p2 writes that into a preference and
		// a switch flipped once is never flipped back
		return new TrustInfo(certificates, keys, false, true);
	}

	/** Enough of a certificate to recognise it in an answer. */
	private static String describe(Certificate certificate) {
		if (certificate instanceof java.security.cert.X509Certificate x509) {
			return String.valueOf(x509.getSubjectX500Principal());
		}
		return certificate.getType();
	}

	@Override
	public AuthenticationInfo getUsernamePassword(String location) {
		prompted = true;
		synchronized (this) {
			record("credentials for " + location);
		}
		return AUTHENTICATION_PROMPT_CANCELED;
	}

	@Override
	public AuthenticationInfo getUsernamePassword(String location, AuthenticationInfo previous) {
		return getUsernamePassword(location);
	}

	/**
	 * Installs {@code trust} in place of the IDE's dialogs.
	 * <p>
	 * Overlapping operations each install their own trust, and the IDE's dialogs
	 * come back only when the last of them has ended, whatever order they end in.
	 */
	public static void install(IProvisioningAgent agent, HeadlessTrust trust) {
		synchronized (ACTIVE) {
			Deque<HeadlessTrust> active = ACTIVE.computeIfAbsent(agent, a -> new ArrayDeque<>());
			if (active.isEmpty()) {
				ORIGINALS.put(agent, agent.getService(UIServices.SERVICE_NAME));
			}
			active.add(trust);
			agent.registerService(UIServices.SERVICE_NAME, trust);
		}
	}

	/** Ends {@code trust}, handing the prompts to a still running operation's trust or back to the IDE. */
	public static void restore(IProvisioningAgent agent, HeadlessTrust trust) {
		synchronized (ACTIVE) {
			Deque<HeadlessTrust> active = ACTIVE.get(agent);
			if (active == null || !active.remove(trust)) {
				return;
			}
			Object next = active.isEmpty() ? ORIGINALS.remove(agent) : active.peekLast();
			if (active.isEmpty()) {
				ACTIVE.remove(agent);
			}
			// registering replaces in place, so a prompt never meets a moment without a trust and falls to a dialog
			if (next != null) {
				agent.registerService(UIServices.SERVICE_NAME, next);
			} else {
				Object current = agent.getService(UIServices.SERVICE_NAME);
				if (current != null) {
					agent.unregisterService(UIServices.SERVICE_NAME, current);
				}
			}
		}
	}
}
