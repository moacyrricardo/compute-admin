package com.iskeru.computeadmin.machine;

import com.iskeru.computeadmin.auth.api.AuthDtos;
import com.iskeru.computeadmin.machine.api.MachineDtos;
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
 * Machine registry over the real HTTP surface (spec-003): a UI-authenticated user
 * registers a machine and lists it back; user B gets 404 (not 403) on user A's
 * machine. A {@code @Primary} fake {@link SshExecutor} keeps the context free of
 * the real MINA adapter.
 *
 * <p>spec-003.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MachineWebTest {

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
                    return new ExecResult(0, "", "");
                }

                @Override
                public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
                    sink.onComplete(0);
                }
            };
        }
    }

    @Test
    void register_ThenList_RoundTripsForTheOwner() {
        AuthDtos.Session owner = login("owner@example.com");

        MachineDtos.MachineView created = rest.postForObject(
                "/api/machines",
                new HttpEntity<>(new MachineDtos.RegisterMachineRequest("web-host", "web-host", 2222, "deploy"), bearer(owner.token())),
                MachineDtos.MachineView.class);

        assertThat(created.id()).isNotBlank();
        assertThat(created.host()).isEqualTo("web-host");
        assertThat(created.port()).isEqualTo(2222);
        assertThat(created.loginUser()).isEqualTo("deploy");

        ResponseEntity<MachineDtos.MachineView[]> listed = rest.exchange(
                "/api/machines", HttpMethod.GET, new HttpEntity<>(bearer(owner.token())), MachineDtos.MachineView[].class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).extracting(MachineDtos.MachineView::id).contains(created.id());
    }

    @Test
    void get_OnAnotherUsersMachine_Is404() {
        AuthDtos.Session a = login("a-owner@example.com");
        AuthDtos.Session b = login("b-owner@example.com");

        MachineDtos.MachineView aMachine = rest.postForObject(
                "/api/machines",
                new HttpEntity<>(new MachineDtos.RegisterMachineRequest("a-host", "a-host", 22, "root"), bearer(a.token())),
                MachineDtos.MachineView.class);

        // B lists: A's machine is invisible.
        ResponseEntity<MachineDtos.MachineView[]> bList = rest.exchange(
                "/api/machines", HttpMethod.GET, new HttpEntity<>(bearer(b.token())), MachineDtos.MachineView[].class);
        assertThat(bList.getBody()).extracting(MachineDtos.MachineView::id).doesNotContain(aMachine.id());

        // B fetches A's machine id: 404, not 403.
        ResponseEntity<String> bGet = rest.exchange(
                "/api/machines/" + aMachine.id(), HttpMethod.GET, new HttpEntity<>(bearer(b.token())), String.class);
        assertThat(bGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void register_WithoutJwt_Is401() {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/machines", new MachineDtos.RegisterMachineRequest("nope", "nope", 22, "root"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
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
