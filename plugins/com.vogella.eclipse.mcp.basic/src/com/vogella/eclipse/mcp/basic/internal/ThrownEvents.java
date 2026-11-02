package com.vogella.eclipse.mcp.basic.internal;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.vogella.eclipse.mcp.core.ServerFrames;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Groups {@code jdk.JavaErrorThrow} and {@code jdk.JavaExceptionThrow} by thrown class, message
 * and nearest non-JDK frame.
 * <p>
 * The JVM reports one thrown Error once in the error stream and twice in the exception stream, which
 * alone is throttled, so a group with error events counts those and never adds the streams.
 */
public final class ThrownEvents {

	private static final int MAX_MESSAGE = 200;

	private final Map<List<String>, long[]> groups = new LinkedHashMap<>();

	private int events;

	public void add(boolean error, String thrownClass, String message, String[] frames) {
		events++;
		groups.computeIfAbsent(Arrays.asList(thrownClass, message, nearestFrame(thrownClass, frames)),
				unused -> new long[2])[error ? 0 : 1]++;
	}

	/** The innermost frame outside the JDK that is not the thrown class's own constructor. */
	public static String nearestFrame(String thrownClass, String[] frames) {
		for (String frame : frames) {
			int dot = frame.lastIndexOf('.');
			String type = dot < 0 ? frame : frame.substring(0, dot);
			if (!ServerFrames.isJdk(type) && !(frame.endsWith(".<init>") && type.equals(thrownClass))) { //$NON-NLS-1$
				return frame;
			}
		}
		return null;
	}

	private static long count(long[] streams) {
		return streams[0] > 0 ? streams[0] : streams[1];
	}

	public JsonObject toJson(int limit, String throttle) {
		long thrown = 0;
		for (long[] streams : groups.values()) {
			thrown += count(streams);
		}
		JsonArray array = new JsonArray();
		groups.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<List<String>, long[]> e) -> -count(e.getValue()))
				.thenComparing(e -> String.valueOf(e.getKey()))).limit(limit).forEach(entry -> {
					String message = entry.getKey().get(1);
					array.add(new JsonObject().put("class", entry.getKey().get(0)) //$NON-NLS-1$
							.put("message", message != null && message.length() > MAX_MESSAGE //$NON-NLS-1$
									? message.substring(0, MAX_MESSAGE) + "..." //$NON-NLS-1$
									: message)
							.put("frame", entry.getKey().get(2)) //$NON-NLS-1$
							.put("count", Long.valueOf(count(entry.getValue()))) //$NON-NLS-1$
							.put("errorEvents", Long.valueOf(entry.getValue()[0])) //$NON-NLS-1$
							.put("exceptionEvents", Long.valueOf(entry.getValue()[1]))); //$NON-NLS-1$
				});
		JsonObject result = new JsonObject().put("thrown", Long.valueOf(thrown)) //$NON-NLS-1$
				.put("events", Integer.valueOf(events)) //$NON-NLS-1$
				.put("groups", array) //$NON-NLS-1$
				.put("groupsTotal", Integer.valueOf(groups.size())) //$NON-NLS-1$
				.put("groupsTruncated", Boolean.valueOf(groups.size() > limit)); //$NON-NLS-1$
		if (throttle != null && !"off".equals(throttle)) { //$NON-NLS-1$
			result.put("exceptionThrottle", throttle) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"jdk.JavaExceptionThrow was throttled to %s, so exception counts are lower bounds. An Error is in both streams, twice in the exception stream, so its count is errorEvents and the streams do not add up." //$NON-NLS-1$
									.formatted(throttle));
		}
		return result;
	}
}
