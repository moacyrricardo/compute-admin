package com.iskeru.computeadmin.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.iskeru.computeadmin.auth.service.DuplicateEmailException;
import com.iskeru.computeadmin.auth.service.PairingNotFoundException;
import com.iskeru.computeadmin.auth.service.TokenNotFoundException;
import com.iskeru.computeadmin.blueprint.service.BlueprintActionNotFoundException;
import com.iskeru.computeadmin.blueprint.service.BlueprintNotFoundException;
import com.iskeru.computeadmin.machine.service.MachineAlreadyRegisteredException;
import com.iskeru.computeadmin.machine.service.MachineNameTakenException;
import com.iskeru.computeadmin.machine.service.MachineNotFoundException;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.service.ActionNotApprovedException;
import com.iskeru.computeadmin.recipe.service.ActionNotFoundException;
import com.iskeru.computeadmin.recipe.service.IllegalApprovalTransitionException;
import com.iskeru.computeadmin.recipe.service.ParamValidationException;
import com.iskeru.computeadmin.recipe.service.RecipeNotFoundException;
import com.iskeru.computeadmin.recipe.service.ScriptUnreadableException;
import com.iskeru.computeadmin.run.service.ActionModifiedException;
import com.iskeru.computeadmin.run.service.RunNotFoundException;
import com.iskeru.computeadmin.run.service.ScriptModifiedException;
import com.iskeru.computeadmin.ssh.SshExecutionException;
import com.iskeru.computeadmin.ssh.SshTarget;
import jakarta.ws.rs.core.Response;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Locks the wire contract every {@link AppException} subclass carries: the HTTP
 * status and the {@code {"error":code}} body the deleted {@code *ExceptionMapper}s
 * used to produce. If a status or code drifts, this fails.
 *
 * <p>spec-046.
 */
class AppExceptionTest {

    private static Stream<Arguments> exceptions() {
        return Stream.of(
                // 8× NOT_FOUND
                Arguments.of(new MachineNotFoundException("m1"), 404, "machine_not_found"),
                Arguments.of(new RecipeNotFoundException("r1"), 404, "recipe_not_found"),
                Arguments.of(new ActionNotFoundException("a1"), 404, "action_not_found"),
                Arguments.of(new RunNotFoundException("run1"), 404, "run_not_found"),
                Arguments.of(new TokenNotFoundException("t1"), 404, "token_not_found"),
                Arguments.of(new PairingNotFoundException("uc1"), 404, "pairing_not_found"),
                Arguments.of(new BlueprintNotFoundException("b1"), 404, "blueprint_not_found"),
                Arguments.of(new BlueprintActionNotFoundException("ba1"), 404, "blueprint_action_not_found"),
                // 8× CONFLICT
                Arguments.of(new MachineAlreadyRegisteredException("h", 22, "u"), 409, "machine_already_registered"),
                Arguments.of(new MachineNameTakenException("web-1"), 409, "machine_name_taken"),
                Arguments.of(new DuplicateEmailException("a@b.com"), 409, "email_taken"),
                Arguments.of(
                        new IllegalApprovalTransitionException(ApprovalState.DRAFT, "approve"),
                        409, "illegal_approval_transition"),
                Arguments.of(new ActionNotApprovedException("a1"), 409, "action_not_approved"),
                Arguments.of(new ActionModifiedException("a1"), 409, "action_modified"),
                Arguments.of(new ScriptModifiedException("a1"), 409, "script_modified"),
                Arguments.of(new ScriptUnreadableException("a1"), 409, "script_unreadable"),
                // 1× UNAUTHORIZED, 1× BAD_REQUEST
                Arguments.of(new UnauthorizedException(), 401, "unauthorized"),
                Arguments.of(new ParamValidationException("bad"), 400, "param_validation_failed"));
    }

    @ParameterizedTest
    @MethodSource("exceptions")
    void eachException_carriesItsStatusAndCode(AppException ex, int status, String code) {
        Response response = ex.getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        ErrorResponse body = (ErrorResponse) response.getEntity();
        assertThat(body.error()).isEqualTo(code);
        assertThat(body.detail()).isNull();
    }

    @Test
    void sshExecutionException_is502WithMessageAsDetail() {
        SshExecutionException ex = new SshExecutionException(new SshTarget("host", 22, "admin"), new RuntimeException());

        Response response = ex.getResponse();
        assertThat(response.getStatus()).isEqualTo(502);
        ErrorResponse body = (ErrorResponse) response.getEntity();
        assertThat(body.error()).isEqualTo("ssh_failed");
        assertThat(body.detail()).isEqualTo("SSH command failed against admin@host:22");
        // The detail is also the exception message the run-output capture reads.
        assertThat(ex.getMessage()).isEqualTo("SSH command failed against admin@host:22");
    }

    @Test
    void unauthorizedException_keepsInternalMessageOffTheWire() {
        UnauthorizedException ex = new UnauthorizedException("Invalid email or password");

        ErrorResponse body = (ErrorResponse) ex.getResponse().getEntity();
        assertThat(body.error()).isEqualTo("unauthorized");
        assertThat(body.detail()).isNull();
        assertThat(ex.getMessage()).isEqualTo("Invalid email or password");
    }
}
