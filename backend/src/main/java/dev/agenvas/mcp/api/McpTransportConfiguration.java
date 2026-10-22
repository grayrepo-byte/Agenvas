package dev.agenvas.mcp.api;

import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.application.McpToolPolicy;
import dev.agenvas.mcp.application.McpToolService;
import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.i18n.ApiMessages;
import dev.agenvas.shared.i18n.SupportedLocales;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** Explicit protocol adapter: internal Agent tools are never discovered or published as MCP tools. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agenvas.mcp.enabled", havingValue = "true", matchIfMissing = true)
public class McpTransportConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(McpTransportConfiguration.class);
    private static final String PRINCIPAL = "agenvas.mcp.principal";
    private static final String LOCALE = "agenvas.mcp.locale";

    @Bean
    WebMvcStatelessServerTransport mcpHttpTransport() {
        return WebMvcStatelessServerTransport.builder()
                .messageEndpoint("/mcp")
                .jsonMapper(McpJsonDefaults.getMapper())
                // Native clients omit Origin. Cross-origin browser requests are rejected, including on LAN installs.
                .securityValidator(DefaultServerTransportSecurityValidator.builder().allowedOrigins(List.of()).build())
                .contextExtractor(request -> {
                    Authentication authentication = request.principal()
                            .filter(Authentication.class::isInstance).map(Authentication.class::cast)
                            .orElseThrow(McpTokenService::unauthenticated);
                    if (!(authentication.getPrincipal() instanceof McpPrincipal principal)) {
                        throw McpTokenService.unauthenticated();
                    }
                    return McpTransportContext.create(Map.of(PRINCIPAL, principal, LOCALE,
                            SupportedLocales.negotiate(request.headers().firstHeader("Accept-Language"))));
                }).build();
    }

    @Bean
    RouterFunction<ServerResponse> mcpRoutes(WebMvcStatelessServerTransport transport,
            McpStatelessSyncServer server) {
        // Depending on the server ensures its handlers exist before the route is published.
        return transport.getRouterFunction().filter((request, next) -> {
            List<String> versions = request.headers().header("MCP-Protocol-Version");
            if (versions.size() > 1 || (!versions.isEmpty() && !transport.protocolVersions().contains(versions.getFirst()))) {
                return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
                        .header("Cache-Control", "no-store").body(Map.of("error", "Unsupported MCP protocol version"));
            }
            ServerResponse response = next.handle(request);
            // SDK parser errors may contain excerpts of a private request. Keep status and strip diagnostic bodies.
            if (response.statusCode().isError()) {
                return ServerResponse.status(response.statusCode()).contentType(MediaType.APPLICATION_JSON)
                        .headers(headers -> {
                            // A stateless server offers POST only; keep 405 useful to Streamable HTTP clients.
                            if (response.statusCode().value() == 405) headers.set("Allow", "POST");
                        })
                        .header("Cache-Control", "no-store").body(Map.of("error", "Invalid MCP request"));
            }
            return response;
        });
    }

    @Bean(destroyMethod = "close")
    McpStatelessSyncServer mcpServer(WebMvcStatelessServerTransport transport, McpToolPolicy policy,
            McpTokenService tokens, McpToolService tools, ObjectMapper mapper, ApiMessages messages) {
        McpJsonMapper json = McpJsonDefaults.getMapper();
        var builder = McpServer.sync(new ScopedTransport(transport, policy, tokens))
                .serverInfo("agenvas", "1.0")
                .instructions("Use list_projects to select a project. Writes require a stable commandId; replay the same "
                        + "commandId and arguments after a lost response. Media generation directly accepts tasks with "
                        + "no additional approval. Poll read_task_status; UNKNOWN must never be automatically resubmitted.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .jsonMapper(json)
                // The catalog validator also enforces application limits and never echoes arbitrary input in errors.
                .validateToolInputs(false);
        for (var definition : policy.definitions()) {
            String name = definition.path("name").asText();
            McpScope scope = policy.requiredScope(name);
            boolean readOnly = scope == McpScope.READ;
            var tool = McpSchema.Tool.builder().name(name).description(definition.path("description").asText())
                    .inputSchema(json, definition.path("inputSchema").toString())
                    .outputSchema(json, definition.path("outputSchema").toString())
                    .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly)
                            .destructiveHint(false).idempotentHint(true)
                            .openWorldHint(scope == McpScope.MEDIA_GENERATE).build()).build();
            builder.toolCall(tool, (context, request) -> {
                Locale locale = (Locale) context.get(LOCALE);
                try {
                    var result = tools.execute(principal(context), name, mapper.valueToTree(request.arguments()));
                    var response = McpSchema.CallToolResult.builder().isError(false)
                            .structuredContent(json, result.data().toString()).addTextContent(result.data().toString());
                    for (var preview : result.images()) {
                        response.addContent(McpSchema.ImageContent.builder(preview.data(), preview.mimeType()).build());
                    }
                    return response.build();
                } catch (ApiProblemException problem) {
                    return error(problem.code(), problem.status().value(), messages.text(problem.detail(), locale),
                            problem.retryable());
                } catch (RuntimeException unexpected) {
                    // Do not log arguments, credential material, private drafts or exception messages from dependencies.
                    LOG.error("MCP tool failed: tool={}, exceptionType={}", name, unexpected.getClass().getSimpleName());
                    return error("MCP_INTERNAL_ERROR", 500,
                            messages.text(ApiMessage.of("api.mcp.internal-error"), locale), false);
                }
            });
        }
        return builder.build();
    }

    private static McpPrincipal principal(McpTransportContext context) {
        if (context.get(PRINCIPAL) instanceof McpPrincipal principal) return principal;
        throw McpTokenService.unauthenticated();
    }

    private static McpSchema.CallToolResult error(String code, int status, String detail, boolean retryable) {
        var data = Map.of("code", code, "status", status, "detail", detail, "retryable", retryable);
        return McpSchema.CallToolResult.builder().isError(true).structuredContent(data)
                .addTextContent(code + ": " + detail).build();
    }

    /** Stateless transport context crosses executor boundaries; no user identity is recovered from a ThreadLocal. */
    private record ScopedTransport(WebMvcStatelessServerTransport delegate, McpToolPolicy policy,
            McpTokenService tokens) implements McpStatelessServerTransport {
        @Override
        public void setMcpHandler(McpStatelessServerHandler handler) {
            delegate.setMcpHandler(new McpStatelessServerHandler() {
                @Override
                public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
                        McpSchema.JSONRPCRequest request) {
                    return Mono.defer(() -> {
                        if (McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
                            McpPrincipal current = tokens.requireValidForRead(principal(context));
                            return handler.handleRequest(context, request).map(response -> {
                                if (!(response.result() instanceof McpSchema.ListToolsResult result)) return safe(response);
                                var visible = result.tools().stream()
                                        .filter(tool -> current.scopes().contains(policy.requiredScope(tool.name()))).toList();
                                return McpSchema.JSONRPCResponse.result(request.id(),
                                        new McpSchema.ListToolsResult(visible, result.nextCursor(), result.meta()));
                            });
                        }
                        return handler.handleRequest(context, request).map(ScopedTransport::safe);
                    }).onErrorResume(failure -> Mono.just(McpSchema.JSONRPCResponse.error(request.id(),
                            new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
                                    "MCP request could not be completed"))));
                }

                @Override
                public Mono<Void> handleNotification(McpTransportContext context, McpSchema.JSONRPCNotification notification) {
                    return handler.handleNotification(context, notification);
                }
            });
        }

        private static McpSchema.JSONRPCResponse safe(McpSchema.JSONRPCResponse response) {
            if (response.error() == null) return response;
            return McpSchema.JSONRPCResponse.error(response.id(),
                    new McpSchema.JSONRPCResponse.JSONRPCError(response.error().code(), "Invalid MCP request"));
        }

        @Override
        public List<String> protocolVersions() { return delegate.protocolVersions(); }

        @Override
        public Mono<Void> closeGracefully() { return delegate.closeGracefully(); }
    }
}
