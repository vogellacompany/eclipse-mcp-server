package com.vogella.eclipse.mcp.core.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes JSON for the small value model of this package.
 */
public final class Json {

	private Json() {
	}

	/**
	 * Serializes {@link JsonObject}, {@link JsonArray}, {@link JsonRaw}, {@link String},
	 * {@link Number}, {@link Boolean}, {@code null} and a {@link Collection} or {@link Map} of these as indented JSON.
	 */
	public static String write(Object value) {
		StringBuilder out = new StringBuilder();
		writeValue(value, out, 0);
		return out.toString();
	}

	/**
	 * Parses a JSON document into {@link Map}, {@link List}, {@link String},
	 * {@link Long}, {@link Double}, {@link Boolean} and {@code null}.
	 *
	 * @throws IllegalArgumentException when the text is not JSON
	 */
	public static Object parse(String text) {
		Parser parser = new Parser(text);
		Object value = parser.value();
		parser.skipWhitespace();
		if (!parser.atEnd()) {
			throw parser.error("Unexpected trailing content"); //$NON-NLS-1$
		}
		return value;
	}

	private static final class Parser {

		private final String text;
		private int at;

		Parser(String text) {
			this.text = text;
		}

		Object value() {
			skipWhitespace();
			if (atEnd()) {
				throw error("Unexpected end of input"); //$NON-NLS-1$
			}
			char c = text.charAt(at);
			return switch (c) {
			case '{' -> object();
			case '[' -> array();
			case '"' -> string();
			case 't' -> literal("true", Boolean.TRUE); //$NON-NLS-1$
			case 'f' -> literal("false", Boolean.FALSE); //$NON-NLS-1$
			case 'n' -> literal("null", null); //$NON-NLS-1$
			default -> number();
			};
		}

		private Map<String, Object> object() {
			Map<String, Object> members = new LinkedHashMap<>();
			at++;
			skipWhitespace();
			if (peek() == '}') {
				at++;
				return members;
			}
			while (true) {
				skipWhitespace();
				if (peek() != '"') {
					throw error("Expected a member name"); //$NON-NLS-1$
				}
				String name = string();
				skipWhitespace();
				expect(':');
				members.put(name, value());
				skipWhitespace();
				if (peek() == ',') {
					at++;
					continue;
				}
				expect('}');
				return members;
			}
		}

		private List<Object> array() {
			List<Object> elements = new ArrayList<>();
			at++;
			skipWhitespace();
			if (peek() == ']') {
				at++;
				return elements;
			}
			while (true) {
				elements.add(value());
				skipWhitespace();
				if (peek() == ',') {
					at++;
					continue;
				}
				expect(']');
				return elements;
			}
		}

		private String string() {
			at++;
			StringBuilder out = new StringBuilder();
			while (true) {
				if (atEnd()) {
					throw error("Unterminated string"); //$NON-NLS-1$
				}
				char c = text.charAt(at++);
				if (c == '"') {
					return out.toString();
				}
				if (c != '\\') {
					out.append(c);
					continue;
				}
				if (atEnd()) {
					throw error("Unterminated escape"); //$NON-NLS-1$
				}
				char escaped = text.charAt(at++);
				switch (escaped) {
				case '"', '\\', '/' -> out.append(escaped);
				case 'b' -> out.append('\b');
				case 'f' -> out.append('\f');
				case 'n' -> out.append('\n');
				case 'r' -> out.append('\r');
				case 't' -> out.append('\t');
				case 'u' -> {
					if (at + 4 > text.length()) {
						throw error("Truncated unicode escape"); //$NON-NLS-1$
					}
					try {
						out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
					} catch (NumberFormatException e) {
						throw error("Invalid unicode escape"); //$NON-NLS-1$
					}
					at += 4;
				}
				default -> throw error("Invalid escape"); //$NON-NLS-1$
				}
			}
		}

		private Number number() {
			int start = at;
			while (!atEnd() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) { //$NON-NLS-1$
				at++;
			}
			String token = text.substring(start, at);
			if (token.isEmpty()) {
				throw error("Unexpected character"); //$NON-NLS-1$
			}
			try {
				if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
					return Long.valueOf(token);
				}
				return Double.valueOf(token);
			} catch (NumberFormatException e) {
				throw error("Invalid number"); //$NON-NLS-1$
			}
		}

