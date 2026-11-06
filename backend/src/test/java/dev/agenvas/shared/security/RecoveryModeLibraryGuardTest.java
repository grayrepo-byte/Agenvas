package dev.agenvas.shared.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.shared.error.ApiProblemException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** HTTP recovery restrictions cover the new account library as well as project writes. */
@SpringJUnitWebConfig(RecoveryModeLibraryGuardTest.Config.class)
@TestPropertySource(properties = "agenvas.recovery-mode=true")
class RecoveryModeLibraryGuardTest {
    @Configuration
    @EnableWebMvc
    @Import({RecoveryModeWriteGuard.class, Probe.class})
    static class Config {}

    @RestController
    static class Probe {
        @RequestMapping({"/api/v1/character-library/profiles", "/api/v1/library/entries"})
        String library() { return "readable"; }
    }

    @Autowired WebApplicationContext context;

    @Test
    void accountLibrariesRemainReadableButWritesAreBlockedDuringRestore() throws Exception {
        var mvc = webAppContextSetup(context).build();
        for (String path : new String[]{"/api/v1/character-library/profiles", "/api/v1/library/entries"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
            assertThatThrownBy(() -> mvc.perform(post(path)))
                    .hasRootCauseInstanceOf(ApiProblemException.class)
                    .rootCause().extracting("code").isEqualTo("RECOVERY_MODE_READ_ONLY");
        }
    }
}
