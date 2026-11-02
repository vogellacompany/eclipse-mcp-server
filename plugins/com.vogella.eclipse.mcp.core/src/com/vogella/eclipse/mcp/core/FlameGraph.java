package com.vogella.eclipse.mcp.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a self contained dark themed page showing a stack trace profile as a flame
 * graph, with the numbers that go with it.
 * <p>
 * Self contained on purpose: the page is served by an IDE that may have no network, so
 * everything it needs is in the one document. Nothing is loaded from a CDN and no file
 * sits beside it.
 */
public final class FlameGraph {

	/** Marks a frame that names a thread, which the page draws as a branch and the tables leave out. */
	public static final String THREAD_PREFIX = "thread: "; //$NON-NLS-1$

	private FlameGraph() {
	}

	/** One frame of the merged call tree, with the weight of everything beneath it. */
	public static final class Node {

		private final String frame;

		private final Map<String, Node> children = new LinkedHashMap<>();

		private long value;

		/** The part of the value from stacks parked in a wait. */
		private long idle;

		Node(String frame) {
			this.frame = frame;
		}

		private Node child(String name) {
			return children.computeIfAbsent(name, Node::new);
		}
	}

	/** Collects stacks into the merged tree a flame graph draws. */
	public static final class Builder {

		private final Node root = new Node("all"); //$NON-NLS-1$

		private long total;

		private int stacks;

		private String quiet;

		/**
		 * Adds one stack and its weight.
		 *
		 * @param framesRootFirst outermost frame first, the order a flame graph stacks
		 *                        them upwards
		 */
		public Builder add(List<String> framesRootFirst, long weight) {
			return add(framesRootFirst, weight, false);
		}

		/**
		 * Adds one stack with the verdict of the answer beside the page, so that the two count
		 * the same stacks as idle.
		 */
		public Builder add(List<String> framesRootFirst, long weight, boolean idle) {
			if (framesRootFirst == null || framesRootFirst.isEmpty() || weight <= 0) {
				return this;
			}
			stacks++;
			total += weight;
			root.value += weight;
			root.idle += idle ? weight : 0;
			Node current = root;
			for (String frame : framesRootFirst) {
				current = current.child(frame);
				current.value += weight;
				current.idle += idle ? weight : 0;
			}
			return this;
		}

		/** Says that nothing was busy and why, which the page then leads with instead of naming a frame. */
		public Builder quiet(String why) {
			quiet = why;
			return this;
		}

		/** Why nothing was busy, or null when nobody said so. */
		public String quiet() {
			return quiet;
		}

		public boolean isEmpty() {
			return total == 0;
		}

		public long total() {
			return total;
		}

		public int stacks() {
			return stacks;
		}

		/** One frame and a weight, for the tables beside the graph. */
		public record Ranked(String frame, long weight) {
		}

		/**
		 * Frames by the weight sitting in them rather than below them, which is where
		 * the cost actually is: a frame's own weight less everything its children took.
		 */
		public List<Ranked> topSelf(int limit) {
			Map<String, Long> self = new LinkedHashMap<>();
			collectSelf(root, self);
			return rank(self, limit);
		}

		/** Frames by the weight of everything beneath them, which is how wide they draw. */
		public List<Ranked> topTotal(int limit) {
			Map<String, Long> total = new LinkedHashMap<>();
			collectTotal(root, total);
			return rank(total, limit);
		}

		private static void collectSelf(Node node, Map<String, Long> into) {
			long below = 0;
			for (Node child : node.children.values()) {
				below += child.value;
				collectSelf(child, into);
			}
			long own = node.value - below;
			if (own > 0 && !node.frame.startsWith(THREAD_PREFIX)) {
				into.merge(node.frame, Long.valueOf(own), (a, b) -> Long.valueOf(a.longValue() + b.longValue()));
			}
		}

		private static void collectTotal(Node node, Map<String, Long> into) {
			for (Node child : node.children.values()) {
				// merged across every place the frame appears, so recursion does not
				// split one method into a dozen rows
				if (!child.frame.startsWith(THREAD_PREFIX)) {
					into.merge(child.frame, Long.valueOf(child.value),
							(a, b) -> Long.valueOf(a.longValue() + b.longValue()));
				}
				collectTotal(child, into);
			}
		}

		private static List<Ranked> rank(Map<String, Long> weights, int limit) {
			List<Ranked> ranked = new ArrayList<>();
			weights.forEach((frame, weight) -> ranked.add(new Ranked(frame, weight.longValue())));
			ranked.sort((a, b) -> Long.compare(b.weight(), a.weight()));
			return ranked.subList(0, Math.min(limit, ranked.size()));
		}

		/** The tree as the compact JSON the page's script walks. */
		String toJson() {
			StringBuilder out = new StringBuilder();
			write(root, out);
			return out.toString();
		}

		private static void write(Node node, StringBuilder out) {
			out.append("{\"n\":"); //$NON-NLS-1$
			quote(node.frame, out);
			out.append(",\"v\":").append(node.value); //$NON-NLS-1$
			if (node.idle > 0) {
				out.append(",\"i\":").append(node.idle); //$NON-NLS-1$
			}
			if (!node.children.isEmpty()) {
				out.append(",\"c\":["); //$NON-NLS-1$
				boolean first = true;
				// biggest first, so the eye lands on the expensive branch
				List<Node> ordered = new ArrayList<>(node.children.values());
				ordered.sort((a, b) -> Long.compare(b.value, a.value));
				for (Node child : ordered) {
					if (!first) {
						out.append(',');
					}
					first = false;
					write(child, out);
				}
				out.append(']');
			}
			out.append('}');
		}
	}

