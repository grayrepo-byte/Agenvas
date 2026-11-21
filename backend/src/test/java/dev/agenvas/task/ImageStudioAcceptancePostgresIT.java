package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Cold concurrent acceptance proves atomic creation, rather than only replay of an existing ledger row. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class ImageStudioAcceptancePostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    IdentityService identities;
    @Autowired
    MediaCapabilityService capabilities;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    WebApplicationContext context;
    @Autowired
    ObjectMapper mapper;

    @Test
    void coldSameKeyRacesCreateOneFullBatchAndConflictingCommandsLeaveNoPartialWork() throws Exception {
        var owner = identities.setup("studio-acceptance-admin", "studio-acceptance-password-123");
        UUID capability = capabilities.defaultCapabilityId(Task.Kind.IMAGE_GENERATION);
        int version = capabilities.forDraft(capability, Task.Kind.IMAGE_GENERATION).capabilityVersion();
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        assertThat(jdbc.sql("select count(*) from project where owner_id=:owner").param("owner", owner.userId())
                .query(Long.class).single()).isZero();
        String request = "{\"capabilityId\":\"" + capability + "\",\"expectedCapabilityVersion\":" + version
                + ",\"prompt\":\"Cold garden\",\"parameters\":{\"generationCount\":2},\"referenceVersionIds\":[]}";
        var start = new CountDownLatch(1);
        String batchId = null;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = java.util.stream.IntStream.range(0, 20).mapToObj(ignored -> executor.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/image-studio/batches").with(auth).with(csrf())
                        .header("Idempotency-Key", "cold-studio-batch").contentType("application/json")
                        .content(request)).andReturn();
            })).toList();
            start.countDown();
            for (var call : calls) {
                MvcResult response = call.get(30, TimeUnit.SECONDS);
                assertThat(response.getResponse().getStatus()).isEqualTo(201);
                var batch = mapper.readTree(response.getResponse().getContentAsString());
                if (batchId == null)
                    batchId = batch.path("id").asText();
                assertThat(batch.path("id").asText()).isEqualTo(batchId);
                assertThat(batch.path("tasks")).hasSize(2);
            }
        }
        assertThat(jdbc.sql("select count(*) from project where purpose='IMAGE_STUDIO'").query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from image_studio_batch").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from task").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from artifact").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from usage_ledger where entry_type='RESERVATION'")
                .query(Long.class).single()).isEqualTo(2);

        String fourImages = request.replace("generationCount\":2", "generationCount\":4");
        var conflictStart = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = List.of("Conflict first", "Conflict second").stream().map(prompt -> executor.submit(() -> {
                conflictStart.await();
                return mvc.perform(post("/api/v1/image-studio/batches").with(auth).with(csrf())
                        .header("Idempotency-Key", "conflicting-studio-batch").contentType("application/json")
                        .content(fourImages.replace("Cold garden", prompt))).andReturn();
            })).toList();
            conflictStart.countDown();
            var responses = new java.util.ArrayList<MvcResult>();
            for (var call : calls)
                responses.add(call.get(30, TimeUnit.SECONDS));
            assertThat(responses).extracting(result -> result.getResponse().getStatus())
                    .containsExactlyInAnyOrder(201, 409);
            var accepted = responses.stream().filter(result -> result.getResponse().getStatus() == 201).findFirst()
                    .orElseThrow();
            assertThat(mapper.readTree(accepted.getResponse().getContentAsString()).path("tasks")).hasSize(4);
            var rejected = responses.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst()
                    .orElseThrow();
            assertThat(mapper.readTree(rejected.getResponse().getContentAsString()).path("code").asText())
                    .isEqualTo("IDEMPOTENCY_CONFLICT");
        }
        assertThat(jdbc.sql("select count(*) from project where purpose='IMAGE_STUDIO'").query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from image_studio_batch").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from task").query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("select count(*) from artifact").query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("select count(*) from usage_ledger where entry_type='RESERVATION'")
                .query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("select count(*) from canvas_item").query(Long.class).single()).isZero();
    }
}
