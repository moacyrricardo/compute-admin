package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.auth.api.AuthDtos;
import com.iskeru.computeadmin.discovery.api.DiscoveryDtos;
import com.iskeru.computeadmin.machine.api.MachineDtos;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recipe discovery over the real HTTP surface (spec-006): a UI-authenticated owner
 * discovers recipes on his machine and gets pending proposals back; an
 * unauthenticated call is 401; discovery on another user's machine is 404. A
 * {@code @Primary} fake {@link SshExecutor} returns canned nginx probe output.
 *
 * <p>spec-006.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DiscoveryWebTest {

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
                    if (argv.equals(List.of("command", "-v", "nginx"))) {
                        return new ExecResult(0, "/usr/sbin/nginx", "");
                    }
                    if (argv.equals(List.of("ls", "/etc/nginx/sites-available"))
                            || argv.equals(List.of("ls", "/etc/nginx/sites-enabled"))) {
                        return new ExecResult(0, "default\n", "");
                    }
                    if (argv.equals(List.of("nginx", "-t"))) {
                        return new ExecResult(0, "ok", "");
                    }
                    return new ExecResult(1, "", "");
                }

                @Override
                public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
                    sink.onComplete(0);
                }
            };
        }
    }

    @Test
    void discover_ForTheOwner_ReturnsPendingProposals() {
        AuthDtos.Session owner = login("owner@example.com");
        MachineDtos.MachineView machine = registerMachine(owner.token());

        ResponseEntity<DiscoveryDtos.DiscoveryResult> response = rest.exchange(
                "/api/machines/" + machine.id() + "/discover", HttpMethod.POST,
                new HttpEntity<>(bearer(owner.token())), DiscoveryDtos.DiscoveryResult.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DiscoveryDtos.DiscoveryResult body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.machineId()).isEqualTo(machine.id());
        assertThat(body.recipes()).isNotEmpty();

        DiscoveryDtos.ProposedRecipeView nginx = body.recipes().stream()
                .filter(r -> r.recipe().name().equals("nginx"))
                .findFirst().orElseThrow();
        assertThat(nginx.actions()).isNotEmpty();
        assertThat(nginx.actions()).allSatisfy(action -> {
            assertThat(action.approvalState()).isEqualTo(ApprovalState.PENDING_APPROVAL);
            assertThat(action.pendingApproval()).isTrue();
        });
    }

    @Test
    void discover_WithoutJwt_Is401() {
        ResponseEntity<String> response = rest.exchange(
                "/api/machines/whatever/discover", HttpMethod.POST, HttpEntity.EMPTY, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void discover_OnAnotherUsersMachine_Is404() {
        AuthDtos.Session a = login("a-owner@example.com");
        AuthDtos.Session b = login("b-owner@example.com");
        MachineDtos.MachineView aMachine = registerMachine(a.token());

        ResponseEntity<String> response = rest.exchange(
                "/api/machines/" + aMachine.id() + "/discover", HttpMethod.POST,
                new HttpEntity<>(bearer(b.token())), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private MachineDtos.MachineView registerMachine(String jwt) {
        return rest.postForObject(
                "/api/machines",
                new HttpEntity<>(new MachineDtos.RegisterMachineRequest("host", 22, "deploy"), bearer(jwt)),
                MachineDtos.MachineView.class);
    }

    private AuthDtos.Session login(String email) {
        return rest.postForObject("/api/auth/google", new AuthDtos.GoogleLogin(email), AuthDtos.Session.class);
    }

    private static HttpHeaders bearer(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return headers;
    }
}
