package dev.agenvas.settings.api;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.agenvas.identity.infrastructure.AdminAuthenticationProvider;
import dev.agenvas.settings.application.SystemLogBuffer;
import dev.agenvas.shared.error.ApiExceptionHandler;
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

/** Uses the actual application security chain without a database or external model. */
class SystemLogsControllerTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class, SystemLogsController.class})
    static class WebConfiguration {
        @Bean AdminAuthenticationProvider authenticationProvider() { return mock(AdminAuthenticationProvider.class); }
        @Bean SystemLogBuffer logs() { return new SystemLogBuffer(Clock.systemUTC()); }
    }

    @Test void contractAndAdministratorBoundary() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(WebConfiguration.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            String path = "/api/v1/settings/system-logs";
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
            mvc.perform(get(path).with(user("reader").roles("USER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.processId").isString()).andExpect(jsonPath("$.startedAt").isString())
                    .andExpect(jsonPath("$.capacity").value(SystemLogBuffer.CAPACITY))
                    .andExpect(jsonPath("$.entries").isArray()).andExpect(jsonPath("$.matchedCount").value(0));
            for (String query : new String[]{"?limit=0", "?limit=1001", "?limit=invalid", "?stream=STDIN", "?search=" + "x".repeat(201)}) {
                mvc.perform(get(path + query).with(user("admin").roles("ADMIN")))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            }
        }
    }
}
