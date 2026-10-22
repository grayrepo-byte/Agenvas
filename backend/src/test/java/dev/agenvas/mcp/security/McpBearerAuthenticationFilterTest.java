package dev.agenvas.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.shared.error.ApiProblemException;
import jakarta.servlet.FilterChain;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

class McpBearerAuthenticationFilterTest {
    private final McpTokenService tokens = mock(McpTokenService.class);
    private final HandlerExceptionResolver errors = mock(HandlerExceptionResolver.class);
    private final McpBearerAuthenticationFilter filter = new McpBearerAuthenticationFilter(tokens, errors);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void onlyBearerEstablishesASecretFreeRequestPrincipalAndNeverCreatesSession() throws Exception {
        var principal = new McpPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(McpScope.READ));
        when(tokens.authenticate("synthetic-secret")).thenReturn(principal);
        var request = request();
        request.addHeader(HttpHeaders.AUTHORIZATION, "bEaReR synthetic-secret");
        FilterChain next = (httpRequest, httpResponse) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.getPrincipal()).isEqualTo(principal);
            assertThat(authentication.getCredentials()).isNull();
            assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_MCP");
        };
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, next);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void cookieBasicAndDuplicateAuthorizationCannotBypassBearerAuthentication() throws Exception {
        for (String scheme : new String[] {null, "Basic dGVzdDp0ZXN0", "Bearer first"}) {
            var request = request();
            request.addHeader("Cookie", "SESSION=synthetic-web-session");
            if (scheme != null) request.addHeader(HttpHeaders.AUTHORIZATION, scheme);
            if ("Bearer first".equals(scheme)) request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer second");
            var response = new MockHttpServletResponse();
            doAnswer(call -> {
                ApiProblemException failure = call.getArgument(3);
                ((MockHttpServletResponse) call.getArgument(1)).setStatus(failure.status().value());
                return null;
            }).when(errors).resolveException(any(), any(), any(), any());
            filter.doFilter(request, response, (a, b) -> { throw new AssertionError("must reject before dispatch"); });
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer realm=\"agenvas-mcp\"");
            assertThat(request.getSession(false)).isNull();
        }
    }

    @Test
    void revokedCredentialIsRevalidatedOnEachTransportRequest() throws Exception {
        when(tokens.authenticate("revoked")).thenThrow(McpTokenService.unauthenticated());
        var request = request();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer revoked");
        filter.doFilter(request, new MockHttpServletResponse(),
                (a, b) -> { throw new AssertionError("must reject before dispatch"); });
        verify(tokens).authenticate("revoked");
        verify(errors).resolveException(any(), any(), any(), any(ApiProblemException.class));
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/mcp");
    }
}
