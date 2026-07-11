package com.iskeru.computeadmin.mcp.api;

import java.util.List;

/**
 * DTO records for the read-only MCP-surface catalogue ({@code GET /api/mcp/tools}).
 * A plain descriptive read source — it holds no business rules and touches no
 * repository, so it does not weaken the gate (see {@code GateArchTest}). The point
 * is to make the trust model legible to a human: what an agent connected to
 * {@code /mcp} can do, grouped by kind, and that there is no approve tool.
 *
 * <p>One {@code *Dtos} final class per api package, private ctor, nested records;
 * no mapper framework (ARCH "DTOs").
 *
 * <p>spec-012.
 */
public final class McpCatalogDtos {

    private McpCatalogDtos() {
    }

    /**
     * The whole MCP surface as the UI renders it: the tool groups, the exposed
     * resources, and the {@code approveTool} flag — always {@code false}, because
     * approval is UI-only and there is deliberately no approve tool.
     */
    public record Catalog(List<ToolGroup> groups, List<String> resources, boolean approveTool) {
    }

    /** A named group of tools: {@code Read}, {@code Create}, {@code Run}, {@code Bootstrap}. */
    public record ToolGroup(String group, List<Tool> tools) {
    }

    /** One tool: its wire name, a human-readable signature, and a one-line description. */
    public record Tool(String name, String signature, String description) {
    }
}
