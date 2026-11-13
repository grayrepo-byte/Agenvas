package dev.agenvas.settings.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.agenvas.identity.infrastructure.AdminAuthenticationProvider;
import dev.agenvas.settings.application.LocalServiceDiscoveryService;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Runtime;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Service;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Status;
import dev.agenvas.shared.error.ApiExceptionHandler;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.i18n.ApiMessages;
import dev.agenvas.shared.i18n.I18nConfiguration;
import dev.agenvas.shared.i18n.LocaleResponseFilter;
import dev.agenvas.shared.security.RecoveryModeWriteGuard;
import dev.agenvas.shared.security.SecurityConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

/** Actual application security chain, without database or external service. */
class LocalServiceDiscoveryControllerTest {
    private static final String PATH = "/api/v1/settings/local-services/discover";
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class, LocalServiceDiscoveryController.class,
            I18nConfiguration.class, ApiMessages.class, LocaleResponseFilter.class, RecoveryModeWriteGuard.class})
    static class WebConfiguration {
        @Bean
        AdminAuthenticationProvider authenticationProvider() {
            return mock(AdminAuthenticationProvider.class);
        }
        @Bean
        LocalServiceDiscoveryService discovery() {
            return mock(LocalServiceDiscoveryService.class);
        }
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Test
    void requiresAdministratorAndCsrfAndRejectsArbitraryTargets() throws Exception {
        try (var context = context(false)) {
            var mvc = mvc(context);
            var discovery = context.getBean(LocalServiceDiscoveryService.class);
            mvc.perform(post(PATH).with(csrf()).contentType("application/json").content("{\"service\":\"OLLAMA\"}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(PATH).with(csrf()).with(user("reader").roles("USER"))
                    .contentType("application/json").content("{\"service\":\"OLLAMA\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post(PATH).with(user("admin").roles("ADMIN"))
                    .contentType("application/json").content("{\"service\":\"OLLAMA\"}"))
                    .andExpect(status().isForbidden());
            for (String invalid : new String[]{"{}", "null", "[]", "{\"service\":null}", "{\"service\":1}",
                    "{\"service\":\"other\"}", "{\"service\":\"OLLAMA\",\"url\":\"http://169.254.169.254\"}",
                    "{\"service\":\"COMFYUI\",\"port\":80}"}) {
                mvc.perform(post(PATH).with(csrf()).with(user("admin").roles("ADMIN"))
                        .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
            }
            verifyNoInteractions(discovery);
            when(discovery.discover(Service.OLLAMA)).thenReturn(new LocalServiceDiscoveryService.Result(
                    Service.OLLAMA, Runtime.CONTAINER, Status.FOUND, "http://host.docker.internal:11434/v1"));
            var result = mvc.perform(post(PATH).with(csrf()).with(user("admin").roles("ADMIN"))
                    .contentType("application/json").content("{\"service\":\"OLLAMA\"}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.service").value("OLLAMA"))
                    .andExpect(jsonPath("$.runtime").value("CONTAINER"))
                    .andExpect(jsonPath("$.status").value("FOUND"))
                    .andReturn();
            assertThat(new ObjectMapper().readTree(result.getResponse().getContentAsString()).size()).isEqualTo(4);
        }
    }

    @Test
    void recoveryModeRejectsBeforeProbe() throws Exception {
        try (var context = context(true)) {
            mvc(context).perform(post(PATH).with(csrf()).with(user("admin").roles("ADMIN"))
                    .contentType("application/json").content("{\"service\":\"OLLAMA\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("RECOVERY_MODE_READ_ONLY"));
            verifyNoInteractions(context.getBean(LocalServiceDiscoveryService.class));
        }
    }

    @Test
    void busyProblemUsesFourLocaleCatalogsWithoutUpstreamMetadata() throws Exception {
        try (var context = context(false)) {
            when(context.getBean(LocalServiceDiscoveryService.class).discover(Service.OLLAMA)).thenThrow(
                    new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "LOCAL_SERVICE_DISCOVERY_BUSY",
                            ApiMessage.of("api.local-service-discovery.title"),
                            ApiMessage.of("api.local-service-discovery.busy"), true));
            for (String language : new String[]{"en", "zh", "ru", "ja"}) {
                var result = mvc(context).perform(post(PATH).with(csrf()).with(user("admin").roles("ADMIN"))
                        .header("Accept-Language", language).contentType("application/json")
                        .content("{\"service\":\"OLLAMA\"}"))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.code").value("LOCAL_SERVICE_DISCOVERY_BUSY"))
                        .andExpect(header().string("Content-Language", language)).andReturn();
                assertThat(result.getResponse().getContentAsString()).doesNotContain("api.local-service-discovery",
                        "/private/", "argv", "version");
            }
        }
    }

    private static AnnotationConfigWebApplicationContext context(boolean recovery) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("agenvas.recovery-mode", recovery)));
        context.register(WebConfiguration.class);
        context.refresh();
        return context;
    }
    private static MockMvc mvc(AnnotationConfigWebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(LocaleResponseFilter.class))
                .apply(springSecurity()).build();
    }
}
