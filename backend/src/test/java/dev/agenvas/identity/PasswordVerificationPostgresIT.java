package dev.agenvas.identity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Actual account hashes, HTTP errors and failure limiting in a disposable database. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class PasswordVerificationPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired
    IdentityService identities;
    @Autowired
    WebApplicationContext context;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcClient jdbc;

    @Test
    void rejectsTruncatedLoginAndCurrentPasswordAndStillLimitsFailures() throws Exception {
        String password = "猫".repeat(24); // 72 UTF-8 bytes, fewer than the request's character limit.
        var owner = identities.setup("password-boundary-admin", password);
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        // Simulate a preexisting supported hash imported by an older deployment; assertions stay HTTP-only.
        String legacyPassword = "legacy-full-length-" + "a".repeat(64);
        var legacyEncoder = org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
                .defaultsForSpringSecurity_v5_8();
        jdbc.sql("update app_user set password_hash=:hash, version=version+1 where id=:id")
                .param("hash", "{pbkdf2@SpringSecurity_v5_8}" + legacyEncoder.encode(legacyPassword))
                .param("id", owner.userId()).update();
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content(mapper
                        .writeValueAsString(Map.of("loginName", owner.loginName(), "password", legacyPassword + "x"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("loginName", owner.loginName(), "password", legacyPassword))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/change-password").with(auth).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("currentPassword", legacyPassword, "newPassword", password))))
                .andExpect(status().isNoContent());
        String login = mapper.writeValueAsString(Map.of("loginName", owner.loginName(), "password", password));
        String overlong = mapper.writeValueAsString(Map.of("loginName", owner.loginName(), "password", password + "x"));
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json").content(overlong))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json").content(login))
                .andExpect(status().isOk());
        String next = "updated-synthetic-password";
        mvc.perform(post("/api/v1/auth/change-password").with(auth).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("currentPassword", password + "x", "newPassword", next))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"));
        // Rejected verification left the original hash usable; the exact password can still change it.
        mvc.perform(post("/api/v1/auth/change-password").with(auth).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("currentPassword", password, "newPassword", next))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("loginName", owner.loginName(), "password", next))))
                .andExpect(status().isOk());
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json").content(overlong))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json").content(overlong))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"));
        String overlongCurrent = mapper.writeValueAsString(Map.of("currentPassword", password + "x",
                "newPassword", "another-synthetic-password"));
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(post("/api/v1/auth/change-password").with(auth).with(csrf()).contentType("application/json")
                    .content(overlongCurrent))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"));
        }
        mvc.perform(post("/api/v1/auth/change-password").with(auth).with(csrf()).contentType("application/json")
                .content(overlongCurrent))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"));
    }
}
