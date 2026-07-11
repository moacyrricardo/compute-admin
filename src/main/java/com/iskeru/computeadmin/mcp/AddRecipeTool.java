package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.recipe.service.RecipeService.CreateRecipeInput;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Create-only MCP tool {@code add_recipe(machineId, name, description?, type?)}.
 * Delegates to {@link RecipeService#create} on one of the caller's machines. A
 * recipe has no approval state; its actions do, and those are added (and approved)
 * separately. Touches no repository and cannot approve.
 *
 * <p>spec-008.
 */
@Component
public class AddRecipeTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "machineId": {"type": "string", "description": "Machine to add the recipe to."},
                "name": {"type": "string", "description": "Recipe name."},
                "description": {"type": "string", "description": "Optional free-text description."},
                "type": {"type": "string", "description": "NGINX|DOCKER|DATABASE|CRON|CUSTOM (default CUSTOM)."}
              },
              "required": ["machineId", "name"]
            }
            """;

    private final RecipeService recipeService;
    private final ObjectMapper objectMapper;

    public AddRecipeTool(RecipeService recipeService, ObjectMapper objectMapper) {
        this.recipeService = recipeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "add_recipe",
                "Add a recipe to one of your machines (its actions still need UI approval before they run).",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            CreateRecipeInput input = objectMapper.convertValue(arguments, CreateRecipeInput.class);
            Recipe recipe = recipeService.create(input);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(Map.of(
                            "id", recipe.getId(),
                            "machineId", recipe.getMachine().getId(),
                            "name", recipe.getName(),
                            "type", recipe.getType().name())))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to add recipe: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
