package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Puts text and key events into the IDE, so an editor state can be set up.
 */
public final class KeyboardTools {

	private static final long UI_TIMEOUT_SECONDS = 10;

	private KeyboardTools() {
	}

	private static ITextEditor textEditor(String partId) {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		if (page == null) {
			throw new IllegalStateException("There is no active workbench page."); //$NON-NLS-1$
		}
		IEditorPart editor;
		if (partId == null || partId.isBlank()) {
			editor = page.getActiveEditor();
			if (editor == null) {
				throw new IllegalStateException("There is no active editor. Give 'part' to name one."); //$NON-NLS-1$
			}
		} else {
			editor = Parts.part(page, partId) instanceof IEditorPart named ? named : null;
			if (editor == null) {
				throw new IllegalStateException(
						"No open editor has the id '%s'. eclipse_list_ui_targets lists the open parts.".formatted(partId)); //$NON-NLS-1$
			}
		}
		if (!(editor instanceof ITextEditor textEditor)) {
			throw new IllegalStateException("The editor '%s' is not a text editor.".formatted(editor.getTitle())); //$NON-NLS-1$
		}
		return textEditor;
	}

	/** Inserts text at the caret of a text editor through its document. */
	public static final class TypeText implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_type_text"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Inserts text at the caret of the active, or named, text editor by editing its document, the way a typed key ends up in the file: the caret moves behind the text and the editor becomes dirty. CHANGES THE DOCUMENT. This is the deterministic way to set up an editor state, independent of focus and keyboard layout, so prefer it over eclipse_press_key for entering characters. A current selection is replaced. It does NOT open content assist or run any key binding; use eclipse_press_key for Ctrl+Space and the like. The answer reports the new caret offset and line. Nothing is saved."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "text": {"type":"string","description":"The text to insert at the caret. A current selection is replaced by it."},
					    "part": {"type":"string","description":"Editor part id. Defaults to the active editor. Several open parts share one id, every Java editor above all; the active or a visible one is chosen, and a part's TITLE from eclipse_list_ui_targets names a particular one."}
					  },
					  "required": ["text"],
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String text = args.getString("text"); //$NON-NLS-1$
			if (text == null) {
				return McpToolResult.error("Give the 'text' to insert."); //$NON-NLS-1$
			}
			String part = args.getString("part"); //$NON-NLS-1$
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> {
				ITextEditor editor = textEditor(part);
				IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
				if (document == null) {
					throw new IllegalStateException("The editor '%s' has no document.".formatted(editor.getTitle())); //$NON-NLS-1$
				}
				int offset = 0;
				int length = 0;
				if (editor.getSelectionProvider() != null
						&& editor.getSelectionProvider().getSelection() instanceof ITextSelection selection) {
					offset = selection.getOffset();
					length = selection.getLength();
				}
				try {
					document.replace(offset, length, text);
				} catch (BadLocationException e) {
					throw new IllegalStateException("The caret is not a valid place to insert: " + e.getMessage()); //$NON-NLS-1$
				}
				int caret = offset + text.length();
				editor.selectAndReveal(caret, 0);
				int line;
				try {
					line = document.getLineOfOffset(caret) + 1;
				} catch (BadLocationException e) {
					line = -1;
				}
				return new JsonObject().put("editor", editor.getTitle()) //$NON-NLS-1$
						.put("inserted", text.length()) //$NON-NLS-1$
						.put("replacedSelection", Integer.valueOf(length)) //$NON-NLS-1$
						.put("caretOffset", Integer.valueOf(caret)) //$NON-NLS-1$
						.put("caretLine", Integer.valueOf(line)) //$NON-NLS-1$
						.put("dirty", Boolean.valueOf(editor.isDirty())) //$NON-NLS-1$
						.put("note", "Inserted through the document. The editor is dirty and not saved."); //$NON-NLS-1$ //$NON-NLS-2$
			});
		}
	}

	/** Posts real key events to the focused control. */
	public static final class PressKey implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_press_key"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Posts real key events through Display.post to whatever control has focus, for the cases the document API cannot reach: Ctrl+Space to open content assist, Escape to close a popup, Enter to accept a proposal, arrow keys to move the selection in a proposal table, Tab, Backspace. CHANGES WHAT THE IDE DOES, which is whatever that key does with the current focus. Take a key like 'Ctrl+Space', 'Escape', 'Down', 'Enter', 'Tab', 'BackSpace', or a single character. Display.post goes to the window that has OS focus, so this refuses when the IDE is not the active window rather than sending the key somewhere else. On Wayland Display.post is often ignored by the compositor; the answer reports whether the post was accepted and whether focus was inside the IDE, and for entering plain characters eclipse_type_text is the reliable path. count repeats the key."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "key":       {"type":"string","description":"A key name like 'Ctrl+Space', 'Escape', 'Down', 'Up', 'Left', 'Right', 'Enter', 'Tab', 'BackSpace', 'Delete', 'Home', 'End', 'PageDown', 'F3', or a single character."},
					    "count":     {"type":"integer","default":1,"minimum":1,"maximum":100,"description":"How many times to send the key."}
					  },
					  "required": ["key"],
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String key = args.getString("key"); //$NON-NLS-1$
			if (key == null || key.isBlank()) {
				return McpToolResult.error("Give the 'key' to press, e.g. 'Ctrl+Space' or 'Escape'."); //$NON-NLS-1$
			}
			int count = args.getInt("count", 1, 1, 100); //$NON-NLS-1$
			Stroke stroke;
			try {
				stroke = parse(key);
			} catch (IllegalArgumentException e) {
				return McpToolResult.error(e.getMessage());
			}
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> {
				Display display = PlatformUI.getWorkbench().getDisplay();
				if (display.getActiveShell() == null) {
					throw new IllegalStateException(
							"The IDE is not the active window, so a posted key would go to another application. Bring the IDE to the front, or use eclipse_type_text for plain text."); //$NON-NLS-1$
				}
				Control focus = display.getFocusControl();
				boolean posted = true;
				for (int i = 0; i < count && posted; i++) {
					posted = post(display, stroke);
				}
				JsonObject result = new JsonObject().put("key", key) //$NON-NLS-1$
						.put("count", Integer.valueOf(count)) //$NON-NLS-1$
						.put("posted", Boolean.valueOf(posted)) //$NON-NLS-1$
						.put("focusControl", focus == null ? null : focus.getClass().getSimpleName()) //$NON-NLS-1$
						.put("focusInsideIde", Boolean.valueOf(focus != null)); //$NON-NLS-1$
				String wayland = System.getenv("WAYLAND_DISPLAY"); //$NON-NLS-1$
				if (!posted) {
					result.put("note", //$NON-NLS-1$
							"Display.post returned false, so the key was not delivered. On Wayland posting synthetic events is commonly blocked; use eclipse_type_text for characters and eclipse_run_workbench_command for a command bound to a key."); //$NON-NLS-1$
				} else if (wayland != null && !wayland.isBlank()) {
					result.put("waylandWarning", //$NON-NLS-1$
							"The session is Wayland, where a posted event may be silently ignored by the compositor even though the post was accepted. Verify the effect with a screenshot; if nothing happened, use eclipse_type_text or a command."); //$NON-NLS-1$
				}
				return result;
			});
		}

		private static boolean post(Display display, Stroke stroke) {
			boolean ok = true;
			for (int modifier : stroke.modifiers()) {
				ok &= postEvent(display, SWT.KeyDown, modifier, (char) 0);
			}
			ok &= postEvent(display, SWT.KeyDown, stroke.keyCode(), stroke.character());
			ok &= postEvent(display, SWT.KeyUp, stroke.keyCode(), stroke.character());
			List<Integer> mods = stroke.modifiers();
			for (int i = mods.size() - 1; i >= 0; i--) {
				ok &= postEvent(display, SWT.KeyUp, mods.get(i).intValue(), (char) 0);
			}
			return ok;
		}

		private static boolean postEvent(Display display, int type, int keyCode, char character) {
			Event event = new Event();
			event.type = type;
			event.keyCode = keyCode;
			if (character != 0) {
				event.character = character;
			}
			return display.post(event);
		}
	}

	/** A resolved keystroke: its modifiers, key code and character. */
	private record Stroke(List<Integer> modifiers, int keyCode, char character) {
	}

	static Stroke parse(String key) {
		List<Integer> modifiers = new ArrayList<>();
		String[] parts = key.split("\\+"); //$NON-NLS-1$
		String main = key;
		for (int i = 0; i < parts.length; i++) {
			String token = parts[i].strip();
			Integer modifier = modifier(token);
			if (modifier != null && i < parts.length - 1) {
				modifiers.add(modifier);
			} else {
				main = token;
			}
		}
		// a lone '+' pressed as the last segment splits to an empty token
		if (main.isEmpty() && key.endsWith("+")) { //$NON-NLS-1$
			main = "+"; //$NON-NLS-1$
		}
		int keyCode = named(main);
		char character = 0;
		if (keyCode == 0) {
			if (main.length() != 1) {
				throw new IllegalArgumentException(
						"'%s' is not a known key. Use a name like Ctrl+Space, Escape, Down or Enter, or a single character." //$NON-NLS-1$
								.formatted(key));
			}
			character = main.charAt(0);
			keyCode = Character.toLowerCase(character);
		}
		return new Stroke(modifiers, keyCode, character);
	}

	private static Integer modifier(String token) {
		return switch (token.toLowerCase(Locale.ROOT)) {
		case "ctrl", "control" -> Integer.valueOf(SWT.CTRL); //$NON-NLS-1$ //$NON-NLS-2$
		case "shift" -> Integer.valueOf(SWT.SHIFT); //$NON-NLS-1$
		case "alt" -> Integer.valueOf(SWT.ALT); //$NON-NLS-1$
		case "cmd", "command", "meta" -> Integer.valueOf(SWT.COMMAND); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		default -> null;
		};
	}

	private static int named(String name) {
		return switch (name.toLowerCase(Locale.ROOT)) {
		case "space" -> ' '; //$NON-NLS-1$
		case "enter", "return", "cr" -> SWT.CR; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		case "tab" -> SWT.TAB; //$NON-NLS-1$
		case "escape", "esc" -> SWT.ESC; //$NON-NLS-1$ //$NON-NLS-2$
		case "backspace", "bs" -> SWT.BS; //$NON-NLS-1$ //$NON-NLS-2$
		case "delete", "del" -> SWT.DEL; //$NON-NLS-1$ //$NON-NLS-2$
		case "up", "arrowup" -> SWT.ARROW_UP; //$NON-NLS-1$ //$NON-NLS-2$
		case "down", "arrowdown" -> SWT.ARROW_DOWN; //$NON-NLS-1$ //$NON-NLS-2$
		case "left", "arrowleft" -> SWT.ARROW_LEFT; //$NON-NLS-1$ //$NON-NLS-2$
		case "right", "arrowright" -> SWT.ARROW_RIGHT; //$NON-NLS-1$ //$NON-NLS-2$
		case "home" -> SWT.HOME; //$NON-NLS-1$
		case "end" -> SWT.END; //$NON-NLS-1$
		case "pageup", "pgup" -> SWT.PAGE_UP; //$NON-NLS-1$ //$NON-NLS-2$
		case "pagedown", "pgdn" -> SWT.PAGE_DOWN; //$NON-NLS-1$ //$NON-NLS-2$
		case "f1" -> SWT.F1;
		case "f2" -> SWT.F2;
		case "f3" -> SWT.F3;
		case "f4" -> SWT.F4;
		case "f5" -> SWT.F5;
		case "f6" -> SWT.F6;
		case "f7" -> SWT.F7;
		case "f8" -> SWT.F8;
		case "f9" -> SWT.F9;
		case "f10" -> SWT.F10;
		case "f11" -> SWT.F11;
		case "f12" -> SWT.F12;
		default -> 0;
		};
	}

	/** Exposed for the test, which cannot post real events. */
	public static JsonObject describe(String key) {
		Stroke stroke = parse(key);
		JsonArray mods = new JsonArray();
		stroke.modifiers().forEach(m -> mods.add(Integer.valueOf(m.intValue())));
		return new JsonObject().put("keyCode", Integer.valueOf(stroke.keyCode())) //$NON-NLS-1$
				.put("character", stroke.character() == 0 ? null : String.valueOf(stroke.character())) //$NON-NLS-1$
				.put("modifiers", mods); //$NON-NLS-1$
	}
}
