package dev.agenvas.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.shared.error.ApiProblemException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

class McpRequestLimitsFilterTest {
    private static final int MAX_BODY = 300 * 1024;
    private final HandlerExceptionResolver errors = mock(HandlerExceptionResolver.class);
    private final McpRequestLimitsFilter filter = new McpRequestLimitsFilter(errors);

    @BeforeEach
    void problemResponses() {
        doAnswer(call -> {
            var response = (MockHttpServletResponse) call.getArgument(1);
            var problem = (ApiProblemException) call.getArgument(3);
            response.setStatus(problem.status().value());
            response.setHeader("X-Test-Problem-Code", problem.code());
            return null;
        }).when(errors).resolveException(any(), any(), any(), any());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void checksActualChunkedBytesAndPreservesTheBoundedBodyForTheSdk() throws Exception {
        authenticate(UUID.randomUUID());
        var oversized = chunked(new byte[MAX_BODY + 1]);
        var rejected = new MockHttpServletResponse();
        filter.doFilter(oversized, rejected, mustNotDispatch());
        assertThat(rejected.getStatus()).isEqualTo(413);
        assertThat(rejected.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(rejected.getHeader("X-Test-Problem-Code")).isEqualTo("MCP_REQUEST_TOO_LARGE");

        byte[] content = "{\"text\":\"镜头 1\"}".getBytes(StandardCharsets.UTF_8);
        var accepted = new MockHttpServletResponse();
        filter.doFilter(chunked(content), accepted, (request, response) -> {
            var bodyRequest = (jakarta.servlet.http.HttpServletRequest) request;
            assertThat(bodyRequest.getContentLengthLong()).isEqualTo(content.length);
            assertThat(bodyRequest.getInputStream().readAllBytes()).isEqualTo(content);
            assertThat(bodyRequest.getReader().readLine()).isEqualTo("{\"text\":\"镜头 1\"}");
        });
        assertThat(accepted.getStatus()).isEqualTo(200);
        assertThat(accepted.getHeader("Cache-Control")).isEqualTo("no-store");
        filter.doFilter(chunked(new byte[MAX_BODY]), new MockHttpServletResponse(),
                (request, response) -> assertThat(request.getInputStream().readAllBytes()).hasSize(MAX_BODY));
    }

    @Test
    void rejectsAnOversizedDeclaredLengthWithoutReadingAndReleasesQuotaAfterBodyFailures() throws Exception {
        authenticate(UUID.randomUUID());
        for (int attempt = 0; attempt < 8; attempt++) {
            var declared = new MockHttpServletRequest("POST", "/mcp") {
                @Override public long getContentLengthLong() { return MAX_BODY + 1L; }
                @Override public ServletInputStream getInputStream() { throw new AssertionError("must reject before reading"); }
            };
            var response = new MockHttpServletResponse();
            filter.doFilter(declared, response, mustNotDispatch());
            assertThat(response.getStatus()).isEqualTo(413);
        }
        var broken = new MockHttpServletRequest("POST", "/mcp") {
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() throws IOException { throw new IOException("synthetic disconnect"); }
                    @Override public boolean isFinished() { return false; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(jakarta.servlet.ReadListener listener) {}
                };
            }
        };
        for (int attempt = 0; attempt < 8; attempt++) {
            assertThatThrownBy(() -> filter.doFilter(broken, new MockHttpServletResponse(), mustNotDispatch()))
                    .isInstanceOf(IOException.class);
        }
        filter.doFilter(chunked(new byte[0]), new MockHttpServletResponse(), (a, b) -> {});
    }

    @Test
    void aUsersFourRequestsShareQuotaAcrossTokensWhileAnotherUserCanProceed() throws Exception {
        UUID busyUser = UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> requests = new ArrayList<>();
            for (int request = 0; request < 4; request++) {
                requests.add(executor.submit(() -> blockRequest(busyUser, entered, release)));
            }
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                authenticate(busyUser); // A separate token belonging to the same user cannot evade the limit.
                var rejected = new MockHttpServletResponse();
                filter.doFilter(chunked(new byte[0]), rejected, mustNotDispatch());
                assertThat(rejected.getStatus()).isEqualTo(429);
                assertThat(rejected.getHeader("Retry-After")).isEqualTo("1");
                authenticate(UUID.randomUUID());
                filter.doFilter(chunked(new byte[0]), new MockHttpServletResponse(), (a, b) -> {});
            } finally {
                release.countDown();
            }
            for (Future<?> request : requests) request.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        authenticate(busyUser);
        filter.doFilter(chunked(new byte[0]), new MockHttpServletResponse(), (a, b) -> {});
    }

    @Test
    void totalSixtyFourRequestLimitIsSharedAcrossUsersAndPermitsAreReusable() throws Exception {
        CountDownLatch entered = new CountDownLatch(64);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> requests = new ArrayList<>();
            for (int user = 0; user < 16; user++) {
                UUID userId = UUID.randomUUID();
                for (int request = 0; request < 4; request++) {
                    requests.add(executor.submit(() -> blockRequest(userId, entered, release)));
                }
            }
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                authenticate(UUID.randomUUID());
                var rejected = new MockHttpServletResponse();
                filter.doFilter(chunked(new byte[0]), rejected, mustNotDispatch());
                assertThat(rejected.getStatus()).isEqualTo(429);
            } finally {
                release.countDown();
            }
            for (Future<?> request : requests) request.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        for (int request = 0; request < 65; request++) {
            authenticate(UUID.randomUUID());
            var response = new MockHttpServletResponse();
            filter.doFilter(chunked(new byte[0]), response, (a, b) -> {});
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void downstreamExceptionsReleaseUserAndGlobalPermitsAndAnonymousRequestsDoNotDispatch() throws Exception {
        var unauthorized = new MockHttpServletResponse();
        filter.doFilter(chunked(new byte[0]), unauthorized, mustNotDispatch());
        assertThat(unauthorized.getStatus()).isEqualTo(401);
        authenticate(UUID.randomUUID());
        for (int attempt = 0; attempt < 65; attempt++) {
            assertThatThrownBy(() -> filter.doFilter(chunked(new byte[0]), new MockHttpServletResponse(),
                    (a, b) -> { throw new jakarta.servlet.ServletException("synthetic failure"); }))
                    .isInstanceOf(jakarta.servlet.ServletException.class);
        }
        filter.doFilter(chunked(new byte[0]), new MockHttpServletResponse(), (a, b) -> {});
    }

    private void blockRequest(UUID userId, CountDownLatch entered, CountDownLatch release) {
        authenticate(userId);
        try {
            filter.doFilter(chunked(new byte[0]), new MockHttpServletResponse(), (request, response) -> {
                entered.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });
        } catch (IOException | jakarta.servlet.ServletException failure) {
            throw new IllegalStateException(failure);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new McpPrincipal(userId, UUID.randomUUID(), Set.of(McpScope.READ)), null, List.of()));
    }

    private MockHttpServletRequest chunked(byte[] content) {
        var request = new MockHttpServletRequest("POST", "/mcp") {
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1; }
        };
        request.addHeader("Transfer-Encoding", "chunked");
        request.setContent(content);
        return request;
    }

    private FilterChain mustNotDispatch() {
        return (request, response) -> { throw new AssertionError("request must be rejected before dispatch"); };
    }
}
