package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.ui.internal.PaintRecording;
import com.vogella.eclipse.mcp.ui.internal.ScreencastTools;

/**
 * The optional paint recording of the screencast: how it is chosen and refused.
 */
class PaintRecordingTest {

	@Test
	void paintIsRefusedWithTheReasonWhereTheRecorderIsMissing() {
		String reason = PaintRecording.unavailableReason();
		assumeTrue(reason != null, "this SWT can record paint passes");
		McpToolResult result = new ScreencastTools.Start().call(Map.of("method", "paint"), null);
		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains(reason), result.text());
	}

	@Test
	void paintRefusesWhatOnlyFramesCanDo() {
		McpToolResult result = new ScreencastTools.Start()
				.call(Map.of("method", "paint", "bounds", "0,0 10x10", "caption", "x"), null);
		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("bounds, caption"), result.text());
	}

	@Test
	void tuningTheFrameTimerAsksForFrames() {
		McpToolResult result = new ScreencastTools.Start()
				.call(Map.of("method", "paint", "intervalMillis", Integer.valueOf(200)), null);
		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("intervalMillis"), result.text());
	}

	@Test
	void anUnknownMethodIsRefused() {
		McpToolResult result = new ScreencastTools.Start().call(Map.of("method", "video"), null);
		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("auto, paint or frames"), result.text());
	}

	@Test
	void theRecordedShellIsFoundByItsTitleAtStopThenAtStartThenFirst() {
		Map<Integer, String> shells = new LinkedHashMap<>();
		shells.put(Integer.valueOf(3), "Preferences");
		shells.put(Integer.valueOf(1), "workspace - Debug - Eclipse IDE");
		shells.put(Integer.valueOf(2), "workspace - Java - Eclipse IDE");
		assertEquals(new PaintRecording.ShellChoice(Integer.valueOf(1), true),
				PaintRecording.chooseShell(shells, "workspace - Debug - Eclipse IDE", "workspace - Java - Eclipse IDE"));
		assertEquals(new PaintRecording.ShellChoice(Integer.valueOf(2), true),
				PaintRecording.chooseShell(shells, null, "workspace - Java - Eclipse IDE"));
		assertEquals(new PaintRecording.ShellChoice(Integer.valueOf(2), true),
				PaintRecording.chooseShell(shells, "Gone", "Java - Eclipse"));
		assertEquals(new PaintRecording.ShellChoice(Integer.valueOf(3), false),
				PaintRecording.chooseShell(shells, null, ""));
	}

	@Test
	void echoingTheTimerDefaultsKeepsAuto() {
		assertEquals(List.of(), ScreencastTools.framesOnly("shell", null, List.of(), null, null, 0));
		assertEquals(List.of("a part", "bounds", "parts", "caption", "resume", "maxWidth"),
				ScreencastTools.framesOnly("part", "0,0 1x1", List.of("p"), "c", "latest", 800));
		McpToolResult result = new ScreencastTools.Start()
				.call(Map.of("method", "paint", "intervalMillis", Integer.valueOf(500), "maxFrames", Integer.valueOf(120)), null);
		assertFalse(result.text().contains("intervalMillis") || result.text().contains("maxFrames"), result.text());
	}
}
