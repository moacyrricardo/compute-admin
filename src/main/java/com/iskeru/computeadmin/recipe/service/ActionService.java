package com.iskeru.computeadmin.recipe.service;

import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.ArgToken;
import com.iskeru.computeadmin.recipe.model.ParamAllowedValue;
import com.iskeru.computeadmin.recipe.model.ParamDef;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.repository.ActionRepository;
import jakarta.ws.rs.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Authoring of actions, scoped per user through {@code recipe.machine.owner}. It
 * builds the structured argv + typed param schema, validates the schema at write
 * time (so a malformed action can never be approved), and — critically —
 * <strong>resets approval on any structural edit</strong> so an action can't be
 * approved benign then mutated (TOCTOU). That reset is enforced centrally here in
 * {@link #editAction}, not left to callers.
 *
 * <p>spec-004.
 */
@Service
public class ActionService {

    /** One argv element: literal text, or a param reference (the param name in {@code value}). */
    public record ArgTokenInput(TokenKind kind, String value) {
    }

    /** One typed parameter rule. Only the fields relevant to {@code kind} are used. */
    public record ParamDefInput(String name, ParamKind kind, String pattern,
                                Integer intMin, Integer intMax, List<String> allowedValues) {
    }

    /** Service-input for {@link #addAction}. */
    public record AddActionInput(String recipeId, String name, String description, boolean sudo,
                                 List<ArgTokenInput> argTokens, List<ParamDefInput> paramDefs) {
    }

    /** Service-input for {@link #editAction} — the mutable structural fields of an action. */
    public record EditActionInput(String name, String description, boolean sudo,
                                  List<ArgTokenInput> argTokens, List<ParamDefInput> paramDefs) {
    }

    /**
     * Service-input for {@link #addCustomAction}. Wraps an existing script already on
     * the box: {@code scriptPath} is an <strong>absolute</strong> path bound as the
     * leading {@code LITERAL} token (never a param, so it can't vary at run time),
     * optionally followed by declared typed {@code paramDefs}.
     *
     * <p>The action is grouped under one {@code CUSTOM} recipe: target an existing one
     * by {@code recipeId} (owned, on {@code machineId}, {@code CUSTOM}), or omit it and
     * pass {@code recipeName} to get-or-create the named recipe. {@code actionName} is
     * this custom command's own name within that recipe.
     */
    public record CustomActionInput(String machineId, String recipeId, String recipeName,
                                    String actionName, String scriptPath,
                                    List<ParamDefInput> paramDefs, boolean sudo) {
    }

    private final ActionRepository actions;
    private final RecipeService recipeService;

    public ActionService(ActionRepository actions, RecipeService recipeService) {
        this.actions = actions;
        this.recipeService = recipeService;
    }

    /** Adds a new (DRAFT) action to one of the current user's recipes. */
    @Transactional
    public Action addAction(AddActionInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        Recipe recipe = recipeService.requireRecipe(input.recipeId());
        Action action = new Action();
        action.setRecipe(recipe);
        action.setName(input.name().trim());
        action.setDescription(input.description());
        action.setSudo(input.sudo());
        action.setApprovalState(ApprovalState.DRAFT);
        applyStructure(action, input.argTokens(), input.paramDefs());
        return actions.save(action);
    }

    /**
     * Wraps an existing on-box script as a {@code CUSTOM} recipe/action so it flows
     * through the <strong>same</strong> gate (004) and run path (005) as everything
     * else — no bypass. The {@code scriptPath} is validated as an absolute path at
     * authoring time and stored as the leading {@code LITERAL} argv token (it is
     * <strong>not</strong> a param, so it can't vary at run time); each declared
     * param becomes a later {@code PARAM} token, subject to the identical 004
     * add-action validation. Created {@code DRAFT}; only runnable once {@code
     * APPROVED}. No free-form command param is ever allowed (S4).
     *
     * <p>Several custom commands group under one {@code CUSTOM} recipe: target an
     * existing recipe by {@code recipeId} (must be owned by the current user, on
     * {@code machineId}, and of type {@code CUSTOM}), or omit it and pass {@code
     * recipeName} to reuse-or-create the named recipe. Each action keeps its own
     * name, script and params.
     *
     * <p>spec-007.
     */
    @Transactional
    public Action addCustomAction(CustomActionInput input) {
        if (input.actionName() == null || input.actionName().isBlank()) {
            throw new BadRequestException("actionName is required");
        }
        String scriptPath = input.scriptPath();
        if (scriptPath == null || scriptPath.isBlank()) {
            throw new BadRequestException("scriptPath is required");
        }
        scriptPath = scriptPath.trim();
        if (!scriptPath.startsWith("/")) {
            throw new BadRequestException("scriptPath must be an absolute path");
        }

        // Resolve the CUSTOM recipe this custom command belongs to. Either an explicit
        // existing recipe, or get-or-create by name — so multiple custom commands can
        // be bundled under one named recipe.
        Recipe recipe;
        if (input.recipeId() != null) {
            // Owner-scoped: a not-owned/absent recipe is a 404 (existence never leaked).
            recipe = recipeService.requireRecipe(input.recipeId());
            if (input.machineId() == null || !recipe.getMachine().getId().equals(input.machineId())) {
                throw new BadRequestException("recipe is not on machineId");
            }
            if (recipe.getType() != RecipeType.CUSTOM) {
                throw new BadRequestException("recipe is not a CUSTOM recipe");
            }
        } else {
            if (input.recipeName() == null || input.recipeName().isBlank()) {
                throw new BadRequestException("recipeName is required when recipeId is absent");
            }
            recipe = recipeService.getOrCreateCustom(input.machineId(), input.recipeName());
        }

        // Leading LITERAL = the fixed absolute path; then one PARAM token per declared
        // param. The command shape is fixed literals; only declared typed params vary.
        List<ParamDefInput> defs = input.paramDefs() == null ? List.of() : input.paramDefs();
        List<ArgTokenInput> tokens = new ArrayList<>();
        tokens.add(new ArgTokenInput(TokenKind.LITERAL, scriptPath));
        for (ParamDefInput def : defs) {
            String paramName = def == null || def.name() == null ? null : def.name().trim();
            tokens.add(new ArgTokenInput(TokenKind.PARAM, paramName));
        }

        // Delegate to the ordinary add-action path so the 004 schema validation
        // (param defs, PARAM↔def cross-checks) applies verbatim.
        return addAction(new AddActionInput(
                recipe.getId(), input.actionName(), scriptPath, input.sudo(), tokens, defs));
    }

    /**
     * Replaces the structural fields of one of the current user's actions and
     * <strong>resets its approval</strong> to {@link ApprovalState#DRAFT}, clearing
     * the bound snapshot hash and the approval record. This is the single choke
     * point that defeats approve-then-mutate.
     */
    @Transactional
    public Action editAction(String actionId, EditActionInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        Action action = requireAction(actionId);
        action.setName(input.name().trim());
        action.setDescription(input.description());
        action.setSudo(input.sudo());
        // Force the orphan DELETEs of the old tokens/params before re-inserting, so a
        // later autoflush in the same transaction (e.g. a blueprint re-instantiation
        // that reconciles then re-reads) cannot hit the (action, position)/(action,
        // name) unique constraints with insert-before-delete ordering.
        action.getArgTokens().clear();
        action.getParamDefs().clear();
        actions.saveAndFlush(action);
        applyStructure(action, input.argTokens(), input.paramDefs());

        // Central reset: any structural edit drops the action back to DRAFT so it
        // must be re-reviewed and re-approved. Never left to callers.
        action.setApprovalState(ApprovalState.DRAFT);
        action.setApprovedSnapshotHash(null);
        // Clear the pinned script hash alongside the snapshot hash so the two are always
        // cleared together: a re-approval re-probes and re-pins the script (spec-015).
        action.setApprovedScriptHash(null);
        action.setApprovedAt(null);
        action.setApprovedByUserId(null);
        return actions.save(action);
    }

    /**
     * The current user's action by id.
     *
     * @throws ActionNotFoundException 404 if absent or owned by another user.
     */
    public Action requireAction(String id) {
        return actions.findByIdAndRecipe_Machine_Owner_Id(id, CurrentUser.require().userId())
                .orElseThrow(() -> new ActionNotFoundException(id));
    }

    /**
     * The current user's action named {@code name} within one of their recipes, if any
     * (spec-021). The recipe is owner-scoped through {@link RecipeService#requireRecipe}
     * — a not-owned/absent recipe 404s — so discovery reconciliation can look up "the
     * action this proposal owns" without touching a repository itself.
     */
    @Transactional(readOnly = true)
    public Optional<Action> findOnRecipe(String recipeId, String name) {
        Recipe recipe = recipeService.requireRecipe(recipeId);
        return actions.findByRecipe_IdAndName(recipe.getId(), name);
    }

    /**
     * The content hash a proposed structure would have, computed on a transient
     * {@link Action} without persisting anything (spec-021). Discovery uses it to
     * decide whether a re-discovered proposal differs from what a human already
     * approved: build a transient action from the proposal, hash it via
     * {@link ActionSnapshot#hash(Action)}, and compare against the stored
     * {@code approvedSnapshotHash}. Equal ⇒ the approval still matches the proposal;
     * different ⇒ discovery re-proposed a changed definition. The structure is
     * validated by the same {@link #applyStructure} the write path uses, so a
     * malformed proposal fails fast rather than yielding a meaningless hash.
     */
    public String snapshotHashOf(boolean sudo, List<ArgTokenInput> argTokens, List<ParamDefInput> paramDefs) {
        Action probe = new Action();
        probe.setSudo(sudo);
        applyStructure(probe, argTokens, paramDefs);
        return ActionSnapshot.hash(probe);
    }

    /** Rebuilds the argv tokens and param defs, validating the resulting schema. */
    private void applyStructure(Action action, List<ArgTokenInput> tokenInputs, List<ParamDefInput> defInputs) {
        List<ArgTokenInput> tokens = tokenInputs == null ? List.of() : tokenInputs;
        List<ParamDefInput> defs = defInputs == null ? List.of() : defInputs;

        Set<String> declaredNames = new HashSet<>();
        String appPortListName = null;
        // Mutate the existing collections so orphanRemoval deletes the old rows.
        action.getParamDefs().clear();
        for (ParamDefInput in : defs) {
            ParamDef def = buildParamDef(action, in);
            if (!declaredNames.add(def.getName())) {
                throw new BadRequestException("Duplicate param definition: " + def.getName());
            }
            if (def.getKind() == ParamKind.APP_PORT_LIST) {
                // At most one repeatable composite per action (spec-022): a fan-out is
                // over a single (app-name, port) list, and the item components bind to
                // the fixed component names, which cannot serve two composites.
                if (appPortListName != null) {
                    throw new BadRequestException("An action may declare at most one APP_PORT_LIST param");
                }
                appPortListName = def.getName();
            }
            action.getParamDefs().add(def);
        }

        Set<String> referencedNames = new HashSet<>();
        action.getArgTokens().clear();
        int position = 0;
        for (ArgTokenInput in : tokens) {
            if (in == null || in.kind() == null) {
                throw new BadRequestException("Each argToken needs a kind");
            }
            if (in.value() == null || in.value().isBlank()) {
                throw new BadRequestException("Each argToken needs a value");
            }
            ArgToken token = new ArgToken();
            token.setAction(action);
            token.setPosition(position++);
            token.setKind(in.kind());
            token.setValue(in.value());
            if (in.kind() == TokenKind.PARAM) {
                if (declaredNames.contains(in.value())) {
                    // A composite is never referenced directly by a token — only via its
                    // components — so a bare reference to it is a malformed template.
                    if (in.value().equals(appPortListName)) {
                        throw new BadRequestException(
                                "APP_PORT_LIST param '" + in.value()
                                        + "' must be referenced by its '"
                                        + ParamBinder.APP_NAME_COMPONENT + "'/'"
                                        + ParamBinder.PORT_COMPONENT + "' components, not by name");
                    }
                    referencedNames.add(in.value());
                } else if (appPortListName != null && ParamBinder.isAppPortComponent(in.value())) {
                    // A fan-out template references the item's app-name/port components,
                    // which the single declared APP_PORT_LIST composite supplies per item.
                    referencedNames.add(appPortListName);
                } else {
                    throw new BadRequestException(
                            "PARAM token references undeclared param: " + in.value());
                }
            }
            action.getArgTokens().add(token);
        }

        for (String declared : declaredNames) {
            if (!referencedNames.contains(declared)) {
                throw new BadRequestException("Param declared but never referenced: " + declared);
            }
        }
    }

    private ParamDef buildParamDef(Action action, ParamDefInput in) {
        if (in == null || in.name() == null || in.name().isBlank()) {
            throw new BadRequestException("Each param needs a name");
        }
        if (in.kind() == null) {
            throw new BadRequestException("Param '" + in.name() + "' needs a kind");
        }
        ParamDef def = new ParamDef();
        def.setAction(action);
        def.setName(in.name().trim());
        def.setKind(in.kind());
        // spec-026: reserve the `app-name` param name. A scalar `app-name` is the app-ops
        // correlation key, so it must be an ALLOWED_SET of valid app identifiers — that is
        // what lets the dashboard enumerate the apps the action targets. (The APP_PORT_LIST
        // composite is exempt: its `app-name` is a fixed per-item component, not this param.)
        boolean reservedAppName = ParamBinder.APP_NAME_COMPONENT.equals(def.getName())
                && in.kind() != ParamKind.APP_PORT_LIST;
        if (reservedAppName && in.kind() != ParamKind.ALLOWED_SET) {
            throw new BadRequestException(
                    "Param 'app-name' is reserved and must be an ALLOWED_SET of target apps");
        }
        switch (in.kind()) {
            case ALLOWED_SET -> {
                List<String> values = in.allowedValues();
                if (values == null || values.isEmpty()) {
                    throw new BadRequestException(
                            "ALLOWED_SET param '" + in.name() + "' needs at least one value");
                }
                List<ParamAllowedValue> allowed = new ArrayList<>();
                for (String raw : values) {
                    if (raw == null || raw.isEmpty()) {
                        throw new BadRequestException(
                                "ALLOWED_SET param '" + in.name() + "' has a blank value");
                    }
                    if (reservedAppName && !Pattern.matches(ParamBinder.APP_NAME_PATTERN, raw)) {
                        throw new BadRequestException(
                                "app-name value '" + raw + "' is not a valid app identifier");
                    }
                    ParamAllowedValue value = new ParamAllowedValue();
                    value.setParamDef(def);
                    value.setValue(raw);
                    allowed.add(value);
                }
                def.getAllowedValues().addAll(allowed);
            }
            case REGEX -> {
                if (in.pattern() == null || in.pattern().isBlank()) {
                    throw new BadRequestException("REGEX param '" + in.name() + "' needs a pattern");
                }
                try {
                    Pattern.compile(in.pattern());
                } catch (PatternSyntaxException e) {
                    throw new BadRequestException(
                            "REGEX param '" + in.name() + "' has an invalid pattern: " + e.getMessage());
                }
                def.setPattern(in.pattern());
            }
            case INT_RANGE -> {
                if (in.intMin() == null || in.intMax() == null) {
                    throw new BadRequestException(
                            "INT_RANGE param '" + in.name() + "' needs intMin and intMax");
                }
                if (in.intMin() > in.intMax()) {
                    throw new BadRequestException(
                            "INT_RANGE param '" + in.name() + "' needs intMin <= intMax");
                }
                def.setIntMin(in.intMin());
                def.setIntMax(in.intMax());
            }
            // A repeatable composite has a fixed item schema (the app-name pattern +
            // port range are constants on ParamBinder), so there is no per-instance
            // config to store; the kind alone carries the meaning (and is hashed). spec-022.
            case APP_PORT_LIST -> {
            }
        }
        return def;
    }
}
