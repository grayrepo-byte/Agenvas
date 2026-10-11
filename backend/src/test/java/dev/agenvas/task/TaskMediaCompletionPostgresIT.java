package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic PNG outputs through real PostgreSQL; no external provider or user media is used. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class TaskMediaCompletionPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private MediaDraftService drafts;
    @Autowired private CanvasService canvas;
    @Autowired private DirectMediaTaskService direct;
    @Autowired private TaskService tasks;
    @Autowired private AssetService assets;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcClient jdbc;

    @Test
    void aPrimaryInTheMiddleKeepsTheTargetAndTheAdditionalOutputOrder() {
        Fixture fixture = submitting("middle-primary");
        ObjectNode first = content(fixture, "extra-before-primary");
        ObjectNode primary = content(fixture, "primary");
        ObjectNode last = content(fixture, "extra-after-primary");
        var results = List.of(result(0, false, first), result(1, true, primary), result(2, false, last));

        var archived = tasks.succeedWithArtifacts(fixture.lease(), fixture.workerId(), results,
                mapper.createObjectNode().put("syntheticUsage", "unknown"));

        Task completed = current(fixture);
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.output().path("artifactId").asText()).isEqualTo(fixture.artifactId().toString());
        assertThat(completed.output().path("artifactVersionId").asText()).isEqualTo(archived.versionId().toString());
        assertThat(completed.output().path("providerUsage").path("syntheticUsage").asText()).isEqualTo("unknown");
        assertThat(card(fixture, fixture.cardId()).item().selectedVersionId()).isEqualTo(archived.versionId());
        assertThat(versionContent(fixture, completed.output())).isEqualTo(primary);
        assertThat(canvas.listMediaVersions(fixture.owner().userId(), fixture.project().id(), fixture.cardId())).hasSize(1);

        JsonNode additional = completed.output().path("additionalResults");
        assertThat(additional).hasSize(2);
        assertThat(additional.get(0).path("ordinal").asInt()).isZero();
        assertThat(additional.get(1).path("ordinal").asInt()).isEqualTo(2);
        assertThat(versionContent(fixture, additional.get(0))).isEqualTo(first);
        assertThat(versionContent(fixture, additional.get(1))).isEqualTo(last);
        var firstCard = card(fixture, UUID.fromString(additional.get(0).path("canvasItemId").asText()));
        var secondCard = card(fixture, UUID.fromString(additional.get(1).path("canvasItemId").asText()));
        assertThat(firstCard.item().title()).isEqualTo("Synthetic outputs · 2");
        assertThat(secondCard.item().title()).isEqualTo("Synthetic outputs · 3");
        assertThat(secondCard.item().y()).isGreaterThan(firstCard.item().y());
        assertThat(secondCard.item().zIndex()).isGreaterThan(firstCard.item().zIndex());
        assertThat(firstCard.item().selectedVersionId().toString()).isEqualTo(additional.get(0).path("artifactVersionId").asText());
        assertThat(secondCard.item().selectedVersionId().toString()).isEqualTo(additional.get(1).path("artifactVersionId").asText());
        assertThat(canvas.listMediaVersions(fixture.owner().userId(), fixture.project().id(), firstCard.item().id())).hasSize(1);
        assertThat(canvas.listMediaVersions(fixture.owner().userId(), fixture.project().id(), secondCard.item().id())).hasSize(1);
        assertThat(artifacts.listVersions(fixture.owner().userId(), fixture.project().id(), fixture.artifactId())).hasSize(3);
        assertThat(settlements(fixture)).isEqualTo(1);
        assertThat(attemptStatus(fixture)).isEqualTo("ACCEPTED");
    }

    @Test
    void anInvalidLaterOutputRollsBackTheArchiveAndTheSameAssetsCanFinishExactlyOnce() {
        Fixture fixture = submitting("rollback-and-retry");
        ObjectNode primary = content(fixture, "primary");
        ObjectNode firstExtra = content(fixture, "first-extra");
        ObjectNode lastExtra = content(fixture, "last-extra");
        ObjectNode invalid = lastExtra.deepCopy().put("sourceTaskId", UUID.randomUUID().toString());
        ArchiveCounts before = counts(fixture);
        Task submitting = current(fixture);

        // The primary version and first additional card have already been written in this transaction.
        assertThatThrownBy(() -> tasks.succeedWithArtifacts(fixture.lease(), fixture.workerId(),
                        List.of(result(0, true, primary), result(1, false, firstExtra), result(2, false, invalid)), null))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("VALIDATION_ERROR"));

        assertThat(counts(fixture)).isEqualTo(before);
        assertThat(current(fixture)).isEqualTo(submitting);
        assertThat(attemptStatus(fixture)).isEqualTo("SUBMITTING");
        assertThat(card(fixture, fixture.cardId()).item().selectedVersionId()).isNull();
        assertThat(artifacts.get(fixture.owner().userId(), fixture.project().id(), fixture.artifactId())
                .resourceDefaultVersion()).isNull();

        // Reuse the same original archived PNG assets, lease, and provider attempt; no new submission occurs.
        var valid = List.of(result(0, true, primary), result(1, false, firstExtra), result(2, false, lastExtra));
        tasks.succeedWithArtifacts(fixture.lease(), fixture.workerId(), valid, null);
        Task completed = current(fixture);
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        ArchiveCounts committed = counts(fixture);
        assertThat(committed.versions()).isEqualTo(before.versions() + 3);
        assertThat(committed.cards()).isEqualTo(before.cards() + 2);
        assertThat(committed.historyEntries()).isEqualTo(before.historyEntries() + 3);
        assertThat(committed.assets()).isEqualTo(before.assets());
        assertThat(committed.settlements()).isEqualTo(1);
        assertThat(attemptStatus(fixture)).isEqualTo("ACCEPTED");

        assertThatThrownBy(() -> tasks.succeedWithArtifacts(fixture.lease(), fixture.workerId(), valid, null))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("TASK_LEASE_LOST"));
        assertThat(counts(fixture)).isEqualTo(committed);
        assertThat(current(fixture)).isEqualTo(completed);
        assertThat(attemptStatus(fixture)).isEqualTo("ACCEPTED");
    }

    private Fixture submitting(String workerId) {
        AdminPrincipal owner = jdbc.sql("select id from app_user where login_name='completion-admin'")
                .query(UUID.class).optional().map(id -> new AdminPrincipal(id, "completion-admin"))
                .orElseGet(() -> identities.setup("completion-admin", "completion-password-123"));
        Project project = projects.create(owner.userId(), "Synthetic completion", Project.AspectRatio.SQUARE_1_1);
        UUID artifactId = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Synthetic outputs", null)
                .artifact().id();
        UUID cardId = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), artifactId);
        var draft = CanvasMediaFixture.save(drafts, owner.userId(), project.id(), cardId, 0,
                "Synthetic completion", null, null, null);
        Task task = direct.run(owner.userId(), project.id(), artifactId, cardId, draft.version(), workerId);
        Task lease = tasks.claimBoundMedia(workerId, 1).getFirst();
        assertThat(lease.id()).isEqualTo(task.id());
        tasks.beginSubmission(lease, workerId);
        return new Fixture(owner, project, artifactId, cardId, lease, workerId);
    }

    private ObjectNode content(Fixture fixture, String prompt) {
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", ImageAssetFixture.archive(assets, fixture.owner().userId(), fixture.project().id()).toString());
        content.put("prompt", prompt);
        content.put("workflowVersion", "synthetic-v1");
        content.putObject("parameters");
        content.put("sourceTaskId", fixture.lease().id().toString());
        return content;
    }

    private TaskService.ArchivedProviderResult result(int ordinal, boolean primary, ObjectNode content) {
        return new TaskService.ArchivedProviderResult(ordinal, Artifact.Kind.IMAGE, primary, content);
    }

    private Task current(Fixture fixture) {
        return tasks.get(fixture.owner().userId(), fixture.project().id(), fixture.lease().id());
    }

    private CanvasService.CanvasEntry card(Fixture fixture, UUID itemId) {
        return canvas.list(fixture.owner().userId(), fixture.project().id()).stream()
                .filter(entry -> entry.item().id().equals(itemId)).findFirst().orElseThrow();
    }

    private JsonNode versionContent(Fixture fixture, JsonNode output) {
        return artifacts.requireMediaVersionForTask(fixture.owner().userId(), fixture.project().id(),
                UUID.fromString(output.path("artifactVersionId").asText()), Artifact.Kind.IMAGE).content();
    }

    private int settlements(Fixture fixture) {
        return jdbc.sql("select count(*) from usage_ledger where task_id=:task and entry_type='SETTLEMENT'")
                .param("task", fixture.lease().id()).query(Integer.class).single();
    }

    private String attemptStatus(Fixture fixture) {
        return jdbc.sql("select status from provider_attempt where task_id=:task")
                .param("task", fixture.lease().id()).query(String.class).single();
    }

    private ArchiveCounts counts(Fixture fixture) {
        UUID projectId = fixture.project().id();
        return new ArchiveCounts(count("artifact", projectId), count("artifact_version", projectId),
                count("canvas_item", projectId), count("canvas_item_media_version", projectId),
                count("asset", projectId), settlements(fixture), count("project_event", projectId),
                projects.get(fixture.owner().userId(), projectId).eventSeq());
    }

    private int count(String table, UUID projectId) {
        return jdbc.sql("select count(*) from " + table + " where project_id=:project")
                .param("project", projectId).query(Integer.class).single();
    }

    private record Fixture(AdminPrincipal owner, Project project, UUID artifactId, UUID cardId, Task lease, String workerId) {}
    private record ArchiveCounts(int artifacts, int versions, int cards, int historyEntries, int assets,
            int settlements, int events, long eventSeq) {}
}
