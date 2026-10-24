package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.llm.application.ReadToolService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Tool arguments and Run scope remain enforced even when a task belongs to the project. */
class ReadToolServiceTest {

    private final TaskService tasks = mock(TaskService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final dev.agenvas.llm.application.ToolExecutionRepository ledger = mock(dev.agenvas.llm.application.ToolExecutionRepository.class);
    private final ReadToolService reader = new ReadToolService(
            mock(ProjectService.class), artifacts, assets, tasks, mapper, ledger, mock(dev.agenvas.llm.application.AgentMediaSampleService.class));
    private final TrustedToolContext context = new TrustedToolContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @ParameterizedTest
    @EnumSource(value = Artifact.Kind.class, names = {"IMAGE", "VIDEO", "AUDIO"})
    void readsVisibleMediaWithoutAResourceDefault(Artifact.Kind kind) {
        AgentRun run = mock(AgentRun.class);
        JsonNode snapshot = mapper.createObjectNode();
        when(run.contextSnapshot()).thenReturn(snapshot);
        when(run.policySnapshot()).thenReturn(mapper.createObjectNode().put("systemPromptVersion", 13));
        Artifact artifact = mock(Artifact.class);
        ArtifactVersion version = mock(ArtifactVersion.class);
        UUID artifactId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        JsonNode content = mapper.createObjectNode().put("assetId", assetId.toString()).put("prompt", "镜头 02：小李说‘等一下’");
        when(version.id()).thenReturn(versionId);
        when(version.artifactId()).thenReturn(artifactId);
        when(version.runId()).thenReturn(context.runId());
        when(version.content()).thenReturn(content);
        when(artifact.kind()).thenReturn(kind);
        when(assets.metadata(context.ownerId(), context.projectId(), assetId)).thenReturn(new Asset(
                assetId, context.projectId(), Asset.MediaKind.valueOf(kind.name()), "PRIVATE_STORAGE_KEY",
                kind == Artifact.Kind.AUDIO ? "audio/wav" : "video/mp4", 1234, "PRIVATE_HASH",
                kind == Artifact.Kind.VIDEO ? 1920 : null, kind == Artifact.Kind.VIDEO ? 1080 : null,
                kind == Artifact.Kind.IMAGE ? null : 9876, null, null, null, java.time.Instant.EPOCH));
        when(artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(), snapshot, context.runId(), versionId)).thenReturn(version);
        when(artifacts.get(context.ownerId(), context.projectId(), artifactId))
                .thenReturn(new ArtifactService.ArtifactView(artifact, null));

        JsonNode item = reader.artifacts(context, run, UUID.randomUUID(),
                mapper.createObjectNode().set("versionIds",
                        mapper.createArrayNode().add(versionId.toString())).toString()).path("data").get(0);

        assertThat(item.path("versionId").asText()).isEqualTo(versionId.toString());
        assertThat(item.path("kind").asText()).isEqualTo(kind.name());
        assertThat(item.path("imagePreviewRequested").asBoolean()).isEqualTo(kind == Artifact.Kind.IMAGE);
        assertThat(item.path("current").asBoolean()).isFalse();
        assertThat(item.path("content")).isEqualTo(content);
        assertThat(item.path("contentTruncated").asBoolean()).isFalse();
        assertThat(item.has("title")).isFalse();
        assertThat(item.has("expectedVersion")).isFalse();
        if (kind != Artifact.Kind.IMAGE) {
            assertThat(item.at("/mediaMetadata/durationMs").asInt()).isEqualTo(9876);
            assertThat(item.at("/content/prompt").asText()).contains("小李", "等一下");
            assertThat(item.toString()).doesNotContain("PRIVATE_STORAGE_KEY", "PRIVATE_HASH", "objectKey");
        }
    }

    @Test
    void missingSkillResourceDoesNotClaimTheReadToolsAreUnavailable() {
        AgentRun run = mock(AgentRun.class);
        when(run.contextSnapshot()).thenReturn(mapper.createObjectNode());
        when(run.policySnapshot()).thenReturn(mapper.createObjectNode().put("toolPolicyVersion", 5));
        assertThatThrownBy(() -> reader.skillResource(run, UUID.randomUUID(), "{\"skillVersionId\":\"" + UUID.randomUUID() + "\",\"path\":\"guide.md\"}"))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("资源")
                .hasMessageNotContaining("允许列表");
    }