	/** What the page says about itself, beside the graph. */
	public record Spec(String title, String subtitle, String unit, Builder flame, List<Table> tables, String note,
			List<Stat> stats, boolean rankByCaller, List<String> warnings, ProductFamily product, boolean idleShown) {

		public Spec(String title, String subtitle, String unit, Builder flame, List<Table> tables, String note,
				List<Stat> stats, boolean rankByCaller, List<String> warnings, ProductFamily product) {
			this(title, subtitle, unit, flame, tables, note, stats, rankByCaller, warnings, product, false);
		}

		public Spec(String title, String subtitle, String unit, Builder flame, List<Table> tables, String note) {
			this(title, subtitle, unit, flame, tables, note, List.of());
		}

		public Spec(String title, String subtitle, String unit, Builder flame, List<Table> tables, String note,
				List<Stat> stats) {
			this(title, subtitle, unit, flame, tables, note, stats, false);
		}

		public Spec(String title, String subtitle, String unit, Builder flame, List<Table> tables, String note,
				List<Stat> stats, boolean rankByCaller) {
			this(title, subtitle, unit, flame, tables, note, stats, rankByCaller, List.of(), null);
		}
	}

	/** One headline figure, shown as a tile above the graph. */
	public record Stat(String label, String value) {
	}

	/** One summary table: a caption and its rows. */
	public record Table(String caption, List<String> columns, List<List<String>> rows) {
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Pool threads differ only by a number, and a branch per worker would split one job into many. */
	public static String threadBranch(String name) {
		if (name == null) {
			return "unnamed"; //$NON-NLS-1$
		}
		// Eclipse workers append the running job, "Worker-19: Indexing", which is not the thread
		return name.replaceAll(":.*$", "").replaceAll("([-# ])\\d+$", "$1N").strip(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	/** Splits a rendered stack such as {@code a &lt;- b &lt;- c}, innermost frame first. */
	public static List<String> parseArrowStack(String stack) {
		List<String> frames = new ArrayList<>();
		for (String part : stack.split("<-")) { //$NON-NLS-1$
			String frame = part.strip();
			if (!frame.isEmpty()) {
				frames.add(frame);
			}
		}
		// the rendered form is innermost first, a flame graph stacks outermost first
		Collections.reverse(frames);
		return frames;
	}

	/** Bytes as something a person reads, since an allocation profile is mostly megabytes. */
	public static String bytes(long value) {
		if (value < 1024) {
			return value + " B"; //$NON-NLS-1$
		}
		// Locale.ROOT: the page is English, and a German default locale wrote "1,0 KB"
		if (value < 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f KB", Double.valueOf(value / 1024.0)); //$NON-NLS-1$
		}
		if (value < 1024L * 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f MB", Double.valueOf(value / (1024.0 * 1024))); //$NON-NLS-1$
		}
		return String.format(Locale.ROOT, "%.2f GB", Double.valueOf(value / (1024.0 * 1024 * 1024))); //$NON-NLS-1$
	}

	public static String page(Spec spec) {
		StringBuilder out = new StringBuilder(1 << 16);
		out.append("<!doctype html>\n<html lang=\"en\" data-unit=\""); //$NON-NLS-1$
		escape(spec.unit(), out);
		out.append(spec.rankByCaller() ? "\" data-rank=\"caller" : ""); //$NON-NLS-1$ //$NON-NLS-2$
		out.append(spec.idleShown() ? "\" data-idle=\"shown" : ""); //$NON-NLS-1$ //$NON-NLS-2$
		if (spec.product() != null) {
			out.append("\" data-product=\""); //$NON-NLS-1$
			escape(spec.product().toJson().toString(), out);
		}
		out.append("\">\n<head>\n<meta charset=\"utf-8\">\n"); //$NON-NLS-1$
		out.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n<title>"); //$NON-NLS-1$
		escape(spec.title(), out);
		out.append("</title>\n<style>\n").append(STYLE).append("\n</style>\n</head>\n<body>\n"); //$NON-NLS-1$ //$NON-NLS-2$

		out.append("<div class=\"wrap\">\n<header><h1>"); //$NON-NLS-1$
		escape(spec.title(), out);
		out.append("</h1><p class=\"sub\">"); //$NON-NLS-1$
		escape(spec.subtitle(), out);
		out.append("</p>"); //$NON-NLS-1$
		if (spec.warnings() != null) {
			for (String warning : spec.warnings()) {
				out.append("<p class=\"warn\">"); //$NON-NLS-1$
				escape(warning, out);
				out.append("</p>"); //$NON-NLS-1$
			}
		}
		if (spec.stats() != null && !spec.stats().isEmpty()) {
			// a line of facts rather than tiles: none of them says where the cost is
			out.append("<p class=\"facts\">"); //$NON-NLS-1$
			for (Stat stat : spec.stats()) {
				out.append("<span><b>"); //$NON-NLS-1$
				escape(stat.value(), out);
				out.append("</b> "); //$NON-NLS-1$
				escape(stat.label().toLowerCase(Locale.ROOT), out);
				out.append("</span>"); //$NON-NLS-1$
			}
			out.append("</p>"); //$NON-NLS-1$
		}
		out.append("</header>\n"); //$NON-NLS-1$

		if (spec.flame() == null || spec.flame().isEmpty()) {
			if (spec.flame() != null && spec.flame().quiet != null) {
				out.append("<p id=\"hot\">"); //$NON-NLS-1$
				escape(spec.flame().quiet, out);
				out.append("</p>\n"); //$NON-NLS-1$
			} else {
				out.append("<div class=\"empty\">No stacks were recorded, so there is nothing to draw. " //$NON-NLS-1$
						+ "A profile with no samples usually means the recording was too short, or that every sample was filtered out.</div>\n"); //$NON-NLS-1$
			}
		} else {
			out.append("<p id=\"hot\" aria-live=\"polite\""); //$NON-NLS-1$
			if (spec.flame().quiet != null) {
				out.append(" data-quiet=\""); //$NON-NLS-1$
				escape(spec.flame().quiet, out);
				out.append('"');
			}
			out.append("></p>\n"); //$NON-NLS-1$
			out.append("<div class=\"stage\">\n<aside id=\"rail\" aria-label=\"Where the time sits\"><h2 id=\"railTitle\"></h2><ol id=\"hottest\"></ol></aside>\n"); //$NON-NLS-1$
			out.append("<section class=\"panel\">\n"); //$NON-NLS-1$
			out.append("<div class=\"head\">"); //$NON-NLS-1$
			out.append("<input id=\"find\" type=\"search\" placeholder=\"Highlight frames by name, press / to search\" spellcheck=\"false\">"); //$NON-NLS-1$
			out.append("<button id=\"reset\" type=\"button\" title=\"Escape\">Reset zoom</button>"); //$NON-NLS-1$
			if (spec.product() != null) {
				out.append("<button id=\"only\" type=\"button\" aria-pressed=\"false\" title=\"Only the stacks with a frame of "); //$NON-NLS-1$
				escape(String.join(", ", spec.product().prefixes()), out); //$NON-NLS-1$
				out.append("\">Only "); //$NON-NLS-1$
				escape(spec.product().label(), out);
				out.append("</button>"); //$NON-NLS-1$
			}
			out.append("<span id=\"status\"></span>"); //$NON-NLS-1$
			out.append("</div>\n<div class=\"bar\"><nav id=\"crumbs\" aria-label=\"Zoom path\"></nav><div id=\"legend\"></div></div>\n"); //$NON-NLS-1$
			out.append("<div id=\"flame\" role=\"img\" aria-label=\"Flame graph of the recorded stacks\"></div>\n"); //$NON-NLS-1$
			out.append("</section>\n</div>\n<div id=\"tip\" hidden></div>\n"); //$NON-NLS-1$
		}

		if (spec.tables() != null && !spec.tables().isEmpty()) {
			out.append("<section class=\"tables\">\n"); //$NON-NLS-1$
			for (Table table : spec.tables()) {
				if (table.rows().isEmpty()) {
					continue;
				}
				out.append("<div class=\"card\"><h2>"); //$NON-NLS-1$
				escape(table.caption(), out);
				out.append("</h2><div class=\"scroll\"><table><thead><tr>"); //$NON-NLS-1$
				for (String column : table.columns()) {
					out.append("<th>"); //$NON-NLS-1$
					escape(column, out);
					out.append("</th>"); //$NON-NLS-1$
				}
				out.append("</tr></thead><tbody>"); //$NON-NLS-1$
				for (List<String> row : table.rows()) {
					out.append("<tr>"); //$NON-NLS-1$
					for (int i = 0; i < row.size(); i++) {
						out.append(i == 0 ? "<td>" : "<td class=\"num\">"); //$NON-NLS-1$ //$NON-NLS-2$
						escape(row.get(i), out);
						out.append("</td>"); //$NON-NLS-1$
					}
					out.append("</tr>"); //$NON-NLS-1$
				}
				out.append("</tbody></table></div></div>\n"); //$NON-NLS-1$
			}
			out.append("</section>\n"); //$NON-NLS-1$
		}

		if (spec.note() != null && !spec.note().isBlank()) {
			out.append("<footer>"); //$NON-NLS-1$
			escape(spec.note(), out);
			out.append("</footer>\n"); //$NON-NLS-1$
		}
		out.append("</div>\n"); //$NON-NLS-1$

		if (spec.flame() != null && !spec.flame().isEmpty()) {
			out.append("<script id=\"profile\" type=\"application/json\">"); //$NON-NLS-1$
			// inside a script element, so only the closing tag has to be broken up
			out.append(spec.flame().toJson().replace("</", "<\\/")); //$NON-NLS-1$ //$NON-NLS-2$
			out.append("</script>\n<script>\n").append(SCRIPT).append("\n</script>\n"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		out.append("</body>\n</html>\n"); //$NON-NLS-1$
		return out.toString();
	}

	private static void quote(String value, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '"' -> out.append("\\\""); //$NON-NLS-1$
			case '\\' -> out.append("\\\\"); //$NON-NLS-1$
			case '\n' -> out.append("\\n"); //$NON-NLS-1$
			case '\r' -> out.append("\\r"); //$NON-NLS-1$
			case '\t' -> out.append("\\t"); //$NON-NLS-1$
			default -> {
				if (c < 0x20) {
					out.append("\\u%04x".formatted(Integer.valueOf(c))); //$NON-NLS-1$
				} else {
					out.append(c);
				}
			}
			}
		}
		out.append('"');
	}

	private static void escape(String value, StringBuilder out) {
		if (value == null) {
			return;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '&' -> out.append("&amp;"); //$NON-NLS-1$
			case '<' -> out.append("&lt;"); //$NON-NLS-1$
			case '>' -> out.append("&gt;"); //$NON-NLS-1$
			case '"' -> out.append("&quot;"); //$NON-NLS-1$
			case '\'' -> out.append("&#39;"); //$NON-NLS-1$
			default -> out.append(c);
			}
		}
	}

	private static final String STYLE = """
			:root {
			  color-scheme: dark;
			  --ink: #1b1834; --panel: #23203f; --raise: #2c2850; --line: #373260;
			  --text: #eceaf6; --dim: #9d98bd; --faint: #6e6995;
			  --heat: #ffb347; --heat-soft: #ffb34726;
			  --mono: ui-monospace, "JetBrains Mono", "Cascadia Code", "DejaVu Sans Mono", Menlo, Consolas, monospace;
			}
			* { box-sizing: border-box; }
			html { -webkit-text-size-adjust: 100%; }
			body {
			  margin: 0; background: var(--ink); color: var(--text);
			  font: 14px/1.5 system-ui, -apple-system, "Segoe UI", Ubuntu, Cantarell, sans-serif;
			}
			.wrap { max-width: 1680px; margin: 0 auto; padding: 0 24px 40px; }
			button, input { font: inherit; color: inherit; }
			:focus-visible { outline: 2px solid var(--heat); outline-offset: 2px; }

			header { padding: 26px 0 14px; }
			h1 { margin: 0; font-size: 22px; font-weight: 600; letter-spacing: -0.01em; }
			.sub { margin: 4px 0 0; color: var(--dim); }
			.warn { margin: 8px 0 0; padding: 6px 12px; color: var(--text); background: var(--heat-soft); border-radius: 6px; font-size: 13px; max-width: 110ch; }
			.facts { margin: 10px 0 0; display: flex; flex-wrap: wrap; gap: 4px 22px; color: var(--dim); font-size: 13px; }
			.facts b { color: var(--text); font-weight: 600; font-variant-numeric: tabular-nums; }

			#hot {
			  margin: 6px 0 16px; padding: 12px 16px; border-left: 3px solid var(--heat);
			  background: var(--heat-soft); border-radius: 0 8px 8px 0; font-size: 15px; line-height: 1.6;
			}
			#hot:empty { display: none; }
			#hot b { color: var(--heat); font-weight: 650; }
			#hot code { font-family: var(--mono); font-size: 13px; background: #0003; padding: 1px 5px; border-radius: 4px; }
			#hot button { border: 0; background: none; padding: 0; color: var(--heat); text-decoration: underline; cursor: pointer; }

			.stage { display: grid; grid-template-columns: minmax(250px, 320px) minmax(0, 1fr); gap: 16px; align-items: start; }
			@media (max-width: 900px) { .stage { grid-template-columns: 1fr; } }

			#rail { position: sticky; top: 12px; }
			#rail h2 { margin: 2px 0 10px; font-size: 14px; font-weight: 600; }
			#rail ol { list-style: none; margin: 0; padding: 0; }
			#rail li button {
			  display: block; width: 100%; text-align: left; border: 0; background: none; cursor: pointer;
			  padding: 7px 8px 8px; border-radius: 7px;
			}
			#rail li button:hover, #rail li button.on { background: var(--panel); }
			#rail .fn { display: flex; justify-content: space-between; gap: 10px; font-family: var(--mono); font-size: 12.5px; }
			#rail .fn span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
			#rail .fn em { font-style: normal; color: var(--heat); font-variant-numeric: tabular-nums; flex: none; }
			#rail .pk { color: var(--faint); font-size: 11.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
			#rail .meter { height: 4px; margin-top: 5px; background: var(--raise); border-radius: 2px; overflow: hidden; }
			#rail .meter i { display: block; height: 100%; background: var(--heat); border-radius: 2px; }

			.panel { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; overflow: hidden; min-width: 0; }
			.panel > .head { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; padding: 10px 12px; }
			input[type=search] {
			  flex: 1 1 260px; min-width: 170px; padding: 7px 11px;
			  background: var(--ink); border: 1px solid var(--line); border-radius: 7px;
			}
			input[type=search]::placeholder { color: var(--faint); }
			input[type=search]:focus { outline: none; border-color: var(--heat); }
			button { cursor: pointer; }
			#reset { padding: 7px 12px; background: var(--raise); border: 1px solid var(--line); border-radius: 7px; }
			#reset:hover { border-color: var(--dim); }
			#only { padding: 7px 12px; background: var(--raise); border: 1px solid var(--line); border-radius: 7px; max-width: 28ch; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
			#only:hover { border-color: var(--dim); }
			#only[aria-pressed=true] { background: var(--heat-soft); border-color: var(--heat); }
			#status { color: var(--dim); font-size: 13px; font-variant-numeric: tabular-nums; margin-left: auto; }

			.bar {
			  display: flex; gap: 8px 18px; align-items: center; flex-wrap: wrap; justify-content: space-between;
			  padding: 0 12px 10px;
			}
			#crumbs { display: flex; align-items: center; flex-wrap: wrap; gap: 2px; min-width: 0; }
			.crumb {
			  padding: 2px 7px; border: 0; background: none; color: var(--dim); border-radius: 5px;
			  font-family: var(--mono); font-size: 12px; max-width: 34ch; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
			}
			.crumb:hover { color: var(--text); background: var(--raise); }
			.crumb.on { color: var(--text); }
			.sep { color: var(--faint); }
			#legend { display: flex; flex-wrap: wrap; gap: 4px 12px; }
			.chip { display: inline-flex; align-items: center; gap: 6px; padding: 2px 4px; border: 0; background: none; font-size: 12.5px; color: var(--dim); border-radius: 5px; }
			.chip:hover { color: var(--text); }
			.chip i { width: 10px; height: 10px; border-radius: 2px; display: inline-block; }
			.chip em { font-style: normal; font-variant-numeric: tabular-nums; }
			.chip.off { opacity: .45; }
			.chip.off span { text-decoration: line-through; }

			#flame { padding: 4px 6px 10px; overflow-x: auto; }
			#flame svg { display: block; }
			#flame g { cursor: pointer; }
			#flame g:hover rect.f { stroke: var(--text); stroke-width: 1; }
			#flame rect.heat { fill: var(--heat); pointer-events: none; }
			#flame text { pointer-events: none; font: 11.5px var(--mono); fill: #17142c; }
			#flame text.idle { fill: #b4afd0; }
			#flame text.dim { fill: #6e6995; }
			#tip {
			  position: fixed; z-index: 10; max-width: 64ch; padding: 10px 12px;
			  background: #120f26f5; border: 1px solid var(--line); border-radius: 9px;
			  font-size: 12.5px; pointer-events: none; box-shadow: 0 14px 34px #0009; overflow-wrap: anywhere;
			}
			#tip .tn { font-family: var(--mono); font-weight: 650; }
			#tip .tp { color: var(--faint); font-family: var(--mono); font-size: 11.5px; }
			#tip .tg { display: grid; grid-template-columns: auto 1fr; gap: 2px 14px; margin-top: 8px; }
			#tip .tg span { color: var(--dim); }
			#tip .tg b { font-weight: 600; font-variant-numeric: tabular-nums; }
			#tip .tl { margin-top: 8px; color: var(--dim); font-family: var(--mono); font-size: 11.5px; }
			[hidden] { display: none !important; }

			.tables { display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: 16px; margin: 22px 0 0; }
			.card { min-width: 0; }
			.card h2 { margin: 0 0 8px; font-size: 14px; font-weight: 600; }
			.scroll { overflow-x: auto; border: 1px solid var(--line); border-radius: 10px; }
			table { border-collapse: collapse; width: 100%; font-size: 12.5px; }
			th, td { text-align: left; padding: 6px 12px; border-bottom: 1px solid var(--line); }
			th { color: var(--dim); font-weight: 500; background: var(--panel); }
			tbody tr:last-child td { border-bottom: 0; }
			tbody tr:hover { background: var(--panel); }
			td { font-family: var(--mono); overflow-wrap: anywhere; }
			td.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; color: var(--dim); }
			.empty { margin: 8px 0; padding: 20px; border: 1px dashed var(--line); border-radius: 10px; color: var(--dim); }
			footer { padding: 22px 0 0; color: var(--faint); font-size: 13px; max-width: 90ch; line-height: 1.6; }
			@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
			"""; //$NON-NLS-1$

	private static final String SCRIPT = """
			(function () {
			  var root = JSON.parse(document.getElementById('profile').textContent);
			  var unit = document.documentElement.dataset.unit || '';
			  var host = document.getElementById('flame');
			  var tip = document.getElementById('tip');
			  var status = document.getElementById('status');
			  var crumbs = document.getElementById('crumbs');
			  var legend = document.getElementById('legend');
			  var find = document.getElementById('find');
			  var NS = 'http://www.w3.org/2000/svg';
			  var ROW = 20, focus = root, needle = '', exact = null, hidden = { idle: unit !== 'bytes' && document.documentElement.dataset.idle !== 'shown' };
			  var THREAD = 'thread: ';
			  // narrower than this a frame is unreadable, so its siblings of that size are drawn as one block
			  var THIN = 6;
			  var QUIET = document.getElementById('hot').dataset.quiet;
			  var PRODUCT = document.documentElement.dataset.product ? JSON.parse(document.documentElement.dataset.product) : null;
			  var only = false;

			  // families by package, so a colour says where the code lives instead of being noise
			  var FAMILIES = [
			    { key: 'thread', label: 'Threads', hue: 250, s: 18, l: 58, test: function (n) { return n.indexOf(THREAD) === 0; } },
			    { key: 'product', label: PRODUCT ? PRODUCT.label : '', hue: 0, s: 82, l: 66, prefix: PRODUCT ? PRODUCT.prefixes : [] },
			    { key: 'self', label: 'This server', hue: 262, s: 62, l: 66, prefix: ['com.vogella.'] },
			    { key: 'jdt', label: 'JDT', hue: 28, s: 72, l: 60, prefix: ['org.eclipse.jdt.'] },
			    { key: 'swt', label: 'SWT', hue: 190, s: 58, l: 52, prefix: ['org.eclipse.swt.'] },
			    { key: 'ui', label: 'Workbench UI', hue: 205, s: 62, l: 62, prefix: ['org.eclipse.ui.', 'org.eclipse.e4.', 'org.eclipse.jface.'] },
			    { key: 'platform', label: 'Platform', hue: 150, s: 42, l: 52, prefix: ['org.eclipse.core.', 'org.eclipse.equinox.', 'org.eclipse.osgi.', 'org.osgi.'] },
			    { key: 'eclipse', label: 'Other Eclipse', hue: 110, s: 34, l: 55, prefix: ['org.eclipse.'] },
			    { key: 'jdk', label: 'JDK', hue: 40, s: 22, l: 58, prefix: ['java.', 'javax.', 'jdk.', 'sun.', 'com.sun.'] },
			    { key: 'other', label: 'Libraries', hue: 330, s: 45, l: 64, test: function () { return true; } }
			  ];
			  var BY_CALLER = document.documentElement.dataset.rank === 'caller';

			  function family(name) {
			    for (var i = 0; i < FAMILIES.length; i++) {
			      var f = FAMILIES[i];
			      if (f.test ? f.test(name) : f.prefix.some(function (p) { return name.indexOf(p) === 0; })) return f;
			    }
			    return FAMILIES[FAMILIES.length - 1];
			  }
			  // decorate once: parent links, family, whether under a product frame, idle, self weight
			  // idle comes from the server, per stack: a frame is grey only when every stack through it waits
			  (function prep(n, parent) {
			    n.p = parent;
			    n.f = family(n.n);
			    n.u = n.f.key === 'product' || (parent !== null && parent.u);
			    n.i = n.i || 0;
			    n.idle = n.i > 0 && n.i === n.v;
			    var below = 0, belowIdle = 0, kids = n.c || [];
			    for (var i = 0; i < kids.length; i++) { prep(kids[i], n); below += kids[i].v; belowIdle += kids[i].i; }
			    n.s = Math.max(n.v - below, 0);
			    n.si = n.s > 0 && n.i > belowIdle;
			  })(root, null);

			  // pkg.Outer$Inner.method:12 -> Outer$Inner.method:12, which is the part that tells frames apart
			  function short(name) {
			    if (name.indexOf(THREAD) === 0) return name.slice(THREAD.length);
			    var colon = name.lastIndexOf(':'), tail = '', body = name;
			    if (colon > 0 && /^\\d+$/.test(name.slice(colon + 1))) { tail = name.slice(colon); body = name.slice(0, colon); }
			    var dot = body.lastIndexOf('.');
			    if (dot < 0) return name;
			    var cls = body.slice(0, dot), method = body.slice(dot + 1);
			    return cls.slice(cls.lastIndexOf('.') + 1) + '.' + method + tail;
			  }
			  function pkg(name) {
			    if (name.indexOf(THREAD) === 0) return '';
			    var body = name.replace(/:\\d+$/, ''), dot = body.lastIndexOf('.');
			    var cls = dot < 0 ? '' : body.slice(0, dot);
			    return cls.slice(0, Math.max(cls.lastIndexOf('.'), 0));
			  }
			  function hash(s) {
			    var h = 0;
			    for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
			    return Math.abs(h);
			  }
			  function fill(n) {
			    if (n === root) return 'hsl(248 22% 36%)';
			    if (n.block) return 'hsl(248 16% 46%)';
			    if (n.idle) return 'hsl(248 14% ' + (30 + hash(n.n) % 6) + '%)';
			    var f = n.f, h = hash(n.n);
			    // a little lightness and hue jitter per class, so neighbours of one family stay apart
			    return 'hsl(' + (f.hue + (h % 11) - 5) + ' ' + f.s + '% ' + (f.l + (h % 9) - 4) + '%)';
			  }
			  function fmt(v) {
			    // the only bytes here are the allocation sampler's weights, which are estimates
			    if (unit === 'bytes') {
			      if (v < 1024) return '~' + v + ' B';
			      if (v < 1048576) return '~' + (v / 1024).toFixed(1) + ' KB';
			      if (v < 1073741824) return '~' + (v / 1048576).toFixed(1) + ' MB';
			      return '~' + (v / 1073741824).toFixed(2) + ' GB';
			    }
			    return v.toLocaleString('en') + (unit ? ' ' + unit : '');
			  }
			  function pct(v, of) { return of ? (v * 100 / of).toFixed(v * 1000 < of ? 2 : 1) + '%' : '0%'; }
			  function base() { return root.w || root.v; }
			  function selfWeight(n) { return (hidden.idle && n.si) || (only && !n.u) ? 0 : n.s; }
			  function weight(n) {
			    if (n !== root && hidden[n.f.key]) return 0;
			    var w = selfWeight(n), kids = n.c || [];
			    for (var i = 0; i < kids.length; i++) w += weight(kids[i]);
			    return w;
			  }
			  function measure(n) {
			    n.w = weight(n);
			    (n.c || []).forEach(measure);
			  }
			  function isHit(n) { return !n.block && (exact ? n.n === exact : !!needle && n.n.toLowerCase().indexOf(needle) >= 0); }
			  function contains(n) { return isHit(n) || (n.c || []).some(function (k) { return k.w > 0 && contains(k); }); }

			  // a block's own frames can all be thin too, so inside one the floor drops and the widest half gets out; under half a pixel they stay undrawn
			  function split(n, span) {
			    var big = [], small = [], kids = n.c || [], floor = n.block ? 1 : THIN;
			    for (var i = 0; i < kids.length; i++) {
			      if (kids[i].w > 0) (span * kids[i].w / n.w < floor ? small : big).push(kids[i]);
			    }
			    if (n.block && !big.length) big = small.splice(0, Math.ceil(small.length / 2));
			    return { big: big, small: small };
			  }
			  function blockOf(parent, frames) {
			    var block = parent.more || (parent.more = { block: true, p: parent, s: 0, i: 0, f: { key: 'block', label: 'Smaller frames, drawn as one' } });
			    block.c = frames;
			    block.v = block.w = frames.reduce(function (sum, k) { return sum + k.w; }, 0);
			    block.n = frames.length + ' smaller';
			    return block;
			  }
			  function depth(n, span) {
			    if (span < 0.5) return 0;
			    var d = 1, parts = split(n, span);
			    for (var i = 0; i < parts.big.length; i++) d = Math.max(d, 1 + depth(parts.big[i], span * parts.big[i].w / n.w));
			    return parts.small.length ? Math.max(d, 2) : d;
			  }

			  function draw() {
			    measure(root);
			    if (focus.block) blockOf(focus.p, focus.c);
			    if (focus !== root && !focus.w) focus = root;
			    var width = Math.max(host.clientWidth - 16 || 900, 320);
			    var levels = Math.max(depth(focus, width), 1);
			    var height = levels * ROW + 2;
			    var svg = document.createElementNS(NS, 'svg');
			    svg.setAttribute('width', width);
			    svg.setAttribute('height', height);
			    svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
			    var matches = 0, matched = 0;
			    // over the whole tree rather than what is drawn, or a search misses what sits in a block
			    (function scan(n, inside) {
			      if (!(n.w > 0)) return;
			      var hit = isHit(n);
			      if (hit) { matches++; if (!inside) matched += n.w; }
			      (n.c || []).forEach(function (k) { scan(k, inside || hit); });
			    })(focus, false);

			    (function place(node, x, level, span, alone) {
			      if (span < 0.5 && !node.block) return;
			      var hit = node.block ? (needle || exact) && contains(node) : isHit(node);
			      var g = document.createElementNS(NS, 'g');
			      var r = document.createElementNS(NS, 'rect');
			      r.setAttribute('x', x.toFixed(2));
			      r.setAttribute('y', level * ROW);
			      r.setAttribute('width', Math.max(span - 1, 0.5).toFixed(2));
			      r.setAttribute('height', ROW - 2);
			      r.setAttribute('rx', 2.5);
			      var marking = needle || exact;
			      r.setAttribute('class', 'f');
			      r.setAttribute('fill', marking ? (hit ? '#ffb347' : 'hsl(248 18% 24%)') : fill(node));
			      g.appendChild(r);
			      // the heat stripe: how much of this frame's width is spent in the frame itself
			      var own = node.si || node === root || node.f.key === 'thread' ? 0 : span * selfWeight(node) / node.w;
			      if (!marking && own >= 1) {
			        var h = document.createElementNS(NS, 'rect');
			        h.setAttribute('class', 'heat');
			        h.setAttribute('x', x.toFixed(2));
			        h.setAttribute('y', level * ROW + ROW - 5);
			        h.setAttribute('width', Math.max(Math.min(own, span - 1), 1).toFixed(2));
			        h.setAttribute('height', 3);
			        g.appendChild(h);
			      }
			      if (span > 34) {
			        var t = document.createElementNS(NS, 'text');
			        t.setAttribute('x', (x + 5).toFixed(2));
			        t.setAttribute('y', level * ROW + ROW - 8);
			        t.setAttribute('class', marking && !hit ? 'dim' : node.idle && !hit ? 'idle' : '');
			        var label = node === root ? (unit === 'bytes' ? 'all allocations' : 'all threads') : short(node.n);
			        var max = Math.floor((span - 10) / 6.6);
			        t.textContent = label.length > max ? label.slice(0, Math.max(max - 1, 1)) + '\\u2026' : label;
			        g.appendChild(t);
			      }
			      g.addEventListener('mousemove', function (e) { showTip(node, e); });
			      g.addEventListener('mouseleave', function () { tip.hidden = true; });
			      g.addEventListener('click', function () { zoom(node); });
			      svg.appendChild(g);
			      if (alone) return;
			      var parts = split(node, span), at = x;
			      for (var i = 0; i < parts.big.length; i++) {
			        var w = span * parts.big[i].w / node.w;
			        place(parts.big[i], at, level + 1, w);
			        at += w;
			      }
			      if (parts.small.length === 1) place(parts.small[0], at, level + 1, span * parts.small[0].w / node.w, true);
			      else if (parts.small.length) {
			        var block = blockOf(node, parts.small);
			        place(block, at, level + 1, span * block.w / node.w, true);
			      }
			    })(focus, 0, 0, width);

			    host.replaceChildren(svg);
			    renderCrumbs();
			    status.textContent = needle || exact
			      ? matches + ' frame' + (matches === 1 ? '' : 's') + ' matched, ' + fmt(matched) + ' (' + pct(matched, base()) + ')'
			      : fmt(focus.w) + (focus === root ? '' : ', ' + pct(focus.w, base()) + ' of what is shown');
			  }

			  function showTip(node, e) {
			    tip.hidden = false;
			    tip.replaceChildren();
			    var head = document.createElement('div');
			    head.className = 'tn';
			    head.textContent = node === root ? 'all' : short(node.n);
			    tip.append(head);
			    var where = node.block ? '' : pkg(node.n);
			    if (where) {
			      var p = document.createElement('div');
			      p.className = 'tp';
			      p.textContent = where;
			      tip.append(p);
			    }
			    var rows = [['Total', fmt(node.v) + ', ' + pct(node.v, base())]];
			    if (!node.block) rows.push(['Self', fmt(node.s) + ', ' + pct(node.s, base())]);
			    if (node.p) rows.push(['Of parent', pct(node.v, node.p.v)]);
			    rows.push(['Kind', node.idle ? 'idle or waiting' : node.f.label]);
			    if (node.i > 0 && !node.idle) rows.push(['Of it waiting', pct(node.i, node.v)]);
			    var grid = document.createElement('div');
			    grid.className = 'tg';
			    rows.forEach(function (row) {
			      var k = document.createElement('span'), v = document.createElement('b');
			      k.textContent = row[0];
			      v.textContent = row[1];
			      grid.append(k, v);
			    });
			    tip.append(grid);
			    if (node.block) {
			      var list = document.createElement('div');
			      list.className = 'tl';
			      list.textContent = node.c.slice(0, 5).map(function (k) { return short(k.n); }).join(', ')
			        + (node.c.length > 5 ? ' and ' + (node.c.length - 5) + ' more. ' : '. ') + 'Click to open.';
			      tip.append(list);
			    }
			    var pad = 14;
			    var left = Math.min(e.clientX + pad, window.innerWidth - tip.offsetWidth - 8);
			    var top = e.clientY + pad + tip.offsetHeight > window.innerHeight ? e.clientY - tip.offsetHeight - pad : e.clientY + pad;
			    tip.style.left = Math.max(8, left) + 'px';
			    tip.style.top = Math.max(8, top) + 'px';
			  }

			  function zoom(node) { focus = node; tip.hidden = true; draw(); }
			  function renderCrumbs() {
			    var chain = [];
			    for (var n = focus; n; n = n.p) chain.unshift(n);
			    crumbs.replaceChildren();
			    chain.forEach(function (n, i) {
			      if (i > 0) {
			        var sep = document.createElement('span');
			        sep.className = 'sep';
			        sep.textContent = '\\u203a';
			        crumbs.append(sep);
			      }
			      var b = document.createElement('button');
			      b.type = 'button';
			      b.className = 'crumb' + (n === focus ? ' on' : '');
			      b.textContent = n === root ? 'all' : short(n.n);
			      b.title = n.n;
			      b.addEventListener('click', function () { zoom(n); });
			      crumbs.append(b);
			    });
			  }

			  // share of the self weight per family, which is where the cost actually sits
			  (function renderLegend() {
			    var sums = {}, idle = 0;
			    (function walk(n) {
			      if (n !== root) {
			        if (n.si) idle += n.s; else sums[n.f.key] = (sums[n.f.key] || 0) + n.s;
			      }
			      (n.c || []).forEach(walk);
			    })(root);
			    // a family under a thousandth of the weight is a legend entry nobody can see in the graph
			    var entries = FAMILIES.filter(function (f) { return f.key !== 'thread' && sums[f.key] * 1000 >= root.v; })
			      .sort(function (a, b) { return sums[b.key] - sums[a.key]; })
			      .map(function (f) { return { key: f.key, label: f.label, colour: 'hsl(' + f.hue + ' ' + f.s + '% ' + f.l + '%)', v: sums[f.key] }; });
			    if (idle * 1000 >= root.v) entries.push({ key: 'idle', label: 'Idle or waiting', colour: 'hsl(220 8% 27%)', v: idle });
			    entries.forEach(function (e) {
			      var b = document.createElement('button');
			      b.type = 'button';
			      b.className = 'chip';
			      b.title = 'Click to hide or show';
			      var dot = document.createElement('i');
			      dot.style.background = e.colour;
			      var label = document.createElement('span');
			      label.textContent = e.label;
			      var share = document.createElement('em');
			      share.textContent = pct(e.v, root.v);
			      b.append(dot, label, share);
			      b.classList.toggle('off', !!hidden[e.key]);
			      b.setAttribute('aria-pressed', String(!hidden[e.key]));
			      b.addEventListener('click', function () {
			        hidden[e.key] = !hidden[e.key];
			        b.classList.toggle('off', !!hidden[e.key]);
			        b.setAttribute('aria-pressed', String(!hidden[e.key]));
			        draw();
			        summarise();
			      });
			      legend.append(b);
			    });
			  })();

			  var hot = document.getElementById('hot');
			  var hottest = document.getElementById('hottest');
			  document.getElementById('railTitle').textContent = BY_CALLER
			    ? (unit === 'bytes' ? 'Nearest non-JDK allocator' : 'Nearest non-JDK frame')
			    : (unit === 'bytes' ? 'Where the bytes are allocated' : 'Where the time sits');
			  if (QUIET) document.getElementById('railTitle').textContent = 'What little ran';
			  var onlyButton = document.getElementById('only');
			  if (onlyButton) onlyButton.addEventListener('click', function () {
			    only = !only;
			    onlyButton.setAttribute('aria-pressed', String(only));
			    draw();
			    summarise();
			  });

			  // the frame a weight is credited to: JDK leaves such as Arrays.copyOf name no culprit
			  function credited(node) {
			    if (!BY_CALLER) return node;
			    for (var n = node; n !== root && n.f.key !== 'thread'; n = n.p) if (n.f.key !== 'jdk') return n;
			    return node;
			  }
			  // the widest frame that is not trunk: dispatch plumbing names nothing, and the launcher path runs to about half of a startup
			  var PLUMBING = /^(java\\.lang\\.invoke\\.|java\\.lang\\.reflect\\.|jdk\\.internal\\.reflect\\.)|\\.0x|SafeRunner\\.run/;
			  // a frame handing nine tenths of its weight to one child decided nothing, whatever it is called
			  function passesOn(n) { return (n.c || []).some(function (k) { return k.w * 10 >= n.w * 9; }); }
			  function widest(total) {
			    var below = {}, onStack = {};
			    (function walk(n) {
			      var fresh = n !== root && n.f.key !== 'thread' && !n.idle && !onStack[n.n] && !PLUMBING.test(n.n) && !passesOn(n);
			      if (fresh) { below[n.n] = (below[n.n] || 0) + n.w; onStack[n.n] = true; }
			      (n.c || []).forEach(function (kid) { if (kid.w > 0) walk(kid); });
			      if (fresh) delete onStack[n.n];
			    })(root);
			    var best = null;
			    Object.keys(below).forEach(function (name) {
			      if (below[name] * 5 < total * 2 && (best === null || below[name] > below[best])) best = name;
			    });
			    return best === null ? null : { name: best, w: below[best] };
			  }
			  function select(name) {
			    exact = name;
			    hottest.querySelectorAll('button').forEach(function (x) { x.classList.toggle('on', x.title === name); });
			    draw();
			  }

			  // the answer first: follow the heaviest branch until a frame keeps more than it passes on
			  function summarise() {
			    measure(root);
			    var total = base(), n = root, path = [];
			    while (n.c) {
			      var best = null;
			      for (var i = 0; i < n.c.length; i++) if (n.c[i].w > 0 && (!best || n.c[i].w > best.w)) best = n.c[i];
			      if (!best || best.w < n.s || best.w * 4 < total) break;
			      n = best;
			      path.push(n);
			    }
			    hot.replaceChildren();
			    var wide = !QUIET && BY_CALLER && total ? widest(total) : null;
			    if (QUIET) {
			      hot.textContent = QUIET;
			    } else if (wide) {
			      var wideLead = document.createElement('b');
			      wideLead.textContent = pct(wide.w, total);
			      var wideName = document.createElement('code');
			      wideName.textContent = short(wide.name);
			      var mark = document.createElement('button');
			      mark.type = 'button';
			      mark.textContent = 'Highlight it';
			      mark.addEventListener('click', function () { select(wide.name); });
			      hot.append(wideLead, (unit === 'bytes' ? ' of the estimated bytes are allocated below ' : ' of the ' + (hidden.idle ? 'busy ' : '') + 'samples run below '),
			        wideName, ', the widest branching frame under 40% of the total. ', mark);
			    } else if (path.length && total) {
			      var thread = path[0].f.key === 'thread' ? short(path[0].n) : null;
			      var end = path[path.length - 1];
			      var lead = document.createElement('b');
			      lead.textContent = pct(end.w, total);
			      var where = document.createElement('code');
			      where.textContent = short(end.n);
			      var go = document.createElement('button');
			      go.type = 'button';
			      go.textContent = 'Zoom to it';
			      go.addEventListener('click', function () { zoom(end); });
			      hot.append(lead, (unit === 'bytes' ? ' of the bytes are allocated below ' : ' of the ' + (hidden.idle ? 'busy ' : '') + 'samples run below '), where);
			      if (thread && end !== path[0]) {
			        var t = document.createElement('code');
			        t.textContent = thread;
			        hot.append(' on ', t);
			      }
			      hot.append('. ', go);
			    }

			    var self = {};
			    (function walk(node) {
			      if (!(node.w > 0) || (node !== root && hidden[node.f.key])) return;
			      if (node !== root && !node.si && node.f.key !== 'thread' && selfWeight(node) > 0) {
			        var to = credited(node).n;
			        self[to] = (self[to] || 0) + node.s;
			      }
			      (node.c || []).forEach(walk);
			    })(root);
			    var ranked = Object.keys(self).sort(function (a, b) { return self[b] - self[a]; }).slice(0, 12);
			    var top = ranked.length ? self[ranked[0]] : 1;
			    hottest.replaceChildren();
			    ranked.forEach(function (name) {
			      var li = document.createElement('li'), b = document.createElement('button');
			      b.type = 'button';
			      b.title = name;
			      var fn = document.createElement('div');
			      fn.className = 'fn';
			      var label = document.createElement('span');
			      label.textContent = short(name);
			      var share = document.createElement('em');
			      share.textContent = pct(self[name], total);
			      fn.append(label, share);
			      var pk = document.createElement('div');
			      pk.className = 'pk';
			      pk.textContent = pkg(name);
			      var meter = document.createElement('div');
			      meter.className = 'meter';
			      var fillBar = document.createElement('i');
			      fillBar.style.width = (self[name] * 100 / top).toFixed(1) + '%';
			      meter.append(fillBar);
			      b.append(fn, pk, meter);
			      b.addEventListener('click', function () {
			        var on = exact !== name;
			        exact = on ? name : null;
			        hottest.querySelectorAll('button').forEach(function (x) { x.classList.remove('on'); });
			        b.classList.toggle('on', on);
			        draw();
			      });
			      li.append(b);
			      hottest.append(li);
			    });
			  }

			  document.getElementById('reset').addEventListener('click', function () { zoom(root); });
			  document.addEventListener('keydown', function (e) {
			    if (e.key === 'Escape') {
			      if (exact) { exact = null; hottest.querySelectorAll('button').forEach(function (x) { x.classList.remove('on'); }); draw(); }
			      else if (find.value) { find.value = ''; needle = ''; draw(); }
			      else zoom(root);
			    }
			    if (e.key === '/' && document.activeElement !== find) { e.preventDefault(); find.focus(); }
			  });
			  var pending;
			  find.addEventListener('input', function () {
			    clearTimeout(pending);
			    pending = setTimeout(function () { needle = find.value.trim().toLowerCase(); exact = null; draw(); }, 90);
			  });
			  var resizing;
			  window.addEventListener('resize', function () { clearTimeout(resizing); resizing = setTimeout(draw, 120); });
			  draw();
			  summarise();
			})();
			"""; //$NON-NLS-1$
}
