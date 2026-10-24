package dev.agenvas.mcp.security;

import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.shared.error.ApiProblemException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Authenticates every MCP request; browser cookies and cached protocol sessions are ignored. */
public class McpBearerAuthenticationFilter extends OncePerRequestFilter {
    private static final String BEARER = "Bearer ";
    private static final String MCP_AUTHORITY = "ROLE_MCP";
    private final McpTokenService tokens;
    private final HandlerExceptionResolver resolver;

    public McpBearerAuthenticationFilter(McpTokenService tokens, HandlerExceptionResolver resolver) {
        this.tokens = tokens;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        McpPrincipal principal;
        try {
            List<String> headers = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
            if (headers.size() != 1 || !headers.getFirst().regionMatches(true, 0, BEARER, 0, BEARER.length())) {
                throw McpTokenService.unauthenticated();
            }
            principal = tokens.authenticate(headers.getFirst().substring(BEARER.length()));
        } catch (ApiProblemException rejected) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"agenvas-mcp\"");
            resolver.resolveException(request, response, null, rejected);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(MCP_AUTHORITY))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
