package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.blueprint.model.RecipeBlueprint;
import com.iskeru.computeadmin.blueprint.service.BlueprintService;
import com.iskeru.computeadmin.blueprint.service.BlueprintService.CreateBlueprintInput;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Create-only MCP tool {@code add_blueprint}. Delegates straight to
 * {@link BlueprintService#createBlueprint} — it holds no business logic and, like
 * every {@code mcp} tool, holds no approval logic and cannot approve: a blueprint
 * has no approval state and is never runnable, so there is nothing here to approve
 * (asserted by {@code BlueprintGateTest}).
 *
 * <p>Scopes to the caller: the spec-008 wrapper in {@code config/McpServletConfig}
 * re-binds {@code CurrentUser} on the dispatch thread before this handler runs.
 *
 * <p>spec-010.
 */
@Component
public class AddBlueprintTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "name": {"type": "string", "description": "Blueprint name."},
                "description": {"type": "string", "description": "Optional free-text description."},
                "type": {"type": "string", "description": "Recipe type: NGINX|DOCKER|DATABASE|CRON|CUSTOM (default CUSTOM)."}
              },
              "required": ["name"]
            }
            """;

    private final BlueprintService blueprintService;
    private final ObjectMapper objectMapper;

    public AddBlueprintTool(BlueprintService blueprintService, ObjectMapper objectMapper) {
        this.blueprintService = blueprintService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "add_blueprint",
                "Author a machine-independent recipe blueprint (never runnable, no approval).",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            CreateBlueprintInput input = objectMapper.convertValue(arguments, CreateBlueprintInput.class);
            RecipeBlueprint blueprint = blueprintService.createBlueprint(input);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(Map.of(
                            "id", blueprint.getId(),
                            "name", blueprint.getName(),
                            "type", blueprint.getType().name(),
                            "version", blueprint.getVersion())))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to add blueprint: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