		private Object literal(String word, Object value) {
			if (!text.startsWith(word, at)) {
				throw error("Unexpected character"); //$NON-NLS-1$
			}
			at += word.length();
			return value;
		}

		private void expect(char c) {
			if (peek() != c) {
				throw error("Expected '" + c + "'"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			at++;
		}

		private char peek() {
			return atEnd() ? 0 : text.charAt(at);
		}

		void skipWhitespace() {
			while (!atEnd() && Character.isWhitespace(text.charAt(at))) {
				at++;
			}
		}

		boolean atEnd() {
			return at >= text.length();
		}

		IllegalArgumentException error(String message) {
			return new IllegalArgumentException(message + " at offset " + at); //$NON-NLS-1$
		}
	}

	private static void writeValue(Object value, StringBuilder out, int indent) {
		switch (value) {
		case null -> out.append("null"); //$NON-NLS-1$
		case JsonRaw raw -> out.append(raw.json());
		case JsonObject object -> writeObject(object.values(), out, indent);
		case JsonArray array -> writeArray(array.values(), out, indent);
		case String string -> writeString(string, out);
		case Boolean bool -> out.append(bool.booleanValue());
		case Number number -> writeNumber(number, out);
		case Collection<?> collection -> writeArray(new ArrayList<>(collection), out, indent);
		case Map<?, ?> map -> {
			Map<String, Object> entries = new LinkedHashMap<>();
			map.forEach((key, entry) -> entries.put(String.valueOf(key), entry));
			writeObject(entries, out, indent);
		}
		default -> writeString(String.valueOf(value), out);
		}
	}

	private static void writeObject(Map<String, Object> values, StringBuilder out, int indent) {
		if (values.isEmpty()) {
			out.append("{}"); //$NON-NLS-1$
			return;
		}
		out.append("{\n"); //$NON-NLS-1$
		int remaining = values.size();
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			indent(out, indent + 1);
			writeString(entry.getKey(), out);
			out.append(": "); //$NON-NLS-1$
			writeValue(entry.getValue(), out, indent + 1);
			if (--remaining > 0) {
				out.append(',');
			}
			out.append('\n');
		}
		indent(out, indent);
		out.append('}');
	}

	private static void writeArray(List<Object> values, StringBuilder out, int indent) {
		if (values.isEmpty()) {
			out.append("[]"); //$NON-NLS-1$
			return;
		}
		out.append("[\n"); //$NON-NLS-1$
		int remaining = values.size();
		for (Object value : values) {
			indent(out, indent + 1);
			writeValue(value, out, indent + 1);
			if (--remaining > 0) {
				out.append(',');
			}
			out.append('\n');
		}
		indent(out, indent);
		out.append(']');
	}

	/**
	 * JSON has no spelling for NaN or the infinities, so a ratio that divided by
	 * zero would otherwise produce a document the client cannot parse at all. Null
	 * loses that one value and keeps the rest of the answer readable.
	 */
	private static void writeNumber(Number number, StringBuilder out) {
		// only the floating point types, because a BigInteger too large for a double
		// is still an exact number JSON can spell
		boolean broken = (number instanceof Double || number instanceof Float)
				&& (Double.isNaN(number.doubleValue()) || Double.isInfinite(number.doubleValue()));
		out.append(broken ? "null" : number.toString()); //$NON-NLS-1$
	}

	private static void indent(StringBuilder out, int level) {
		out.append("  ".repeat(level)); //$NON-NLS-1$
	}

	private static void writeString(String value, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '"' -> out.append("\\\""); //$NON-NLS-1$
			case '\\' -> out.append("\\\\"); //$NON-NLS-1$
			case '\b' -> out.append("\\b"); //$NON-NLS-1$
			case '\f' -> out.append("\\f"); //$NON-NLS-1$
			case '\n' -> out.append("\\n"); //$NON-NLS-1$
			case '\r' -> out.append("\\r"); //$NON-NLS-1$
			case '\t' -> out.append("\\t"); //$NON-NLS-1$
			default -> {
				if (c < 0x20) {
					out.append("\\u%04x".formatted((int) c)); //$NON-NLS-1$
				} else {
					out.append(c);
				}
			}
			}
		}
		out.append('"');
	}
}
