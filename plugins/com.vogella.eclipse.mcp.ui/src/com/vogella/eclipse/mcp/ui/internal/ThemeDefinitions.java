package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.ITheme;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reads the {@code org.eclipse.ui.themes} colour and font definitions of the running
 * IDE, each with the value its declaration asks for and the value the active theme
 * actually resolves it to.
 * <p>
 * Two of the fields cannot be answered from a source tree at all: which definitions
 * come from installed bundles that are in no workspace project, and what the active
 * theme resolves a definition to. A declaration is read from the extension registry
 * rather than from {@code IThemeRegistry}, which is internal to the workbench, and
 * the resolved value from the public {@link ITheme} registries, so nothing here
 * depends on workbench internals.
 */
final class ThemeDefinitions {

	private static final String EXTENSION_POINT = "org.eclipse.ui.themes"; //$NON-NLS-1$

	private static final String COLOR = "colorDefinition"; //$NON-NLS-1$

	private static final String FONT = "fontDefinition"; //$NON-NLS-1$

	private static final String CATEGORY = "themeElementCategory"; //$NON-NLS-1$

	private ThemeDefinitions() {
	}

	/** What the caller asked to see. */
	record Query(boolean colors, boolean fonts, Pattern id, String categoryId, Pattern bundle,
			boolean onlyOverridden, boolean countOnly, int maxResults) {
	}

	static JsonObject list(Query query) {
		IExtensionRegistry registry = Platform.getExtensionRegistry();
		if (registry == null) {
			return new JsonObject().put("reason", //$NON-NLS-1$
					"There is no extension registry, so no theme definition can be read."); //$NON-NLS-1$
		}
		Map<String, String> categories = categories(registry);
		ITheme theme = activeTheme();

		List<JsonObject> colors = query.colors() ? read(registry, COLOR, theme, query, categories) : List.of();
		List<JsonObject> fonts = query.fonts() ? read(registry, FONT, theme, query, categories) : List.of();

		// the workbench ITheme owns the registries below, but the e4 CSS theme is what repaints the IDE;
		// reporting only the first says "org.eclipse.ui.defaultTheme" at a dark IDE
		String cssTheme = CssStyling.activeCssThemeId();
		JsonObject result = new JsonObject()
				.put("activeThemeId", theme == null ? null : theme.getId()) //$NON-NLS-1$
				.put("activeCssThemeId", cssTheme) //$NON-NLS-1$
				.put("colorDefinitions", Integer.valueOf(colors.size())) //$NON-NLS-1$
				.put("fontDefinitions", Integer.valueOf(fonts.size())); //$NON-NLS-1$
		if (cssTheme != null && theme != null && !cssTheme.equals(theme.getId())) {
			result.put("themeNote", //$NON-NLS-1$
					"activeThemeId is the workbench theme the colour and font registries belong to, and activeCssThemeId is the e4 CSS theme that repaints the IDE. They are separate systems and they differ here, which is normal: a dark IDE usually still reports org.eclipse.ui.defaultTheme as its workbench theme. resolvedValue is what the registries hold, so it is what an editor asking the registry gets."); //$NON-NLS-1$
		}
		if (query.countOnly()) {
			return result.put("countOnly", Boolean.TRUE); //$NON-NLS-1$
		}
		result.put("colors", capped(colors, query.maxResults())) //$NON-NLS-1$
				.put("fonts", capped(fonts, query.maxResults())); //$NON-NLS-1$
		boolean truncated = colors.size() > query.maxResults() || fonts.size() > query.maxResults();
		result.put("truncated", Boolean.valueOf(truncated)); //$NON-NLS-1$
		if (truncated) {
			result.put("truncationNote", //$NON-NLS-1$
					"Each kind is capped at maxResults separately. The counts above are the totals before the cap."); //$NON-NLS-1$
		}
		if (theme == null) {
			result.put("resolvedValueNote", //$NON-NLS-1$
					"No workbench theme could be reached, so only the declared values are reported and resolvedValue is null throughout."); //$NON-NLS-1$
		}
		return result;
	}

	private static JsonArray capped(List<JsonObject> entries, int maxResults) {
		JsonArray array = new JsonArray();
		entries.stream().limit(maxResults).forEach(array::add);
		return array;
	}

	private static ITheme activeTheme() {
		if (!PlatformUI.isWorkbenchRunning()) {
			return null;
		}
		return PlatformUI.getWorkbench().getThemeManager().getCurrentTheme();
	}

