package dev.agenvas.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL proof that manual creation and its original response replay are atomic. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=artifact-idempotency-secret")
class ArtifactCreateIdempotencyPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext context;

    @Test
    void sameKeyReplaysOriginalResponseAndConcurrentRequestsCreateOnlyOnce() throws Exception {
        AdminPrincipal owner = identities.setup("artifact-idempotency-secret",
                "artifact-idempotency-admin", "artifact-idempotency-password");
        Project project = projects.create(owner.userId(), "Manual creation",
                Project.AspectRatio.LANDSCAPE_16_9);
        JsonNode original = mapper.readTree("""
                {"format":"PLAIN_TEXT","text":"Original"}
                """);
        var first = artifacts.createIdempotent(owner.userId(), project.id(),
                Artifact.Kind.TEXT, "Brief", original, "create-brief-1");
        assertThat(first.replayed()).isFalse();
        artifacts.revise(owner.userId(), project.id(), first.view().artifact().id(), 0,
                null, mapper.readTree("{\"format\":\"PLAIN_TEXT\",\"text\":\"Edited\"}"));
        var replay = artifacts.createIdempotent(owner.userId(), project.id(),
                Artifact.Kind.TEXT, "Brief", original, "create-brief-1");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.view()).isEqualTo(first.view());
        assertThat(artifacts.get(owner.userId(), project.id(), first.view().artifact().id())
                .currentVersion().content().path("text").asText()).isEqualTo("Edited");

        assertThatThrownBy(() -> artifacts.createIdempotent(owner.userId(), project.id(),
                Artifact.Kind.TEXT, "Brief", mapper.readTree("""
                        {"format":"PLAIN_TEXT","text":"Different"}
                        """), "create-brief-1"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("IDEMPOTENCY_CONFLICT");

        int contenders = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ArtifactService.CreateResult>> futures = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(contenders)) {
            for (int index = 0; index < contenders; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return artifacts.createIdempotent(owner.userId(), project.id(),
                            Artifact.Kind.TEXT, "Concurrent", original, "same-concurrent-key");
                }));
            }
            start.countDown();
            List<ArtifactService.CreateResult> results = new ArrayList<>();
            for (Future<ArtifactService.CreateResult> future : futures) results.add(future.get());
            assertThat(results.stream().map(result -> result.view().artifact().id()).distinct()
                    .toList())
                    .hasSize(1);
            assertThat(results.stream().filter(result -> !result.replayed()).toList())
                    .hasSize(1);
        }
        assertThat(jdbc.sql("""
                        select count(*) from artifact
                        where project_id = :projectId and title = 'Concurrent'
                        """).param("projectId", project.id()).query(Long.class).single())
                .isEqualTo(1);

        MockMvc mvc = webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity()).build();
        String path = "/api/v1/projects/" + project.id() + "/artifacts";
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null,
                List.of()));
        String body = """
                {"kind":"TEXT","title":"HTTP","content":
                {"format":"PLAIN_TEXT","text":"HTTP brief"}}
                """;
        mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "http-key-1").content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"));
        mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "http-key-1").content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"));
        mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("""
                        select count(*) from artifact
                        where project_id = :projectId and title = 'HTTP'
                        """).param("projectId", project.id()).query(Long.class).single())
                .isEqualTo(1);
    }
}
