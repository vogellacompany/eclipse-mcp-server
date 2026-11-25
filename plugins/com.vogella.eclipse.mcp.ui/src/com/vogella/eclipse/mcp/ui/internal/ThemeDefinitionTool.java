package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;

/**
 * Lists the colour and font definitions registered in the running IDE.
 */
public final class ThemeDefinitionTool implements IMcpTool {

	/** Reading two registries and the extension registry, with no restyling involved. */
	private static final long UI_TIMEOUT_SECONDS = 15;

	@Override
	public String getName() {
		return "eclipse_list_theme_definitions"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Lists the org.eclipse.ui.themes colorDefinition and fontDefinition entries registered in THIS RUNNING IDE, each with its id, label, category, the value its declaration asks for, the value the active theme resolves it to, isEditable and the contributing bundle. READ ONLY. Two of those cannot be answered by grepping a source tree, which is the reason this exists: definitions contributed by installed bundles that are in no workspace project are invisible to a grep, and the resolved value is a property of the active theme rather than of any file. THE TWO VALUES ARE REPORTED SEPARATELY ON PURPOSE, because they disagree more often than expected: isEditable false takes a definition off the preference page but does not stop the CSS path in ThemeElementHelper.populateDefinition overwriting it, so a declaration and what the IDE actually draws are different questions. 'overridden' says whether they differ, and is omitted rather than guessed when the declaration has no literal to compare against, which is the case for defaultsTo, colorFactory and OS colour names. Filter with idPattern for a family such as tag, comment or string, with bundleFilter for what one bundle contributes, or with categoryId. countOnly gives the totals per kind without the entries."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "kind":           {"type":"string","enum":["colors","fonts","all"],"default":"all"},
				    "idPattern":      {"type":"string","description":"Regular expression matched anywhere in the id, e.g. tag|comment|string. Omit for every definition."},
				    "categoryId":     {"type":"string","description":"Only definitions hanging under this theme element category."},
				    "bundleFilter":   {"type":"string","description":"Regular expression matched anywhere in the contributing bundle's symbolic name, e.g. pde|jdt. Answers which bundle brings which definitions."},
				    "onlyOverridden": {"type":"boolean","default":false,"description":"Only definitions whose resolved value differs from the literal they declare. Never includes one whose declaration has no literal to compare."},
				    "countOnly":      {"type":"boolean","default":false,"description":"Report the totals per kind and no entries."},
				    "maxResults":     {"type":"integer","default":200,"minimum":1,"maximum":2000,"description":"Applied to each kind separately."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public String getOutputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "reason":            {"type":"string"},
				    "activeThemeId":     {"type":["string","null"]},
				    "activeCssThemeId":  {"type":["string","null"]},
				    "colorDefinitions":  {"type":"integer"},
				    "fontDefinitions":   {"type":"integer"},
				    "themeNote":         {"type":"string"},
				    "countOnly":         {"type":"boolean"},
				    "colors":            {"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"label":{"type":["string","null"]},"categoryId":{"type":["string","null"]},"categoryLabel":{"type":["string","null"]},"declaredValue":{"type":["string","null"]},"defaultsTo":{"type":["string","null"]},"resolvedValue":{"type":["string","null"]},"isEditable":{"type":"boolean"},"bundle":{"type":["string","null"]},"colorFactory":{"type":"string"},"overridden":{"type":"boolean"}}}},
				    "fonts":             {"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"label":{"type":["string","null"]},"categoryId":{"type":["string","null"]},"categoryLabel":{"type":["string","null"]},"declaredValue":{"type":["string","null"]},"defaultsTo":{"type":["string","null"]},"resolvedValue":{"type":["string","null"]},"isEditable":{"type":"boolean"},"bundle":{"type":["string","null"]},"colorFactory":{"type":"string"},"overridden":{"type":"boolean"}}}},
				    "truncated":         {"type":"boolean"},
				    "truncationNote":    {"type":"string"},
				    "resolvedValueNote": {"type":"string"}
				  }
				}"""; //$NON-NLS-1$
	}

	private static Pattern compile(String pattern) {
		return pattern == null ? null : Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String kind = args.getString("kind", "all"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!"all".equals(kind) && !"colors".equals(kind) && !"fonts".equals(kind)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			return McpToolResult.error("'kind' has to be colors, fonts or all, not '%s'.".formatted(kind)); //$NON-NLS-1$
		}
		Pattern id;
		Pattern bundle;
		try {
			id = compile(args.getString("idPattern")); //$NON-NLS-1$
		} catch (PatternSyntaxException e) {
			return McpToolResult.error("'idPattern' is not a valid regular expression: %s".formatted(e.getMessage())); //$NON-NLS-1$
		}
		try {
			bundle = compile(args.getString("bundleFilter")); //$NON-NLS-1$
		} catch (PatternSyntaxException e) {
			return McpToolResult.error("'bundleFilter' is not a valid regular expression: %s".formatted(e.getMessage())); //$NON-NLS-1$
		}
		ThemeDefinitions.Query query = new ThemeDefinitions.Query(!"fonts".equals(kind), !"colors".equals(kind), id, //$NON-NLS-1$ //$NON-NLS-2$
				args.getString("categoryId"), bundle, args.getBoolean("onlyOverridden", false), //$NON-NLS-1$ //$NON-NLS-2$
				args.getBoolean("countOnly", false), args.getInt("maxResults", 200, 1, 2000)); //$NON-NLS-1$ //$NON-NLS-2$
		// the colour and font registries belong to the UI thread, and the extension
		// registry is read alongside them so that one answer describes one moment
		return UiThread.call(UI_TIMEOUT_SECONDS, () -> ThemeDefinitions.list(query));
	}
}
