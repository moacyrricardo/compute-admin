package com.iskeru.computeadmin.blueprint;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.blueprint.model.BlueprintAction;
import com.iskeru.computeadmin.blueprint.model.RecipeBlueprint;
import com.iskeru.computeadmin.blueprint.service.BlueprintNotFoundException;
import com.iskeru.computeadmin.blueprint.service.BlueprintService;
import com.iskeru.computeadmin.blueprint.service.BlueprintService.AddBlueprintActionInput;
import com.iskeru.computeadmin.blueprint.service.BlueprintService.CreateBlueprintInput;
import com.iskeru.computeadmin.blueprint.service.BlueprintService.EditBlueprintActionInput;
import com.iskeru.computeadmin.blueprint.service.BlueprintService.EditBlueprintInput;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Blueprint authoring (spec-010): create a blueprint with several described actions,
 * the 004-equivalent schema validation, the CUSTOM absolute-path rule, the version
 * bump on edit, and owner-scoped 404s.
 *
 * <p>spec-010.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({BlueprintService.class})
class BlueprintServiceTest {

    @Autowired
    private BlueprintService blueprintService;

    @Autowired
    private AppUserRepository users;

    private AppUser alice;
    private AppUser bob;

    @BeforeEach
    void seedUsers() {
        alice = saveUser("alice@example.com");
        bob = saveUser("bob@example.com");
    }

    @Test
    void createBlueprint_StartsAtVersionOne() {
        RecipeBlueprint blueprint = asUser(alice, () -> blueprintService.createBlueprint(
                new CreateBlueprintInput("nginx ops", "shared nginx recipe", RecipeType.NGINX)));

        assertThat(blueprint.getVersion()).isEqualTo(1);
        assertThat(blueprint.getName()).isEqualTo("nginx ops");
        assertThat(blueprint.getType()).isEqualTo(RecipeType.NGINX);
    }

