package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.ReadToolService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL and synthetic screenplay text; no model or media Provider call. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.mode=mock", "agenvas.llm.scheduler-enabled=false"})
class ReadArtifactTextPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AgentInstanceService agents;
    @Autowired AgentRunService runs;
    @Autowired CanvasService canvas;
    @Autowired ReadToolService reader;
    @Autowired ObjectMapper mapper;

    @Test
    void reconstructsTheExactLongTextAndRechecksProjectCanvasAndVersionAccessOnEveryPage() {
        var owner = identities.setup("screenplay-reader-admin", "screenplay-reader-password-123");
        var project = projects.create(owner.userId(), "Synthetic screenplay", Project.AspectRatio.LANDSCAPE_16_9);
        String source = "甲😀\n".repeat(3200) + "SCREENPLAY_END";
        var screenplay = artifacts.create(owner.userId(), project.id(), Artifact.Kind.TEXT, "Long screenplay", text(source));
        UUID versionId = screenplay.resourceDefaultVersion().id();
        UUID itemId = UUID.randomUUID();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(itemId,
                screenplay.artifact().id(), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("320"), new BigDecimal("200"), 0, null, false)));
        var director = agents.ensureDirector(owner.userId(), project.id());
        AgentRun run = runs.create(owner.userId(), project.id(), director.id(),
                "拆解完整剧本", "screenplay-page-read").run();
        var context = new TrustedToolContext(owner.userId(), project.id(), run.id());
        String ordinary = mapper.createObjectNode().set("versionIds", mapper.createArrayNode().add(versionId.toString())).toString();
        assertThat(reader.artifacts(context, run, UUID.randomUUID(), ordinary)
                .at("/data/0/contentTruncated").asBoolean()).isTrue();

        StringBuilder reconstructed = new StringBuilder();
        int offset = 0;
        int total = source.codePointCount(0, source.length());
        while (true) {
            JsonNode item = page(context, run, versionId, offset);
            assertThat(item.path("versionId").asText()).isEqualTo(versionId.toString());
            assertThat(item.at("/textPage/offset").asInt()).isEqualTo(offset);
            assertThat(item.at("/textPage/total").asInt()).isEqualTo(total);
            assertThat(item.at("/content/format").asText()).isEqualTo("MARKDOWN");
            reconstructed.append(item.at("/content/text").asText());
            if (item.at("/textPage/nextOffset").isNull()) {
                assertThat(item.at("/textPage/endOffset").asInt()).isEqualTo(total);
                assertThat(item.path("contentTruncated").asBoolean()).isTrue();
                break;
            }
            int next = item.at("/textPage/nextOffset").asInt();
            assertThat(next).isGreaterThan(offset);
            offset = next;
        }
        assertThat(reconstructed.toString()).isEqualTo(source);

        var otherProject = projects.create(owner.userId(), "Other project", Project.AspectRatio.SQUARE_1_1);
        var foreign = artifacts.create(owner.userId(), otherProject.id(), Artifact.Kind.TEXT,
                "Private screenplay", text("FOREIGN_BODY"));
        assertThatThrownBy(() -> page(context, run, foreign.resourceDefaultVersion().id(), 0))
                .isInstanceOf(ApiProblemException.class).hasMessageNotContaining("FOREIGN_BODY");

        // A current TEXT version can change between pages; the old ID must not silently read the replacement.
        var revised = artifacts.revise(owner.userId(), project.id(), screenplay.artifact().id(),
                screenplay.artifact().version(), null, text("REVISED_BODY"));
        assertThatThrownBy(() -> page(context, run, versionId, 4000))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("INPUT_SCOPE_DENIED");
        assertThat(page(context, run, revised.resourceDefaultVersion().id(), 0).at("/content/text").asText())
                .isEqualTo("REVISED_BODY");

        // This fixture models an already frozen explicit Skill mapping, which grants only its exact version.
        ObjectNode mappedSnapshot = (ObjectNode) run.contextSnapshot().deepCopy();
        mappedSnapshot.putArray("creativeSkills").addObject().putArray("inputs").addObject()
                .put("artifactId", screenplay.artifact().id().toString()).put("artifactVersionId", versionId.toString());
        AgentRun mapped = new AgentRun(run.id(), run.projectId(), run.agentInstanceId(), run.conversationId(),
                run.conversationTurn(), run.userId(), run.status(), run.instruction(), mappedSnapshot,
                run.policySnapshot(), run.profileVersion(), run.nextStepIndex(), run.version(), run.createdAt(),
                run.updatedAt(), run.completedAt());
        JsonNode historical = page(context, mapped, versionId, 4000);
        assertThat(historical.at("/content/text").asText()).isEqualTo(source.substring(
                source.offsetByCodePoints(0, 4000), source.offsetByCodePoints(0, 8000)));
        assertThat(historical.has("expectedVersion")).isFalse();

        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.Remove(itemId, 0)));
        assertThatThrownBy(() -> page(context, run, revised.resourceDefaultVersion().id(), 0))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("INPUT_SCOPE_DENIED");
        assertThat(page(context, mapped, versionId, 8000).path("versionId").asText()).isEqualTo(versionId.toString());
        assertThat(artifacts.listVersions(owner.userId(), project.id(), screenplay.artifact().id())).hasSize(2);
    }

    private JsonNode page(TrustedToolContext context, AgentRun run, UUID versionId, int offset) {
        ObjectNode arguments = mapper.createObjectNode().put("textOffset", offset).put("textLimit", 4000);
        arguments.putArray("versionIds").add(versionId.toString());
        return reader.artifacts(context, run, UUID.randomUUID(), arguments.toString()).at("/data/0");
    }

    private ObjectNode text(String body) {
        return mapper.createObjectNode().put("format", "MARKDOWN").put("text", body);
    }
}
