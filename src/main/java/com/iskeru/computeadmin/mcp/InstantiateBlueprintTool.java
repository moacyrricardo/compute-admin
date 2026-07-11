package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.blueprint.service.InstantiationService;
import com.iskeru.computeadmin.blueprint.service.InstantiationService.InstantiateInput;
import com.iskeru.computeadmin.blueprint.service.InstantiationService.InstantiatedRecipe;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Create-only MCP tool {@code instantiate_blueprint(blueprintId, machineIds?|tag?)}.
 * Delegates to {@link InstantiationService#instantiate}, which copies the blueprint
 * into per-machine {@code PENDING_APPROVAL} recipes/actions. It <strong>never
 * approves and never runs</strong> and holds no approval logic: the instantiated
 * actions must still be approved through the UI gate the {@code mcp} module cannot
 * reach (asserted by {@code BlueprintGateTest}).
 *
 * <p><strong>Auth binding gap (inherited, spec 008):</strong> runs on a Reactor
 * thread where {@code CurrentUser} is unbound. See {@code ListMachinesTool}.
 *
 * <p>spec-010.
 */
@Component
public class InstantiateBlueprintTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "blueprintId": {"type": "string", "description": "Blueprint to instantiate."},
                "machineIds": {"type": "array", "items": {"type": "string"},
                    "description": "Explicit target machines (provide this OR tag)."},
                "tag": {"type": "string", "description": "Instantiate onto all your machines with this tag (provide this OR machineIds)."}
              },
              "required": ["blueprintId"]
            }
            """;

    private final InstantiationService instantiationService;
    private final ObjectMapper objectMapper;

    public InstantiateBlueprintTool(InstantiationService instantiationService, ObjectMapper objectMapper) {
        this.instantiationService = instantiationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "instantiate_blueprint",
                "Instantiate a blueprint onto machines (explicit ids or a tag); actions land PENDING_APPROVAL, never approved.",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            String blueprintId = arguments == null ? null : (String) arguments.get("blueprintId");
            InstantiateInput input = objectMapper.convertValue(arguments, InstantiateInput.class);
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (InstantiatedRecipe instantiated : instantiationService.instantiate(blueprintId, input)) {
                summaries.add(Map.of(
                        "recipeId", instantiated.recipe().getId(),
                        "machineId", instantiated.recipe().getMachine().getId(),
                        "actionCount", instantiated.actions().size()));
            }
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(summaries))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to instantiate blueprint: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
