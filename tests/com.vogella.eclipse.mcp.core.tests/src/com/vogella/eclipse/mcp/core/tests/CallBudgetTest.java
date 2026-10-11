package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.CallBudget;

/**
 * How long a tool may wait inside the server's call timeout.
 */
class CallBudgetTest {

	private static final String QUALIFIER = "com.vogella.eclipse.mcp.server";

	private static final String KEY = "callTimeoutSeconds";

	@AfterEach
	void restoreTimeout() {
		InstanceScope.INSTANCE.getNode(QUALIFIER).remove(KEY);
		InstanceScope.INSTANCE.getNode(QUALIFIER).remove(CallBudget.UI_WAIT_KEY);
	}

	private static void timeout(int seconds) {
		InstanceScope.INSTANCE.getNode(QUALIFIER).putInt(KEY, seconds);
	}

	@Test
	void theDefaultTimeoutLeavesAMarginForTheAnswer() {
		assertEquals(30, CallBudget.callTimeoutSeconds());
		assertEquals(27, CallBudget.maxWaitSeconds());
	}

	@Test
	void aShortTimeoutStillAllowsAOneSecondWait() {
		timeout(4);
		assertEquals(1, CallBudget.maxWaitSeconds());

		timeout(1);
		assertEquals(1, CallBudget.maxWaitSeconds(), "the wait never drops below a second");
	}

	@Test
	void aRequestIsCappedAtTheBudgetAndNeverRaisedToIt() {
		timeout(10);

		assertEquals(7, CallBudget.boundedWaitSeconds(60));
		assertEquals(3, CallBudget.boundedWaitSeconds(3));
	}

	@Test
	void aWaitThatFitsNeedsNoNote() {
		timeout(10);

		assertNull(CallBudget.clampNote(7, "eclipse_build"));
		assertNull(CallBudget.clampNote(2, "eclipse_build"));
	}

	@Test
	void aClampedWaitSaysWhatHappenedAndWhichHandleToPoll() {
		timeout(10);

		String note = CallBudget.clampNote(60, "eclipse_get_build_status");

		assertTrue(note.contains("Waited 7 of the 60 seconds"), note);
		assertTrue(note.contains("10 second"), note);
		assertTrue(note.contains("eclipse_get_build_status"), note);
	}

	@Test
	void theUiWaitIsTheLongerOfToolAndPreference() {
		assertEquals(CallBudget.DEFAULT_UI_WAIT_SECONDS, CallBudget.uiWaitSeconds(10));
		InstanceScope.INSTANCE.getNode(QUALIFIER).putInt(CallBudget.UI_WAIT_KEY, 5);
		assertEquals(15, CallBudget.uiWaitSeconds(15));
		InstanceScope.INSTANCE.getNode(QUALIFIER).putInt(CallBudget.UI_WAIT_KEY, 600);
		assertEquals(27, CallBudget.uiWaitSeconds(10), "capped by the call timeout");
	}

	@Test
	void insideACallEveryWaitIsCappedByWhatIsLeftOfIt() {
		CallBudget.enter(10);
		try {
			int ui = CallBudget.uiWaitSeconds(5);
			int bounded = CallBudget.boundedWaitSeconds(60);
			assertTrue(ui >= 6 && ui <= 7, "got " + ui);
			assertTrue(bounded >= 6 && bounded <= 7, "got " + bounded);
		} finally {
			CallBudget.leave();
		}
		assertEquals(27, CallBudget.boundedWaitSeconds(60), "outside a call the whole timeout applies again");
	}
}
