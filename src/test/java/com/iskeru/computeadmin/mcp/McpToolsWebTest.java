package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.auth.api.AuthDtos;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec-008 MCP write/run surface end to end through the personal-token filter:
 * register a machine → add a recipe → add an action <em>all over MCP</em>;
 * {@code list_actions} marks the action {@code pending_approval}; {@code run_action}
 * on the unapproved action is refused; the owner approves over <strong>REST</strong>;
 * then {@code run_action} succeeds and {@code get_run} shows the completed run. This
 * proves the create-open / approve-REST-only / run-when-approved invariant, and — by
 * every tool scoping to the token's user — that the caller is propagated to the tool
 * handler thread (spec-008 actor fix).
 *
 * <p>A {@code @Primary} fake {@link SshExecutor} emits deterministic output.
 *
 * <p>spec-008.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpToolsWebTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @TestConfiguration
    static class FakeSshConfig {
        @Bean
        @Primary
        SshExecutor fakeSshExecutor() {
            return new SshExecutor() {
                @Override
                public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
                    return new ExecResult(0, "hello from mcp run\n", "");
                }

                @Override
                public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
                    sink.onStdout("hello from mcp run\n");
                    sink.onComplete(0);
                }
            };
        }
    }

    @Test
    void createOverMcp_approveOverRest_thenRunSucceeds() throws Exception {
        AuthDtos.Session owner = login("mcp-tools@example.com");
        String token = mintToken(owner);

        try (McpSyncClient client = connect(token)) {
            client.initialize();

            // --- create over MCP: register machine → recipe → action ---
            JsonNode machine = callJson(client, "register_machine",
                    Map.of("host", "mcp-host", "port", 22, "loginUser", "root"));
            String machineId = machine.get("id").asText();
            assertThat(machineId).isNotBlank();

            // Actor propagation: the machine just created is visible to list_machines,
            // which scopes to CurrentUser — proof the token's user is bound in-tool.
            JsonNode machines = callJson(client, "list_machines", Map.of());
            assertThat(machines.isArray()).isTrue();
            assertThat(machines).anySatisfy(m -> assertThat(m.get("id").asText()).isEqualTo(machineId));

            JsonNode recipe = callJson(client, "add_recipe",
                    Map.of("machineId", machineId, "name", "echo", "type", "CUSTOM"));
            String recipeId = recipe.get("id").asText();

            JsonNode action = callJson(client, "add_action", Map.of(
                    "recipeId", recipeId,
                    "name", "say",
                    "description", "echo a greeting",
                    "sudo", false,
                    "argTokens", List.of(
                            Map.of("kind", "LITERAL", "value", "echo"),
                            Map.of("kind", "PARAM", "value", "msg")),
                    "paramDefs", List.of(
                            Map.of("name", "msg", "kind", "ALLOWED_SET",
                                    "allowedValues", List.of("hi", "bye")))));
            String actionId = action.get("id").asText();
            assertThat(action.get("approvalState").asText()).isEqualTo("DRAFT");

            // --- list_actions marks it pending_approval ---
            JsonNode actions = callJson(client, "list_actions",
                    Map.of("machineId", machineId, "recipeId", recipeId));
            assertThat(actions).hasSize(1);
            assertThat(actions.get(0).get("pending_approval").asBoolean()).isTrue();

            // --- run_action on the unapproved action is refused ---
            McpSchema.CallToolResult refused = client.callTool(new McpSchema.CallToolRequest(
                    "run_action", Map.of("machineId", machineId, "actionId", actionId,
                            "params", Map.of("msg", "hi"))));
            assertThat(refused.isError()).isTrue();

            // --- approve over REST (UI-only path; there is no approve tool) ---
            approve(owner, actionId);

            // --- now run_action succeeds ---
            JsonNode ran = callJson(client, "run_action", Map.of(
                    "machineId", machineId, "actionId", actionId, "params", Map.of("msg", "hi")));
            String runId = ran.get("runId").asText();
            assertThat(runId).isNotBlank();
            assertThat(ran.get("status").asText()).isEqualTo("DONE");
            assertThat(ran.get("exitCode").asInt()).isZero();

            // --- get_run reflects the completed run ---
            JsonNode fetched = callJson(client, "get_run", Map.of("runId", runId));
            assertThat(fetched.get("status").asText()).isEqualTo("DONE");
            assertThat(fetched.get("stdout").asText()).contains("hello from mcp run");
        }
    }

    @Test
    void tokenlessSession_reachesOnlyBootstrapTools() throws Exception {
        try (McpSyncClient client = connectTokenless()) {
            client.initialize();

            // begin_setup is reachable without a token and yields a deviceCode.
            JsonNode begin = callJson(client, "begin_setup", Map.of());
            assertThat(begin.get("deviceCode").asText()).isNotBlank();
            assertThat(begin.get("userCode").asText()).isNotBlank();

            // A data tool is refused for a tokenless session.
            McpSchema.CallToolResult machines = client.callTool(
                    new McpSchema.CallToolRequest("list_machines", Map.of()));
            assertThat(machines.isError()).isTrue();
            assertThat(text(machines)).contains("unauthorized");
        }
    }

    // --- helpers ------------------------------------------------------------

    private JsonNode callJson(McpSyncClient client, String tool, Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, args));
        assertThat(result.isError()).as("tool %s errored: %s", tool, text(result)).isNotEqualTo(Boolean.TRUE);
        return json.readTree(text(result));
    }

    private static String text(McpSchema.CallToolResult result) {
        assertThat(result.content()).isNotEmpty();
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private McpSyncClient connect(String token) {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport
                .builder("http://localhost:" + port)
                .sseEndpoint("/mcp/sse")
                .customizeRequest(req -> req.header("Authorization", "Bearer " + token))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
    }

    private McpSyncClient connectTokenless() {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport
                .builder("http://localhost:" + port)
                .sseEndpoint("/mcp/sse")
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
    }

    private AuthDtos.Session login(String email) {
        return rest.postForObject("/api/auth/google", new AuthDtos.GoogleLogin(email), AuthDtos.Session.class);
    }

    private String mintToken(AuthDtos.Session session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(session.token());
        AuthDtos.CreatedTokenView created = rest.postForObject(
                "/api/tokens", new HttpEntity<>(new AuthDtos.TokenCreate("mcp-tools"), headers),
                AuthDtos.CreatedTokenView.class);
        return created.token();
    }

    private void approve(AuthDtos.Session session, String actionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(session.token());
        rest.postForObject("/api/actions/" + actionId + "/submit",
                new HttpEntity<>(null, headers), String.class);
        rest.postForObject("/api/actions/" + actionId + "/approve",
                new HttpEntity<>(null, headers), String.class);
    }
}