    @Test
    void addBlueprintAction_BuildsStructureAndKeepsDescription() {
        RecipeBlueprint blueprint = asUser(alice, () -> blueprintService.createBlueprint(
                new CreateBlueprintInput("nginx ops", null, RecipeType.NGINX)));

        BlueprintAction action = asUser(alice, () -> blueprintService.addBlueprintAction(new AddBlueprintActionInput(
                blueprint.getId(), "restart nginx", "restart the service", true,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "systemctl"),
                        new ArgTokenInput(TokenKind.LITERAL, "restart"),
                        new ArgTokenInput(TokenKind.PARAM, "svc")),
                List.of(new ParamDefInput("svc", ParamKind.ALLOWED_SET, null, null, null,
                        List.of("nginx", "docker"))))));

        assertThat(action.getDescription()).isEqualTo("restart the service");
        assertThat(action.isSudo()).isTrue();
        assertThat(action.getArgTokens()).hasSize(3);
        assertThat(action.getParamDefs()).hasSize(1);
    }

    @Test
    void addBlueprintAction_WithUndeclaredParamToken_Rejected() {
        RecipeBlueprint blueprint = asUser(alice, () -> blueprintService.createBlueprint(
                new CreateBlueprintInput("nginx ops", null, RecipeType.NGINX)));

        assertThatThrownBy(() -> asUser(alice, () -> blueprintService.addBlueprintAction(new AddBlueprintActionInput(
                blueprint.getId(), "restart", null, false,
                List.of(new ArgTokenInput(TokenKind.PARAM, "svc")),
                List.of()))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void addBlueprintAction_WithUnreferencedParam_Rejected() {
        RecipeBlueprint blueprint = asUser(alice, () -> blueprintService.createBlueprint(
                new CreateBlueprintInput("nginx ops", null, RecipeType.NGINX)));
        AddBlueprintActionInput input = new AddBlueprintActionInput(
                blueprint.getId(), "restart", null, false,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "systemctl")),
                List.of(new ParamDefInput("svc", ParamKind.ALLOWED_SET, null, null, null, List.of("nginx"))));

        assertThatThrownBy(() -> asUser(alice, () -> blueprintService.addBlueprintAction(input)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void addBlueprintAction_WithEmptyAllowedSet_Rejected() {
        RecipeBlueprint blueprint = asUser(alice, () -> blueprintService.createBlueprint(
                new CreateBlueprintInput("nginx ops", null, RecipeType.NGINX)));
        AddBlueprintActionInput input = new AddBlueprintActionInput(
                blueprint.getId(), "restart", null, false,
                List.of(new ArgTokenInput(TokenKind.PARAM, "svc")),
                List.of(new ParamDefInput("svc", ParamKind.ALLOWED_SET, null, null, null, List.of())));

        assertThatThrownBy(() -> asUser(alice, () -> blueprintService.addBlueprintAction(input)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void addBlueprintAction_DuplicateName_Rejected() {
        RecipeBlueprint blueprint = asUser(alice, () -> blueprintService.createBlueprint(
                new CreateBlueprintInput("nginx ops", null, RecipeType.NGINX)));
        asUser(alice, () -> blueprintService.addBlueprintAction(new AddBlueprintActionInput(
                blueprint.getId(), "restart", null, false,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "systemctl")), List.of())));

        assertThatThrownBy(() -> asUser(alice, () -> blueprintService.addBlueprintAction(new AddBlueprintActionInput(
                blueprint.getId(), "restart", null, false,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "service")), List.of()))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void addBlueprintAction_CustomWithRelativePath_Rejected() {
        RecipeBlueprint blueprint = asUser(alice, () -> blueprintService.createBlueprint(
                new CreateBlueprintInput("deploy", null, RecipeType.CUSTOM)));

        assertThatThrownBy(() -> asUser(alice, () -> blueprintService.addBlueprintAction(new AddBlueprintActionInput(
                blueprint.getId(), "deploy", null, false,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "deploy.sh")), List.of()))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void addBlueprintAction_CustomWithAbsolutePath_Accepted() {
        RecipeBlueprint blueprint = asUser(alice, () -> blueprintService.createBlueprint(
                new CreateBlueprintInput("deploy", null, RecipeType.CUSTOM)));

        BlueprintAction action = asUser(alice, () -> blueprintService.addBlueprintAction(new AddBlueprintActionInput(
                blueprint.getId(), "deploy", "run the shared deploy script", false,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "/opt/app/deploy.sh")), List.of())));

        assertThat(action.getArgTokens()).hasSize(1);
    }

    @Test
    void editBlueprint_BumpsVersion() {
        RecipeBlueprint blueprint = asUser(alice, () -> blueprintService.createBlueprint(
                new CreateBlueprintInput("nginx ops", null, RecipeType.NGINX)));

        RecipeBlueprint edited = asUser(alice, () -> blueprintService.editBlueprint(blueprint.getId(),
                new EditBlueprintInput("nginx ops v2", "now with more", RecipeType.NGINX)));

        assertThat(edited.getVersion()).isEqualTo(2);
        assertThat(edited.getName()).isEqualTo("nginx ops v2");
    }

    @Test
    void editBlueprintAction_BumpsBlueprintVersion() {
        RecipeBlueprint blueprint = asUser(alice, () -> blueprintService.createBlueprint(
                new CreateBlueprintInput("nginx ops", null, RecipeType.NGINX)));
        BlueprintAction action = asUser(alice, () -> blueprintService.addBlueprintAction(new AddBlueprintActionInput(
                blueprint.getId(), "restart", null, false,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "systemctl")), List.of())));

        asUser(alice, () -> blueprintService.editBlueprintAction(action.getId(), new EditBlueprintActionInput(
                "restart", null, true,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "systemctl"),
                        new ArgTokenInput(TokenKind.LITERAL, "restart")), List.of())));

        RecipeBlueprint reloaded = asUser(alice, () -> blueprintService.requireBlueprint(blueprint.getId()));
        assertThat(reloaded.getVersion()).isEqualTo(2);
    }

    @Test
    void requireBlueprint_OfAnotherUser_Is404() {
        RecipeBlueprint blueprint = asUser(alice, () -> blueprintService.createBlueprint(
                new CreateBlueprintInput("nginx ops", null, RecipeType.NGINX)));

        assertThatThrownBy(() -> asUser(bob, () -> blueprintService.requireBlueprint(blueprint.getId())))
                .isInstanceOf(BlueprintNotFoundException.class);
    }

    private AppUser saveUser(String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setGoogleSub("dev|" + email);
        user.setName(email.substring(0, email.indexOf('@')));
        return users.save(user);
    }

    private <R> R asUser(AppUser user, Supplier<R> body) {
        return CurrentUser.runWhere(AuthContext.ui(user.getId(), user.getEmail()), body::get);
    }
}
