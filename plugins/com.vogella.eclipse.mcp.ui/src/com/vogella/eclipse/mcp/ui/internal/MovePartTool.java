package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MArea;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartSashContainerElement;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.model.application.ui.basic.MStackElement;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Moves a view into another part stack, beside one, or out into a window of its
 * own, which is the drag and drop of the workbench without a mouse.
 */
public final class MovePartTool implements IMcpTool {

	private static final long UI_TIMEOUT_SECONDS = 15;

	private static final int MAX_STACKS = 50;

	private static final AtomicLong STACK_IDS = new AtomicLong();

	@Override
	public String getName() {
		return "eclipse_move_part"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Moves a view into another stack, beside another stack, or out into a detached window, which is what dragging its tab does and what nothing else here can reach. CHANGES THE PERSPECTIVE LAYOUT, which Eclipse remembers across restarts, but writes nothing to the workspace. Use it to build the layout a screenshot or a CSS question needs: a stack with several tabs, a stack with exactly one, a narrow column that makes tabs overflow into the chevron, or a detached view, which is its own shell and is drawn by a different set of CSS selectors. The target is a part id or a stack id; position stack drops it into that stack, left, right, above and below split the target's stack in two, and detached opens a window. The answer lists under layout every stack of the active perspective with the parts in it, on success and on refusal alike, so one call is enough to see what can be addressed. It reports previousStack and previousIndex to move the part back, and eclipse_reset_perspective puts the whole perspective back. An editor can be moved too, named by its editor id from eclipse_list_ui_targets or by a substring of its tab title, but only within the editor area: left, right, above or below another editor splits the area, which is how two editors are put side by side, and stack puts it into another editor stack; a view stack or a detached window is refused for an editor, and the editor area is refused for a view."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["part"],
				  "properties": {
				    "part":       {"type":"string","description":"Id of the view or editor to move, from eclipse_list_ui_targets, or the id@path address it reports when several parts share an id. An editor can also be named by a substring of its tab title; when several editors match, the active one wins."},
				    "target":     {"type":"string","description":"Id of a part to move it to, or of a stack from the stacks this tool reports. An editor id or tab title works here too. Required unless position is detached."},
				    "position":   {"type":"string","enum":["stack","left","right","above","below","detached"],"default":"stack","description":"stack puts it in the target's stack as another tab; left, right, above and below put it in a new stack next to the target's; detached opens a window of its own."},
				    "index":      {"type":"integer","minimum":0,"description":"Tab position within the stack, for position stack. Omit for last."},
				    "ratio":      {"type":"integer","default":50,"minimum":10,"maximum":90,"description":"Percent of the target's area the new stack takes, for left, right, above and below."},
				    "bounds":     {"type":"string","description":"x,y widthxheight of the detached window, e.g. '400,300 800x500'. Detached only."},
				    "activate":   {"type":"boolean","default":true,"description":"Bring the part to the front of its new stack and give it focus."},
				    "maxResults": {"type":"integer","default":50,"minimum":1,"maximum":200,"description":"Cap on the stacks reported back."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String partId = args.getString("part"); //$NON-NLS-1$
		if (partId == null) {
			return McpToolResult.error("The argument 'part' is required. Use eclipse_list_ui_targets."); //$NON-NLS-1$
		}
		String position = args.getString("position", "stack"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!List.of("stack", "left", "right", "above", "below", "detached").contains(position)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
			return McpToolResult.error(
					"Unknown position '%s'. Use stack, left, right, above, below or detached.".formatted(position)); //$NON-NLS-1$
		}
		String target = args.getString("target"); //$NON-NLS-1$
		if (target == null && !"detached".equals(position)) { //$NON-NLS-1$
			return McpToolResult.error("The argument 'target' is required unless position is detached."); //$NON-NLS-1$
		}
		Request request = new Request(partId, target, position, //
				args.getInt("index", -1, -1, 100), //$NON-NLS-1$
				args.getInt("ratio", 50, 10, 90) / 100f, //$NON-NLS-1$
				args.getString("bounds"), //$NON-NLS-1$
				args.getBoolean("activate", true), //$NON-NLS-1$
				args.getInt("maxResults", MAX_STACKS, 1, 200)); //$NON-NLS-1$
		return UiThread.call(UI_TIMEOUT_SECONDS, () -> move(request));
	}

	private record Request(String partId, String target, String position, int index, float ratio, String bounds,
			boolean activate, int maxResults) {
	}

	private static JsonObject move(Request request) {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IEclipseContext windowContext = window == null ? null : window.getService(IEclipseContext.class);
		IEclipseContext applicationContext = PlatformUI.getWorkbench().getService(IEclipseContext.class);
		EModelService modelService = service(windowContext, applicationContext, EModelService.class);
		EPartService partService = service(windowContext, applicationContext, EPartService.class);
		MWindow modelWindow = modelWindow(windowContext, applicationContext);
		if (modelService == null || partService == null || modelWindow == null) {
			return refused("This window has no e4 model to move parts in."); //$NON-NLS-1$
		}

		MPart part;
		try {
			part = resolvePart(partService, request.partId());
		} catch (IllegalStateException e) {
			return refused(e.getMessage()).put("layout", layout(modelService, modelWindow, request.maxResults())); //$NON-NLS-1$
		}
		if (part == null) {
			return refused("No open part '%s'. Use eclipse_list_ui_targets.".formatted(request.partId())) //$NON-NLS-1$
					.put("layout", layout(modelService, modelWindow, request.maxResults())); //$NON-NLS-1$
		}
		boolean editor = inEditorArea(part);
		// a view sits in the stack as a placeholder; moving the part itself would remove it from every perspective
		MUIElement placed = part.getCurSharedRef() == null ? part : part.getCurSharedRef();
		MElementContainer<MUIElement> oldParent = placed.getParent();
		if (!(placed instanceof MStackElement toMove) || oldParent == null) {
			return refused("'%s' is not placed in a stack, so it cannot be moved.".formatted(request.partId())); //$NON-NLS-1$
		}
		// the parent has to be read as a plain element: MElementContainer<MUIElement>
		// and MPartStack are different parameterizations of the same interface
		MUIElement oldParentElement = oldParent;
		String previousStack = oldParentElement instanceof MPartStack stack ? stack.getElementId() : null;
		int previousIndex = oldParent.getChildren().indexOf(placed);

		JsonObject result = new JsonObject().put("moved", Boolean.TRUE) //$NON-NLS-1$
				.put("part", request.partId()) //$NON-NLS-1$
				.put("position", request.position()) //$NON-NLS-1$
				.put("target", request.target()) //$NON-NLS-1$
				.put("previousStack", previousStack) //$NON-NLS-1$
				.put("previousIndex", Integer.valueOf(previousIndex)); //$NON-NLS-1$

		if ("detached".equals(request.position())) { //$NON-NLS-1$
			if (editor) {
				return refused("An editor cannot be detached; it stays inside the editor area.") //$NON-NLS-1$
						.put("layout", layout(modelService, modelWindow, request.maxResults())); //$NON-NLS-1$
			}
			if (!(placed instanceof MPartSashContainerElement detachable)) {
				return refused("'%s' cannot be detached.".formatted(request.partId())); //$NON-NLS-1$
			}
			int[] bounds = parseBounds(request.bounds());
			modelService.detach(detachable, bounds[0], bounds[1], bounds[2], bounds[3]);
			result.put("bounds", "%d,%d %dx%d".formatted(Integer.valueOf(bounds[0]), Integer.valueOf(bounds[1]), //$NON-NLS-1$ //$NON-NLS-2$
					Integer.valueOf(bounds[2]), Integer.valueOf(bounds[3])));
		} else {
			MPartStack targetStack;
			try {
				targetStack = findStack(modelService, modelWindow, partService, request.target());
			} catch (IllegalStateException e) {
				return refused(e.getMessage()).put("layout", layout(modelService, modelWindow, request.maxResults())); //$NON-NLS-1$
			}
			if (targetStack == null) {
				return refused("No part or stack '%s' in the active perspective.".formatted(request.target())) //$NON-NLS-1$
						.put("layout", layout(modelService, modelWindow, request.maxResults())); //$NON-NLS-1$
			}
			// the editor area is a shared MArea; editors outside it and views inside it are not rendered, so the boundary is refused
			if (editor != inEditorArea(targetStack)) {
				return refused(editor
						? "'%s' is an editor and can only be placed within the editor area; name another editor or an editor stack as target." //$NON-NLS-1$
								.formatted(request.partId())
						: "'%s' is a view and cannot be placed in the editor area.".formatted(request.partId())) //$NON-NLS-1$
						.put("layout", layout(modelService, modelWindow, request.maxResults())); //$NON-NLS-1$
			}
			if (targetStack == oldParentElement && "stack".equals(request.position()) && request.index() < 0) { //$NON-NLS-1$
				result.put("moved", Boolean.FALSE).put("reason", //$NON-NLS-1$ //$NON-NLS-2$
						"'%s' is already in that stack.".formatted(request.partId())); //$NON-NLS-1$
			} else if ("stack".equals(request.position())) { //$NON-NLS-1$
				modelService.move(toMove, targetStack, clamp(request.index(), targetStack));
				result.put("stack", targetStack.getElementId()); //$NON-NLS-1$
			} else {
				MPartStack newStack = modelService.createModelElement(MPartStack.class);
				newStack.setElementId("com.vogella.eclipse.mcp.stack.%d".formatted(STACK_IDS.incrementAndGet())); //$NON-NLS-1$
				newStack.setToBeRendered(true);
				newStack.setVisible(true);
				// into the new stack first, then insert it: an empty stack that is already
				// in the model is a container the cleanup addon takes straight back out
				modelService.move(toMove, newStack, -1);
				modelService.insert(newStack, targetStack, where(request.position()), request.ratio());
				result.put("stack", newStack.getElementId()) //$NON-NLS-1$
						.put("splitFrom", targetStack.getElementId()); //$NON-NLS-1$
			}
			// whichever stack it ended in, including the freshly created one: a stack
			// with no selected element renders as a tab row with nothing under it
			MUIElement newParent = toMove.getParent();
			if (newParent instanceof MPartStack holder && holder.getSelectedElement() != toMove) {
				holder.setSelectedElement(toMove);
			}
		}
		if (request.activate()) {
			partService.activate(part);
		}
		return result
				.put("note", //$NON-NLS-1$
						"Pass previousStack as target with position stack and index previousIndex to put it back, or use eclipse_reset_perspective to put the whole perspective back.") //$NON-NLS-1$
				.put("layout", layout(modelService, modelWindow, request.maxResults())); //$NON-NLS-1$
	}

	/**
	 * The window context first, because the part service there is the one for this
	 * window; the application context is the fallback for a workbench window that
	 * does not publish its own.
	 */
	private static <T> T service(IEclipseContext windowContext, IEclipseContext applicationContext, Class<T> type) {
		T fromWindow = windowContext == null ? null : windowContext.get(type);
		if (fromWindow != null) {
			return fromWindow;
		}
		return applicationContext == null ? null : applicationContext.get(type);
	}

	private static MWindow modelWindow(IEclipseContext windowContext, IEclipseContext applicationContext) {
		MWindow fromContext = windowContext == null ? null : windowContext.get(MWindow.class);
		if (fromContext != null) {
			return fromContext;
		}
		MApplication application = service(windowContext, applicationContext, MApplication.class);
		return application == null ? null : application.getSelectedElement();
	}

	private static JsonObject refused(String reason) {
		return new JsonObject().put("moved", Boolean.FALSE).put("reason", reason); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static int clamp(int index, MPartStack stack) {
		return index < 0 ? -1 : Math.min(index, stack.getChildren().size());
	}

	private static int where(String position) {
		return switch (position) {
		case "left" -> EModelService.LEFT_OF; //$NON-NLS-1$
		case "right" -> EModelService.RIGHT_OF; //$NON-NLS-1$
		case "above" -> EModelService.ABOVE; //$NON-NLS-1$
		default -> EModelService.BELOW;
		};
	}

	/** {@code x,y widthxheight}, falling back to a readable window near the corner. */
	private static int[] parseBounds(String bounds) {
		int[] fallback = { 200, 200, 700, 500 };
		if (bounds == null) {
			return fallback;
		}
		try {
			String[] parts = bounds.trim().split("[ ,x]+"); //$NON-NLS-1$
			if (parts.length != 4) {
				return fallback;
			}
			int[] parsed = new int[4];
			for (int i = 0; i < 4; i++) {
				parsed[i] = Integer.parseInt(parts[i]);
			}
			return parsed[2] > 0 && parsed[3] > 0 ? parsed : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * The e4 part behind a view or editor id. Every compatibility editor carries the
	 * same element id, so an editor is found through the workbench page instead, by
	 * its editor id or a substring of its title, the active editor first.
	 */
	private static MPart resolvePart(EPartService partService, String id) {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		// PartAddress picks the visible one of several parts sharing an id and takes the id@path form
		IWorkbenchPartReference addressed = page == null ? null : PartAddress.find(page, id);
		IWorkbenchPart addressedPart = addressed == null ? null : addressed.getPart(true);
		if (addressedPart != null) {
			// no fall back to the id lookup, which finds the first of several parts sharing it
			return modelOf(addressedPart);
		}
		MPart byId = partService.findPart(id);
		if (byId != null) {
			return byId;
		}
		if (page == null) {
			return null;
		}
		IEditorPart active = page.getActiveEditor();
		if (active != null && matches(active.getSite().getId(), active.getTitle(), id)) {
			return modelOf(active);
		}
		for (IEditorReference reference : page.getEditorReferences()) {
			if (matches(reference.getId(), reference.getTitle(), id)) {
				IEditorPart opened = reference.getEditor(true);
				MPart model = opened == null ? null : modelOf(opened);
				if (model != null) {
					return model;
				}
			}
		}
		return null;
	}

	private static boolean matches(String editorId, String title, String requested) {
		return requested.equals(editorId) || (title != null && title.contains(requested));
	}

	private static MPart modelOf(IWorkbenchPart part) {
		return part.getSite().getService(MPart.class);
	}

	/** Whether the element sits inside the shared editor area. */
	private static boolean inEditorArea(MUIElement element) {
		MUIElement current = element instanceof MPart part && part.getCurSharedRef() != null ? part.getCurSharedRef()
				: element;
		while (current != null) {
			if (current instanceof MArea) {
				return true;
			}
			MElementContainer<MUIElement> parent = current.getParent();
			current = parent;
		}
		return false;
	}

	/** The stack a target names, whether the caller named the stack or a part in it. */
	private static MPartStack findStack(EModelService modelService, MWindow window, EPartService partService,
			String target) {
		List<MPartStack> byId = modelService.findElements(window, target, MPartStack.class, null,
				EModelService.PRESENTATION);
		if (!byId.isEmpty()) {
			return byId.get(0);
		}
		MPart part = resolvePart(partService, target);
		if (part == null) {
			return null;
		}
		MUIElement placed = part.getCurSharedRef() == null ? part : part.getCurSharedRef();
		MUIElement parent = placed.getParent();
		return parent instanceof MPartStack stack ? stack : null;
	}

	/** Every stack the active perspective shows, with what is in it. */
	private static JsonObject layout(EModelService modelService, MWindow window, int maxResults) {
		List<MPartStack> all = new ArrayList<>();
		for (MPartStack stack : modelService.findElements(window, null, MPartStack.class, null,
				EModelService.PRESENTATION)) {
			if (!stack.getChildren().isEmpty()) {
				all.add(stack);
			}
		}
		JsonArray stacks = new JsonArray();
		for (MPartStack stack : all.subList(0, Math.min(maxResults, all.size()))) {
			JsonArray parts = new JsonArray();
			for (MStackElement child : stack.getChildren()) {
				parts.add(idOf(child));
			}
			stacks.add(new JsonObject().put("id", stack.getElementId()) //$NON-NLS-1$
					.put("visible", Boolean.valueOf(stack.isVisible())) //$NON-NLS-1$
					.put("parts", parts)); //$NON-NLS-1$
		}
		return new JsonObject().put("stacks", stacks) //$NON-NLS-1$
				.put("total", Integer.valueOf(all.size())) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(all.size() > maxResults)); //$NON-NLS-1$
	}

	/** The id a caller can address, which for a placeholder is the part it stands for. */
	private static String idOf(MStackElement element) {
		if (element instanceof MPlaceholder placeholder && placeholder.getRef() != null) {
			return placeholder.getRef().getElementId();
		}
		// every compatibility editor has the same element id, so the tab title is
		// the only thing that tells them apart in a layout listing
		if (element instanceof MPart part && part.getTags().contains("Editor") && part.getLabel() != null) { //$NON-NLS-1$
			return "editor '" + part.getLabel() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
		}
		return element.getElementId();
	}
}
