package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;

/**
 * Puts an ad-hoc stylesheet on top of the running IDE's theme, and takes it away
 * again.
 */
public final class ApplyCssTool implements IMcpTool {

	/** Two full re-styles of every shell, so it needs more room than the other UI tools. */
	private static final long UI_TIMEOUT_SECONDS = 25;

	@Override
	public String getName() {
		return "eclipse_apply_css"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Applies a CSS snippet to the running IDE's theme engine and re-styles every shell, the way PDE's CSS scratch pad does. CHANGES WHAT THE PERSON AT THE IDE SEES, immediately and everywhere, so a garish test colour is visible to them until it is taken back. Nothing is written to disk: the snippet lives in memory, is gone after a restart or a theme change, and 'reset' takes it back at once. Each call re-applies the current theme first, so snippets replace each other instead of piling up, and the answer says how many rules were parsed and reports the parse errors the engine raised, which is where a selector typo shows up. An IEclipsePreferences block, the way themes set JDT syntax colours, takes effect when a theme is activated, which this tool cannot do; it drives the engine's own preference styling all the same and reads every key back, since the engine leaves a value it did not set itself alone until the theme has changed this session. eclipse_set_theme activates a theme and applies such blocks outright. This is what turns a theme question into an experiment: apply a rule with an unmistakable colour, then read eclipse_inspect_widget to see whether it matched, rather than rebuilding a theme plug-in and restarting for every attempt."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "css":   {"type":"string","description":"The stylesheet to apply, e.g. 'CTabFolder ToolBar {background-color: #00ff00;}'. It is appended after the theme's own sheets, so it wins ties of equal specificity."},
				    "reset": {"type":"boolean","default":false,"description":"Take back the snippet applied earlier and leave the IDE on the unmodified theme. Give this instead of css, not with it."}
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
				    "applied":         {"type":"boolean"},
				    "reset":           {"type":"boolean"},
				    "reason":          {"type":"string"},
				    "previousSnippet": {"type":["string","null"]},
				    "theme":           {"type":["string","null"]},
				    "themeReapplied":  {"type":"boolean"},
				    "engines":         {"type":"integer"},
				    "rules":           {"type":["integer","null"]},
				    "errors":          {"type":"array","items":{"type":"string"}},
				    "elapsedMillis":   {"type":"integer"},
				    "ignoredRules":    {"type":"array"},
				    "preferenceRules": {"type":"array"},
				    "note":            {"type":"string"}
				  },
				  "required": ["applied", "errors"]
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String css = args.getString("css"); //$NON-NLS-1$
		boolean reset = args.getBoolean("reset", false); //$NON-NLS-1$
		if (css == null && !reset) {
			return McpToolResult.error("Either 'css' or 'reset' is required."); //$NON-NLS-1$
		}
		if (css != null && reset) {
			return McpToolResult.error("Give either 'css' or 'reset', not both."); //$NON-NLS-1$
		}
		return UiThread.call(UI_TIMEOUT_SECONDS, () -> CssStyling.apply(css, reset));
	}
}
