package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
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
 * Where a piece of text is on screen, and which annotations sit on it.
 * <p>
 * Both answer in the coordinates the capture tools use, so a text range can be
 * handed to {@code eclipse_screenshot} as a highlight without any guessing.
 */
public final class TextBoundsTools {

	private static final long UI_TIMEOUT_SECONDS = 15;

	private TextBoundsTools() {
	}

	/** The editor, its viewer and its widget, or the reason there is none. */
	record Target(ITextEditor editor, ITextViewer viewer, StyledText text, Control part, String error) {

		static Target failed(String reason) {
			return new Target(null, null, null, null, reason);
		}
	}

	static Target target(String partId) {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		if (page == null) {
			return Target.failed("There is no active workbench page."); //$NON-NLS-1$
		}
		IEditorPart editor = null;
		if (partId == null || partId.isBlank()) {
			editor = page.getActiveEditor();
			if (editor == null) {
				return Target.failed("There is no active editor. Give 'part' to name one."); //$NON-NLS-1$
			}
		} else {
			editor = Parts.part(page, partId) instanceof IEditorPart named ? named : null;
			if (editor == null) {
				return Target.failed("No open editor has the id '%s'. eclipse_list_ui_targets lists the open parts." //$NON-NLS-1$
						.formatted(partId));
			}
		}
		if (!(editor instanceof ITextEditor textEditor)) {
			return Target.failed("The editor '%s' is not a text editor.".formatted(editor.getTitle())); //$NON-NLS-1$
		}
		// a text editor hands its viewer out as the operation target; ITextViewer
		// itself is not among its adapters
		if (!(textEditor.getAdapter(ITextOperationTarget.class) instanceof ITextViewer viewer)
				|| viewer.getTextWidget() == null || viewer.getTextWidget().isDisposed()) {
			return Target.failed("The editor '%s' has no text widget yet.".formatted(editor.getTitle())); //$NON-NLS-1$
		}
		Control part = ScreenshotTools.Capture.controlOf(editor);
		if (part == null) {
			return Target.failed("The editor '%s' is not rendered.".formatted(editor.getTitle())); //$NON-NLS-1$
		}
		return new Target(textEditor, viewer, viewer.getTextWidget(), part, null);
	}

