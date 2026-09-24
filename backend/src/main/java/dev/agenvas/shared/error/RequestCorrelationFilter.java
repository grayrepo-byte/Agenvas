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

/** 在安全过滤器和应用处理前生成可信请求 ID，并加入响应头与日志 MDC。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    /** 记录请求方法、状态和耗时，不记录请求体或凭据。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    /** Servlet request attribute 中可信请求 ID 的键。 */
    public static final String ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".id";
    /** 返回给客户端的请求关联头名称。 */
    public static final String HEADER = "X-Request-Id";

    /** 无条件生成服务端关联 ID，不使用客户端传入头作为日志标识。
     * @param request 当前 Servlet 请求
     * @param response 当前 Servlet 响应
     * @param chain 后续安全与应用过滤器链
     */
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

    /** SSE 异步派发属于原请求的延续，不重新生成或覆盖原关联头。 */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }
}
