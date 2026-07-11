package com.iskeru.computeadmin.blueprint.service;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.blueprint.model.BlueprintAction;
import com.iskeru.computeadmin.blueprint.model.BlueprintArgToken;
import com.iskeru.computeadmin.blueprint.model.BlueprintParamAllowedValue;
import com.iskeru.computeadmin.blueprint.model.BlueprintParamDef;
import com.iskeru.computeadmin.blueprint.model.RecipeBlueprint;
import com.iskeru.computeadmin.blueprint.repository.BlueprintActionRepository;
import com.iskeru.computeadmin.blueprint.repository.RecipeBlueprintRepository;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.common.UnauthorizedException;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import jakarta.ws.rs.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Authoring of blueprints and their actions, scoped per user (blueprints are
 * per-user, never shared). It reuses the 004 command shape — structured argv
 * ({@code ArgTokenInput}) plus a typed param schema ({@code ParamDefInput}) — and
 * applies the <strong>same authoring validation as {@code ActionService}</strong>
 * (every {@code PARAM} token names a declared def, every def is referenced, param
 * rules well-formed). A {@code CUSTOM} blueprint action's leading literal must be an
 * absolute script path (the "shared {@code deploy.sh}" case, per spec 007).
 *
 * <p>Unlike {@code Action}, a blueprint action has <strong>no approval state and no
 * run path</strong>: it is a definition to instantiate, never runnable. Content
 * edits ({@link #editBlueprint}, {@link #editBlueprintAction}) bump the blueprint
 * {@code version}, which instantiation records as provenance and uses to reconcile.
 *
 * <p>spec-010.
 */
@Service
public class BlueprintService {

    /** Service-input for {@link #createBlueprint}. {@code type} defaults to CUSTOM when null. */
    public record CreateBlueprintInput(String name, String description, RecipeType type) {
    }

    /** Service-input for {@link #editBlueprint}. {@code type} defaults to CUSTOM when null. */
    public record EditBlueprintInput(String name, String description, RecipeType type) {
    }

    /** Service-input for {@link #addBlueprintAction}. */
    public record AddBlueprintActionInput(String blueprintId, String name, String description, boolean sudo,
                                          List<ArgTokenInput> argTokens, List<ParamDefInput> paramDefs) {
    }

    /** Service-input for {@link #editBlueprintAction} — the mutable structural fields. */
    public record EditBlueprintActionInput(String name, String description, boolean sudo,
                                           List<ArgTokenInput> argTokens, List<ParamDefInput> paramDefs) {
    }

    private final RecipeBlueprintRepository blueprints;
    private final BlueprintActionRepository blueprintActions;
    private final AppUserRepository users;

    public BlueprintService(RecipeBlueprintRepository blueprints,
                            BlueprintActionRepository blueprintActions,
                            AppUserRepository users) {
        this.blueprints = blueprints;
        this.blueprintActions = blueprintActions;
        this.users = users;
    }

    /** Creates a blueprint owned by the current user (version 1). */
    @Transactional
    public RecipeBlueprint createBlueprint(CreateBlueprintInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        RecipeBlueprint blueprint = new RecipeBlueprint();
        blueprint.setOwner(currentUser());
        blueprint.setName(input.name().trim());
        blueprint.setDescription(input.description());
        blueprint.setType(input.type() == null ? RecipeType.CUSTOM : input.type());
        return blueprints.save(blueprint);
    }

    /**
     * Edits the display fields of one of the current user's blueprints and
     * <strong>bumps its {@code version}</strong> so re-instantiation reconciles
     * targets against the new revision.
     */
    @Transactional
    public RecipeBlueprint editBlueprint(String blueprintId, EditBlueprintInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        RecipeBlueprint blueprint = requireBlueprint(blueprintId);
        blueprint.setName(input.name().trim());
        blueprint.setDescription(input.description());
        blueprint.setType(input.type() == null ? RecipeType.CUSTOM : input.type());
        bumpVersion(blueprint);
        return blueprints.save(blueprint);
    }

    /** Adds an action to one of the current user's blueprints. Action names are unique per blueprint. */
    @Transactional
    public BlueprintAction addBlueprintAction(AddBlueprintActionInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        RecipeBlueprint blueprint = requireBlueprint(input.blueprintId());
        String name = input.name().trim();
        for (BlueprintAction existing : blueprintActions.findByBlueprint_IdOrderByName(blueprint.getId())) {
            if (existing.getName().equals(name)) {
                throw new BadRequestException("Duplicate blueprint action name: " + name);
            }
        }
        BlueprintAction action = new BlueprintAction();
        action.setBlueprint(blueprint);
        action.setName(name);
        action.setDescription(input.description());
        action.setSudo(input.sudo());
        applyStructure(action, input.argTokens(), input.paramDefs(), blueprint.getType());
        return blueprintActions.save(action);
    }

    /**
     * Replaces the structural fields of one of the current user's blueprint actions
     * and <strong>bumps the owning blueprint's {@code version}</strong> — the edit
     * that a later re-instantiation detects (via the 004 content hash) and that
     * resets any changed approved instance back to DRAFT.
     */
    @Transactional
    public BlueprintAction editBlueprintAction(String actionId, EditBlueprintActionInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        BlueprintAction action = requireBlueprintAction(actionId);
        RecipeBlueprint blueprint = action.getBlueprint();
        String name = input.name().trim();
        for (BlueprintAction sibling : blueprintActions.findByBlueprint_IdOrderByName(blueprint.getId())) {
            if (!sibling.getId().equals(action.getId()) && sibling.getName().equals(name)) {
                throw new BadRequestException("Duplicate blueprint action name: " + name);
            }
        }
        action.setName(name);
        action.setDescription(input.description());
        action.setSudo(input.sudo());
        // Force the orphan DELETEs of the old tokens/params before re-inserting, so a
        // later autoflush cannot hit the (action, position)/(action, name) unique
        // constraints with insert-before-delete ordering.
        action.getArgTokens().clear();
        action.getParamDefs().clear();
        blueprintActions.saveAndFlush(action);
        applyStructure(action, input.argTokens(), input.paramDefs(), blueprint.getType());
        bumpVersion(blueprint);
        blueprints.save(blueprint);
        return blueprintActions.save(action);
    }

    /** The current user's blueprints, ordered by name. */
    public List<RecipeBlueprint> list() {
        return blueprints.findByOwnerIdOrderByName(CurrentUser.require().userId());
    }

    /**
     * The current user's blueprint by id.
     *
     * @throws BlueprintNotFoundException 404 if absent or owned by another user.
     */
    public RecipeBlueprint requireBlueprint(String id) {
        return blueprints.findByIdAndOwnerId(id, CurrentUser.require().userId())
                .orElseThrow(() -> new BlueprintNotFoundException(id));
    }

    /**
     * The current user's blueprint action by id.
     *
     * @throws BlueprintActionNotFoundException 404 if absent or owned by another user.
     */
    public BlueprintAction requireBlueprintAction(String id) {
        return blueprintActions.findByIdAndBlueprint_Owner_Id(id, CurrentUser.require().userId())
                .orElseThrow(() -> new BlueprintActionNotFoundException(id));
    }

    /** The actions of one of the current user's blueprints, ordered by name. */
    public List<BlueprintAction> listActions(String blueprintId) {
        RecipeBlueprint blueprint = requireBlueprint(blueprintId);
        return blueprintActions.findByBlueprint_IdOrderByName(blueprint.getId());
    }

    private void bumpVersion(RecipeBlueprint blueprint) {
        blueprint.setVersion(blueprint.getVersion() + 1);
        blueprint.setUpdatedAt(Instant.now());
    }

    /**
     * Rebuilds the argv tokens and param defs of a blueprint action, validating the
     * schema exactly as {@code ActionService} does, plus the CUSTOM absolute-path
     * rule (spec 007). Mutates the existing collections so orphanRemoval deletes the
     * old rows on save.
     */
    private void applyStructure(BlueprintAction action, List<ArgTokenInput> tokenInputs,
                                List<ParamDefInput> defInputs, RecipeType type) {
        List<ArgTokenInput> tokens = tokenInputs == null ? List.of() : tokenInputs;
        List<ParamDefInput> defs = defInputs == null ? List.of() : defInputs;

        Set<String> declaredNames = new HashSet<>();
        action.getParamDefs().clear();
        for (ParamDefInput in : defs) {
            BlueprintParamDef def = buildParamDef(action, in);
            if (!declaredNames.add(def.getName())) {
                throw new BadRequestException("Duplicate param definition: " + def.getName());
            }
            action.getParamDefs().add(def);
        }

        Set<String> referencedNames = new HashSet<>();
        action.getArgTokens().clear();
        int position = 0;
        String firstLiteral = null;
        for (ArgTokenInput in : tokens) {
            if (in == null || in.kind() == null) {
                throw new BadRequestException("Each argToken needs a kind");
            }
            if (in.value() == null || in.value().isBlank()) {
                throw new BadRequestException("Each argToken needs a value");
            }
            BlueprintArgToken token = new BlueprintArgToken();
            token.setBlueprintAction(action);
            token.setPosition(position++);
            token.setKind(in.kind());
            token.setValue(in.value());
            if (in.kind() == TokenKind.PARAM) {
                if (!declaredNames.contains(in.value())) {
                    throw new BadRequestException(
                            "PARAM token references undeclared param: " + in.value());
                }
                referencedNames.add(in.value());
            } else if (firstLiteral == null) {
                firstLiteral = in.value();
            }
            action.getArgTokens().add(token);
        }

        for (String declared : declaredNames) {
            if (!referencedNames.contains(declared)) {
                throw new BadRequestException("Param declared but never referenced: " + declared);
            }
        }

        // CUSTOM blueprint actions run a script by path (the shared deploy.sh case,
        // spec 007): the leading literal must be an absolute path so the same
        // blueprint resolves identically on every target machine.
        if (type == RecipeType.CUSTOM) {
            if (firstLiteral == null) {
                throw new BadRequestException("CUSTOM blueprint action needs a leading command literal");
            }
            if (!firstLiteral.startsWith("/")) {
                throw new BadRequestException(
                        "CUSTOM blueprint action command must be an absolute path: " + firstLiteral);
            }
        }
    }

    private BlueprintParamDef buildParamDef(BlueprintAction action, ParamDefInput in) {
        if (in == null || in.name() == null || in.name().isBlank()) {
            throw new BadRequestException("Each param needs a name");
        }
        if (in.kind() == null) {
            throw new BadRequestException("Param '" + in.name() + "' needs a kind");
        }
        BlueprintParamDef def = new BlueprintParamDef();
        def.setBlueprintAction(action);
        def.setName(in.name().trim());
        def.setKind(in.kind());
        switch (in.kind()) {
            case ALLOWED_SET -> {
                List<String> values = in.allowedValues();
                if (values == null || values.isEmpty()) {
                    throw new BadRequestException(
                            "ALLOWED_SET param '" + in.name() + "' needs at least one value");
                }
                List<BlueprintParamAllowedValue> allowed = new ArrayList<>();
                for (String raw : values) {
                    if (raw == null || raw.isEmpty()) {
                        throw new BadRequestException(
                                "ALLOWED_SET param '" + in.name() + "' has a blank value");
                    }
                    BlueprintParamAllowedValue value = new BlueprintParamAllowedValue();
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
        }
        return def;
    }

    private AppUser currentUser() {
        String userId = CurrentUser.require().userId();
        return users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Authenticated user no longer exists"));
    }
}
