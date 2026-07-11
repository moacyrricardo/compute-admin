package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionService.AddActionInput;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Create-only MCP tool {@code add_action(recipeId, name, description?, sudo?,
 * argTokens[], paramDefs[])}. Delegates to {@link ActionService#addAction}, which
 * builds and validates the structured argv + typed param schema (spec-004). The
 * action lands {@code DRAFT} — <strong>never approved</strong>: only a human can
 * approve it in the UI, and there is no approve tool. Touches no repository and
 * never references the approval state machine (asserted by {@code GateArchTest}).
 *
 * <p>{@code argTokens} are ordered {@code {kind: LITERAL|PARAM, value}} elements;
 * {@code paramDefs} are typed rules {@code {name, kind, pattern, intMin, intMax,
 * allowedValues}} — kept as discrete argv, never a free-form command (S4).
 *
 * <p>spec-008.
 */
@Component
public class AddActionTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "recipeId": {"type": "string", "description": "Recipe to add the action to."},
                "name": {"type": "string", "description": "Action name (unique within the recipe)."},
                "description": {"type": "string", "description": "What a human reads when approving. Never executed."},
                "sudo": {"type": "boolean", "description": "Whether the command runs with sudo."},
                "argTokens": {"type": "array", "description": "Ordered argv: {kind: LITERAL|PARAM, value}."},
                "paramDefs": {"type": "array", "description": "Typed param rules: {name, kind, pattern, intMin, intMax, allowedValues}."}
              },
              "required": ["recipeId", "name"]
            }
            """;

    private final ActionService actionService;
    private final ObjectMapper objectMapper;

    public AddActionTool(ActionService actionService, ObjectMapper objectMapper) {
        this.actionService = actionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "add_action",
                "Add a runnable action to a recipe. It lands DRAFT and needs UI approval before it can run.",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            AddActionInput input = objectMapper.convertValue(arguments, AddActionInput.class);
            Action action = actionService.addAction(input);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(Map.of(
                            "id", action.getId(),
                            "recipeId", action.getRecipe().getId(),
                            "name", action.getName(),
                            "approvalState", action.getApprovalState().name())))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to add action: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
