package com.vogella.eclipse.mcp.basic.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the entries of the platform log, the file behind the Error Log view.
 */
public final class GetLogEntriesTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 50;

	private static final Set<String> SEVERITIES = Set.of("error", "warning", "info", "all"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	@Override
	public String getName() {
		return "eclipse_get_log_entries"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Returns entries from the Eclipse platform log, the file behind the Error Log view, including the stack traces and the nested child statuses of a multi status. This is where UI freezes reported by org.eclipse.ui.monitoring and exceptions thrown by builders end up; those never become problem markers, so eclipse_get_problems does not see them. Note that UI freezes are logged as warnings, which is why the default severity here is 'all' rather than 'error'."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "severity":           {"type":"string","enum":["error","warning","info","all"],"default":"all","description":"Only return entries of exactly this severity. UI freezes are warnings."},
				    "plugin":             {"type":"string","description":"Restrict to this bundle symbolic name, for example org.eclipse.ui.monitoring."},
				    "messageFilter":      {"type":"string","description":"Only return entries whose message contains this text, case insensitive."},
				    "marker":             {"type":"string","description":"A marker from eclipse_mark_log. Reports only what was logged after that point, with no dependence on the caller's clock."},
				    "since":              {"type":"string","description":"Only return entries logged at or after this local timestamp, for example 2026-08-20T11:00 or 2026-08-20."},
				    "maxResults":         {"type":"integer","default":50,"minimum":1,"maximum":500},
				    "includeStackTraces": {"type":"boolean","default":true,"description":"Include the full stack trace of every entry and child. Set to false for a compact overview; the stack traces of a UI freeze are long."},
				    "newestFirst":        {"type":"boolean","default":true,"description":"Return the newest entries first, so that truncation keeps the most recent ones."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String severity = args.getString("severity", "all"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!SEVERITIES.contains(severity)) {
			return McpToolResult.error("Unknown severity '%s', expected one of error, warning, info, all." //$NON-NLS-1$
					.formatted(severity));
		}
		String plugin = args.getString("plugin"); //$NON-NLS-1$
		String messageFilter = args.getString("messageFilter"); //$NON-NLS-1$
		String sinceText = args.getString("since"); //$NON-NLS-1$
		LocalDateTime since = null;
		if (sinceText != null) {
			since = parseTimestamp(sinceText);
			if (since == null) {
				return McpToolResult.error(
						"Could not read '%s' as a timestamp, expected something like 2026-08-20T11:00 or 2026-08-20." //$NON-NLS-1$
								.formatted(sinceText));
			}
		}
		int maxResults = args.getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 500); //$NON-NLS-1$
		String marker = args.getString("marker"); //$NON-NLS-1$
		long fromByte = 0;
		boolean markerStale = false;
		if (marker != null) {
			Long position = positionOf(marker);
			if (position == null) {
				return McpToolResult.error(
						"Could not read '%s' as a marker. Get one from eclipse_mark_log.".formatted(marker)); //$NON-NLS-1$
			}
			fromByte = position.longValue();
		}
		boolean includeStackTraces = args.getBoolean("includeStackTraces", true); //$NON-NLS-1$
		boolean newestFirst = args.getBoolean("newestFirst", true); //$NON-NLS-1$

		Path logFile = logFile();
		if (logFile == null) {
			return McpToolResult.error("The platform does not report a log file location."); //$NON-NLS-1$
		}
		if (!Files.isReadable(logFile)) {
			// an IDE that has not logged anything yet has no log file at all
			return McpToolResult.of(new JsonObject().put("logFile", logFile.toString()) //$NON-NLS-1$
					.put("total", 0) //$NON-NLS-1$
					.put("truncated", false) //$NON-NLS-1$
					.put("entries", new JsonArray()) //$NON-NLS-1$
					.toString());
		}

		List<PlatformLogFile.Entry> entries;
		try {
			if (fromByte > 0 && !PlatformLogFile.stillHas(logFile, fromByte)) {
				// the log was rotated or cleared since the mark, so the position means
				// nothing any more. Reading from it would silently return the wrong
				// window, which is the failure a marker exists to prevent
				markerStale = true;
				fromByte = 0;
			}
			entries = PlatformLogFile.read(logFile, fromByte);
		} catch (IOException e) {
			throw new McpToolException("Could not read the platform log at " + logFile, e); //$NON-NLS-1$
		}

		List<PlatformLogFile.Entry> matching = new ArrayList<>();
		for (PlatformLogFile.Entry entry : entries) {
			if (monitor.isCanceled()) {
				return McpToolResult.error("The request was cancelled."); //$NON-NLS-1$
			}
			if (matches(entry, severity, plugin, messageFilter, since)) {
				matching.add(entry);
			}
		}
		if (newestFirst) {
			Collections.reverse(matching);
		}

		JsonArray reported = new JsonArray();
		for (PlatformLogFile.Entry entry : matching.subList(0, Math.min(maxResults, matching.size()))) {
			reported.add(toJson(entry, includeStackTraces));
		}
		JsonObject result = new JsonObject().put("logFile", logFile.toString()) //$NON-NLS-1$
				.put("total", matching.size()) //$NON-NLS-1$
				.put("truncated", matching.size() > reported.size()) //$NON-NLS-1$
				.put("entries", reported); //$NON-NLS-1$
		if (marker != null) {
			result.put("marker", marker).put("markerStale", Boolean.valueOf(markerStale)); //$NON-NLS-1$ //$NON-NLS-2$
			if (markerStale) {
				result.put("markerNote", //$NON-NLS-1$
						"The log is shorter than it was when the marker was taken, so it has been rotated or cleared since and the marker no longer locates anything. Everything readable is reported instead of a window that would be wrong."); //$NON-NLS-1$
			}
		}
		return McpToolResult.of(result.toString());
	}

	/** The byte position a marker records, or {@code null} when it is not one of ours. */
	private static Long positionOf(String marker) {
		String[] parts = marker.split("-"); //$NON-NLS-1$
		if (parts.length != 3 || !LogStateTools.MARKER_PREFIX.equals(parts[0])) {
			return null;
		}
		try {
			return Long.valueOf(Long.parseLong(parts[1]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Path logFile() {
		var location = Platform.getLogFileLocation();
		return location == null ? null : location.toFile().toPath();
	}

	private static boolean matches(PlatformLogFile.Entry entry, String severity, String plugin, String messageFilter,
			LocalDateTime since) {
		Integer wanted = severityOf(severity);
		if (wanted != null && wanted.intValue() != entry.severity()) {
			return false;
		}
		if (plugin != null && !plugin.equals(entry.plugin())) {
			return false;
		}
		if (messageFilter != null && !entry.message().toLowerCase(Locale.ROOT)
				.contains(messageFilter.toLowerCase(Locale.ROOT))) {
			return false;
		}
		// an entry without a parsable timestamp cannot be shown to be recent enough
		return since == null || (entry.time() != null && !entry.time().isBefore(since));
	}

	private static JsonObject toJson(PlatformLogFile.Entry entry, boolean includeStackTraces) {
		JsonObject json = new JsonObject().put("plugin", entry.plugin()) //$NON-NLS-1$
				.put("severity", severityName(entry.severity())) //$NON-NLS-1$
				.put("code", entry.code()) //$NON-NLS-1$
				.put("timestamp", entry.time() == null ? null : entry.time().toString()) //$NON-NLS-1$
				.put("message", entry.message()) //$NON-NLS-1$
				.put("exception", entry.exception()); //$NON-NLS-1$
		if (includeStackTraces) {
			json.put("stackTrace", entry.stackTrace()); //$NON-NLS-1$
		}
		if (!entry.children().isEmpty()) {
			JsonArray children = new JsonArray();
			for (PlatformLogFile.Entry child : entry.children()) {
				children.add(toJson(child, includeStackTraces));
			}
			json.put("children", children); //$NON-NLS-1$
		}
		return json;
	}

	/** Returns the status severity to match, {@code null} for {@code all} and for unknown names. */
	private static Integer severityOf(String severity) {
		return switch (severity) {
		case "error" -> Integer.valueOf(IStatus.ERROR); //$NON-NLS-1$
		case "warning" -> Integer.valueOf(IStatus.WARNING); //$NON-NLS-1$
		case "info" -> Integer.valueOf(IStatus.INFO); //$NON-NLS-1$
		default -> null;
		};
	}

	private static String severityName(int severity) {
		return switch (severity) {
		case IStatus.ERROR -> "error"; //$NON-NLS-1$
		case IStatus.WARNING -> "warning"; //$NON-NLS-1$
		case IStatus.INFO -> "info"; //$NON-NLS-1$
		case IStatus.CANCEL -> "cancel"; //$NON-NLS-1$
		default -> "ok"; //$NON-NLS-1$
		};
	}

	private static LocalDateTime parseTimestamp(String text) {
		try {
			return LocalDateTime.parse(text);
		} catch (DateTimeParseException e) {
			// a bare date is the more convenient thing to type
		}
		try {
			return LocalDate.parse(text).atStartOfDay();
		} catch (DateTimeParseException e) {
			return null;
		}
	}
}
