package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.blueprint.model.BlueprintAction;
import com.iskeru.computeadmin.blueprint.service.BlueprintService;
import com.iskeru.computeadmin.blueprint.service.BlueprintService.AddBlueprintActionInput;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Create-only MCP tool {@code add_blueprint_action}. Delegates to
 * {@link BlueprintService#addBlueprintAction} with the same structured argv + typed
 * param schema as {@code add_action}. It holds no approval logic and cannot approve
 * — a blueprint action has no approval state (asserted by {@code BlueprintGateTest}).
 *
 * <p><strong>Auth binding gap (inherited, spec 008):</strong> runs on a Reactor
 * thread where {@code CurrentUser} is unbound. See {@code ListMachinesTool}.
 *
 * <p>spec-010.
 */
@Component
public class AddBlueprintActionTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "blueprintId": {"type": "string", "description": "Blueprint to add the action to."},
                "name": {"type": "string", "description": "Action name (unique within the blueprint)."},
                "description": {"type": "string", "description": "What a human reads when approving an instance."},
                "sudo": {"type": "boolean", "description": "Whether the command runs with sudo."},
                "argTokens": {"type": "array", "description": "Ordered argv: {kind: LITERAL|PARAM, value}."},
                "paramDefs": {"type": "array", "description": "Typed param rules: {name, kind, pattern, intMin, intMax, allowedValues}."}
              },
              "required": ["blueprintId", "name"]
            }
            """;

    private final BlueprintService blueprintService;
    private final ObjectMapper objectMapper;

    public AddBlueprintActionTool(BlueprintService blueprintService, ObjectMapper objectMapper) {
        this.blueprintService = blueprintService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "add_blueprint_action",
                "Add an action definition to a blueprint (never runnable, no approval).",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            AddBlueprintActionInput input = objectMapper.convertValue(arguments, AddBlueprintActionInput.class);
            BlueprintAction action = blueprintService.addBlueprintAction(input);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(Map.of(
                            "id", action.getId(),
                            "blueprintId", action.getBlueprint().getId(),
                            "name", action.getName())))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to add blueprint action: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