    @Test
    void rejectsAnotherRunTaskWithoutReturningItsStatus() {
        UUID taskId = UUID.randomUUID();
        Task foreignRunTask = mock(Task.class);
        when(tasks.get(context.ownerId(), context.projectId(), taskId))
                .thenReturn(foreignRunTask);
        when(foreignRunTask.runId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> reader.taskStatus(context, UUID.randomUUID(),
                "{\"taskIds\":[\"" + taskId + "\"]}"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("TASK_NOT_FOUND");
    }

    @Test
    void rejectsOversizedAndDuplicateListsBeforeReadingAnyTask() {
        String oneId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> reader.taskStatus(context, UUID.randomUUID(),
                "{\"taskIds\":[\"" + oneId + "\",\"" + oneId + "\"]}"))
                .isInstanceOf(ApiProblemException.class);
        String thirteen = (",\"" + oneId + "\"").repeat(12);
        assertThatThrownBy(() -> reader.taskStatus(context, UUID.randomUUID(),
                "{\"taskIds\":[\"" + oneId + "\"" + thirteen + "]}"))
                .isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(tasks);
    }

    @Test
    void readsOnlyLocalCompositionsInitiatedByThisRunAndExposesArchivedResultIdentity() {
        UUID taskId = UUID.randomUUID();
        Task composition = mock(Task.class);
        when(tasks.get(context.ownerId(), context.projectId(), taskId)).thenReturn(composition);
        when(composition.id()).thenReturn(taskId);
        when(ledger.ownsLocalTask(context.projectId(), context.runId(), taskId)).thenReturn(true);
        when(composition.agentLocalRunId()).thenReturn(context.runId());
        when(composition.kind()).thenReturn(Task.Kind.VIDEO_GENERATION);
        when(composition.status()).thenReturn(Task.Status.SUCCEEDED);
        when(composition.updatedAt()).thenReturn(java.time.Instant.EPOCH);
        when(composition.output()).thenReturn(mapper.createObjectNode()
                .put("artifactId", "result-artifact").put("artifactVersionId", "result-version")
                .put("selected", true).put("privateDiagnostic", "DO_NOT_DISCLOSE"));
        String arguments = "{\"taskIds\":[\"" + taskId + "\"]}";

        JsonNode reply = reader.taskStatus(context, UUID.randomUUID(), arguments);
        assertThat(reply.at("/data/0/artifactVersionId").asText()).isEqualTo("result-version");
        assertThat(reply.toString()).doesNotContain("DO_NOT_DISCLOSE");

        when(composition.agentLocalRunId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> reader.taskStatus(context, UUID.randomUUID(), arguments))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("TASK_NOT_FOUND");
        when(composition.agentLocalRunId()).thenReturn(context.runId());
        when(ledger.ownsLocalTask(context.projectId(), context.runId(), taskId)).thenReturn(false);
        assertThatThrownBy(() -> reader.taskStatus(context, UUID.randomUUID(), arguments))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("TASK_NOT_FOUND");
    }

    @Test
    void inaccessibleVersionCannotRevealAssetMetadata() {
        AgentRun run = mock(AgentRun.class);
        JsonNode snapshot = mapper.createObjectNode();
        when(run.contextSnapshot()).thenReturn(snapshot);
        UUID versionId = UUID.randomUUID();
        when(artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(), snapshot, context.runId(), versionId))
                .thenThrow(new IllegalStateException("denied"));
        assertThatThrownBy(() -> reader.artifacts(context, run, UUID.randomUUID(),
                "{\"versionIds\":[\"" + versionId + "\"]}")).hasMessage("denied");
        verifyNoInteractions(assets);
    }

    @Test
    void textPagesCountUnicodeCodePointsAndDistinguishTheLastPageFromTheWholeSource() {
        var fixture = textFixture("😀甲😀乙");
        JsonNode middle = textPage(fixture, ",\"textOffset\":1,\"textLimit\":2");
        assertThat(middle.at("/content/text").asText()).isEqualTo("甲😀");
        assertThat(middle.at("/content/format").asText()).isEqualTo("MARKDOWN");
        assertThat(middle.path("contentTruncated").asBoolean()).isTrue();
        assertThat(middle.path("textPage")).isEqualTo(mapper.createObjectNode()
                .put("offset", 1).put("endOffset", 3).put("total", 4).put("nextOffset", 3));

        JsonNode last = textPage(fixture, ",\"textOffset\":3,\"textLimit\":1");
        assertThat(last.at("/content/text").asText()).isEqualTo("乙");
        assertThat(last.path("contentTruncated").asBoolean()).isTrue();
        assertThat(last.at("/textPage/nextOffset").isNull()).isTrue();
        JsonNode whole = textPage(fixture, ",\"textOffset\":0,\"textLimit\":4");
        assertThat(whole.at("/content/text").asText()).isEqualTo("😀甲😀乙");
        assertThat(whole.path("contentTruncated").asBoolean()).isFalse();
        JsonNode emptyEnd = textPage(fixture, ",\"textOffset\":4");
        assertThat(emptyEnd.at("/content/text").asText()).isEmpty();
        assertThat(emptyEnd.at("/textPage/nextOffset").isNull()).isTrue();
        assertThat(emptyEnd.path("contentTruncated").asBoolean()).isTrue();
        assertInvalid(() -> textPage(fixture, ",\"textOffset\":5"));
        assertInvalid(() -> textPage(fixture, ",\"textOffset\":2147483647"));
    }

