package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import dev.agenvas.usage.domain.UsageEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Mock acceptance and real PostgreSQL history/ledger; no external provider is called. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=capability-config-test-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false"})
class MediaCapabilityConfigurationPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private CanvasService canvas;
    @Autowired private MediaDraftService drafts;
    @Autowired private MediaCapabilityService catalog;
    @Autowired private DirectMediaTaskService direct;
    @Autowired private UsageService usage;
    @Autowired private ObjectMapper mapper;

    @Test
    void freezesDefaultsLimitsAndDecimalPricesAndReleasesTheOriginalEstimate() {
        var owner = identities.setup("capability-config-test-secret", "config-admin", "config-password-123");
        var project = projects.create(owner.userId(), "Configured media", Project.AspectRatio.LANDSCAPE_16_9);
        var connection = catalog.createConnection("Mock configuration", null);
        var capability = catalog.publishCapability(connection.id(), "Priced video", "MOCK_VIDEO", mapper.readTree("""
                {"minimumSeconds":5,"maximumSeconds":10,"maxReferenceImages":2,
                "defaultDurationSeconds":8,"defaultParameters":{"aspectRatio":"9:16"},
                "pricing":{"amount":"0.123456","currency":"CNY","unit":"SECOND"}}
                """));
        var before = catalog.resolve(capability.id(), Task.Kind.VIDEO_GENERATION, 8);
        assertThatThrownBy(() -> catalog.resolve(capability.id(), Task.Kind.VIDEO_GENERATION, 4))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, 11)).noneMatch(candidate ->
                candidate.binding().capabilityId().equals(capability.id()));

        var video = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO, "Video", null).artifact();
        UUID card = UUID.randomUUID();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(card, video.id(),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("280"), new BigDecimal("240"), 0, null, false)));
        var draft = drafts.save(owner.userId(), project.id(), card, 0, "A moving landscape",
                mapper.createObjectNode(), null, capability.id(), MediaDraft.VideoInputMode.TEXT, List.of(), List.of(), null);
        Task task = direct.run(owner.userId(), project.id(), video.id(), card, draft.version(), "priced-video-run");
        assertThat(task.input().path("durationSeconds").asInt()).isEqualTo(8);
        assertThat(task.input().at("/mediaInput/parameters/aspectRatio").asText()).isEqualTo("9:16");
        assertThat(task.input().at("/mediaPricing/amount").asText()).isEqualTo("0.123456");
        UsageEntry reservation = usage.listProject(owner.userId(), project.id()).getFirst();
        assertThat(reservation.estimatedCost()).isEqualByComparingTo("0.987648");
        assertThat(reservation.actualCost()).isNull();
        assertThat(reservation.costStatus()).isEqualTo(UsageEntry.CostStatus.ESTIMATED);

        catalog.updateCapability(connection.id(), capability.id(), capability.version(), "Repriced video", true,
                "MOCK_VIDEO", mapper.readTree("""
                {"minimumSeconds":6,"maximumSeconds":9,"maxReferenceImages":1,
                "defaultDurationSeconds":7,"pricing":{"amount":"9","currency":"USD","unit":"VIDEO"}}
                """));
        assertThat(catalog.inputPolicy(before).minimumSeconds()).isEqualTo(5);
        assertThat(catalog.inputPolicy(before).maxReferenceImages()).isEqualTo(2);
        assertThat(catalog.settings(before).path("defaultDurationSeconds").asInt()).isEqualTo(8);
        direct.cancelQueued(owner.userId(), project.id(), task.id());
        var release = usage.listProject(owner.userId(), project.id()).stream()
                .filter(entry -> entry.entryType() == UsageEntry.EntryType.RELEASE).findFirst().orElseThrow();
        assertThat(release.estimatedCost()).isEqualByComparingTo("0.987648");
        assertThat(release.currency()).isEqualTo("CNY");
        assertThat(release.actualCost()).isNull();

        var imageCapability = catalog.publishCapability(connection.id(), "Priced images", "MOCK_IMAGE", mapper.readTree("""
                {"defaultParameters":{"aspectRatio":"16:9","resolution":"2K","generationCount":2},
                "pricing":{"amount":"0.1","currency":"USD","unit":"IMAGE"}}
                """));
        var imageBinding = catalog.resolve(imageCapability.id(), Task.Kind.IMAGE_GENERATION, 0);
        assertThat(catalog.parameters(imageBinding, mapper.readTree("{\"resolution\":\"1K\"}"))
                .path("resolution").asText()).isEqualTo("1K");
        assertThat(catalog.parameters(imageBinding, mapper.createObjectNode()).path("generationCount").asInt())
                .isEqualTo(2);
    }
}
