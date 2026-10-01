package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.RunningHubClient;
import dev.agenvas.task.domain.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Query/download an already paid task in a fresh isolated database. No upload or generation methods are called. */
@EnabledIfEnvironmentVariable(named = "AGENVAS_RUNNINGHUB_REAL_RESULT_ARCHIVE", matches = "true")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=runninghub-result-isolated-test", "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.comfyui.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class RunningHubRealResultArchiveIT {
    private static final int EXPECTED_WIDTH = 608;
    private static final int EXPECTED_HEIGHT = 352;
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static Path privateRoot() { return Path.of(System.getenv("AGENVAS_RUNNINGHUB_REAL_DIRECTORY")); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", () -> privateRoot().resolve("result-archive").toString());
    }
    @Autowired ObjectMapper mapper;
    @Autowired RunningHubClient client;
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired AssetService assets;

    @Test void queryOriginalTaskFilterCompanionZipAndDecodeArchiveRealVideo() throws Exception {
        var receipt = mapper.readTree(Files.readString(privateRoot().resolve("real-evidence.json")));
        assertThat(receipt.path("targetId").asText()).isEqualTo("2084320751339032577");
        String providerId = receipt.path("attempts").get(0).path("providerRequestId").asText();
        assertThat(providerId).isNotBlank();
        String key = Files.readString(privateRoot().resolve("api-key")).strip();
        String origin = receipt.path("origin").asText();
        var response = client.query(origin, key, providerId);
        assertThat(response.path("status").asText()).isEqualTo("SUCCESS");
        RunningHubDefinition definition;
        try (var input = getClass().getResourceAsStream("/runninghub/minimax-h3-app-settings.json")) {
            assertThat(input).isNotNull();
            definition = RunningHubDefinition.parse(mapper, mapper.readTree(input).path("runningHub"), Task.Kind.VIDEO_GENERATION);
        }
        var adapter = new RunningHubAdapter("RUNNINGHUB_VIDEO", Task.Kind.VIDEO_GENERATION,
                null, null, null, null, client, mapper, Clock.systemUTC());
        var manifest = adapter.manifest(response, definition, origin);
        assertThat(manifest.results()).hasSize(1);
        assertThat(manifest.results().getFirst().nodeId()).isEqualTo("155");
        var owner = identities.setup("runninghub-result-isolated-test", "result-rh-admin", "isolated-rh-password-123").userId();
        var project = projects.create(owner, "Original RunningHub result archive", Project.AspectRatio.LANDSCAPE_16_9);
        var result = manifest.results().getFirst();
        UUID originalLocalTask = UUID.fromString(receipt.path("attempts").get(0).path("taskId").asText());
        var asset = assets.archiveTaskVideo(owner, project.id(), originalLocalTask, () -> client.download(origin, result.url()).stream());
        assertThat(asset.width()).isEqualTo(EXPECTED_WIDTH);
        assertThat(asset.height()).isEqualTo(EXPECTED_HEIGHT);
        assertThat(asset.durationMs()).isPositive();
        var file = assets.get(owner, project.id(), asset.id());
        Files.createDirectories(privateRoot().resolve("results"));
        Files.copy(file.path(), privateRoot().resolve("results/minimax-h3-app.mp4"), StandardCopyOption.REPLACE_EXISTING);
        var evidence = mapper.createObjectNode().put("providerRequestId", providerId).put("providerStatus", "SUCCESS")
                .put("scope", "original-task-query-and-asset-archive-only");
        evidence.set("usage", manifest.usage());
        evidence.set("asset", mapper.valueToTree(asset));
        Files.writeString(privateRoot().resolve("result-archive-evidence.json"), mapper.writeValueAsString(evidence));
        System.out.println("REAL_RUNNINGHUB ORIGINAL_RESULT_ARCHIVED taskId=" + providerId + " usage=" + manifest.usage());
    }
}
