package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.ArgToken;
import com.iskeru.computeadmin.recipe.model.ParamAllowedValue;
import com.iskeru.computeadmin.recipe.model.ParamDef;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only MCP tool {@code list_actions(machineId, recipeId)}. Delegates to
 * {@link RecipeService#listActions(String)} (owner-scoped) and — per the core
 * invariant (ARCH.md gate point 4) — marks every non-{@code APPROVED} action
 * {@code pending_approval: true} so an agent can see what still needs a human to
 * approve in the UI. It never approves and touches no repository.
 *
 * <p>The declared argv tokens and typed param schema are included so an agent can
 * build a valid {@code run_action} call for an approved action.
 *
 * <p>spec-008.
 */
@Component
public class ListActionsTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "machineId": {"type": "string", "description": "Machine the recipe belongs to."},
                "recipeId": {"type": "string", "description": "Recipe whose actions to list."}
              },
              "required": ["machineId", "recipeId"]
            }
            """;

    private final RecipeService recipeService;
    private final ObjectMapper objectMapper;

    public ListActionsTool(RecipeService recipeService, ObjectMapper objectMapper) {
        this.recipeService = recipeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "list_actions",
                "List a recipe's actions; non-approved ones are marked pending_approval.",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            String recipeId = arguments == null ? null : (String) arguments.get("recipeId");
            List<Map<String, Object>> summaries = recipeService.listActions(recipeId).stream()
                    .map(ListActionsTool::summarize)
                    .toList();
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(summaries))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to list actions: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    private static Map<String, Object> summarize(Action action) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", action.getId());
        summary.put("name", action.getName());
        summary.put("description", action.getDescription());
        summary.put("sudo", action.isSudo());
        summary.put("approvalState", action.getApprovalState().name());
        // ARCH gate point 4: surface non-APPROVED as pending_approval so an agent
        // can ask a human to approve; the run tool still refuses it.
        summary.put("pending_approval", action.getApprovalState() != ApprovalState.APPROVED);
        summary.put("argTokens", action.getArgTokens().stream()
                .map(ListActionsTool::tokenView).toList());
        summary.put("paramDefs", action.getParamDefs().stream()
                .map(ListActionsTool::paramView).toList());
        return summary;
    }

    private static Map<String, Object> tokenView(ArgToken token) {
        return Map.of("kind", token.getKind().name(), "value", token.getValue());
    }

    private static Map<String, Object> paramView(ParamDef def) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", def.getName());
        view.put("kind", def.getKind().name());
        if (def.getPattern() != null) {
            view.put("pattern", def.getPattern());
        }
        if (def.getIntMin() != null) {
            view.put("intMin", def.getIntMin());
        }
        if (def.getIntMax() != null) {
            view.put("intMax", def.getIntMax());
        }
        if (!def.getAllowedValues().isEmpty()) {
            view.put("allowedValues", def.getAllowedValues().stream()
                    .map(ParamAllowedValue::getValue).toList());
        }
        return view;
    }
}
