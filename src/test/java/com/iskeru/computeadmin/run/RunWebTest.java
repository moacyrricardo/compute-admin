package com.iskeru.computeadmin.run;

import com.iskeru.computeadmin.auth.api.AuthDtos;
import com.iskeru.computeadmin.machine.api.MachineDtos;
import com.iskeru.computeadmin.recipe.api.RecipeDtos;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import com.iskeru.computeadmin.run.api.RunDtos;
import com.iskeru.computeadmin.run.model.RunStatus;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run engine over the real HTTP surface (spec-005): a UI-authenticated user
 * runs an approved action, then consumes the {@code text/event-stream} output to
 * completion; another user gets 404 on that run. A {@code @Primary} fake
 * {@link SshExecutor} emits deterministic output instead of touching a real target.
 *
 * <p>spec-005.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RunWebTest {

    @Autowired
    private TestRestTemplate rest;

    @TestConfiguration
    static class FakeSshConfig {

        @Bean
        @Primary
        SshExecutor fakeSshExecutor() {
            return new SshExecutor() {
                @Override
                public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
                    return new ExecResult(0, "hello from run\n", "");
                }

                @Override
                public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
                    sink.onStdout("hello from run\n");
                    sink.onComplete(0);
                }
            };
        }
    }

    @Test
    void run_ThenStreamOutput_CompletesForTheOwner() {
        AuthDtos.Session owner = login("run-owner@example.com");
        String actionId = approvedAction(owner);
        String machineId = machineOf(owner);

        RunDtos.RunView started = rest.postForObject(
                "/api/runs",
                new HttpEntity<>(new RunDtos.RunRequest(machineId, actionId, Map.of("svc", "nginx")), bearer(owner.token())),
                RunDtos.RunView.class);
        assertThat(started.id()).isNotBlank();

        String stream = consumeSse("/api/runs/" + started.id() + "/output", owner.token());
        assertThat(stream).contains("hello from run");
        assertThat(stream).contains("exit");

        RunDtos.RunView finished = rest.exchange(
                "/api/runs/" + started.id(), HttpMethod.GET, new HttpEntity<>(bearer(owner.token())),
                RunDtos.RunView.class).getBody();
        assertThat(finished.status()).isEqualTo(RunStatus.DONE);
        assertThat(finished.exitCode()).isZero();
    }

    @Test
    void streamOutput_OfAnotherUsersRun_Is404() {
        AuthDtos.Session a = login("run-a@example.com");
        AuthDtos.Session b = login("run-b@example.com");
        String actionId = approvedAction(a);
        String machineId = machineOf(a);

        RunDtos.RunView aRun = rest.postForObject(
                "/api/runs",
                new HttpEntity<>(new RunDtos.RunRequest(machineId, actionId, Map.of("svc", "nginx")), bearer(a.token())),
                RunDtos.RunView.class);

        ResponseEntity<String> bGet = rest.exchange(
                "/api/runs/" + aRun.id(), HttpMethod.GET, new HttpEntity<>(bearer(b.token())), String.class);
        assertThat(bGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void run_UnapprovedAction_Is409() {
        AuthDtos.Session owner = login("run-unapproved@example.com");
        String machineId = registerMachine(owner);
        String recipeId = createRecipe(owner, machineId);
        String actionId = addAction(owner, recipeId); // left as DRAFT

        ResponseEntity<String> response = rest.exchange(
                "/api/runs", HttpMethod.POST,
                new HttpEntity<>(new RunDtos.RunRequest(machineId, actionId, Map.of("svc", "nginx")), bearer(owner.token())),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // --- setup helpers ------------------------------------------------------

    private String approvedAction(AuthDtos.Session s) {
        String machineId = registerMachine(s);
        String recipeId = createRecipe(s, machineId);
        String actionId = addAction(s, recipeId);
        post("/api/actions/" + actionId + "/submit", null, s.token(), RecipeDtos.ActionView.class);
        post("/api/actions/" + actionId + "/approve", null, s.token(), RecipeDtos.ActionView.class);
        return actionId;
    }

    private String machineOf(AuthDtos.Session s) {
        // The run request needs the machine id; recompute by listing the user's machines.
        MachineDtos.MachineView[] machines = rest.exchange(
                "/api/machines", HttpMethod.GET, new HttpEntity<>(bearer(s.token())),
                MachineDtos.MachineView[].class).getBody();
        return machines[0].id();
    }

    private String registerMachine(AuthDtos.Session s) {
        return post("/api/machines",
                new MachineDtos.RegisterMachineRequest("run-host", "run-host", 22, "root"),
                s.token(), MachineDtos.MachineView.class).id();
    }

    private String createRecipe(AuthDtos.Session s, String machineId) {
        return post("/api/recipes",
                new RecipeDtos.RecipeRequest(machineId, "nginx", "nginx ops", RecipeType.NGINX),
                s.token(), RecipeDtos.RecipeView.class).id();
    }

    private String addAction(AuthDtos.Session s, String recipeId) {
        RecipeDtos.AddActionRequest body = new RecipeDtos.AddActionRequest(
                recipeId, "restart nginx", "restart a service", true,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "systemctl"),
                        new ArgTokenInput(TokenKind.LITERAL, "restart"),
                        new ArgTokenInput(TokenKind.PARAM, "svc")),
                List.of(new ParamDefInput("svc", ParamKind.ALLOWED_SET, null, null, null,
                        List.of("nginx", "docker", "mysql"))));
        return post("/api/actions", body, s.token(), RecipeDtos.ActionView.class).id();
    }

    // --- http helpers -------------------------------------------------------

    private String consumeSse(String path, String token) {
        return rest.execute(path, HttpMethod.GET,
                request -> {
                    request.getHeaders().setBearerAuth(token);
                    request.getHeaders().setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                },
                response -> {
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            body.append(line).append('\n');
                        }
                    }
                    return body.toString();
                });
    }

    private <T> T post(String path, Object body, String token, Class<T> type) {
        return rest.postForObject(path, new HttpEntity<>(body, bearer(token)), type);
    }

    private AuthDtos.Session login(String email) {
// Register on first use, fall back to login if the email already exists in
        // the shared in-memory DB (register is not idempotent, unlike the old
        // Google find-or-create). Either way the caller gets a live session.
        org.springframework.http.ResponseEntity<AuthDtos.Session> reg = rest.postForEntity(
                "/api/auth/register",
                new AuthDtos.RegisterRequest(email, "password-123", null), AuthDtos.Session.class);
        if (reg.getStatusCode().is2xxSuccessful() && reg.getBody() != null && reg.getBody().token() != null) {
            return reg.getBody();
        }
        return rest.postForObject("/api/auth/login",
                new AuthDtos.LoginRequest(email, "password-123"), AuthDtos.Session.class);
    }

    private static HttpHeaders bearer(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return headers;
    }
}
