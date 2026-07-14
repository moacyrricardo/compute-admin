package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.auth.api.AuthDtos;
import com.iskeru.computeadmin.discovery.api.DiscoveryEnablementDtos.DiscoveryStateView;
import com.iskeru.computeadmin.discovery.api.DiscoveryEnablementDtos.FamilyView;
import com.iskeru.computeadmin.discovery.api.DiscoveryEnablementDtos.SetEnablementRequest;
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
 * The per-machine discovery-enablement REST surface (spec-035): the owner reads the
 * family toggles (docker default-off, the rest on) and flips one with PUT; an
 * unauthenticated call is 401; another user's machine is 404; an unknown family is 400.
 *
 * <p>spec-035.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DiscoveryEnablementWebTest {

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
    void discoveryState_ForTheOwner_ListsFamilies_DockerDefaultOff() {
        AuthDtos.Session owner = login("disc-state-owner@example.com");
        MachineDtos.MachineView machine = registerMachine(owner.token());

        ResponseEntity<DiscoveryStateView> response = rest.exchange(
                "/api/machines/" + machine.id() + "/discovery", HttpMethod.GET,
                new HttpEntity<>(bearer(owner.token())), DiscoveryStateView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DiscoveryStateView body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.machineId()).isEqualTo(machine.id());

        FamilyView docker = family(body, "DOCKER");
        assertThat(docker.enabled()).isFalse();
        assertThat(docker.defaultEnabled()).isFalse();
        assertThat(docker.note()).isNotBlank();
        assertThat(family(body, "NGINX").enabled()).isTrue();
    }

    @Test
    void setEnablement_EnablesDocker_ReflectedInState() {
        AuthDtos.Session owner = login("toggle@example.com");
        MachineDtos.MachineView machine = registerMachine(owner.token());

        ResponseEntity<DiscoveryStateView> put = rest.exchange(
                "/api/machines/" + machine.id() + "/discovery/docker", HttpMethod.PUT,
                new HttpEntity<>(new SetEnablementRequest(true), bearer(owner.token())),
                DiscoveryStateView.class);

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(family(put.getBody(), "DOCKER").enabled()).isTrue();

        // And a fresh GET confirms the toggle persisted.
        DiscoveryStateView state = rest.exchange(
                "/api/machines/" + machine.id() + "/discovery", HttpMethod.GET,
                new HttpEntity<>(bearer(owner.token())), DiscoveryStateView.class).getBody();
        assertThat(family(state, "DOCKER").enabled()).isTrue();
    }

    @Test
    void setEnablement_UnknownFamily_Is400() {
        AuthDtos.Session owner = login("bad-family@example.com");
        MachineDtos.MachineView machine = registerMachine(owner.token());

        ResponseEntity<String> response = rest.exchange(
                "/api/machines/" + machine.id() + "/discovery/bogus", HttpMethod.PUT,
                new HttpEntity<>(new SetEnablementRequest(true), bearer(owner.token())), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void discoveryState_WithoutJwt_Is401() {
        ResponseEntity<String> response = rest.exchange(
                "/api/machines/whatever/discovery", HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void discoveryState_OnAnotherUsersMachine_Is404() {
        AuthDtos.Session a = login("a-disc@example.com");
        AuthDtos.Session b = login("b-disc@example.com");
        MachineDtos.MachineView aMachine = registerMachine(a.token());

        ResponseEntity<String> response = rest.exchange(
                "/api/machines/" + aMachine.id() + "/discovery", HttpMethod.GET,
                new HttpEntity<>(bearer(b.token())), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void setEnablement_OnAnotherUsersMachine_Is404() {
        AuthDtos.Session a = login("a-set@example.com");
        AuthDtos.Session b = login("b-set@example.com");
        MachineDtos.MachineView aMachine = registerMachine(a.token());

        ResponseEntity<String> response = rest.exchange(
                "/api/machines/" + aMachine.id() + "/discovery/docker", HttpMethod.PUT,
                new HttpEntity<>(new SetEnablementRequest(true), bearer(b.token())), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static FamilyView family(DiscoveryStateView state, String key) {
        assertThat(state).isNotNull();
        return state.families().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
    }

    private MachineDtos.MachineView registerMachine(String jwt) {
        return rest.postForObject(
                "/api/machines",
                new HttpEntity<>(new MachineDtos.RegisterMachineRequest("host", "host", 22, "deploy"), bearer(jwt)),
                MachineDtos.MachineView.class);
    }

    private AuthDtos.Session login(String email) {
        ResponseEntity<AuthDtos.Session> reg = rest.postForEntity(
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
