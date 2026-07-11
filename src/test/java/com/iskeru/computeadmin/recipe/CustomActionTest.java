package com.iskeru.computeadmin.recipe;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.ArgToken;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionService.CustomActionInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.ParamBinder;
import com.iskeru.computeadmin.recipe.service.ParamValidationException;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Custom-command recipes (spec-007): wrapping an existing on-box script as a
 * {@code CUSTOM} action that flows through the identical 004 gate and run-path
 * binding. Covers absolute-path validation, the path stored as the leading literal
 * token, a declared param binding as a later argv element, and the action being
 * gated by the ordinary approval state machine before it can bind.
 *
 * <p>The run path (spec-005) is not yet implemented; its binding half — the 004
 * {@link ParamBinder} the run path reuses verbatim — stands in for "runs through
 * the normal gate", exercised after the action is {@code APPROVED}.
 *
 * <p>spec-007.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({ActionService.class, RecipeService.class, MachineService.class,
        ApprovalService.class, ParamBinder.class})
class CustomActionTest {

    private static final String SCRIPT = "/home/ec2-user/app/minhabufunfa/run.sh";

    @Autowired
    private ActionService actionService;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ParamBinder paramBinder;

    @Autowired
    private MachineService machineService;

    @Autowired
    private AppUserRepository users;

    private AppUser alice;

    @BeforeEach
    void seedUser() {
        alice = saveUser("alice@example.com");
    }

    @Test
    void addCustomAction_WithRelativePath_IsRejected() {
        assertThatThrownBy(() -> asUser(alice, () -> addCustom("run.sh", List.of())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void addCustomAction_WithBlankPath_IsRejected() {
        assertThatThrownBy(() -> asUser(alice, () -> addCustom("   ", List.of())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void addCustomAction_StoresPathAsLeadingLiteralInCustomRecipe() {
        Action action = asUser(alice, () -> addCustom(SCRIPT, List.of()));

        assertThat(action.getApprovalState()).isEqualTo(ApprovalState.DRAFT);
        assertThat(action.getRecipe().getType()).isEqualTo(RecipeType.CUSTOM);
        List<ArgToken> tokens = orderedTokens(action);
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).getKind()).isEqualTo(TokenKind.LITERAL);
        assertThat(tokens.get(0).getValue()).isEqualTo(SCRIPT);
        assertThat(tokens.get(0).getPosition()).isZero();
    }

    @Test
    void addCustomAction_AppendsDeclaredParamAsLaterToken() {
        ParamDefInput env = new ParamDefInput("env", ParamKind.ALLOWED_SET, null, null, null,
                List.of("staging", "prod"));

        Action action = asUser(alice, () -> addCustom(SCRIPT, List.of(env)));

        List<ArgToken> tokens = orderedTokens(action);
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).getKind()).isEqualTo(TokenKind.LITERAL);
        assertThat(tokens.get(1).getKind()).isEqualTo(TokenKind.PARAM);
        assertThat(tokens.get(1).getValue()).isEqualTo("env");
        assertThat(action.getParamDefs()).extracting("name").containsExactly("env");
    }

    @Test
    void customAction_OnceApproved_BindsPathThenParamThroughRunGate() {
        ParamDefInput env = new ParamDefInput("env", ParamKind.ALLOWED_SET, null, null, null,
                List.of("staging", "prod"));
        Action action = asUser(alice, () -> addCustom(SCRIPT, List.of(env)));

        // The ordinary approval state machine gates the custom action like any other.
        asUser(alice, () -> approvalService.submitForApproval(action.getId()));
        Action approved = asUser(alice, () -> approvalService.approve(action.getId()));
        assertThat(approved.getApprovalState()).isEqualTo(ApprovalState.APPROVED);

        // The run-path binding (004 ParamBinder, reused verbatim by 005): fixed
        // literal path first, then the validated param value as a discrete argv element.
        List<String> argv = paramBinder.bind(approved, Map.of("env", "prod"));
        assertThat(argv).containsExactly(SCRIPT, "prod");
    }

    @Test
    void customAction_RejectsParamValueOutsideDeclaredSet() {
        ParamDefInput env = new ParamDefInput("env", ParamKind.ALLOWED_SET, null, null, null,
                List.of("staging", "prod"));
        Action action = asUser(alice, () -> addCustom(SCRIPT, List.of(env)));
        asUser(alice, () -> approvalService.submitForApproval(action.getId()));
        Action approved = asUser(alice, () -> approvalService.approve(action.getId()));

        assertThatThrownBy(() -> paramBinder.bind(approved, Map.of("env", "rm -rf /")))
                .isInstanceOf(ParamValidationException.class);
    }

    private Action addCustom(String scriptPath, List<ParamDefInput> paramDefs) {
        Machine machine = machineService.register(new RegisterMachineInput("host", 22, "root"));
        return actionService.addCustomAction(new CustomActionInput(
                machine.getId(), "run minhabufunfa", scriptPath, paramDefs, false));
    }

    private List<ArgToken> orderedTokens(Action action) {
        return action.getArgTokens().stream()
                .sorted(java.util.Comparator.comparingInt(ArgToken::getPosition))
                .toList();
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
