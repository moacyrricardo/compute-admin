package com.iskeru.computeadmin.mcp;

import com.iskeru.computeadmin.ssh.KeyService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Static MCP resource exposing the app's <strong>public</strong> SSH key at
 * {@code ca://app/ssh-public-key}. Delegates to {@link KeyService#publicKeyOpenSsh()}
 * and returns it in {@code authorized_keys} (OpenSSH) form so an agent can install
 * it on a target it is about to register. Only the public half is exposed; the
 * private key never leaves the box (S2). Touches no repository.
 *
 * <p>spec-008.
 */
@Component
public class PublicKeyResource implements McpResource {

    static final String URI = "ca://app/ssh-public-key";

    private final KeyService keyService;

    public PublicKeyResource(KeyService keyService) {
        this.keyService = keyService;
    }

    @Override
    public McpServerFeatures.SyncResourceSpecification specification() {
        McpSchema.Resource resource = McpSchema.Resource.builder()
                .uri(URI)
                .name("app-ssh-public-key")
                .title("App SSH public key")
                .description("The compute-admin app's public SSH key, in authorized_keys form. "
                        + "Install it on a target host to let the app connect.")
                .mimeType("text/plain")
                .build();
        return new McpServerFeatures.SyncResourceSpecification(resource, this::read);
    }

    private McpSchema.ReadResourceResult read(McpSyncServerExchange exchange,
                                              McpSchema.ReadResourceRequest request) {
        McpSchema.TextResourceContents contents =
                new McpSchema.TextResourceContents(request.uri(), "text/plain", keyService.publicKeyOpenSsh());
        return new McpSchema.ReadResourceResult(List.of(contents));
    }
}
