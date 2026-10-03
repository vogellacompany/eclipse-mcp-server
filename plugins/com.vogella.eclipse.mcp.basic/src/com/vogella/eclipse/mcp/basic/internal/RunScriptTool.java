package com.vogella.eclipse.mcp.basic.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolRegistry;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.UiDispatch;
import com.vogella.eclipse.mcp.core.json.Json;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.core.json.JsonRaw;

/**
 * Runs several tools in order, optionally in one turn of the UI thread.
 */
public final class RunScriptTool implements IMcpTool {

	private static final int MAX_STEPS = 100;

	@Override
	public String getName() {
		return "eclipse_run_script"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Runs several tools in order and reports what each one answered, so a sequence is one call instead of several. DOES WHATEVER THE TOOLS IT IS GIVEN DO, so it is exactly as destructive as its steps. TWO THINGS IT BUYS. First, atomic true runs the whole batch inside ONE turn of the UI thread, which is the only way a transient state survives from one step to the next: a content assist popup, a hover or a drag can close as soon as the event loop runs between two ordinary calls, so 'open the proposals then read them' is what this exists for. NOTHING REPAINTS INSIDE THE BATCH EITHER: a widget the batch creates has correct bounds and has never painted, so anything reading the SCREEN rather than the widget sees what was there before. eclipse_screenshot handles that by painting the widget itself and reporting sameTurnCapture, but a whole-display capture cannot, and any tool added later that reads pixels has the same problem. Try the sequence without atomic first; transient state often does survive between two ordinary calls, and the answer is more faithful when it does. Second, each step may carry an expect block, which turns a sequence into a check that passes or fails rather than a transcript somebody has to read: this is what makes a scripted IDE test possible from outside. Steps run in order and stop at the first failure unless stopOnFailure is false or the step says continueOnError. A step whose expectations fail is reported with the path, what was expected and what was there. Do not put eclipse_restart in a script: it takes the IDE down and the remaining steps go with it."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "steps": {"type":"array","description":"The tools to run, in order.","items":{
				      "type":"object",
				      "properties":{
				        "tool":            {"type":"string","description":"Tool name, e.g. eclipse_type_text."},
				        "arguments":       {"type":"object","description":"Its arguments, exactly as the tool takes them."},
				        "label":           {"type":"string","description":"A name for this step in the report."},
				        "expect":          {"type":"object","description":"What the answer has to hold, as path to matcher. A path walks the answer with dots and list indices, as in 'widgets.0.selected', and picks an entry of a list by one of its fields with name[key=value], as in 'items[command=org.eclipse.ui.edit.undo].enabled', which is what keeps a script from breaking when the list gains an entry; the search descends into nested lists, so an item in a submenu needs no path through every level. A plain value means equality; {\\"contains\\":\\"x\\"} a substring; {\\"matches\\":\\"regex\\"} a regular expression; {\\"exists\\":true} presence; {\\"size\\":n} the length of a list."},
				        "continueOnError": {"type":"boolean","default":false,"description":"Run the following steps even when this one fails."}
				      },
				      "required":["tool"],
				      "additionalProperties": false}},
				    "atomic":        {"type":"boolean","default":false,"description":"Run every step in one turn of the UI thread, so nothing repaints or closes in between. Use it for transient state; leave it off for long steps, since the UI is blocked for the whole batch. Note that nothing repaints is the cost as well as the point: a screenshot inside the batch cannot read the screen, and says so as sameTurnCapture."},
				    "stopOnFailure": {"type":"boolean","default":true,"description":"Stop at the first step that errors or fails its expectations."}
				  },
				  "required": ["steps"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		if (!(arguments.get("steps") instanceof List<?> raw)) { //$NON-NLS-1$
			return McpToolResult.error("Give 'steps' as an array of {tool, arguments}."); //$NON-NLS-1$
		}
		if (raw.isEmpty()) {
			return McpToolResult.error("'steps' is empty, so there is nothing to run."); //$NON-NLS-1$
		}
		if (raw.size() > MAX_STEPS) {
			return McpToolResult.error("A script runs at most %d steps; this one has %d." //$NON-NLS-1$
					.formatted(Integer.valueOf(MAX_STEPS), Integer.valueOf(raw.size())));
		}
		List<Step> steps = new ArrayList<>();
		for (Object entry : raw) {
			if (!(entry instanceof Map<?, ?> map) || !(map.get("tool") instanceof String tool)) { //$NON-NLS-1$
				return McpToolResult.error("Every step needs a 'tool' name."); //$NON-NLS-1$
			}
			Optional<IMcpTool> found = McpToolRegistry.getInstance().findTool(tool);
			if (found.isEmpty()) {
				return McpToolResult.error("No tool is registered under the name '%s'.".formatted(tool)); //$NON-NLS-1$
			}
			if (getName().equals(tool)) {
				return McpToolResult.error("A script cannot contain itself."); //$NON-NLS-1$
			}
			steps.add(new Step(found.get(), argumentsOf(map.get("arguments")), //$NON-NLS-1$
					map.get("label") instanceof String label ? label : tool, //$NON-NLS-1$
					map.get("expect") instanceof Map<?, ?> expect ? expect : Map.of(), //$NON-NLS-1$
					Boolean.TRUE.equals(map.get("continueOnError")))); //$NON-NLS-1$
		}
		boolean atomic = args.getBoolean("atomic", false); //$NON-NLS-1$
		boolean stopOnFailure = args.getBoolean("stopOnFailure", true); //$NON-NLS-1$
		try {
			List<McpToolResult.Image> images = new ArrayList<>();
			JsonObject report = atomic
					? UiDispatch.call(() -> run(steps, stopOnFailure, true, images, monitor), CallBudget.maxWaitSeconds())
					: run(steps, stopOnFailure, false, images, monitor);
			return new McpToolResult(report.toString(), false, images);
		} catch (Exception e) {
			return McpToolResult.error("The script could not be run: " + e); //$NON-NLS-1$
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> argumentsOf(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	private record Step(IMcpTool tool, Map<String, Object> arguments, String label, Map<?, ?> expect,
			boolean continueOnError) {
	}

	private static JsonObject run(List<Step> steps, boolean stopOnFailure, boolean atomic,
			List<McpToolResult.Image> images, IProgressMonitor monitor) {
		JsonArray results = new JsonArray();
		int passed = 0;
		int failed = 0;
		boolean stopped = false;
		boolean outOfBudget = false;
		// every step bounds its own wait, but three bounded waits in a row still
		// outlast the call, and a script the server aborted answers nothing at all
		int budgetSeconds = CallBudget.maxWaitSeconds();
		long deadline = System.currentTimeMillis() + budgetSeconds * 1000L;
		for (Step step : steps) {
			if (stopped) {
				results.add(new JsonObject().put("label", step.label()).put("tool", step.tool().getName()) //$NON-NLS-1$ //$NON-NLS-2$
						.put("ran", Boolean.FALSE).put("reason", "An earlier step failed.")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				continue;
			}
			if (System.currentTimeMillis() >= deadline || (monitor != null && monitor.isCanceled())) {
				outOfBudget = true;
				results.add(new JsonObject().put("label", step.label()).put("tool", step.tool().getName()) //$NON-NLS-1$ //$NON-NLS-2$
						.put("ran", Boolean.FALSE).put("reason", //$NON-NLS-1$ //$NON-NLS-2$
								"The script's budget of %d seconds ran out before this step." //$NON-NLS-1$
										.formatted(Integer.valueOf(budgetSeconds))));
				continue;
			}
			long started = System.currentTimeMillis();
			JsonObject entry = new JsonObject().put("label", step.label()) //$NON-NLS-1$
					.put("tool", step.tool().getName()).put("ran", Boolean.TRUE); //$NON-NLS-1$ //$NON-NLS-2$
			String text;
			boolean error;
			try {
				McpToolResult result = step.tool().call(step.arguments(), monitor);
				text = result.text();
				error = result.isError();
				if (!result.images().isEmpty()) {
					// the step's answer only counts them; the images follow the script's own text
					images.addAll(result.images());
					entry.put("images", Integer.valueOf(result.images().size())); //$NON-NLS-1$
				}
			} catch (Exception e) {
				text = String.valueOf(e);
				error = true;
			}
			entry.put("millis", Long.valueOf(System.currentTimeMillis() - started)) //$NON-NLS-1$
					.put("error", Boolean.valueOf(error)); //$NON-NLS-1$
			Object answer = parse(text);
			entry.put("answer", answer instanceof Map || answer instanceof List ? new JsonRaw(text) : text); //$NON-NLS-1$
			JsonArray failures = error ? new JsonArray() : check(step.expect(), answer);
			boolean ok = !error && failures.size() == 0;
			entry.put("ok", Boolean.valueOf(ok)); //$NON-NLS-1$
			if (failures.size() > 0) {
				entry.put("expectationsFailed", failures); //$NON-NLS-1$
			}
			results.add(entry);
			if (ok) {
				passed++;
			} else {
				failed++;
				if (stopOnFailure && !step.continueOnError()) {
					stopped = true;
				}
			}
		}
		return new JsonObject().put("total", Integer.valueOf(steps.size())) //$NON-NLS-1$
				.put("passed", Integer.valueOf(passed)) //$NON-NLS-1$
				.put("failed", Integer.valueOf(failed)) //$NON-NLS-1$
				.put("stoppedEarly", Boolean.valueOf(stopped || outOfBudget)) //$NON-NLS-1$
				.put("budgetExhausted", outOfBudget ? Boolean.TRUE : null) //$NON-NLS-1$
				.put("budgetNote", !outOfBudget ? null //$NON-NLS-1$
						: "The steps ran for longer than the %d seconds that fit inside the server's %d second tool call timeout, so the remaining ones were not started; run them in a second script." //$NON-NLS-1$
								.formatted(Integer.valueOf(budgetSeconds), Integer.valueOf(CallBudget.callTimeoutSeconds())))
				.put("atomic", Boolean.valueOf(atomic)) //$NON-NLS-1$
				.put("steps", results) //$NON-NLS-1$
				.put("note", atomic //$NON-NLS-1$
						? "Every step ran in one turn of the UI thread, so nothing repainted or closed between them." //$NON-NLS-1$
						: "The steps ran as ordinary calls, so the event loop ran between them and anything transient will have closed."); //$NON-NLS-1$
	}

	private static Object parse(String text) {
		try {
			return Json.parse(text);
		} catch (RuntimeException e) {
			return text;
		}
	}

	/** Checks the expectations against one answer. */
	private static JsonArray check(Map<?, ?> expect, Object answer) {
		JsonArray failures = new JsonArray();
		for (Map.Entry<?, ?> entry : expect.entrySet()) {
			String path = String.valueOf(entry.getKey());
			Object found = valueAt(answer, path);
			String failure = match(entry.getValue(), found);
			if (failure != null) {
				failures.add(new JsonObject().put("path", path) //$NON-NLS-1$
						.put("expected", describe(entry.getValue())) //$NON-NLS-1$
						.put("found", found == null ? null : String.valueOf(found)) //$NON-NLS-1$
						.put("reason", failure)); //$NON-NLS-1$
			}
		}
		return failures;
	}

	/** {@code null} when the value satisfies the matcher, the reason otherwise. */
	private static String match(Object matcher, Object found) {
		if (matcher instanceof Map<?, ?> map) {
			if (map.containsKey("exists")) { //$NON-NLS-1$
				boolean wanted = Boolean.TRUE.equals(map.get("exists")); //$NON-NLS-1$
				return wanted == (found != null) ? null : wanted ? "nothing is there" : "something is there"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			if (map.containsKey("contains")) { //$NON-NLS-1$
				String needle = String.valueOf(map.get("contains")); //$NON-NLS-1$
				return found != null && String.valueOf(found).contains(needle) ? null : "does not contain it"; //$NON-NLS-1$
			}
			if (map.containsKey("matches")) { //$NON-NLS-1$
				String regex = String.valueOf(map.get("matches")); //$NON-NLS-1$
				try {
					return found != null && java.util.regex.Pattern.compile(regex).matcher(String.valueOf(found)).find()
							? null
							: "does not match"; //$NON-NLS-1$
				} catch (java.util.regex.PatternSyntaxException e) {
					return "the expectation is not a regular expression: " + e.getDescription(); //$NON-NLS-1$
				}
			}
			if (map.containsKey("size")) { //$NON-NLS-1$
				if (!(map.get("size") instanceof Number wanted)) { //$NON-NLS-1$
					return "the expected size is not a number"; //$NON-NLS-1$
				}
				if (!(found instanceof List<?> list)) {
					return "that is not a list"; //$NON-NLS-1$
				}
				return list.size() == wanted.intValue() ? null
						: "the list has %d entries".formatted(Integer.valueOf(list.size())); //$NON-NLS-1$
			}
			return "the expectation names no matcher; use contains, matches, exists or size"; //$NON-NLS-1$
		}
		return equal(matcher, found) ? null : "not equal"; //$NON-NLS-1$
	}

	/** Compares across the number and boolean spellings JSON produces. */
	private static boolean equal(Object expected, Object found) {
		if (expected == null || found == null) {
			return expected == found;
		}
		if (expected instanceof Boolean || found instanceof Boolean) {
			return String.valueOf(expected).equalsIgnoreCase(String.valueOf(found));
		}
		if (expected instanceof Number && found instanceof Number) {
			return ((Number) expected).doubleValue() == ((Number) found).doubleValue();
		}
		return String.valueOf(expected).equals(String.valueOf(found));
	}

	private static String describe(Object matcher) {
		return matcher == null ? null : String.valueOf(matcher);
	}

	/** {@code name[key=value]}, or {@code [key=value]} when the list is already at hand. */
	private static final java.util.regex.Pattern SELECTOR = java.util.regex.Pattern
			.compile("([^\\[\\]]*)\\[([^=\\]]+)=([^\\]]*)\\]"); //$NON-NLS-1$

	/**
	 * Walks an answer by a dotted path, where a numeric segment indexes a list and
	 * {@code name[key=value]} picks the entry of a list by one of its fields.
	 * <p>
	 * The selector is what keeps a script from breaking when a list gains an entry:
	 * asking for the sixth item of a context menu is a statement about the menu's
	 * order, when the question was about the item carrying a particular command.
	 */
	static Object valueAt(Object answer, String path) {
		Object current = answer;
		for (String segment : segments(path)) {
			if (current == null) {
				return null;
			}
			java.util.regex.Matcher selector = SELECTOR.matcher(segment);
			if (selector.matches()) {
				String name = selector.group(1);
				Object list = name.isEmpty() ? current
						: current instanceof Map<?, ?> map ? map.get(name) : null;
				current = firstWhere(list, selector.group(2), selector.group(3));
				continue;
			}
			if (current instanceof Map<?, ?> map) {
				current = map.get(segment);
			} else if (current instanceof List<?> list) {
				try {
					int index = Integer.parseInt(segment);
					current = index >= 0 && index < list.size() ? list.get(index) : null;
				} catch (NumberFormatException e) {
					return null;
				}
			} else {
				return null;
			}
		}
		return current;
	}

	/**
	 * Splits a path on the dots that separate its segments, which is not every dot:
	 * a selector's value is usually a command id, and splitting inside the brackets
	 * tore 'items[command=org.eclipse.ui.edit.undo]' into six meaningless pieces.
	 */
	static List<String> segments(String path) {
		List<String> segments = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int depth = 0;
		for (int i = 0; i < path.length(); i++) {
			char c = path.charAt(i);
			if (c == '[') {
				depth++;
			} else if (c == ']') {
				depth--;
			}
			if (c == '.' && depth <= 0) {
				segments.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		segments.add(current.toString());
		return segments;
	}

	/**
	 * The first entry of a list whose field equals the value, searched into nested
	 * lists as well, so an item in a submenu is reachable without naming each level.
	 */
	private static Object firstWhere(Object list, String key, String value) {
		if (!(list instanceof List<?> entries)) {
			return null;
		}
		for (Object entry : entries) {
			if (entry instanceof Map<?, ?> map && equal(value, map.get(key))) {
				return entry;
			}
		}
		for (Object entry : entries) {
			if (entry instanceof Map<?, ?> map) {
				for (Object nested : map.values()) {
					Object found = firstWhere(nested, key, value);
					if (found != null) {
						return found;
					}
				}
			}
		}
		return null;
	}
}
