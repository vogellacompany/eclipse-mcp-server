package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.HeadlessInput;

class HeadlessInputTest {

	@Test
	void anSwtWithoutTheHeadlessBackendAnswersWhyRatherThanThrowing() {
		assertFalse(HeadlessInput.applies());
		String failure = HeadlessInput.mouseMove(10, 10);
		assertTrue(failure != null && failure.contains("RecordingInput.mouseMove"), failure);
	}
}
