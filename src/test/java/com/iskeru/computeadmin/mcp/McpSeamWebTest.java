package com.iskeru.computeadmin.mcp;

import com.iskeru.computeadmin.auth.api.AuthDtos;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * Proves the MCP transport seam end to end <em>through the personal-token auth
 * filter</em> (spec-011): with a valid token an MCP client initializes, discovers
 * {@code list_machines}, calls it, and gets an empty result back.
 *
 * <p>Runs under the {@code test} profile for a hermetic in-memory datasource and
 * the dev Google bypass, and supplies a {@code @Primary} stub {@link
 * MachineService} so the assertion doesn't depend on the spec-002 stub's body.
 *
 * <p><strong>Scope note:</strong> this test only proves the transport/auth seam; a
 * {@code @Primary} stub {@code MachineService} makes {@code list_machines} return
 * {@code []} without reading {@code CurrentUser}. That the bound {@code AuthContext}
 * actually reaches a tool handler (the spec-008 actor fix) is proven by
 * {@code McpToolsWebTest}, whose tools all scope to the token's user.
 *
 * <p>spec-011.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpSeamWebTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @TestConfiguration
    static class StubMachineServiceConfig {

        @Bean
        @Primary
        MachineService stubMachineService() {
            return new MachineService(null, null, null) {
                @Override
                public List<Machine> list(String tag) {
                    return List.of();
                }
            };
        }
    }

    @Test
    void seam_WithValidToken_ListsToolsAndCallsListMachines() {
        String personalToken = provisionPersonalToken("seam@example.com");

        HttpClientSseClientTransport transport = HttpClientSseClientTransport
                .builder("http://localhost:" + port)
                .sseEndpoint("/mcp/sse")
                .customizeRequest(req -> req.header("Authorization", "Bearer " + personalToken))
                .build();

        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .build()) {
            client.initialize();

            McpSchema.ListToolsResult tools = client.listTools();
            assertThat(tools.tools())
                    .extracting(McpSchema.Tool::name)
                    .contains("list_machines");

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("list_machines", Map.of()));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
            assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("[]");
        }
    }

    /** Registers an email+password user and mints a personal token for MCP. */
    private String provisionPersonalToken(String email) {
        AuthDtos.Session session = rest.postForObject(
                "/api/auth/register", new AuthDtos.RegisterRequest(email, "password-123", null), AuthDtos.Session.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(session.token());
        AuthDtos.CreatedTokenView created = rest.postForObject(
                "/api/tokens",
                new HttpEntity<>(new AuthDtos.TokenCreate("mcp-seam"), headers),
                AuthDtos.CreatedTokenView.class);
        return created.token();
    }
}
