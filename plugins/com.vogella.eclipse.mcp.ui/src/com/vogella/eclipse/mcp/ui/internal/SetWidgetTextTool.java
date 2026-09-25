package com.vogella.eclipse.mcp.ui.internal;

import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Widget;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Enters text into a Text, StyledText, Combo or CCombo, or selects a combo item,
 * without needing OS focus.
 */
public final class SetWidgetTextTool implements IMcpTool {

	private static final int MAX_TEXT = 5000;

	private static final int MAX_ITEMS_REPORTED = 50;

	@Override
	public String getName() {
		return "eclipse_set_widget_text"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Enters text into a Text, StyledText, Combo or CCombo, or selects an item of a Combo or CCombo, addressed by part or shell plus the path eclipse_get_widget_tree reports. CHANGES THE WIDGET, AND WHATEVER ITS LISTENERS DO IN RESPONSE, which is the point: a find field searches, a dialog validates and enables its OK button. It works without OS focus and inside modal dialogs, which is where eclipse_press_key and eclipse_click refuse, because it drives the widget through its own API rather than through the window system. By default it types one character at a time, sending KeyDown and KeyUp to the widget's listeners and inserting through the widget (a StyledText types from the KeyDown itself), so Verify and Modify fire per character the way they do for real typing; a KeyDown or Verify listener that vetoes a character keeps it out, as it would for a person. perCharacter false enters the whole text in one step. mode 'replace' (the default) replaces the content, 'insert' inserts at the caret, replacing any text selection. pressEnter then sends Return as KeyDown and KeyUp plus a DefaultSelection event, which is what a single line Text and a Combo send on Enter, and inserts no newline; a StyledText handles that Return itself, as it would a real one. For a combo, 'item' (a label, case insensitive, exact or a unique substring) or 'itemIndex' selects an entry and fires Selection instead of typing. The answer reports the text afterwards and the events the widget actually delivered, counted by listeners added for the call, so a Modify that did not fire shows as zero rather than being assumed. A disabled or read-only widget is refused, and a read-only combo only accepts item or itemIndex. focus true gives the widget keyboard focus inside the IDE first, for listeners that react to FocusIn. When pressEnter closes the dialog the answer says widgetDisposed and reports the text as it was just before Enter."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "part":         {"type":"string","description":"Part id the path is rooted in, an editor id included. Use eclipse_list_ui_targets."},
				    "shellTitle":   {"type":"string","description":"Shell to root the path in, by title substring; omit both for the active shell."},
				    "shell":        {"type":"string","description":"Shell independent of title: 'popup', an index from eclipse_list_ui_targets, or its bounds. Wins over shellTitle."},
				    "path":         {"type":"string","description":"Widget path from eclipse_get_widget_tree, such as 0/0/1."},
				    "text":         {"type":"string","maxLength":5000,"description":"Text to enter. Required unless item or itemIndex is given; an empty string with mode replace clears the widget."},
				    "mode":         {"type":"string","enum":["replace","insert"],"default":"replace","description":"replace the whole content, or insert at the caret."},
				    "perCharacter": {"type":"boolean","default":true,"description":"Type one character at a time with key events, or enter the whole text in one step."},
				    "pressEnter":   {"type":"boolean","default":false,"description":"Afterwards send Return as key events plus DefaultSelection."},
				    "item":         {"type":"string","description":"Combo or CCombo only: the entry to select, by label."},
				    "itemIndex":    {"type":"integer","minimum":0,"description":"Combo or CCombo only: the entry to select, by index."},
				    "focus":        {"type":"boolean","default":false,"description":"Give the widget keyboard focus inside the IDE first."}
				  },
				  "required": ["path"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	private record Request(String partId, String shell, String path, String text, boolean replace,
			boolean perCharacter, boolean pressEnter, String item, Integer itemIndex, boolean focus) {
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path"); //$NON-NLS-1$
		if (path == null) {
			return McpToolResult.error("The argument 'path' is required; eclipse_get_widget_tree reports the paths."); //$NON-NLS-1$
		}
		String mode = args.getString("mode", "replace").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
		if (!"replace".equals(mode) && !"insert".equals(mode)) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("Unknown mode '%s'; use replace or insert.".formatted(mode)); //$NON-NLS-1$
		}
		Object raw = arguments == null ? null : arguments.get("text"); //$NON-NLS-1$
		String text = raw == null ? null : raw.toString();
		String item = args.getString("item"); //$NON-NLS-1$
		Integer itemIndex = args.has("itemIndex") ? Integer.valueOf(args.getInt("itemIndex", 0, 0, 100_000)) : null; //$NON-NLS-1$ //$NON-NLS-2$
		boolean selecting = item != null || itemIndex != null;
		if (item != null && itemIndex != null) {
			return McpToolResult.error("Give either 'item' or 'itemIndex', not both."); //$NON-NLS-1$
		}
		if (selecting && text != null) {
			return McpToolResult.error("Give either 'text' or an item to select, not both."); //$NON-NLS-1$
		}
		if (!selecting && text == null) {
			return McpToolResult.error("The argument 'text' is required, or 'item' or 'itemIndex' for a combo."); //$NON-NLS-1$
		}
		if (text != null && text.length() > MAX_TEXT) {
			return McpToolResult.error("The text is %d characters; at most %d are accepted." //$NON-NLS-1$
					.formatted(Integer.valueOf(text.length()), Integer.valueOf(MAX_TEXT)));
		}
		String shell = args.getString("shell") != null ? args.getString("shell") : args.getString("shellTitle"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		Request request = new Request(args.getString("part"), shell, path, text, "replace".equals(mode), //$NON-NLS-1$ //$NON-NLS-2$
				args.getBoolean("perCharacter", true), args.getBoolean("pressEnter", false), item, itemIndex, //$NON-NLS-1$ //$NON-NLS-2$
				args.getBoolean("focus", false)); //$NON-NLS-1$
		return UiThread.call(15, () -> apply(request));
	}

	private static JsonObject apply(Request request) {
		Control root = WidgetTools.rootOf(request.partId(), request.shell(), false);
		if (root == null) {
			return refusal("No such part or shell, or the part is not open. Use eclipse_list_ui_targets."); //$NON-NLS-1$
		}
		Widget target = WidgetTools.resolve(root, request.path());
		if (target == null) {
			return refusal("The path '%s' does not resolve under this part or shell.".formatted(request.path())); //$NON-NLS-1$
		}
		if (!(target instanceof Text || target instanceof StyledText || target instanceof Combo
				|| target instanceof CCombo)) {
			return refusal("'%s' is a %s, not a Text, StyledText, Combo or CCombo." //$NON-NLS-1$
					.formatted(request.path(), target.getClass().getSimpleName()));
		}
		Control control = (Control) target;
		if (!control.isEnabled()) {
			return refusal("The %s at '%s' is disabled.".formatted(kind(control), request.path())); //$NON-NLS-1$
		}
		boolean selecting = request.item() != null || request.itemIndex() != null;
		if (selecting && !(control instanceof Combo || control instanceof CCombo)) {
			return refusal("'item' and 'itemIndex' apply to a Combo or CCombo, and this is a %s." //$NON-NLS-1$
					.formatted(kind(control)));
		}
		if (!selecting && !editable(control)) {
			return refusal("The %s at '%s' is read-only.%s".formatted(kind(control), request.path(), //$NON-NLS-1$
					control instanceof Combo || control instanceof CCombo
							? " Select an entry with 'item' or 'itemIndex' instead." //$NON-NLS-1$
							: "")); //$NON-NLS-1$
		}
		String before = textOf(control);
		EventCounter counter = new EventCounter(control);
		JsonObject result = new JsonObject().put("widget", kind(control)).put("previousText", before); //$NON-NLS-1$ //$NON-NLS-2$
		try {
			if (request.focus()) {
				result.put("focused", Boolean.valueOf(control.setFocus())); //$NON-NLS-1$
			}
			if (selecting) {
				String error = select(control, request, result);
				if (error != null) {
					return refusal(error).put("items", items(control)); //$NON-NLS-1$
				}
			} else {
				int vetoed = request.perCharacter() ? typeEach(control, request) : enterAtOnce(control, request);
				if (vetoed > 0) {
					result.put("charactersVetoedByKeyDown", Integer.valueOf(vetoed)); //$NON-NLS-1$
				}
			}
			String after = textOf(control);
			result.put("text", after); //$NON-NLS-1$
			if (!request.pressEnter()) {
				addCaret(control, result);
			} else {
				pressEnter(control);
				boolean disposed = control.isDisposed();
				result.put("enterPressed", Boolean.TRUE).put("widgetDisposed", Boolean.valueOf(disposed)); //$NON-NLS-1$ //$NON-NLS-2$
				if (!disposed) {
					result.put("text", textOf(control)); //$NON-NLS-1$
					addCaret(control, result);
				}
			}
		} finally {
			counter.dispose();
		}
		result.put("events", counter.toJson()); //$NON-NLS-1$
		if (request.text() != null && request.replace() && !request.pressEnter()
				&& !request.text().equals(textOf(control))) {
			result.put("note", //$NON-NLS-1$
					"The widget holds different text than was entered: a Verify listener changed or vetoed characters, the widget has a text limit, or a listener rewrote it."); //$NON-NLS-1$
		}
		return result.put("changed", Boolean.TRUE); //$NON-NLS-1$
	}

	/** Types each character: KeyDown, an insert through the widget, KeyUp. Answers how many KeyDown listeners vetoed. */
	private static int typeEach(Control control, Request request) {
		if (request.replace()) {
			selectAll(control);
			if (request.text().isEmpty()) {
				insert(control, ""); //$NON-NLS-1$
				return 0;
			}
		}
		int vetoed = 0;
		String text = request.text();
		for (int i = 0; i < text.length() && !control.isDisposed();) {
			int codePoint = text.codePointAt(i);
			String unit = new String(Character.toChars(codePoint));
			i += unit.length();
			char character = unit.charAt(0);
			Event down = keyEvent(character);
			control.notifyListeners(SWT.KeyDown, down);
			if (control.isDisposed()) {
				break;
			}
			if (control instanceof StyledText) {
				// StyledText types from its own KeyDown listener, VerifyKey and Verify included
			} else if (down.doit) {
				insert(control, unit);
			} else {
				vetoed++;
			}
			if (!control.isDisposed()) {
				control.notifyListeners(SWT.KeyUp, keyEvent(character));
			}
		}
		return vetoed;
	}

	private static int enterAtOnce(Control control, Request request) {
		if (request.replace()) {
			switch (control) {
			case Text text -> text.setText(request.text());
			case StyledText styled -> {
				// setText bypasses Verify, a replace of the whole range through insert does not
				styled.setSelection(0, styled.getCharCount());
				styled.insert(request.text());
			}
			case Combo combo -> combo.setText(request.text());
			case CCombo combo -> combo.setText(request.text());
			default -> throw new IllegalStateException();
			}
			return 0;
		}
		insert(control, request.text());
		return 0;
	}

	private static void selectAll(Control control) {
		switch (control) {
		case Text text -> text.selectAll();
		case StyledText styled -> styled.selectAll();
		case Combo combo -> combo.setSelection(new Point(0, combo.getText().length()));
		case CCombo combo -> combo.setSelection(new Point(0, combo.getText().length()));
		default -> throw new IllegalStateException();
		}
	}

	/** Replaces the selection with the text, leaving the caret behind it, the way a keystroke does. */
	private static void insert(Control control, String value) {
		switch (control) {
		case Text text -> text.insert(value);
		case StyledText styled -> {
			styled.insert(value);
			styled.setCaretOffset(styled.getSelection().x + value.length());
		}
		case Combo combo -> {
			Point selection = combo.getSelection();
			String current = combo.getText();
			combo.setText(splice(current, selection, value));
			int caret = Math.min(selection.x, selection.y) + value.length();
			combo.setSelection(new Point(caret, caret));
		}
		case CCombo combo -> {
			Point selection = combo.getSelection();
			String current = combo.getText();
			combo.setText(splice(current, selection, value));
			int caret = Math.min(selection.x, selection.y) + value.length();
			combo.setSelection(new Point(caret, caret));
		}
		default -> throw new IllegalStateException();
		}
	}

	private static String splice(String current, Point selection, String value) {
		int start = Math.max(0, Math.min(Math.min(selection.x, selection.y), current.length()));
		int end = Math.max(start, Math.min(Math.max(selection.x, selection.y), current.length()));
		return current.substring(0, start) + value + current.substring(end);
	}

	private static String select(Control control, Request request, JsonObject result) {
		String[] items = control instanceof Combo combo ? combo.getItems() : ((CCombo) control).getItems();
		int index = request.itemIndex() != null ? request.itemIndex().intValue() : indexOf(items, request.item());
		if (index == -2) {
			return "More than one entry contains '%s'; give the full label or 'itemIndex'.".formatted(request.item()); //$NON-NLS-1$
		}
		if (index < 0) {
			return "No entry labelled '%s'.".formatted(request.item()); //$NON-NLS-1$
		}
		if (index >= items.length) {
			return "The combo has %d entries, so index %d is out of range." //$NON-NLS-1$
					.formatted(Integer.valueOf(items.length), Integer.valueOf(index));
		}
		if (control instanceof Combo combo) {
			combo.select(index);
		} else {
			((CCombo) control).select(index);
		}
		// select() fires nothing, and a ComboViewer or a dialog's validation only listens for Selection
		Event event = new Event();
		event.widget = control;
		control.notifyListeners(SWT.Selection, event);
		result.put("selectedIndex", Integer.valueOf(index)).put("selectedItem", items[index]); //$NON-NLS-1$ //$NON-NLS-2$
		return null;
	}

	/** The index of the label, exact or unique substring, case insensitive; -1 for none and -2 for ambiguous. */
	static int indexOf(String[] items, String label) {
		String needle = label.strip().toLowerCase(Locale.ROOT);
		for (int i = 0; i < items.length; i++) {
			if (items[i].strip().toLowerCase(Locale.ROOT).equals(needle)) {
				return i;
			}
		}
		int found = -1;
		for (int i = 0; i < items.length; i++) {
			if (items[i].toLowerCase(Locale.ROOT).contains(needle)) {
				if (found >= 0) {
					return -2;
				}
				found = i;
			}
		}
		return found;
	}

	private static JsonArray items(Control control) {
		String[] items = control instanceof Combo combo ? combo.getItems()
				: control instanceof CCombo combo ? combo.getItems() : new String[0];
		JsonArray array = new JsonArray();
		for (int i = 0; i < items.length && i < MAX_ITEMS_REPORTED; i++) {
			array.add(items[i]);
		}
		return array;
	}

	private static void pressEnter(Control control) {
		Event down = keyEvent(SWT.CR);
		control.notifyListeners(SWT.KeyDown, down);
		if (control.isDisposed()) {
			return;
		}
		// StyledText answers Return from its own KeyDown listener and never sends DefaultSelection
		if (down.doit && !(control instanceof StyledText)) {
			Event event = new Event();
			event.widget = control;
			control.notifyListeners(SWT.DefaultSelection, event);
		}
		if (!control.isDisposed()) {
			control.notifyListeners(SWT.KeyUp, keyEvent(SWT.CR));
		}
	}

	private static Event keyEvent(char character) {
		Event event = new Event();
		event.character = character;
		event.keyCode = character == SWT.CR ? SWT.CR : Character.toLowerCase(character);
		if (Character.isUpperCase(character)) {
			event.stateMask = SWT.SHIFT;
		}
		event.doit = true;
		return event;
	}

	private static void addCaret(Control control, JsonObject result) {
		Point selection = switch (control) {
		case Text text -> text.getSelection();
		case StyledText styled -> styled.getSelection();
		case Combo combo -> combo.getSelection();
		case CCombo combo -> combo.getSelection();
		default -> null;
		};
		if (selection != null) {
			result.put("caret", Integer.valueOf(selection.y)); //$NON-NLS-1$
		}
	}

	private static boolean editable(Control control) {
		return switch (control) {
		case Text text -> text.getEditable();
		case StyledText styled -> styled.getEditable();
		case Combo combo -> (combo.getStyle() & SWT.READ_ONLY) == 0;
		case CCombo combo -> combo.getEditable();
		default -> false;
		};
	}

	private static String textOf(Control control) {
		return switch (control) {
		case Text text -> text.getText();
		case StyledText styled -> styled.getText();
		case Combo combo -> combo.getText();
		case CCombo combo -> combo.getText();
		default -> ""; //$NON-NLS-1$
		};
	}

	private static String kind(Control control) {
		return control.getClass().getSimpleName();
	}

	private static JsonObject refusal(String reason) {
		return new JsonObject().put("changed", Boolean.FALSE).put("reason", reason); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Counts the events the widget delivered while the call ran, which is the
	 * evidence that listeners heard it rather than a promise that they did.
	 */
	private static final class EventCounter {

		private static final int[] TYPES = { SWT.Verify, SWT.Modify, SWT.Selection, SWT.DefaultSelection,
				SWT.KeyDown };

		private final Control control;

		private final int[] counts = new int[TYPES.length];

		private int verifyVetoed;

		private final Listener listener = event -> {
			for (int i = 0; i < TYPES.length; i++) {
				if (TYPES[i] == event.type) {
					counts[i]++;
				}
			}
			if (event.type == SWT.Verify && !event.doit) {
				verifyVetoed++;
			}
		};

		EventCounter(Control control) {
			this.control = control;
			for (int type : TYPES) {
				control.addListener(type, listener);
			}
		}

		void dispose() {
			if (!control.isDisposed()) {
				for (int type : TYPES) {
					control.removeListener(type, listener);
				}
			}
		}

		JsonObject toJson() {
			return new JsonObject().put("verify", Integer.valueOf(counts[0])) //$NON-NLS-1$
					.put("verifyVetoed", Integer.valueOf(verifyVetoed)) //$NON-NLS-1$
					.put("modify", Integer.valueOf(counts[1])) //$NON-NLS-1$
					.put("selection", Integer.valueOf(counts[2])) //$NON-NLS-1$
					.put("defaultSelection", Integer.valueOf(counts[3])) //$NON-NLS-1$
					.put("keyDown", Integer.valueOf(counts[4])); //$NON-NLS-1$
		}
	}
}
