package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Widget;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Moves a sash of a SashForm to a pixel position by sending it the Selection event a finished drag sends.
 */
public final class SetSashTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_set_sash"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Sets the split of a SashForm, the divided areas inside parts and dialogs such as the e4 model editor's tree and detail form, so the visible control before the sash gets 'position' pixels. CHANGES WHAT IS ON SCREEN. Address the SashForm, or one of its Sash children, by part or shell plus the path eclipse_get_widget_tree reports; with a SashForm, 'sashIndex' picks the sash (0 is between the first and second visible child), with a Sash the index is its own. It does not drag with the mouse: it sends the Sash the Selection events of a real drag, one while moving and one on release, so SashForm's own handler sets the weights, keeps each side at least its minimum size and lays out, and any other listener on the sash hears the same two events a person's drag sends. That makes the result independent of how loaded the machine is, which a synthetic mouse drag is not. The answer reports the requested and effective position, 'clamped' when SashForm kept a side at its minimum, the weights before and after, and the bounds of every child and sash relative to the SashForm, the shell's client area and the display. Workbench part stacks are not SashForms; resize those with eclipse_move_part or eclipse_set_part_state. A hidden SashForm, one with a maximized control, and a disabled sash are refused; sashes only sit between visible controls, so hidden ones are skipped when counting."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "part":       {"type":"string","description":"Part id the path is rooted in, an editor id included. Use eclipse_list_ui_targets. Several parts with one id: id@editor input path or title."},
				    "shellTitle": {"type":"string","description":"Shell to root the path in, by title substring; omit both for the active shell."},
				    "shell":      {"type":"string","description":"Shell independent of title: 'popup', an index from eclipse_list_ui_targets, or its bounds. Wins over shellTitle."},
				    "path":       {"type":"string","description":"Path of the SashForm, or of one of its Sash children, from eclipse_get_widget_tree."},
				    "position":   {"type":"integer","minimum":0,"description":"Width (side by side) or height (stacked) in pixels of the visible control before the sash."},
				    "sashIndex":  {"type":"integer","minimum":0,"default":0,"description":"Which sash of the SashForm, counted from the first; only with a SashForm path."}
				  },
				  "required": ["path", "position"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String path = args.getString("path"); //$NON-NLS-1$
		if (path == null || !args.has("position")) { //$NON-NLS-1$
			return McpToolResult.error("The arguments 'path' and 'position' are required; eclipse_get_widget_tree reports the paths."); //$NON-NLS-1$
		}
		int position = args.getInt("position", 0, 0, 100_000); //$NON-NLS-1$
		Integer sashIndex = args.has("sashIndex") ? Integer.valueOf(args.getInt("sashIndex", 0, 0, 1000)) : null; //$NON-NLS-1$ //$NON-NLS-2$
		String part = args.getString("part"); //$NON-NLS-1$
		String shell = Shells.spec(args);
		return UiThread.call(15, () -> {
			Control root = WidgetTools.rootOf(part, shell, false);
			if (root == null) {
				return refusal("No such part or shell, or the part is not open. Use eclipse_list_ui_targets."); //$NON-NLS-1$
			}
			Widget target = WidgetTools.resolve(root, path);
			if (target == null) {
				return refusal("The path '%s' does not resolve under this part or shell.".formatted(path)); //$NON-NLS-1$
			}
			int slash = path.lastIndexOf('/');
			String formPath = target instanceof SashForm ? path : slash >= 0 ? path.substring(0, slash) : ""; //$NON-NLS-1$
			return set(target, sashIndex, position, formPath);
		});
	}

	/**
	 * Moves sash {@code sashIndex} of the SashForm {@code target} is, or belongs to, so the control before it is
	 * {@code position} pixels; {@code formPath}, when known, prefixes the children's paths. Runs on the UI thread.
	 */
	public static JsonObject set(Widget target, Integer sashIndex, int position, String formPath) {
		SashForm form;
		int index;
		if (target instanceof Sash sash && sash.getParent() instanceof SashForm parent) {
			form = parent;
			index = sashesOf(form).indexOf(sash);
			if (sashIndex != null && sashIndex.intValue() != index) {
				return refusal("The path names sash %d, and sashIndex says %d; give one of them." //$NON-NLS-1$
						.formatted(Integer.valueOf(index), sashIndex));
			}
		} else if (target instanceof SashForm sashForm) {
			form = sashForm;
			index = sashIndex == null ? 0 : sashIndex.intValue();
		} else {
			return refusal("The widget is a %s, not a SashForm or one of its sashes.".formatted(target.getClass().getSimpleName())); //$NON-NLS-1$
		}
		if (form.getMaximizedControl() != null) {
			return refusal("The SashForm shows one control maximized and has no visible sash."); //$NON-NLS-1$
		}
		boolean sideBySide = form.getOrientation() == SWT.HORIZONTAL;
		Rectangle area = form.getClientArea();
		if (!form.isVisible() || (sideBySide ? area.width : area.height) <= 0) {
			return refusal("The SashForm is not visible, so it has no size to split."); //$NON-NLS-1$
		}
		List<Sash> sashes = sashesOf(form);
		List<Control> controls = visibleControls(form);
		if (sashes.size() != Math.max(0, controls.size() - 1)) {
			// a control was shown or hidden and SashForm rebuilds its sashes on the next layout
			form.layout(true);
			sashes = sashesOf(form);
			controls = visibleControls(form);
		}
		if (index < 0 || index >= sashes.size() || index + 1 >= controls.size()) {
			return refusal("The SashForm has %d sashes between %d visible controls, so there is no sash %d." //$NON-NLS-1$
					.formatted(Integer.valueOf(sashes.size()), Integer.valueOf(controls.size()), Integer.valueOf(index)));
		}
		Sash sash = sashes.get(index);
		if (!sash.isEnabled()) {
			return refusal("Sash %d is disabled, so a user could not drag it either.".formatted(Integer.valueOf(index))); //$NON-NLS-1$
		}
		Control before = controls.get(index);
		JsonObject result = new JsonObject().put("orientation", sideBySide ? "horizontal" : "vertical") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("sashIndex", Integer.valueOf(index)).put("requestedPosition", Integer.valueOf(position)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("previousPosition", Integer.valueOf(size(before, sideBySide))) //$NON-NLS-1$
				.put("weightsBefore", weights(form)); //$NON-NLS-1$
		Rectangle bounds = sash.getBounds();
		int shift = position - size(before, sideBySide);
		int x = sideBySide ? bounds.x + shift : bounds.x;
		int y = sideBySide ? bounds.y : bounds.y + shift;
		// a person's drag moves the sash, where SashForm may hold it at a side's minimum, and is released where it stopped
		Event moving = drag(bounds, x, y);
		moving.detail = SWT.DRAG;
		sash.notifyListeners(SWT.Selection, moving);
		boolean clamped = moving.x != x || moving.y != y;
		if (!sash.isDisposed()) {
			sash.notifyListeners(SWT.Selection, drag(bounds, moving.x, moving.y));
		}
		if (form.isDisposed()) {
			return result.put("changed", Boolean.TRUE).put("sashFormDisposed", Boolean.TRUE); //$NON-NLS-1$ //$NON-NLS-2$
		}
		int effective = before.isDisposed() ? -1 : size(before, sideBySide);
		result.put("position", Integer.valueOf(effective)) //$NON-NLS-1$
				.put("clamped", Boolean.valueOf(clamped)) //$NON-NLS-1$
				.put("weightsAfter", weights(form)) //$NON-NLS-1$
				.put("children", children(form, formPath)); //$NON-NLS-1$
		if (formPath != null) {
			result.put("sashFormPath", formPath); //$NON-NLS-1$
		}
		if (effective != position) {
			result.put("note", clamped //$NON-NLS-1$
					? "SashForm keeps each side of a sash at a minimum size, and 'position' is where the sash stopped." //$NON-NLS-1$
					: "The control ended up at a different size than requested; a listener on the sash or the layout moved it."); //$NON-NLS-1$
		}
		return result.put("changed", Boolean.TRUE); //$NON-NLS-1$
	}

	/** A Selection event of a drag of the sash at {@code x}, {@code y}, released unless its detail says otherwise. */
	private static Event drag(Rectangle bounds, int x, int y) {
		Event event = new Event();
		event.x = x;
		event.y = y;
		event.width = bounds.width;
		event.height = bounds.height;
		event.doit = true;
		return event;
	}

	/** The sashes in the order SashForm keeps them, which is the order of the controls they separate. */
	private static List<Sash> sashesOf(SashForm form) {
		List<Sash> sashes = new ArrayList<>();
		for (Control child : form.getChildren()) {
			if (child instanceof Sash sash) {
				sashes.add(sash);
			}
		}
		return sashes;
	}

	private static List<Control> visibleControls(SashForm form) {
		List<Control> controls = new ArrayList<>();
		for (Control child : form.getChildren()) {
			if (!(child instanceof Sash) && child.getVisible()) {
				controls.add(child);
			}
		}
		return controls;
	}

	private static int size(Control control, boolean sideBySide) {
		Rectangle bounds = control.getBounds();
		return sideBySide ? bounds.width : bounds.height;
	}

	private static JsonArray weights(SashForm form) {
		JsonArray weights = new JsonArray();
		for (int weight : form.getWeights()) {
			weights.add(Integer.valueOf(weight));
		}
		return weights;
	}

	private static JsonArray children(SashForm form, String formPath) {
		JsonArray children = new JsonArray();
		Control[] all = form.getChildren();
		for (int i = 0; i < all.length; i++) {
			Control child = all[i];
			Rectangle bounds = child.getBounds();
			Rectangle inShell = form.getDisplay().map(form, form.getShell(), bounds);
			Rectangle inDisplay = form.getDisplay().map(form, null, bounds);
			JsonObject entry = new JsonObject().put("index", Integer.valueOf(i)); //$NON-NLS-1$
			if (formPath != null) {
				entry.put("path", formPath.isEmpty() ? String.valueOf(i) : formPath + "/" + i); //$NON-NLS-1$
			}
			children.add(entry.put("type", child.getClass().getSimpleName()) //$NON-NLS-1$
					.put("visible", Boolean.valueOf(child.getVisible())) //$NON-NLS-1$
					.put("bounds", rectangle(bounds)) //$NON-NLS-1$
					.put("boundsInShell", rectangle(inShell)) //$NON-NLS-1$
					.put("boundsInDisplay", rectangle(inDisplay))); //$NON-NLS-1$
		}
		return children;
	}

	private static JsonObject rectangle(Rectangle bounds) {
		return new JsonObject().put("x", Integer.valueOf(bounds.x)).put("y", Integer.valueOf(bounds.y)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("width", Integer.valueOf(bounds.width)).put("height", Integer.valueOf(bounds.height)); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static JsonObject refusal(String reason) {
		return new JsonObject().put("changed", Boolean.FALSE).put("reason", reason); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
