package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.json.Json;
import com.vogella.eclipse.mcp.ui.internal.FocusTool;
import com.vogella.eclipse.mcp.ui.internal.NativeAppKit;

/**
 * What eclipse_get_focus says about AppKit, which off Cocoa is nothing.
 */
class FocusAppKitTest {

	@Test
	void offCocoaThereIsNoAppKitAnswer() {
		assumeFalse("cocoa".equals(SWT.getPlatform()));
		Display display;
		try {
			display = new Display();
		} catch (SWTError | SWTException | LinkageError e) {
			assumeTrue(false, "no display: " + e.getMessage());
			return;
		}
		try {
			assertNull(NativeAppKit.describe(display, null));
			assertNull(NativeAppKit.active());
			@SuppressWarnings("unchecked")
			Map<String, Object> focus = (Map<String, Object>) Json.parse(FocusTool.describe(display).toString());
			assertTrue(focus.containsKey("appKit"), "got " + focus);
			assertNull(focus.get("appKit"), "got " + focus);
		} finally {
			display.dispose();
		}
	}

	@Test
	void theDescriptionNamesEveryAppKitField() {
		String description = TestFixture.tool("eclipse_get_focus").getDescription();
		for (String field : new String[] { "appKit", "active", "keyWindow", "firstResponder", "nativeClass",
				"widgetClass", "path", "isFocusControl", "responderWindow", "unreadable" }) {
			assertTrue(description.contains(field), field + " missing from " + description);
		}
	}
}
