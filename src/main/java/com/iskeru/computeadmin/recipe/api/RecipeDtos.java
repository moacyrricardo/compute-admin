package com.iskeru.computeadmin.recipe.api;

import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.ArgToken;
import com.iskeru.computeadmin.recipe.model.ParamAllowedValue;
import com.iskeru.computeadmin.recipe.model.ParamDef;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO records for the {@code recipe} REST surface. Request records are plain and
 * reuse the {@code service} input records for the argv/param pieces; response
 * records own their mapping via a static {@code of(...)}. No mapper framework.
 *
 * <p>spec-004.
 */
public final class RecipeDtos {

    private RecipeDtos() {
    }

    /** {@code POST /api/recipes} body. {@code type} defaults to CUSTOM when null. */
    public record RecipeRequest(String machineId, String name, String description, RecipeType type) {
    }

    /** {@code POST /api/actions} body. */
    public record AddActionRequest(String recipeId, String name, String description, boolean sudo,
                                   List<ArgTokenInput> argTokens, List<ParamDefInput> paramDefs) {
    }

    /** {@code PUT /api/actions/{id}} body. Editing resets approval to DRAFT. */
    public record EditActionRequest(String name, String description, boolean sudo,
                                    List<ArgTokenInput> argTokens, List<ParamDefInput> paramDefs) {
    }

    /**
     * {@code POST /api/actions/custom} body. Adds a custom command (an on-box script
     * wrapped as an action) to a {@code CUSTOM} recipe: target an existing recipe by
     * {@code recipeId}, or omit it and pass {@code recipeName} to get-or-create the
     * named recipe on {@code machineId}. {@code actionName} names this command.
     */
    public record AddCustomActionRequest(String machineId, String recipeId, String recipeName,
                                         String actionName, String scriptPath,
                                         List<ParamDefInput> paramDefs, boolean sudo) {
    }

    /** A recipe, including its description and blueprint provenance (spec 010). */
    public record RecipeView(String id, String machineId, String name, String description,
                             RecipeType type, String sourceBlueprintId, Integer sourceBlueprintVersion,
                             Instant createdAt) {
        public static RecipeView of(Recipe recipe) {
            return new RecipeView(recipe.getId(), recipe.getMachine().getId(), recipe.getName(),
                    recipe.getDescription(), recipe.getType(), recipe.getSourceBlueprintId(),
                    recipe.getSourceBlueprintVersion(), recipe.getCreatedAt());
        }
    }

    /**
     * An action, including its description (what a human reads when approving), its
     * approval state and a convenience {@code pendingApproval} flag, plus the
     * structured argv and param schema. The approved snapshot hash is intentionally
     * not exposed.
     */
    public record ActionView(String id, String recipeId, String name, String description, boolean sudo,
                             ApprovalState approvalState, boolean pendingApproval,
                             String approvedByUserId, Instant approvedAt,
                             List<ArgTokenView> argTokens, List<ParamDefView> paramDefs) {
        public static ActionView of(Action action) {
            List<ArgTokenView> tokens = new ArrayList<>();
            for (ArgToken token : action.getArgTokens()) {
                tokens.add(ArgTokenView.of(token));
            }
            List<ParamDefView> defs = new ArrayList<>();
            for (ParamDef def : action.getParamDefs()) {
                defs.add(ParamDefView.of(def));
            }
            return new ActionView(action.getId(), action.getRecipe().getId(), action.getName(),
                    action.getDescription(), action.isSudo(), action.getApprovalState(),
                    action.getApprovalState() == ApprovalState.PENDING_APPROVAL,
                    action.getApprovedByUserId(), action.getApprovedAt(), tokens, defs);
        }
    }

    /** One argv element, in order. */
    public record ArgTokenView(int position, TokenKind kind, String value) {
        public static ArgTokenView of(ArgToken token) {
            return new ArgTokenView(token.getPosition(), token.getKind(), token.getValue());
        }
    }

    /** One typed parameter rule. Only the fields relevant to {@code kind} are populated. */
    public record ParamDefView(String name, ParamKind kind, String pattern,
                               Integer intMin, Integer intMax, List<String> allowedValues) {
        public static ParamDefView of(ParamDef def) {
            List<String> values = new ArrayList<>();
            for (ParamAllowedValue allowed : def.getAllowedValues()) {
                values.add(allowed.getValue());
            }
            return new ParamDefView(def.getName(), def.getKind(), def.getPattern(),
                    def.getIntMin(), def.getIntMax(), values);
        }
    }
}
