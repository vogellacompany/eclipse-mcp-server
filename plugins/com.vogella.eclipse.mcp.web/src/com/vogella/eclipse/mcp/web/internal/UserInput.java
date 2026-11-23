package com.vogella.eclipse.mcp.web.internal;

import java.io.IOException;

import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Real input events sent through the DevTools protocol, so the page sees trusted events as from a user.
 */
final class UserInput {

	private UserInput() {
	}

	/** Moves to the point and presses and releases the left button {@code clicks} times. */
	static void click(WebBrowser browser, double x, double y, int clicks) throws IOException {
		browser.send("Input.dispatchMouseEvent", event("mouseMoved", x, y, 0)); //$NON-NLS-1$ //$NON-NLS-2$
		for (int count = 1; count <= clicks; count++) {
			browser.send("Input.dispatchMouseEvent", event("mousePressed", x, y, count)); //$NON-NLS-1$ //$NON-NLS-2$
			browser.send("Input.dispatchMouseEvent", event("mouseReleased", x, y, count)); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private static JsonObject event(String type, double x, double y, int clickCount) {
		JsonObject event = new JsonObject().put("type", type).put("x", Double.valueOf(x)).put("y", Double.valueOf(y)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (clickCount > 0) {
			event.put("button", "left").put("buttons", Integer.valueOf(1)).put("clickCount", Integer.valueOf(clickCount)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		}
		return event;
	}

	/** Presses and releases a key without text, such as Enter or Backspace. */
	static void press(WebBrowser browser, String key, int keyCode, String text) throws IOException {
		JsonObject down = new JsonObject().put("type", text == null ? "rawKeyDown" : "keyDown").put("key", key) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				.put("code", key).put("windowsVirtualKeyCode", Integer.valueOf(keyCode)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("nativeVirtualKeyCode", Integer.valueOf(keyCode)); //$NON-NLS-1$
		if (text != null) {
			down.put("text", text).put("unmodifiedText", text); //$NON-NLS-1$ //$NON-NLS-2$
		}
		browser.send("Input.dispatchKeyEvent", down); //$NON-NLS-1$
		browser.send("Input.dispatchKeyEvent", new JsonObject().put("type", "keyUp").put("key", key).put("code", key) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
				.put("windowsVirtualKeyCode", Integer.valueOf(keyCode)) //$NON-NLS-1$
				.put("nativeVirtualKeyCode", Integer.valueOf(keyCode))); //$NON-NLS-1$
	}

	/** Types text into the focused element as one input, the way an input method commits it. */
	static void insertText(WebBrowser browser, String text) throws IOException {
		browser.send("Input.insertText", new JsonObject().put("text", text)); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
