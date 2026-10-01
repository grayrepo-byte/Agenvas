package dev.agenvas.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.shared.error.ApiExceptionHandler;
import dev.agenvas.shared.i18n.ApiMessages;
import dev.agenvas.shared.i18n.I18nConfiguration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerExceptionResolver;
import tools.jackson.databind.ObjectMapper;

class SecurityI18nTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApiMessages messages = new ApiMessages(new I18nConfiguration().messageSource(), mapper);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(new Object())
            .setControllerAdvice(new ApiExceptionHandler(messages)).build();
    @Test
    void realSecurityEntryPointAndCsrfHandlerLocalizeBeforeMvcBindsALocale() throws Exception {
        var resolver = mvc.getDispatcherServlet().getWebApplicationContext()
                .getBean("handlerExceptionResolver", HandlerExceptionResolver.class);
        var security = new SecurityConfiguration();
        var response = new MockHttpServletResponse();
        security.authenticationEntryPoint(resolver).commence(request("en"), response, null);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("detail").asText())
                .isEqualTo("Please sign in to continue.");
        response = new MockHttpServletResponse();
        security.accessDeniedHandler(resolver).handle(request("ru"), response, null);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("code").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("请求被拒绝", "请求缺少");
    }

    private MockHttpServletRequest request(String locale) {
        var request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.addHeader("Accept-Language", locale);
        return request;
    }
}
