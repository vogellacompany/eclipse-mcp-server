package com.vogella.eclipse.mcp.ui.internal;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Widget;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** Opens and closes a Combo's drop-down without the pointer and reports what Windows paints in it. */
public final class ComboListTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_combo_list"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reads a Combo's drop-down list, and with 'visible' OPENS OR CLOSES IT, WHICH CHANGES WHAT IS ON SCREEN, without moving the pointer. On Windows the list is a native window with no SWT control, painted by Windows, so eclipse_get_widget_tree does not see it and eclipse_inspect_widget only reports what the CSS engine knows. The answer has found, dropDown and listVisible, 'refused' when the list was not opened, and on Windows nativeList (or nativeListFailure) with 'failure' when the list window is unknown, captureNote or captureFailure when there is no picture, and otherwise hwndList, the list's screen bounds, highlightedRow, topRow and the rows up to maxResults with total and truncated; each row says onScreen and carries its bounds in the list's client area. Measures are native pixels; divide by zoom / 100 for SWT points. An open list is captured with PrintWindow, independent of windows in front, and attached as an image, and each row on screen then reports 'fill', its commonest colour, and 'otherPixels', the pixels of any other colour, so a row with no text on screen has none. A closed list is not captured and captureNote says so. Elsewhere only 'visible' and listVisible work, and nativeListNote says so. A list left open closes on the next click or focus change, so close it again with visible false. A CCombo's list is an SWT shell of its own, so eclipse_list_ui_targets and the other widget tools reach it instead."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["path"],
				  "properties": {
				    "part":       {"type":"string","description":"Part id the path is rooted in. Use eclipse_list_ui_targets. Several parts with one id: id@editor input path or title."},
				    "shellTitle": {"type":"string","description":"Shell to root the path in, by title substring; omit both for the active shell."},
				    "shell":      {"type":"string","description":"Shell independent of title: 'popup', an index from eclipse_list_ui_targets, or its bounds. Wins over shellTitle."},
				    "path":       {"type":"string","description":"Path of the Combo, from eclipse_get_widget_tree."},
				    "visible":    {"type":"boolean","description":"true opens the list, false closes it. Omit to only read."},
				    "capture":    {"type":"boolean","default":true,"description":"Attach a picture of an open list and report each row's fill."},
				    "maxResults": {"type":"integer","default":100,"minimum":1,"maximum":1000,"description":"Rows to report."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path"); //$NON-NLS-1$
		if (path == null) {
			return McpToolResult.error("The argument 'path' is required; eclipse_get_widget_tree reports the paths."); //$NON-NLS-1$
		}
		String partId = args.getString("part"); //$NON-NLS-1$
		String shellTitle = Shells.spec(args);
		Boolean visible = args.has("visible") ? Boolean.valueOf(args.getBoolean("visible", false)) : null; //$NON-NLS-1$ //$NON-NLS-2$
		boolean capture = args.getBoolean("capture", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 100, 1, 1000); //$NON-NLS-1$
		Capture picture = new Capture();
		UiThread.Outcome outcome = UiThread.run(15, () -> {
			Control root = WidgetTools.rootOf(partId, shellTitle, false);
			if (root == null) {
				return refusal("No such part or shell, or the part is not open. Use eclipse_list_ui_targets."); //$NON-NLS-1$
			}
			Widget target = WidgetTools.resolve(root, path);
			if (!(target instanceof Combo combo)) {
				return refusal(target == null ? "The path '%s' does not resolve. Read a current one from eclipse_get_widget_tree.".formatted(path) //$NON-NLS-1$
						: "'%s' is a %s, not a Combo.".formatted(path, target.getClass().getSimpleName())); //$NON-NLS-1$
			}
			return apply(combo, visible, capture, maxResults, picture);
		});
		if (outcome.error() != null) {
			return outcome.errorResult();
		}
		JsonObject answer = outcome.value();
		byte[] png = null;
		try {
			png = picture.finish();
		} catch (RuntimeException | LinkageError | SWTError e) {
			// the list did open or close, so this is not a failed call
			picture.target.put("captureFailure", "measuring or encoding the picture failed: " + e); //$NON-NLS-1$ //$NON-NLS-2$
		}
		McpToolResult result = McpToolResult.of(answer.toString());
		return png != null ? result.withImage(png, "image/png") : result; //$NON-NLS-1$
	}

	/**
	 * Applies {@code visible}, when given, and reads the list, on the UI thread.
	 * The picture and the rows' fill are completed off it by {@link Capture#finish}.
	 */
	public static JsonObject apply(Combo combo, Boolean visible, boolean capture, int maxResults, Capture picture) {
		boolean dropDown = (combo.getStyle() & SWT.DROP_DOWN) != 0;
		JsonObject answer = new JsonObject().put("found", Boolean.TRUE).put("dropDown", Boolean.valueOf(dropDown)); //$NON-NLS-1$ //$NON-NLS-2$
		if (visible != null && visible.booleanValue() && !combo.isVisible()) {
			return answer.put("listVisible", Boolean.valueOf(combo.getListVisible())) //$NON-NLS-1$
					.put("refused", "This Combo is not visible, so its list would open away from it. Bring its page or shell up first."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (visible != null && dropDown) {
			combo.setListVisible(visible.booleanValue());
		}
		answer.put("listVisible", Boolean.valueOf(combo.getListVisible())); //$NON-NLS-1$
		if (!"win32".equals(SWT.getPlatform())) { //$NON-NLS-1$
			return answer.put("nativeListNote", "Rows, fill and capture of the list are only available on Windows."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		try {
			// a drop-down just closed on request needs no note that it was not captured
			boolean closed = dropDown && Boolean.FALSE.equals(visible);
			answer.put("nativeList", NativeComboList.describe(combo, capture && !closed, maxResults, picture)); //$NON-NLS-1$
		} catch (ReflectiveOperationException | RuntimeException | LinkageError | SWTError e) {
			// the list did open or close, so this is not a failed call
			answer.put("nativeListFailure", e.toString()); //$NON-NLS-1$
		}
		return answer;
	}

	private static JsonObject refusal(String reason) {
		return new JsonObject().put("found", Boolean.FALSE).put("reason", reason); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** A captured list and the row fills still to be measured on it, off the UI thread. */
	public static final class Capture {

		ImageData data;
		JsonObject target;
		final List<Runnable> fills = new ArrayList<>();

		/** Measures the rows' fill and answers the picture as PNG, or null when nothing was captured. */
		public byte[] finish() {
			if (data == null) {
				return null;
			}
			fills.forEach(Runnable::run);
			ImageLoader loader = new ImageLoader();
			loader.data = new ImageData[] { data };
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			loader.save(bytes, SWT.IMAGE_PNG);
			return bytes.toByteArray();
		}
	}
}
