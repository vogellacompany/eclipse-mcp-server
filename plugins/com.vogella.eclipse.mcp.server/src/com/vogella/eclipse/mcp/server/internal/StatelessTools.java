package com.vogella.eclipse.mcp.server.internal;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;

/**
 * The fixed tool list of the stateless server, in one page sorted by name, since the
 * 2026-07-28 revision asks for a deterministic {@code tools/list}.
 */
public final class StatelessTools implements McpSyncToolRepository {

	private final Map<String, McpSyncTool> byName = new LinkedHashMap<>();

	private final ToolsPage page;

	public StatelessTools(List<McpSyncTool> tools) {
		tools.stream().sorted(Comparator.comparing(tool -> tool.definition().name()))
				.forEach(tool -> byName.putIfAbsent(tool.definition().name(), tool));
		page = ToolsPage.of(byName.values().stream().map(McpSyncTool::definition).toList());
	}

	@Override
	public ToolsPage list(McpRequestContext context, String cursor) {
		return page;
	}

	@Override
	public McpSyncTool find(McpRequestContext context, String name) {
		return byName.get(name);
	}
}
