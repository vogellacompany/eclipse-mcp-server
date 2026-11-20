package com.vogella.eclipse.mcp.p2.internal;

import java.util.List;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** The listed part of an update or change list, capped with the full count kept. */
public record CappedList(JsonArray listed, int total, boolean truncated) {

	/** Caps {@code entries} at {@code maxResults} without changing the full count. */
	public static CappedList cap(List<JsonObject> entries, int maxResults) {
		JsonArray listed = new JsonArray();
		for (JsonObject entry : entries) {
			if (listed.size() >= maxResults) {
				break;
			}
			listed.add(entry);
		}
		return new CappedList(listed, entries.size(), entries.size() > listed.size());
	}
}
