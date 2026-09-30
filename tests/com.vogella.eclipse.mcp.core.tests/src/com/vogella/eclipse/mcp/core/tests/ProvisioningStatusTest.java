package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.p2.internal.ProvisioningStatus;

/**
 * How the status a provisioning job ends with is reported to the client.
 * <p>
 * An install that completed is not allowed to read as a failure: that sends the
 * caller looking for a problem at the wrong end while the software is installed.
 */
class ProvisioningStatusTest {

	private static final String PLUGIN = "com.vogella.eclipse.mcp.p2";

	@Test
	void anInstallThatCompletedWithWarningsIsDoneRatherThanFailed() {
		// p2 warns about the bundleInfo of the version it just replaced, which is not
		// a reason to tell the caller the install failed
		MultiStatus status = new MultiStatus(PLUGIN, 0, "");
		status.add(new Status(IStatus.WARNING, PLUGIN, "Failed to find a configured bundleInfo for: x"));

		assertEquals("done", ProvisioningStatus.stateOf(status));
	}

	@Test
	void informationIsAlsoDone() {
		assertEquals("done", ProvisioningStatus.stateOf(new Status(IStatus.INFO, PLUGIN, "nothing to do")));
	}

	@Test
	void okAndNoStatusAtAllAreDone() {
		assertEquals("done", ProvisioningStatus.stateOf(Status.OK_STATUS));
		assertEquals("done", ProvisioningStatus.stateOf(null));
	}

	@Test
	void onlyAnErrorIsAFailure() {
		assertEquals("failed", ProvisioningStatus.stateOf(new Status(IStatus.ERROR, PLUGIN, "could not resolve")));
	}

	@Test
	void cancellingIsItsOwnState() {
		assertEquals("cancelled", ProvisioningStatus.stateOf(new Status(IStatus.CANCEL, PLUGIN, "stopped")));
	}

	@Test
	void aMultiStatusWithoutATextOfItsOwnIsDescribedByItsChildren() {
		// this is why a failed install used to arrive with an empty message
		MultiStatus status = new MultiStatus(PLUGIN, 0, "");
		status.add(new Status(IStatus.ERROR, PLUGIN, "Cannot complete the install"));
		status.add(new Status(IStatus.ERROR, PLUGIN, "Only one of the following can be installed at once"));

		String described = ProvisioningStatus.describe(status);

		assertTrue(described.contains("Cannot complete the install"), described);
		assertTrue(described.contains("Only one of the following"), described);
	}

	@Test
	void aStatusWithNothingToSayDescribesToNull() {
		assertNull(ProvisioningStatus.describe(new Status(IStatus.OK, PLUGIN, "")));
		assertNull(ProvisioningStatus.describe(null));
	}

	@Test
	void aHugeStatusTreeSaysHowManyLinesWereLeftOut() {
		MultiStatus status = new MultiStatus(PLUGIN, 0, "");
		for (int i = 0; i < 100; i++) {
			status.add(new Status(IStatus.ERROR, PLUGIN, "unit " + i));
		}

		String described = ProvisioningStatus.describe(status);

		assertTrue(described.contains("... and 60 more line(s)"), described);
		assertTrue(described.contains("unit 0") && !described.contains("unit 99"), described);
	}
}
