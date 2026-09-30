package dev.agenvas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.settings.application.SystemLogBuffer;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real main startup and PostgreSQL, with Mock providers and the production security chain. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = "agenvas.identity.bootstrap-secret=system-logs-integration-bootstrap-secret")
class SystemLogsStartupPostgresIT {
    private static final PrintStream ORIGINAL_OUT = System.out;
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired WebApplicationContext context;
    @Autowired SystemLogBuffer logs;

    @Test void mainCapturesStartupAndBothChannelsInAdministratorApi() throws Exception {
        assertThat(System.out).isNotSameAs(ORIGINAL_OUT);
        assertThat(logs.snapshot(null, "Started", 100).entries()).isNotEmpty();
        System.out.println("system-log-stdout-fixture apiKey=synthetic-private-value");
        System.err.println("system-log-stderr-fixture");
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/v1/settings/system-logs").param("stream", "STDOUT")
                        .param("search", "system-log-stdout-fixture").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.entries[0].stream").value("STDOUT"))
                .andExpect(jsonPath("$.entries[0].message").value("system-log-stdout-fixture apiKey=[REDACTED]"));
        mvc.perform(get("/api/v1/settings/system-logs").param("stream", "STDERR")
                        .param("search", "system-log-stderr-fixture").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].stream").value("STDERR"));
        mvc.perform(get("/api/v1/settings/system-logs")).andExpect(status().isUnauthorized());
    }
}
