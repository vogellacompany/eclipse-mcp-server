package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.CompileErrorPrompt;

/**
 * Pre-answering the compile error prompt for one launch, and putting it back.
 * <p>
 * The counting is the part worth testing: two overlapping launches would
 * otherwise save "always" as the value to restore and pin the user's setting,
 * and the second launch would report "always" instead of the original value.
 */
class CompileErrorPromptTest {

	private static final String DEBUG_UI = "org.eclipse.debug.ui";

	private static final String KEY = "org.eclipse.debug.ui.cancel_launch_with_compile_errors";

	@BeforeEach
	void startFromAKnownValue() throws Exception {
		while (CompileErrorPrompt.held() > 0) {
			CompileErrorPrompt.restore();
		}
		InstanceScope.INSTANCE.getNode(DEBUG_UI).put(KEY, "prompt");
		InstanceScope.INSTANCE.getNode(DEBUG_UI).flush();
	}

	@AfterEach
	void restoreWhatTheTestHeld() throws Exception {
		while (CompileErrorPrompt.held() > 0) {
			CompileErrorPrompt.restore();
		}
		InstanceScope.INSTANCE.getNode(DEBUG_UI).remove(KEY);
		InstanceScope.INSTANCE.getNode(DEBUG_UI).flush();
	}

	@Test
	void theFirstSuppressAnswersThePromptAndReportsTheOriginal() {
		String original = CompileErrorPrompt.suppress();

		assertEquals("prompt", original);
		assertEquals("always", CompileErrorPrompt.effectiveValue());

		CompileErrorPrompt.restore();

		assertEquals("prompt", CompileErrorPrompt.effectiveValue());
	}

	@Test
	void twoOverlappingPairsRestoreTheOriginalWhenTheFirstEndsLast() {
		String first = CompileErrorPrompt.suppress();
		String second = CompileErrorPrompt.suppress();

		assertEquals("prompt", first);
		assertEquals("prompt", second, "an overlapping launch must report the user's original value, not always");

		CompileErrorPrompt.restore();

		assertEquals("always", CompileErrorPrompt.effectiveValue(),
				"one launch has ended, the other has not, so the prompt has to stay answered");
		assertEquals(1, CompileErrorPrompt.held());

		CompileErrorPrompt.restore();

		assertEquals("prompt", CompileErrorPrompt.effectiveValue());
		assertEquals(0, CompileErrorPrompt.held());
	}

	@Test
	void theOriginalValueIsTheUsersWhileALaunchHoldsTheSuppression() {
		assertEquals("prompt", CompileErrorPrompt.originalValue());
		CompileErrorPrompt.suppress();
		try {
			assertEquals("prompt", CompileErrorPrompt.originalValue(), "a held suppression must not report always");
		} finally {
			CompileErrorPrompt.restore();
		}
	}

	@Test
	void twoOverlappingPairsRestoreTheOriginalWhenTheSecondEndsLast() {
		String first = CompileErrorPrompt.suppress();
		String second = CompileErrorPrompt.suppress();

		assertEquals("prompt", first);
		assertEquals("prompt", second);

		CompileErrorPrompt.restore();
		CompileErrorPrompt.restore();

		assertEquals("prompt", CompileErrorPrompt.effectiveValue());
	}

	@Test
	void anAbsentKeyIsRemovedAgainAndBothSuppressionsReportNull() {
		InstanceScope.INSTANCE.getNode(DEBUG_UI).remove(KEY);

		String first = CompileErrorPrompt.suppress();
		String second = CompileErrorPrompt.suppress();

		assertNull(first);
		assertNull(second, "an overlapping launch must report the absent original, not always");

		CompileErrorPrompt.restore();
		CompileErrorPrompt.restore();

		assertNull(CompileErrorPrompt.effectiveValue(), "restoring must not write a value the user never had");
	}
}
