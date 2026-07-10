package com.iskeru.computeadmin.mcp;

import com.iskeru.computeadmin.machine.service.MachineService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the MCP transport seam end to end: over the real HTTP/SSE servlet an MCP
 * client initializes, discovers {@code list_machines}, calls it, and gets an empty
 * result back. Confirms the raw-servlet-beside-RESTEasy wiring responds.
 *
 * <p>Runs under the {@code test} profile for a hermetic in-memory datasource, and
 * supplies a {@code @Primary} stub {@link MachineService} so the assertion doesn't
 * depend on the spec-002 stub's body.
 *
 * <p><strong>Scope note:</strong> this test does <em>not</em> prove that the
 * ambient {@code Actor} bound by {@code ActorScopeFilter} reaches a tool handler.
 * The MCP SDK runs sync tool callbacks on a Reactor {@code boundedElastic} thread,
 * not the Tomcat request thread, so {@code CurrentActor} is unbound inside a tool;
 * {@code list_machines} never reads it, so nothing here exercises that path. Actor
 * propagation into tools is unproven until a tool needs it (spec 008/011). See
 * specs/002-doing-mcp-transport-seam.md, "Known Gaps".
 *
 * <p>spec-002.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpSeamWebTest {

    @LocalServerPort
    private int port;

    @TestConfiguration
    static class StubMachineServiceConfig {

        @Bean
        @Primary
        MachineService stubMachineService() {
            return new MachineService() {
                @Override
                public List<String> list(String tag) {
                    return List.of();
                }
            };
        }
    }

    @Test
    void seam_ListsToolsAndCallsListMachines_RespondsWithEmptyResult() {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport
                .builder("http://localhost:" + port)
                .sseEndpoint("/mcp/sse")
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
}