	/**
	 * The screen rectangle of a document range, in every coordinate space a caller
	 * captures in. {@code visible} is false when folding hides the range.
	 */
	static JsonObject locate(Target target, int offset, int length) {
		StyledText text = target.text();
		Display display = text.getDisplay();
		int widgetStart = offset;
		int widgetEnd = offset + length;
		if (target.viewer() instanceof ITextViewerExtension5 projection) {
			widgetStart = projection.modelOffset2WidgetOffset(offset);
			widgetEnd = projection.modelOffset2WidgetOffset(offset + length);
		}
		JsonObject result = new JsonObject().put("offset", Integer.valueOf(offset)).put("length", //$NON-NLS-1$ //$NON-NLS-2$
				Integer.valueOf(length));
		if (widgetStart < 0 || widgetEnd < 0 || widgetStart > text.getCharCount()) {
			return result.put("visible", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", "The range is folded away or outside the text, so it has no place on screen."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		Rectangle inText;
		try {
			if (widgetEnd > widgetStart) {
				inText = text.getTextBounds(widgetStart, Math.min(widgetEnd, text.getCharCount()) - 1);
			} else {
				var at = text.getLocationAtOffset(widgetStart);
				inText = new Rectangle(at.x, at.y, 1, text.getLineHeight(widgetStart));
			}
		} catch (IllegalArgumentException e) {
			return result.put("visible", Boolean.FALSE).put("reason", "The range has no position: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		Rectangle client = text.getClientArea();
		boolean onScreen = inText.intersects(client);
		Control stack = ScreenshotTools.Capture.stackOf(target.part());
		return result.put("visible", Boolean.valueOf(onScreen)) //$NON-NLS-1$
				.put("scrolledOut", Boolean.valueOf(!onScreen)) //$NON-NLS-1$
				.put("inPart", Overlays.describe(WidgetTools.mapToCapture(display, text, target.part(), inText))) //$NON-NLS-1$
				.put("inPartStack", Overlays.describe(WidgetTools.mapToCapture(display, text, stack, inText))) //$NON-NLS-1$
				.put("inShell", Overlays.describe(WidgetTools.mapToCapture(display, text, text.getShell(), inText))) //$NON-NLS-1$
				.put("inTextWidget", Overlays.describe(inText)) //$NON-NLS-1$
				.put("lineHeight", Integer.valueOf(text.getLineHeight(widgetStart))) //$NON-NLS-1$
				.put("baseline", Integer.valueOf(text.getBaseline(widgetStart))) //$NON-NLS-1$
				.put("widgetOffset", Integer.valueOf(widgetStart)); //$NON-NLS-1$
	}

	private static JsonObject widgetPlacement(Target target) {
		Display display = target.text().getDisplay();
		Rectangle own = target.text().getBounds();
		return new JsonObject()
				.put("inPart", Overlays.describe(WidgetTools.mapToCapture(display, target.text().getParent(), //$NON-NLS-1$
						target.part(), own)))
				.put("inPartStack", Overlays.describe(WidgetTools.mapToCapture(display, target.text().getParent(), //$NON-NLS-1$
						ScreenshotTools.Capture.stackOf(target.part()), own)))
				.put("inShell", Overlays.describe(WidgetTools.mapToCapture(display, target.text().getParent(), //$NON-NLS-1$
						target.text().getShell(), own)));
	}

	/** Turns a line, a column and a length, or an offset, into capture coordinates. */
	public static final class TextBounds implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_get_text_bounds"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Reports where a range of text in an editor is on screen: its rectangle in points relative to the editor part (inPart), to the part stack, to the shell and to the text widget, plus the line height, the baseline and the placement of the text widget itself. Changes nothing. Give line (1-based) with an optional column and length, or a document offset with a length; the answer can be passed straight to eclipse_screenshot as a highlight's bounds. Folded regions are handled: a range that folding hides is reported as not visible rather than at a wrong place, and a range scrolled out of view is reported as scrolledOut with the rectangle it would occupy. Defaults to the active editor."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "part":   {"type":"string","description":"Editor part id, e.g. org.eclipse.jdt.ui.CompilationUnitEditor. Defaults to the active editor. Several open parts share one id, every Java editor above all; the active or a visible one is chosen, and a part's TITLE from eclipse_list_ui_targets names a particular one."},
					    "line":   {"type":"integer","minimum":1,"description":"1-based document line."},
					    "column": {"type":"integer","minimum":1,"default":1,"description":"1-based column within the line."},
					    "offset": {"type":"integer","minimum":0,"description":"Document offset, instead of line and column."},
					    "length": {"type":"integer","minimum":0,"description":"Characters in the range. Defaults to the rest of the line when only line is given, to 1 otherwise."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String part = args.getString("part"); //$NON-NLS-1$
			int line = args.getInt("line", 0, 0, Integer.MAX_VALUE); //$NON-NLS-1$
			int column = args.getInt("column", 1, 1, Integer.MAX_VALUE); //$NON-NLS-1$
			int offset = args.getInt("offset", -1, -1, Integer.MAX_VALUE); //$NON-NLS-1$
			int length = args.getInt("length", -1, -1, Integer.MAX_VALUE); //$NON-NLS-1$
			if (line < 1 && offset < 0) {
				return McpToolResult.error("Give 'line' (1-based) or 'offset'."); //$NON-NLS-1$
			}
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> {
				Target target = target(part);
				if (target.error() != null) {
					throw new IllegalArgumentException(target.error());
				}
				IDocument document = target.viewer().getDocument();
				int start;
				int count;
				try {
					if (offset >= 0) {
						start = offset;
						count = length < 0 ? 1 : length;
					} else {
						IRegion region = document.getLineInformation(line - 1);
						start = region.getOffset() + column - 1;
						count = length >= 0 ? length : args.has("column") ? 1 //$NON-NLS-1$
								: Math.max(0, region.getLength() - (column - 1));
					}
					count = Math.max(0, Math.min(count, document.getLength() - start));
					if (start < 0 || start > document.getLength()) {
						throw new IllegalArgumentException("The offset %d is outside the document of %d characters." //$NON-NLS-1$
								.formatted(Integer.valueOf(start), Integer.valueOf(document.getLength())));
					}
					JsonObject result = locate(target, start, count);
					result.put("line", Integer.valueOf(document.getLineOfOffset(start) + 1)) //$NON-NLS-1$
							.put("text", document.get(start, count)) //$NON-NLS-1$
							.put("editor", target.editor().getTitle()) //$NON-NLS-1$
							.put("textWidget", widgetPlacement(target)); //$NON-NLS-1$
					return result;
				} catch (BadLocationException e) {
					throw new IllegalArgumentException("No such position in the document: " + e.getMessage()); //$NON-NLS-1$
				}
			});
		}
	}

	/** The annotations of an editor, with where each one is on screen. */
	public static final class ListAnnotations implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_list_annotations"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Lists the annotations of a text editor, which is what the squiggles, the ruler icons, the overview ruler marks and the folding markers are drawn from: each with its type (org.eclipse.jdt.ui.error, org.eclipse.ui.workbench.texteditor.spelling, org.eclipse.projection and so on), its text, its offset, length and lines, whether a projection annotation is collapsed, and with includeBounds its rectangle on screen in the same coordinates eclipse_get_text_bounds reports, ready to be handed to eclipse_screenshot as a highlight. Changes nothing. Filter with typePattern, a regular expression matched anywhere in the type, and with fromLine and toLine. Defaults to the active editor."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "part":          {"type":"string","description":"Editor part id. Defaults to the active editor. Several open parts share one id, every Java editor above all; the active or a visible one is chosen, and a part's TITLE from eclipse_list_ui_targets names a particular one."},
					    "typePattern":   {"type":"string","description":"Regular expression matched anywhere in the annotation type, e.g. error|warning or projection."},
					    "fromLine":      {"type":"integer","minimum":1,"description":"Only annotations starting at or after this 1-based line."},
					    "toLine":        {"type":"integer","minimum":1,"description":"Only annotations starting at or before this 1-based line."},
					    "includeBounds": {"type":"boolean","default":true,"description":"Also locate each annotation on screen."},
					    "maxResults":    {"type":"integer","default":100,"minimum":1,"maximum":2000}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String part = args.getString("part"); //$NON-NLS-1$
			String typePattern = args.getString("typePattern"); //$NON-NLS-1$
			Pattern type;
			try {
				type = typePattern == null ? null : Pattern.compile(typePattern);
			} catch (PatternSyntaxException e) {
				return McpToolResult.error("'typePattern' is not a regular expression: " + e.getDescription()); //$NON-NLS-1$
			}
			int fromLine = args.getInt("fromLine", 1, 1, Integer.MAX_VALUE); //$NON-NLS-1$
			int toLine = args.getInt("toLine", Integer.MAX_VALUE, 1, Integer.MAX_VALUE); //$NON-NLS-1$
			boolean includeBounds = args.getBoolean("includeBounds", true); //$NON-NLS-1$
			int maxResults = args.getInt("maxResults", 100, 1, 2000); //$NON-NLS-1$
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> {
				Target target = target(part);
				if (target.error() != null) {
					throw new IllegalArgumentException(target.error());
				}
				IDocument document = target.viewer().getDocument();
				List<Entry> entries = new ArrayList<>();
				IAnnotationModel model = target.editor().getDocumentProvider() == null ? null
						: target.editor().getDocumentProvider().getAnnotationModel(target.editor().getEditorInput());
				collect(model, "editor", entries); //$NON-NLS-1$
				ProjectionAnnotationModel projection = target.editor().getAdapter(ProjectionAnnotationModel.class);
				if (projection != null && projection != model) {
					collect(projection, "projection", entries); //$NON-NLS-1$
				}
				entries.sort((a, b) -> Integer.compare(a.position().getOffset(), b.position().getOffset()));
				JsonArray listed = new JsonArray();
				int total = 0;
				for (Entry entry : entries) {
					int startLine;
					int endLine;
					try {
						startLine = document.getLineOfOffset(entry.position().getOffset()) + 1;
						endLine = document.getLineOfOffset(Math.max(entry.position().getOffset(),
								entry.position().getOffset() + entry.position().getLength() - 1)) + 1;
					} catch (BadLocationException e) {
						continue;
					}
					String typeId = entry.annotation().getType();
					if (startLine < fromLine || startLine > toLine || (type != null && !type.matcher(typeId).find())) {
						continue;
					}
					total++;
					if (listed.size() >= maxResults) {
						continue;
					}
					JsonObject json = new JsonObject().put("type", typeId) //$NON-NLS-1$
							.put("source", entry.source()) //$NON-NLS-1$
							.put("text", entry.annotation().getText()) //$NON-NLS-1$
							.put("offset", Integer.valueOf(entry.position().getOffset())) //$NON-NLS-1$
							.put("length", Integer.valueOf(entry.position().getLength())) //$NON-NLS-1$
							.put("startLine", Integer.valueOf(startLine)) //$NON-NLS-1$
							.put("endLine", Integer.valueOf(endLine)); //$NON-NLS-1$
					if (entry.annotation() instanceof ProjectionAnnotation folding) {
						json.put("collapsed", Boolean.valueOf(folding.isCollapsed())); //$NON-NLS-1$
					}
					if (includeBounds) {
						json.put("bounds", locate(target, entry.position().getOffset(), entry.position().getLength())); //$NON-NLS-1$
					}
					listed.add(json);
				}
				return new JsonObject().put("editor", target.editor().getTitle()) //$NON-NLS-1$
						.put("total", Integer.valueOf(total)) //$NON-NLS-1$
						.put("truncated", Boolean.valueOf(total > listed.size())) //$NON-NLS-1$
						.put("textWidget", widgetPlacement(target)) //$NON-NLS-1$
						.put("annotations", listed); //$NON-NLS-1$
			});
		}

		private record Entry(Annotation annotation, Position position, String source) {
		}

		private static void collect(IAnnotationModel model, String source, List<Entry> into) {
			if (model == null) {
				return;
			}
			Iterator<Annotation> iterator = model.getAnnotationIterator();
			while (iterator.hasNext()) {
				Annotation annotation = iterator.next();
				Position position = model.getPosition(annotation);
				if (position != null && !position.isDeleted() && !annotation.isMarkedDeleted()) {
					into.add(new Entry(annotation, position, source));
				}
			}
		}
	}
}
