package dev.agenvas.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Trusted IDs bind HTTP diagnostics to logs without accepting attacker-controlled headers. */
class RequestCorrelationFilterTest {

    @Test
    void serverIdIsSharedByHeaderMdcAndProblemThenCleared() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.addHeader(RequestCorrelationFilter.HEADER, "attacker-supplied-id\nforged");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            String id = response.getHeader(RequestCorrelationFilter.HEADER);
            assertThat(id).matches("[0-9a-f]{32}").isNotEqualTo("attacker-supplied-id\nforged");
            assertThat(MDC.get("requestId")).isEqualTo(id);
            assertThat(MDC.get("traceId")).isEqualTo(id);
            var problem = new ApiExceptionHandler().handleApiProblem(
                    new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                            "冲突", "版本已变化", false), request);
            assertThat(problem.getBody()).isNotNull();
            assertThat(problem.getBody().getProperties()).containsEntry("traceId", id);
        });
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void unexpectedErrorsReuseTrustedId() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            var problem = new ApiExceptionHandler().handleUnexpected(
                    new IllegalStateException("synthetic test failure"), request);
            assertThat(problem.getBody()).isNotNull();
            assertThat(problem.getBody().getProperties()).containsEntry("traceId",
                    response.getHeader(RequestCorrelationFilter.HEADER));
        });
    }

    @Test
    void thrownServletFailureStillClearsThreadContext() {
        RequestCorrelationFilter filter = new RequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response,
                (servletRequest, servletResponse) -> {
                    throw new ServletException("synthetic failure");
                })).isInstanceOf(ServletException.class);
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }
}
