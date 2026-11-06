package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.CommandTools;

class LateCompletionMessageTest {

	@Test
	void aCommandThatAnsweredDialogOpenIsNotReportedAsALostResult() {
		String message = CommandTools.Run.lateCompletionMessage("org.eclipse.ui.newWizard", "New Feature", "{}", null);
		assertTrue(message.contains("ended after the call had answered dialogOpen for 'New Feature'"), message);
		assertTrue(message.contains("its answer was: {}"), message);
		assertFalse(message.contains("never saw"), message);
	}

	@Test
	void aFailureAfterDialogOpenNamesTheDialogAndTheError() {
		String message = CommandTools.Run.lateCompletionMessage("org.eclipse.ui.newWizard", "New Feature", null,
				new IllegalStateException("broken"));
		assertTrue(message.contains("answered dialogOpen for 'New Feature'. it failed with: java.lang.IllegalStateException: broken"),
				message);
		assertFalse(message.contains("never saw"), message);
	}

	@Test
	void aNullAnswerAfterDialogOpenSaysNoResult() {
		String message = CommandTools.Run.lateCompletionMessage("org.eclipse.ui.newWizard", "New Feature", null, null);
		assertTrue(message.endsWith("no result"), message);
	}

	@Test
	void aTimedOutCommandStillSaysTheCallerNeverSawTheResult() {
		String message = CommandTools.Run.lateCompletionMessage("org.eclipse.ui.newWizard", null, null, null);
		assertTrue(message.contains("the caller never saw this result"), message);
	}
}
