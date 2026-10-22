package dev.agenvas.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.agenvas.shared.i18n.ApiMessages;
import dev.agenvas.shared.i18n.I18nConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.ObjectMapper;

/** Retired routes stay explicit client errors instead of leaking into the unexpected-error handler. */
class RetiredEndpointTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Routes())
            .setControllerAdvice(new ApiExceptionHandler(new ApiMessages(
                    new I18nConfiguration().messageSource(), new ObjectMapper())))
            .build();

    @Test
    void missingHandlerAndMissingResourceAreNotFoundWithoutRetry() throws Exception {
        for (String path : new String[] { "/retired", "/missing-resource" }) {
            mvc.perform(get(path).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.retryable").value(false))
                    .andExpect(jsonPath("$.detail").value("The requested endpoint does not exist."));
        }
    }

    @Test
    void removedMethodKeepsTheAllowedMethodAndStableProblem() throws Exception {
        mvc.perform(get("/director").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "PUT"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @RestController
    static class Routes {
        @PutMapping("/director")
        String ensure() { return "director"; }

        @GetMapping("/missing-resource")
        String missingResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/missing-resource", "/missing-resource");
        }
    }
}