    @Test
    void textPagingDefaultsAllowReconstructingLongSourceWhileOrdinaryReadsKeepTheirShape() {
        String text = "😀甲".repeat(3600) + "SOURCE_END";
        var fixture = textFixture(text);
        JsonNode ordinary = textPage(fixture, "");
        assertThat(ordinary.path("contentTruncated").asBoolean()).isTrue();
        assertThat(ordinary.has("content")).isFalse();
        assertThat(ordinary.has("textPage")).isFalse();
        assertThat(ordinary.path("contentPreview").asText()).isNotEmpty();

        JsonNode first = textPage(fixture, ",\"textOffset\":0");
        assertThat(first.at("/textPage/endOffset").asInt()).isEqualTo(4000);
        assertThat(first.at("/textPage/nextOffset").asInt()).isEqualTo(4000);
        JsonNode second = textPage(fixture, ",\"textOffset\":4000");
        assertThat(second.at("/textPage/nextOffset").isNull()).isTrue();
        assertThat(first.at("/content/text").asText() + second.at("/content/text").asText()).isEqualTo(text);
        assertThat(textPage(fixture, ",\"textLimit\":2").at("/content/text").asText()).isEqualTo("😀甲");

        var shortFixture = textFixture("短剧本");
        JsonNode ordinaryShort = textPage(shortFixture, "");
        assertThat(ordinaryShort.at("/content/text").asText()).isEqualTo("短剧本");
        assertThat(ordinaryShort.path("contentTruncated").asBoolean()).isFalse();
        assertThat(ordinaryShort.has("textPage")).isFalse();
    }

    @Test
    void malformedTextPaginationAndBatchRequestsFailBeforeReadingAnyArtifact() {
        AgentRun run = mock(AgentRun.class);
        String ids = "\"versionIds\":[\"" + UUID.randomUUID() + "\"]";
        for (String field : List.of("\"textOffset\":-1", "\"textOffset\":1.5", "\"textOffset\":\"0\"",
                "\"textOffset\":null", "\"textOffset\":true", "\"textOffset\":2147483648",
                "\"textLimit\":0", "\"textLimit\":-1", "\"textLimit\":4001", "\"textLimit\":2.5",
                "\"textLimit\":\"2\"", "\"textLimit\":null", "\"textLimit\":false",
                "\"textLimit\":2147483648", "\"unknown\":0")) {
            assertInvalid(() -> reader.artifacts(context, run, UUID.randomUUID(), "{" + ids + "," + field + "}"));
        }
        assertInvalid(() -> reader.artifacts(context, run, UUID.randomUUID(),
                "{\"versionIds\":[\"" + UUID.randomUUID() + "\",\"" + UUID.randomUUID() + "\"],\"textOffset\":0}"));
        verifyNoInteractions(artifacts, assets);
    }

    @ParameterizedTest
    @EnumSource(value = Artifact.Kind.class, names = {"IMAGE", "VIDEO", "AUDIO"})
    void rejectsTextPaginationForMediaBeforeRequestingPixelsOrMetadata(Artifact.Kind kind) {
        var fixture = textFixture("Media cannot be paged as text");
        when(fixture.artifact().kind()).thenReturn(kind);
        assertInvalid(() -> textPage(fixture, ",\"textLimit\":1"));
        verifyNoInteractions(assets);
    }

    @Test
    void everyTextPageRechecksAccessToItsExactImmutableVersion() {
        var fixture = textFixture("第一页😀第二页");
        assertThat(textPage(fixture, ",\"textLimit\":3").at("/content/text").asText()).isEqualTo("第一页");
        when(artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(),
                fixture.run().contextSnapshot(), context.runId(), fixture.versionId())).thenThrow(new IllegalStateException("revoked"));
        assertThatThrownBy(() -> textPage(fixture, ",\"textOffset\":3")).hasMessage("revoked");
    }

    private TextFixture textFixture(String text) {
        AgentRun run = mock(AgentRun.class);
        JsonNode snapshot = mapper.createObjectNode();
        when(run.contextSnapshot()).thenReturn(snapshot);
        Artifact artifact = mock(Artifact.class);
        ArtifactVersion version = mock(ArtifactVersion.class);
        UUID artifactId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(artifact.kind()).thenReturn(Artifact.Kind.TEXT);
        when(version.id()).thenReturn(versionId);
        when(version.artifactId()).thenReturn(artifactId);
        when(version.content()).thenReturn(mapper.createObjectNode().put("format", "MARKDOWN").put("text", text));
        when(artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(), snapshot, context.runId(), versionId)).thenReturn(version);
        when(artifacts.get(context.ownerId(), context.projectId(), artifactId))
                .thenReturn(new ArtifactService.ArtifactView(artifact, version));
        return new TextFixture(run, artifact, versionId);
    }

    private JsonNode textPage(TextFixture fixture, String fields) {
        return reader.artifacts(context, fixture.run(), UUID.randomUUID(),
                "{\"versionIds\":[\"" + fixture.versionId() + "\"]" + fields + "}").path("data").get(0);
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApiProblemException.class)
                .extracting("code").isEqualTo("TOOL_ARGUMENT_INVALID");
    }

    private record TextFixture(AgentRun run, Artifact artifact, UUID versionId) {}
}
