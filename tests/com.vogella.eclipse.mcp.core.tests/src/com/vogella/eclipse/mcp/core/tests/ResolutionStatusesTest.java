package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.p2.internal.ResolutionStatuses;

/**
 * The flattening of a p2 resolution status into readable reasons.
 * <p>
 * p2 answers a failed resolution with the top level message "Operation details"
 * and hides the actual conflicts in the children, so this is the logic that
 * decides whether a caller learns why. It is pure logic over {@link IStatus},
 * which is why it can be tested directly with hand built statuses.
 */
class ResolutionStatusesTest {

	private static final String PLUGIN = "com.vogella.eclipse.mcp.p2.tests.fixture";

	@Test
	void collectsChildMessagesInOrderThroughNestedMultiStatuses() {
		MultiStatus nested = new MultiStatus(PLUGIN, IStatus.ERROR, new IStatus[] {
				new Status(IStatus.WARNING, PLUGIN, "inner one"),
				new Status(IStatus.WARNING, PLUGIN, "inner two") }, "nested", null);
		MultiStatus root = new MultiStatus(PLUGIN, IStatus.ERROR, "Operation details", null);
		root.add(new Status(IStatus.WARNING, PLUGIN, "first conflict"));
		root.add(nested);

		// the message of the nested status is collected too, not only its leaves: p2
		// puts the readable sentence on the intermediate level, "cannot complete the
		// install because of a conflicting dependency", and the specifics under it
		assertEquals(List.of("first conflict", "nested", "inner one", "inner two"),
				ResolutionStatuses.explanations(root));
	}

	@Test
	void duplicatesCollapseAndBlankMessagesAreSkipped() {
		MultiStatus root = new MultiStatus(PLUGIN, IStatus.ERROR, "Operation details", null);
		root.add(new Status(IStatus.WARNING, PLUGIN, "same conflict"));
		root.add(new Status(IStatus.WARNING, PLUGIN, ""));
		root.add(new Status(IStatus.WARNING, PLUGIN, "   "));
		root.add(new Status(IStatus.WARNING, PLUGIN, "same conflict"));

		assertEquals(List.of("same conflict"), ResolutionStatuses.explanations(root));
	}

	@Test
	void aLeafStatusHasNoExplanations() {
		Status leaf = new Status(IStatus.ERROR, PLUGIN, "just the message");

		assertTrue(ResolutionStatuses.explanations(leaf).isEmpty());
	}

	@Test
	void failureKeepsTheTopLevelMessageAndTheHeadline() {
		MultiStatus root = new MultiStatus(PLUGIN, IStatus.ERROR, "Operation details", null);
		root.add(new Status(IStatus.WARNING, PLUGIN, "the real reason"));

		String text = ResolutionStatuses.failure("The install could not be resolved", root);

		assertTrue(text.startsWith("The install could not be resolved: Operation details"), "got " + text);
		assertTrue(text.contains("- the real reason"), "got " + text);
	}

	@Test
	void failureCapsTheReasonsAndSaysSo() {
		MultiStatus root = new MultiStatus(PLUGIN, IStatus.ERROR, "Operation details", null);
		for (int i = 0; i < 25; i++) {
			root.add(new Status(IStatus.WARNING, PLUGIN, "conflict %d".formatted(i)));
		}

		String text = ResolutionStatuses.failure("The uninstall could not be resolved", root);

		assertEquals(ResolutionStatuses.MAX_EXPLANATIONS,
				text.split("\n- ", -1).length - 1, "got " + text);
		assertTrue(text.contains("showing 20 of 25"), "got " + text);
		assertTrue(text.contains("logged as a warning"), "got " + text);
	}
}
