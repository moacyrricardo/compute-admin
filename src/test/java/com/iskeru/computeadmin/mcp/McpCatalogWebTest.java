package com.iskeru.computeadmin.mcp;

import com.iskeru.computeadmin.auth.api.AuthDtos;
import com.iskeru.computeadmin.mcp.api.McpCatalogDtos.Catalog;
import com.iskeru.computeadmin.mcp.api.McpCatalogDtos.Tool;
import com.iskeru.computeadmin.mcp.api.McpCatalogDtos.ToolGroup;
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
 * The MCP-surface catalogue over the real HTTP surface (spec-012): the UI's
 * MCP-surface screen reads it from {@code GET /api/mcp/tools} instead of a
 * hardcoded JS list. Asserts it is {@code @Secured}, that it is grouped
 * Read/Create/Run/Bootstrap, that there is no approve tool, and — the guard against
 * drift — that its tool set is exactly the tools actually registered on the MCP
 * server.
 *
 * <p>spec-012.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpCatalogWebTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private List<McpTool> registeredTools;

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
    void tools_WithoutJwt_Is401() {
        ResponseEntity<String> response = rest.getForEntity("/api/mcp/tools", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tools_ForAuthedUser_ReturnsGroupedCatalogWithNoApproveTool() {
        AuthDtos.Session user = login("operator@example.com");

        ResponseEntity<Catalog> response = rest.exchange(
                "/api/mcp/tools", HttpMethod.GET, new HttpEntity<>(bearer(user.token())), Catalog.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Catalog catalog = response.getBody();
        assertThat(catalog).isNotNull();

        // Groups are exactly the four legible kinds, in order.
        assertThat(catalog.groups()).extracting(ToolGroup::group)
                .containsExactly("Read", "Create (never approves)", "Run", "Bootstrap");

        // run_action lives under Run; approval-shaped tools exist nowhere.
        assertThat(group(catalog, "Run")).extracting(Tool::name).containsExactly("run_action");
        assertThat(allNames(catalog)).noneMatch(name -> name.contains("approve"));
        assertThat(catalog.approveTool()).isFalse();

        // Every tool carries a signature + one-line, and the resources are surfaced.
        assertThat(allTools(catalog)).allSatisfy(t -> {
            assertThat(t.signature()).isNotBlank();
            assertThat(t.description()).isNotBlank();
        });
        assertThat(catalog.resources()).contains("app public SSH key", "run output");
    }

    @Test
    void catalog_CoversExactlyTheRegisteredTools() {
        AuthDtos.Session user = login("sync@example.com");
        Catalog catalog = rest.exchange(
                "/api/mcp/tools", HttpMethod.GET, new HttpEntity<>(bearer(user.token())), Catalog.class).getBody();
        assertThat(catalog).isNotNull();

        List<String> registered = registeredTools.stream()
                .map(t -> t.specification().tool().name())
                .toList();

        // The curated catalogue neither omits a real tool nor invents one.
        assertThat(allNames(catalog)).containsExactlyInAnyOrderElementsOf(registered);
    }

    private static List<Tool> group(Catalog catalog, String group) {
        return catalog.groups().stream()
                .filter(g -> g.group().equals(group))
                .flatMap(g -> g.tools().stream())
                .toList();
    }

    private static List<Tool> allTools(Catalog catalog) {
        return catalog.groups().stream().flatMap(g -> g.tools().stream()).toList();
    }

    private static List<String> allNames(Catalog catalog) {
        return allTools(catalog).stream().map(Tool::name).toList();
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
