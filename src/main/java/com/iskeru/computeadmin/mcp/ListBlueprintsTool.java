package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.blueprint.model.RecipeBlueprint;
import com.iskeru.computeadmin.blueprint.service.BlueprintService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only MCP tool {@code list_blueprints}. Delegates to {@link BlueprintService#list}
 * and serializes a flat summary. Holds no approval logic and cannot approve
 * (asserted by {@code BlueprintGateTest}).
 *
 * <p>Scopes to the caller: the spec-008 wrapper in {@code config/McpServletConfig}
 * re-binds {@code CurrentUser} on the dispatch thread before this handler runs.
 *
 * <p>spec-010.
 */
@Component
public class ListBlueprintsTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """;

    private final BlueprintService blueprintService;
    private final ObjectMapper objectMapper;

    public ListBlueprintsTool(BlueprintService blueprintService, ObjectMapper objectMapper) {
        this.blueprintService = blueprintService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "list_blueprints",
                "List the current user's recipe blueprints.",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            List<Map<String, Object>> summaries = blueprintService.list().stream()
                    .map(ListBlueprintsTool::summarize)
                    .toList();
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(summaries))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to list blueprints: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    private static Map<String, Object> summarize(RecipeBlueprint blueprint) {
        return Map.of(
                "id", blueprint.getId(),
                "name", blueprint.getName(),
                "type", blueprint.getType().name(),
                "version", blueprint.getVersion());
    }
}
