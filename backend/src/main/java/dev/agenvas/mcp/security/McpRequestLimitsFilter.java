package dev.agenvas.mcp.security;

import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.mcp.domain.McpHttpEndpoints;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Bounds authenticated in-flight work and actual request bytes before the protocol adapter parses JSON. */
public class McpRequestLimitsFilter extends OncePerRequestFilter {
    private static final int MAX_PER_USER = 4;
    private static final int MAX_TOTAL = 64;
    private static final int MAX_BODY_BYTES = 300 * 1024;
    private static final String RETRY_AFTER_SECONDS = "1";
    private final Semaphore total = new Semaphore(MAX_TOTAL);
    private final ConcurrentHashMap<UUID, Integer> activeByUser = new ConcurrentHashMap<>();
    private final HandlerExceptionResolver resolver;
    private final boolean recoveryMode;

    public McpRequestLimitsFilter(HandlerExceptionResolver resolver) {
        this(resolver, false);
    }

    public McpRequestLimitsFilter(HandlerExceptionResolver resolver, boolean recoveryMode) {
        this.resolver = resolver;
        this.recoveryMode = recoveryMode;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof McpPrincipal principal)) {
            resolver.resolveException(request, response, null, McpTokenService.unauthenticated());
            return;
        }
        if (!acquire(principal.userId())) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
            resolver.resolveException(request, response, null, busy());
            return;
        }
        try {
            if ((request.getContextPath() + McpHttpEndpoints.UPLOAD).equals(request.getRequestURI())) {
                try {
                    if (!principal.scopes().contains(McpScope.CONTENT_WRITE))
                        throw rejected(HttpStatus.FORBIDDEN, "MCP_SCOPE_DENIED", "api.mcp.scope-denied");
                    if (request.getHeader(HttpHeaders.ORIGIN) != null)
                        throw rejected(HttpStatus.FORBIDDEN, "MCP_ORIGIN_DENIED", "mcp.upload.origin-denied");
                    if (recoveryMode) throw rejected(HttpStatus.SERVICE_UNAVAILABLE, "RECOVERY_MODE_READ_ONLY",
                            "api.recovery-mode-write-guard.database-and-media-backups-are-being-reconciled-project-and-model");
                    if (request.getContentLengthLong() > McpHttpEndpoints.MAX_UPLOAD_REQUEST_BYTES)
                        throw rejected(HttpStatus.PAYLOAD_TOO_LARGE, "MCP_UPLOAD_TOO_LARGE", "mcp.upload.too-large");
                } catch (ApiProblemException rejected) {
                    resolver.resolveException(request, response, null, rejected);
                    return;
                }
                // The servlet multipart parser spools to private disk and enforces its actual
                // 501 MiB request / 500 MiB part limits, including chunked requests. Never copy
                // an upload into the protocol's small in-memory JSON buffer.
                chain.doFilter(request, response);
                return;
            }
            byte[] body;
            try {
                if (request.getContentLengthLong() > MAX_BODY_BYTES) throw tooLarge();
                // Check bytes read, not just Content-Length: native clients may use chunked transfer.
                body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
                if (body.length > MAX_BODY_BYTES) throw tooLarge();
            } catch (ApiProblemException rejected) {
                resolver.resolveException(request, response, null, rejected);
                return;
            }
            chain.doFilter(new BoundedBodyRequest(request, body), response);
        } finally {
            activeByUser.computeIfPresent(principal.userId(), (user, count) -> count == 1 ? null : count - 1);
            total.release();
        }
    }

    private ApiProblemException rejected(HttpStatus status, String code, String message) {
        return new ApiProblemException(status, code, ApiMessage.of(message), ApiMessage.of(message), false);
    }

    private boolean acquire(UUID userId) {
        if (!total.tryAcquire()) return false;
        AtomicBoolean acquired = new AtomicBoolean();
        activeByUser.compute(userId, (user, count) -> {
            int current = count == null ? 0 : count;
            if (current >= MAX_PER_USER) return count;
            acquired.set(true);
            return current + 1;
        });
        if (!acquired.get()) total.release();
        return acquired.get();
    }

    private ApiProblemException tooLarge() {
        return new ApiProblemException(HttpStatus.PAYLOAD_TOO_LARGE, "MCP_REQUEST_TOO_LARGE",
                ApiMessage.of("mcp.request.too-large-title"), ApiMessage.of("mcp.request.too-large"), false);
    }

    private ApiProblemException busy() {
        return new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "MCP_RATE_LIMITED",
                ApiMessage.of("mcp.request.busy-title"), ApiMessage.of("mcp.request.busy"), true);
    }

    /** SDK JSON conversion sees only the bounded copy, including for a chunked original request. */
    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BoundedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyStream(body);
        }

        @Override
        public BufferedReader getReader() {
            var charset = getCharacterEncoding() == null ? StandardCharsets.UTF_8
                    : java.nio.charset.Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class CachedBodyStream extends ServletInputStream {
        private final ByteArrayInputStream bytes;
        private ReadListener listener;

        private CachedBodyStream(byte[] body) {
            bytes = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return bytes.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return bytes.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return bytes.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener next) {
            if (next == null || listener != null) throw new IllegalStateException("Read listener unavailable");
            listener = next;
            try {
                if (!isFinished()) next.onDataAvailable();
                if (isFinished()) next.onAllDataRead();
            } catch (IOException failure) {
                next.onError(failure);
            }
        }
    }
}
