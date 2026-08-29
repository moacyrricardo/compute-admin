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
import com.iskeru.computeadmin.recipe.service.ActionSnapshot;

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

    /**
     * A recipe, including its description and blueprint provenance (spec 010) and — for a
     * discovery-pre-filled app-monitor recipe — its {@code appPortList} of per-context
     * records (spec-063). {@code appPortList} is empty for a recipe with no pre-fill and
     * for callers that assemble a {@code RecipeView} without a parsed list (the plain
     * {@link #of(Recipe)} path); the discovery/recipe read path that has the parsed items
     * uses {@link #of(Recipe, List)} to carry them.
     */
    public record RecipeView(String id, String machineId, String name, String description,
                             RecipeType type, String sourceBlueprintId, Integer sourceBlueprintVersion,
                             Instant createdAt, List<AppPortView> appPortList) {
        public static RecipeView of(Recipe recipe) {
            return of(recipe, List.of());
        }

        public static RecipeView of(Recipe recipe, List<AppPortView> appPortList) {
            return new RecipeView(recipe.getId(), recipe.getMachine().getId(), recipe.getName(),
                    recipe.getDescription(), recipe.getType(), recipe.getSourceBlueprintId(),
                    recipe.getSourceBlueprintVersion(), recipe.getCreatedAt(), List.copyOf(appPortList));
        }
    }

    /**
     * One discovery-pre-filled {@code (app-name, port)} item a fan-out probe action runs
     * over (spec-025), with the optional {@code runtime} label (spec-022) the UI uses for
     * the docker/systemd/process affordance and the double-detection link.
     *
     * <p><strong>Discovery-context fields (spec-063).</strong> {@code contextDisplay} (the
     * logical context path the UI shows), {@code contextScripts} (the sibling app-scripts
     * that collapse to the same context), {@code sourceNote} (the human-readable discovery
     * provenance), {@code confidence} (the fingerprint confidence, {@code high}/{@code low}/
     * {@code null}), and the logical {@code scriptFolder} are the rich {@code AppPortItem}
     * side-data (055/056/061), now carried through to the authenticated admin UI. The
     * physical/synthetic {@code contextKey} is deliberately <strong>not</strong> exposed — it
     * is an internal identity key (S9-secret), never user-facing. These are real paths the UI
     * is entitled to (028 precedent); S9 forbids paths only on the MCP surface, which this
     * record never reaches.
     *
     * <p>Lives in the {@code recipe} module (the lower module) so both the {@code recipe} and
     * {@code monitor} read surfaces can reference one shared record without inverting the
     * {@code monitor → recipe} dependency direction (the same reason {@code ArgTokenView}/
     * {@code ParamDefView} live here). The mapping from a {@code MonitorService.AppPort} lives
     * in {@code MonitorDtos}, not here — it would otherwise pull {@code monitor} types into
     * {@code recipe}.
     *
     * <p>spec-004; moved here and widened with the context fields in spec-063.
     */
    public record AppPortView(String appName, int port, String runtime,
                              String contextDisplay, List<String> contextScripts,
                              String sourceNote, String confidence, String scriptFolder,
                              Integer managementPort) {

        public AppPortView {
            contextScripts = contextScripts == null ? List.of() : List.copyOf(contextScripts);
        }

        /** The pre-073 eight-field view (single-port app: no separate management port). */
        public AppPortView(String appName, int port, String runtime,
                           String contextDisplay, List<String> contextScripts,
                           String sourceNote, String confidence, String scriptFolder) {
            this(appName, port, runtime, contextDisplay, contextScripts,
                    sourceNote, confidence, scriptFolder, null);
        }

        /** The bare three-field item (no resolved context) — old rows and docker-object items. */
        public AppPortView(String appName, int port, String runtime) {
            this(appName, port, runtime, null, List.of(), null, null, null, null);
        }
    }

    /**
     * An action, including its description (what a human reads when approving), its
     * approval state and a convenience {@code pendingApproval} flag, plus the
     * structured argv and param schema.
     *
     * <p>The raw approved snapshot hash is intentionally <em>not</em> exposed; instead
     * {@code changedSinceApproval} is a server-derived boolean that is {@code true} only
     * when the action is {@code APPROVED} yet its current content hash no longer matches
     * the hash bound at approval (see {@code ActionSnapshot}). This is the "changed since
     * approval — re-review" drift signal the UI shows: it is reachable when a blueprint
     * re-instantiation mutates an already-approved action's structure without clearing
     * {@code approvedSnapshotHash}, and it is the same mismatch {@code RunService} rejects
     * at run time. The UI cannot compute it (it never receives the hash), so it must be
     * derived here (spec-012).
     */
    public record ActionView(String id, String recipeId, String name, String description, boolean sudo,
                             ApprovalState approvalState, boolean pendingApproval, boolean changedSinceApproval,
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
            boolean changedSinceApproval = action.getApprovalState() == ApprovalState.APPROVED
                    && !ActionSnapshot.hash(action).equals(action.getApprovedSnapshotHash());
            return new ActionView(action.getId(), action.getRecipe().getId(), action.getName(),
                    action.getDescription(), action.isSudo(), action.getApprovalState(),
                    action.getApprovalState() == ApprovalState.PENDING_APPROVAL, changedSinceApproval,
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
