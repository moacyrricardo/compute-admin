package com.iskeru.computeadmin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.mcp.McpTool;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the MCP transport as a <strong>raw servlet beside RESTEasy</strong> on the
 * same Tomcat: {@link HttpServletSseServerTransportProvider} registered as a
 * {@link ServletRegistrationBean} at {@code /mcp/*}, with the MCP {@code Server}
 * built over it and every registered {@link McpTool} bean contributed to it. The
 * MCP endpoint never touches Spring MVC and never pulls in
 * {@code spring-ai-starter-mcp-server-webmvc}.
 *
 * <p>spec-002.
 */
@Configuration
public class McpServletConfig {

    /** Servlet mount point; the actor filter keys the {@code MCP} label off this. */
    static final String MCP_PATH = "/mcp";

    /** SSE (server→client) endpoint the client opens to establish a session. */
    static final String SSE_ENDPOINT = MCP_PATH + "/sse";

    /** Message (client→server) endpoint the server advertises over the SSE stream. */
    static final String MESSAGE_ENDPOINT = MCP_PATH + "/message";

    @Bean
    public HttpServletSseServerTransportProvider mcpTransportProvider(ObjectMapper objectMapper) {
        return HttpServletSseServerTransportProvider.builder()
                .objectMapper(objectMapper)
                .sseEndpoint(SSE_ENDPOINT)
                .messageEndpoint(MESSAGE_ENDPOINT)
                .build();
    }

    /**
     * Builds the MCP server over the transport. {@code build()} installs the
     * server's session factory onto the provider, so this must be created before
     * the servlet serves a request — guaranteed because both are eager singletons
     * instantiated before Tomcat starts accepting connections.
     */
    @Bean
    public McpSyncServer mcpSyncServer(HttpServletSseServerTransportProvider transportProvider,
                                       List<McpTool> tools,
                                       @Value("${ca.version:dev}") String version) {
        return McpServer.sync(transportProvider)
                .serverInfo("compute-admin", version)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tools.stream().map(McpTool::specification).toList())
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletSseServerTransportProvider> mcpServletRegistration(
            HttpServletSseServerTransportProvider transportProvider) {
        ServletRegistrationBean<HttpServletSseServerTransportProvider> registration =
                new ServletRegistrationBean<>(transportProvider, MCP_PATH + "/*");
        registration.setName("mcpTransport");
        registration.setAsyncSupported(true);
        return registration;
    }
}
