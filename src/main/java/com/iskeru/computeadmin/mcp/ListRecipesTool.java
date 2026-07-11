package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only MCP tool {@code list_recipes(machineId)}. Delegates to
 * {@link RecipeService#listForMachine(String)} — owner-scoped, so a not-owned or
 * absent machine simply yields no recipes. Holds no business logic and touches no
 * repository (thin adapter; asserted by {@code GateArchTest}).
 *
 * <p>spec-008.
 */
@Component
public class ListRecipesTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "machineId": {"type": "string", "description": "Machine whose recipes to list."}
              },
              "required": ["machineId"]
            }
            """;

    private final RecipeService recipeService;
    private final ObjectMapper objectMapper;

    public ListRecipesTool(RecipeService recipeService, ObjectMapper objectMapper) {
        this.recipeService = recipeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "list_recipes",
                "List the recipes on one of your machines.",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            String machineId = arguments == null ? null : (String) arguments.get("machineId");
            List<Map<String, Object>> summaries = recipeService.listForMachine(machineId).stream()
                    .map(ListRecipesTool::summarize)
                    .toList();
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(summaries))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to list recipes: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    private static Map<String, Object> summarize(Recipe recipe) {
        return Map.of(
                "id", recipe.getId(),
                "machineId", recipe.getMachine().getId(),
                "name", recipe.getName(),
                "type", recipe.getType().name());
    }
}
