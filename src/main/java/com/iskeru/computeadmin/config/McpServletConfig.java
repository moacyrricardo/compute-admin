package com.iskeru.computeadmin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.mcp.McpTool;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.function.BiFunction;

/**
 * Wires the MCP transport as a <strong>raw servlet beside RESTEasy</strong> on the
 * same Tomcat: {@link HttpServletSseServerTransportProvider} registered as a
 * {@link ServletRegistrationBean} at {@code /mcp/*}, with the MCP {@code Server}
 * built over it and every registered {@link McpTool} bean contributed to it. The
 * MCP endpoint never touches Spring MVC and never pulls in
 * {@code spring-ai-starter-mcp-server-webmvc}.
 *
 * <p>Since spec-011 the MCP surface is authenticated: {@code McpTokenAuthFilter}
 * validates a per-user personal token on {@code /mcp/*} and binds an
 * {@code AuthContext} before this servlet runs. spec-008 makes that binding reach
 * tool handlers by building the server with <strong>immediate execution</strong>
 * ({@code .immediateExecution(true)}). By default the MCP SDK (mcp 0.11.2) adapts a
 * sync tool via {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())},
 * so a tool would run on a Reactor pool thread and the thread-confined
 * {@code ScopedValue} bound by the filter would not follow. Immediate execution
 * skips that offload; because the servlet-SSE transport already blocks the request
 * thread on {@code session.handle(...).block()}, the tool runs on that same
 * (filter-bound) thread and {@code CurrentUser.require()} resolves the caller. This
 * is the recommended mode for a blocking transport.
 *
 * <p>Each tool is still wrapped here ({@link #secure}) to enforce the spec-008
 * bootstrap allowance: a tool that {@linkplain McpTool#requiresAuth() requires auth}
 * is refused as {@code unauthorized} when no caller is bound (a tokenless session),
 * so only the self-setup tools reach an unauthenticated agent.
 *
 * <p>spec-002; MCP auth added in spec-011; actor propagation + bootstrap gate in
 * spec-008.
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
                // Run tools on the (filter-bound) request thread so CurrentUser is in
                // scope inside a tool — see the class Javadoc. The servlet transport
                // is blocking, which is where immediate execution is appropriate.
                .immediateExecution(true)
                .tools(tools.stream().map(McpServletConfig::secure).toList())
                .build();
    }

    /**
     * Wraps a tool's handler to enforce the spec-008 bootstrap allowance. The caller
     * is bound by {@link McpTokenAuthFilter} and, under immediate execution, is in
     * scope on this thread. When no caller is bound (a tokenless bootstrap session),
     * a tool that {@linkplain McpTool#requiresAuth() requires auth} is refused as
     * {@code unauthorized}; only the self-setup tools run without a caller.
     */
    private static McpServerFeatures.SyncToolSpecification secure(McpTool tool) {
        McpServerFeatures.SyncToolSpecification spec = tool.specification();
        boolean requiresAuth = tool.requiresAuth();
        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
                spec.callHandler();
        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> wrapped =
                (exchange, request) -> {
                    if (requiresAuth && CurrentUser.optional().isEmpty()) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("{\"error\":\"unauthorized\"}")
                                .isError(true)
                                .build();
                    }
                    return handler.apply(exchange, request);
                };
        return new McpServerFeatures.SyncToolSpecification(spec.tool(), null, wrapped);
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
