package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.discovery.service.DiscoveryService;
import com.iskeru.computeadmin.discovery.service.DiscoveryService.DiscoveredRecipe;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Create-only MCP tool {@code discover_recipes(machineId)}. Delegates to
 * {@link DiscoveryService#discover}, which SSHes into one of the caller's machines,
 * runs the read-only service probes, and persists each proposal as a recipe +
 * actions in {@code PENDING_APPROVAL}. It <strong>never approves and never mutates
 * the box</strong>: the proposed actions still require UI approval before they can
 * run. Touches no repository.
 *
 * <p>spec-008.
 */
@Component
public class DiscoverRecipesTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "machineId": {"type": "string", "description": "Machine to probe for installed services."}
              },
              "required": ["machineId"]
            }
            """;

    private final DiscoveryService discoveryService;
    private final ObjectMapper objectMapper;

    public DiscoverRecipesTool(DiscoveryService discoveryService, ObjectMapper objectMapper) {
        this.discoveryService = discoveryService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "discover_recipes",
                "Probe a machine and propose recipes; proposals land PENDING_APPROVAL, never approved.",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            String machineId = arguments == null ? null : (String) arguments.get("machineId");
            List<Map<String, Object>> proposals = discoveryService.discover(machineId).stream()
                    .map(DiscoverRecipesTool::summarize)
                    .toList();
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(proposals))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to discover recipes: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    private static Map<String, Object> summarize(DiscoveredRecipe discovered) {
        return Map.of(
                "recipeId", discovered.recipe().getId(),
                "name", discovered.recipe().getName(),
                "type", discovered.recipe().getType().name(),
                "actionCount", discovered.actions().size());
    }
}