	/** Category ids to their labels, so an entry can name the category it hangs under. */
	private static Map<String, String> categories(IExtensionRegistry registry) {
		Map<String, String> labels = new LinkedHashMap<>();
		for (IConfigurationElement element : registry.getConfigurationElementsFor(EXTENSION_POINT)) {
			if (CATEGORY.equals(element.getName())) {
				labels.put(element.getAttribute("id"), element.getAttribute("label")); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return labels;
	}

	private static List<JsonObject> read(IExtensionRegistry registry, String kind, ITheme theme, Query query,
			Map<String, String> categories) {
		List<JsonObject> entries = new ArrayList<>();
		for (IConfigurationElement element : registry.getConfigurationElementsFor(EXTENSION_POINT)) {
			if (!kind.equals(element.getName())) {
				continue;
			}
			String id = element.getAttribute("id"); //$NON-NLS-1$
			if (id == null || (query.id() != null && !query.id().matcher(id).find())) {
				continue;
			}
			String categoryId = element.getAttribute("categoryId"); //$NON-NLS-1$
			if (query.categoryId() != null && !query.categoryId().equals(categoryId)) {
				continue;
			}
			String bundle = element.getContributor().getName();
			if (query.bundle() != null && !query.bundle().matcher(bundle).find()) {
				continue;
			}
			String declared = element.getAttribute("value"); //$NON-NLS-1$
			String resolved = COLOR.equals(kind) ? resolvedColor(theme, id) : resolvedFont(theme, id);
			Boolean overridden = overridden(declared, resolved, kind);
			if (query.onlyOverridden() && !Boolean.TRUE.equals(overridden)) {
				continue;
			}
			entries.add(describe(element, id, categoryId, categories, bundle, declared, resolved, overridden));
		}
		return entries;
	}

	private static JsonObject describe(IConfigurationElement element, String id, String categoryId,
			Map<String, String> categories, String bundle, String declared, String resolved, Boolean overridden) {
		JsonObject entry = new JsonObject().put("id", id) //$NON-NLS-1$
				.put("label", element.getAttribute("label")) //$NON-NLS-1$ //$NON-NLS-2$
				.put("categoryId", categoryId) //$NON-NLS-1$
				.put("categoryLabel", categoryId == null ? null : categories.get(categoryId)) //$NON-NLS-1$
				.put("declaredValue", declared) //$NON-NLS-1$
				.put("defaultsTo", element.getAttribute("defaultsTo")) //$NON-NLS-1$ //$NON-NLS-2$
				.put("resolvedValue", resolved) //$NON-NLS-1$
				// absent means editable: the attribute only ever turns it off
				.put("isEditable", Boolean.valueOf(!"false".equals(element.getAttribute("isEditable")))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("bundle", bundle); //$NON-NLS-1$
		if (element.getAttribute("colorFactory") != null) { //$NON-NLS-1$
			entry.put("colorFactory", element.getAttribute("colorFactory")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (overridden != null) {
			entry.put("overridden", overridden); //$NON-NLS-1$
		}
		return entry;
	}

	/**
	 * Whether the active theme resolved the definition to something other than its
	 * declaration asked for, or {@code null} when the two are not comparable.
	 * <p>
	 * Only a declared literal can be compared. A definition that delegates through
	 * {@code defaultsTo}, computes its value in a {@code colorFactory} or names an
	 * OS colour has no literal to compare the resolved value against, and claiming
	 * either answer there would be a guess.
	 */
	private static Boolean overridden(String declared, String resolved, String kind) {
		if (!COLOR.equals(kind) || declared == null || resolved == null) {
			return null;
		}
		RGB declaredRgb = parseRgb(declared);
		return declaredRgb == null ? null : Boolean.valueOf(!format(declaredRgb).equals(resolved));
	}

	/** A declared value is an {@code r,g,b} triple or a hex literal; anything else is symbolic. */
	private static RGB parseRgb(String value) {
		String text = value.strip();
		try {
			if (text.startsWith("#") && text.length() == 7) { //$NON-NLS-1$
				return new RGB(Integer.parseInt(text.substring(1, 3), 16), Integer.parseInt(text.substring(3, 5), 16),
						Integer.parseInt(text.substring(5, 7), 16));
			}
			String[] parts = text.split(","); //$NON-NLS-1$
			if (parts.length == 3) {
				return new RGB(Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()),
						Integer.parseInt(parts[2].strip()));
			}
		} catch (IllegalArgumentException e) {
			// parseInt's, and RGB's for an out of range component; an unreadable value is symbolic and yields an absent comparison
		}
		return null;
	}

	private static String resolvedColor(ITheme theme, String id) {
		if (theme == null || !theme.getColorRegistry().hasValueFor(id)) {
			return null;
		}
		return format(theme.getColorRegistry().getRGB(id));
	}

	private static String format(RGB rgb) {
		return rgb == null ? null : "#%02x%02x%02x".formatted(Integer.valueOf(rgb.red), Integer.valueOf(rgb.green), //$NON-NLS-1$
				Integer.valueOf(rgb.blue));
	}

	private static String resolvedFont(ITheme theme, String id) {
		if (theme == null || !theme.getFontRegistry().hasValueFor(id)) {
			return null;
		}
		FontData[] data = theme.getFontRegistry().getFontData(id);
		if (data == null || data.length == 0) {
			return null;
		}
		FontData first = data[0];
		return "%s %d%s".formatted(first.getName(), Integer.valueOf(first.getHeight()), style(first)); //$NON-NLS-1$
	}

	private static String style(FontData data) {
		StringBuilder text = new StringBuilder();
		if ((data.getStyle() & SWT.BOLD) != 0) {
			text.append(" bold"); //$NON-NLS-1$
		}
		if ((data.getStyle() & SWT.ITALIC) != 0) {
			text.append(" italic"); //$NON-NLS-1$
		}
		return text.toString();
	}
}
