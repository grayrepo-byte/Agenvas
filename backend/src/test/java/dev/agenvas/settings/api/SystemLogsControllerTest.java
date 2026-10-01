package dev.agenvas.settings.api;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.agenvas.identity.infrastructure.AdminAuthenticationProvider;
import dev.agenvas.settings.application.SystemLogBuffer;
import dev.agenvas.shared.error.ApiExceptionHandler;
import dev.agenvas.shared.i18n.ApiMessages;
import dev.agenvas.shared.i18n.I18nConfiguration;
import dev.agenvas.shared.i18n.LocaleResponseFilter;
import dev.agenvas.shared.security.SecurityConfiguration;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

/** Uses the actual application security chain without a database or external model. */
class SystemLogsControllerTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class, SystemLogsController.class,
            I18nConfiguration.class, ApiMessages.class, LocaleResponseFilter.class})
    static class WebConfiguration {
        @Bean AdminAuthenticationProvider authenticationProvider() { return mock(AdminAuthenticationProvider.class); }
        @Bean SystemLogBuffer logs() { return new SystemLogBuffer(Clock.systemUTC()); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @Test void contractAndAdministratorBoundary() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(WebConfiguration.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(LocaleResponseFilter.class)).apply(springSecurity()).build();
            String path = "/api/v1/settings/system-logs";
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
            mvc.perform(get(path).with(user("reader").roles("USER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.processId").isString()).andExpect(jsonPath("$.startedAt").isString())
                    .andExpect(jsonPath("$.capacity").value(SystemLogBuffer.CAPACITY))
                    .andExpect(jsonPath("$.entries").isArray()).andExpect(jsonPath("$.matchedCount").value(0));
            String[] languages = {"en", "zh", "ru", "ja"};
            String[] details = {"Please sign in to continue.", "请登录后继续。", "Войдите, чтобы продолжить.", "ログインして続行してください。"};
            for (int index = 0; index < languages.length; index++) {
                mvc.perform(get(path).header("Accept-Language", languages[index]))
                        .andExpect(status().isUnauthorized())
                        .andExpect(header().string("Content-Language", languages[index]))
                        .andExpect(header().string("Vary", "Accept-Language"))
                        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                        .andExpect(jsonPath("$.detail").value(details[index]));
            }
            for (String query : new String[]{"?limit=0", "?limit=1001", "?limit=invalid", "?stream=STDIN", "?search=" + "x".repeat(201)}) {
                mvc.perform(get(path + query).with(user("admin").roles("ADMIN")))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            }
        }
    }
}
