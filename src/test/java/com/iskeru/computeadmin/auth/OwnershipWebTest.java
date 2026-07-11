package com.iskeru.computeadmin.auth;

import com.iskeru.computeadmin.auth.api.AuthDtos;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-user isolation over the real HTTP surface (spec-011): unauthenticated
 * {@code /api} is 401; the dev Google bypass mints a distinct user per email;
 * user B cannot see or revoke user A's personal token (404, never 403); a tokenless
 * MCP session reaches no user data (only the spec-008 bootstrap tools) and MCP with
 * a user's token connects.
 *
 * <p>Machine/recipe/run ownership (also 404-on-cross-user) is enforced by the
 * retrofit in specs 003/004/005/010, once those entities exist; this test covers
 * the ownership boundary that is buildable at spec 011: the personal token.
 *
 * <p>spec-011.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OwnershipWebTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void tokens_WithoutJwt_Returns401() {
        ResponseEntity<String> response = rest.getForEntity("/api/tokens", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void googleLogin_DistinctEmails_MintDistinctUsers() {
        AuthDtos.Session a = login("a@example.com");
        AuthDtos.Session b = login("b@example.com");

        assertThat(a.user().id()).isNotEqualTo(b.user().id());
        assertThat(a.token()).isNotEqualTo(b.token());
    }

    @Test
    void userB_CannotSeeOrRevokeUserAsToken() {
        AuthDtos.Session a = login("owner-a@example.com");
        AuthDtos.Session b = login("owner-b@example.com");
        AuthDtos.CreatedTokenView aToken = createToken(a.token(), "a-laptop");

        // B lists: A's token is invisible.
        ResponseEntity<AuthDtos.TokenView[]> bList = rest.exchange(
                "/api/tokens", HttpMethod.GET, new HttpEntity<>(bearer(b.token())), AuthDtos.TokenView[].class);
        assertThat(bList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bList.getBody()).extracting(AuthDtos.TokenView::id).doesNotContain(aToken.id());

        // B revokes A's token id: 404, not 403 (existence never leaked).
        ResponseEntity<String> bRevoke = rest.exchange(
                "/api/tokens/" + aToken.id(), HttpMethod.DELETE, new HttpEntity<>(bearer(b.token())), String.class);
        assertThat(bRevoke.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void mcp_WithoutToken_ReachesNoUserData() {
        // spec-008: a tokenless session may connect (for self-setup) but every
        // data/run tool is refused — it reaches no user data (S8 preserved).
        try (McpSyncClient client = mcpClient(null)) {
            client.initialize();
            McpSchema.CallToolResult machines = client.callTool(
                    new McpSchema.CallToolRequest("list_machines", java.util.Map.of()));
            assertThat(machines.isError()).isTrue();
        }
    }

    @Test
    void mcp_WithUsersToken_Connects() {
        AuthDtos.Session a = login("agent@example.com");
        AuthDtos.CreatedTokenView token = createToken(a.token(), "agent");

        try (McpSyncClient client = mcpClient(token.token())) {
            client.initialize();
            McpSchema.ListToolsResult tools = client.listTools();
            assertThat(tools.tools()).extracting(McpSchema.Tool::name).contains("list_machines");
        }
    }

    // --- helpers ------------------------------------------------------------

    private AuthDtos.Session login(String email) {
        return rest.postForObject("/api/auth/google", new AuthDtos.GoogleLogin(email), AuthDtos.Session.class);
    }

    private AuthDtos.CreatedTokenView createToken(String jwt, String label) {
        return rest.postForObject(
                "/api/tokens",
                new HttpEntity<>(new AuthDtos.TokenCreate(label), bearer(jwt)),
                AuthDtos.CreatedTokenView.class);
    }

    private static HttpHeaders bearer(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return headers;
    }

    private McpSyncClient mcpClient(String personalToken) {
        HttpClientSseClientTransport.Builder transport = HttpClientSseClientTransport
                .builder("http://localhost:" + port)
                .sseEndpoint("/mcp/sse");
        if (personalToken != null) {
            transport.customizeRequest(req -> req.header("Authorization", "Bearer " + personalToken));
        }
        return McpClient.sync(transport.build()).requestTimeout(Duration.ofSeconds(20)).build();
    }
}
