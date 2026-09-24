package dev.agenvas.shared.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Assigns a trusted correlation ID before security or application request handling. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    public static final String ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".id";
    public static final String HEADER = "X-Request-Id";

    /** Never trusts an incoming correlation header as a log identifier. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String id = UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put("requestId", id);
        MDC.put("traceId", id);
        long started = System.nanoTime();
        boolean returned = false;
        try {
            chain.doFilter(request, response);
            returned = true;
        } finally {
            LOGGER.info("HTTP request handled method={} status={} async={} durationMs={}",
                    request.getMethod(), returned ? response.getStatus() : 500,
                    request.isAsyncStarted(),
                    Math.max(0, (System.nanoTime() - started) / 1_000_000));
            MDC.remove("traceId");
            MDC.remove("requestId");
        }
    }

    /** Async SSE completion is not a second client request and retains its original header. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }
}
