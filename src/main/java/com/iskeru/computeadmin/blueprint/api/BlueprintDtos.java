package com.iskeru.computeadmin.blueprint.api;

import com.iskeru.computeadmin.blueprint.model.BlueprintAction;
import com.iskeru.computeadmin.blueprint.model.BlueprintArgToken;
import com.iskeru.computeadmin.blueprint.model.BlueprintParamAllowedValue;
import com.iskeru.computeadmin.blueprint.model.BlueprintParamDef;
import com.iskeru.computeadmin.blueprint.model.RecipeBlueprint;
import com.iskeru.computeadmin.blueprint.service.InstantiationService.InstantiatedRecipe;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * DTO records for the {@code blueprint} REST surface. Request records are plain and
 * reuse the {@code recipe} service argv/param input records; response records own
 * their mapping via a static {@code of(...)}. No mapper framework.
 *
 * <p>spec-010.
 */
public final class BlueprintDtos {

    private BlueprintDtos() {
    }

    /** {@code POST /api/blueprints} body. {@code type} defaults to CUSTOM when null. */
    public record BlueprintRequest(String name, String description, RecipeType type) {
    }

    /** {@code PUT /api/blueprints/{id}} body. Editing bumps the blueprint version. */
    public record EditBlueprintRequest(String name, String description, RecipeType type) {
    }

    /** {@code POST /api/blueprints/{id}/actions} body. */
    public record AddBlueprintActionRequest(String name, String description, boolean sudo,
                                            List<ArgTokenInput> argTokens, List<ParamDefInput> paramDefs) {
    }

    /** {@code PUT /api/blueprints/actions/{actionId}} body. Editing bumps the blueprint version. */
    public record EditBlueprintActionRequest(String name, String description, boolean sudo,
                                             List<ArgTokenInput> argTokens, List<ParamDefInput> paramDefs) {
    }

    /** {@code POST /api/blueprints/{id}/instantiate} body: exactly one of {@code machineIds} or {@code tag}. */
    public record InstantiateRequest(Set<String> machineIds, String tag) {
    }

    /** A blueprint, including its version and timestamps. */
    public record BlueprintView(String id, String name, String description, RecipeType type,
                                int version, Instant createdAt, Instant updatedAt) {
        public static BlueprintView of(RecipeBlueprint blueprint) {
            return new BlueprintView(blueprint.getId(), blueprint.getName(), blueprint.getDescription(),
                    blueprint.getType(), blueprint.getVersion(), blueprint.getCreatedAt(),
                    blueprint.getUpdatedAt());
        }
    }

    /** A blueprint action — command shape only; it has no approval state and is never runnable. */
    public record BlueprintActionView(String id, String blueprintId, String name, String description,
                                      boolean sudo, List<BlueprintArgTokenView> argTokens,
                                      List<BlueprintParamDefView> paramDefs) {
        public static BlueprintActionView of(BlueprintAction action) {
            List<BlueprintArgTokenView> tokens = new ArrayList<>();
            for (BlueprintArgToken token : action.getArgTokens()) {
                tokens.add(BlueprintArgTokenView.of(token));
            }
            List<BlueprintParamDefView> defs = new ArrayList<>();
            for (BlueprintParamDef def : action.getParamDefs()) {
                defs.add(BlueprintParamDefView.of(def));
            }
            return new BlueprintActionView(action.getId(), action.getBlueprint().getId(), action.getName(),
                    action.getDescription(), action.isSudo(), tokens, defs);
        }
    }

    /** One argv element, in order. */
    public record BlueprintArgTokenView(int position, TokenKind kind, String value) {
        public static BlueprintArgTokenView of(BlueprintArgToken token) {
            return new BlueprintArgTokenView(token.getPosition(), token.getKind(), token.getValue());
        }
    }

    /** One typed parameter rule. Only the fields relevant to {@code kind} are populated. */
    public record BlueprintParamDefView(String name, ParamKind kind, String pattern,
                                        Integer intMin, Integer intMax, List<String> allowedValues) {
        public static BlueprintParamDefView of(BlueprintParamDef def) {
            List<String> values = new ArrayList<>();
            for (BlueprintParamAllowedValue allowed : def.getAllowedValues()) {
                values.add(allowed.getValue());
            }
            return new BlueprintParamDefView(def.getName(), def.getKind(), def.getPattern(),
                    def.getIntMin(), def.getIntMax(), values);
        }
    }

    /**
     * One instantiated per-machine recipe: where it landed, the blueprint version it
     * was copied from, and its resulting actions (each with its per-machine approval
     * state, typically {@code PENDING_APPROVAL} straight after instantiation).
     */
    public record InstantiatedRecipeView(String recipeId, String machineId, String name,
                                         String sourceBlueprintId, Integer sourceBlueprintVersion,
                                         List<InstantiatedActionView> actions) {
        public static InstantiatedRecipeView of(InstantiatedRecipe instantiated) {
            Recipe recipe = instantiated.recipe();
            List<InstantiatedActionView> actions = new ArrayList<>();
            for (Action action : instantiated.actions()) {
                actions.add(InstantiatedActionView.of(action));
            }
            return new InstantiatedRecipeView(recipe.getId(), recipe.getMachine().getId(), recipe.getName(),
                    recipe.getSourceBlueprintId(), recipe.getSourceBlueprintVersion(), actions);
        }
    }

    /** A lightweight view of an instantiated action: its id, name and per-machine approval state. */
    public record InstantiatedActionView(String id, String name, ApprovalState approvalState) {
        public static InstantiatedActionView of(Action action) {
            return new InstantiatedActionView(action.getId(), action.getName(), action.getApprovalState());
        }
    }
}
