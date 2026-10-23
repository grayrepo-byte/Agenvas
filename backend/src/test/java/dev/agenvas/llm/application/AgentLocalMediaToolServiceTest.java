package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.AudioTrimService;
import dev.agenvas.task.application.VideoOperationService;
import dev.agenvas.task.domain.AudioExtractionParameters;
import dev.agenvas.task.domain.AudioTrimParameters;
import dev.agenvas.task.domain.Task;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AgentLocalMediaToolServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CanvasItemQueryService items = mock(CanvasItemQueryService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final VideoOperationService video = mock(VideoOperationService.class);
    private final AudioTrimService audio = mock(AudioTrimService.class);
    private final AgentLocalMediaToolService tools = new AgentLocalMediaToolService(items, artifacts, video, audio, mapper);
    private final TrustedToolContext context = new TrustedToolContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final AgentRun run = mock(AgentRun.class);
    private final CanvasItem item = mock(CanvasItem.class);
    private final UUID itemId = UUID.randomUUID(), artifactId = UUID.randomUUID(), versionId = UUID.randomUUID(), operationId = UUID.randomUUID();
    private final ObjectNode snapshot = mapper.createObjectNode();

    @Test void extractionPinsExactSourceAndServerOriginAndReturnsOnlyAcceptance() {
        allow(Artifact.Kind.VIDEO);
        Task task = accepted();
        var parameters = new AudioExtractionParameters(AudioExtractionParameters.Format.MP3, 31);
        when(video.extractAudioFromAgent(context.ownerId(), context.projectId(), artifactId, itemId,
                versionId, 4, parameters, "agent-audio-extract:" + operationId, context.runId(), operationId)).thenReturn(task);
        var result = tools.extract(context, run, operationId, request().put("format", "MP3").put("trackIndex", 31).toString());
        assertThat(result.path("awaitingLocalTask").asBoolean()).isTrue();
        assertThat(result.at("/data/completed").asBoolean()).isFalse();
        assertThat(result.at("/taskIds/0").asText()).isEqualTo(task.id().toString());
        assertThat(result.toString()).doesNotContain("artifactVersionId", "approvalId");
        verify(video).extractAudioFromAgent(context.ownerId(), context.projectId(), artifactId, itemId,
                versionId, 4, parameters, "agent-audio-extract:" + operationId, context.runId(), operationId);
    }

    @Test void trimmingUsesIntegerMillisecondsAndDefaultsToWav() {
        allow(Artifact.Kind.AUDIO);
        Task task = accepted();
        when(audio.runFromAgent(eq(context.ownerId()), eq(context.projectId()), eq(artifactId), eq(itemId), eq(versionId),
                eq(4L), any(), eq("agent-audio-trim:" + operationId), eq(context.runId()), eq(operationId))).thenReturn(task);
        var result = tools.trim(context, run, operationId, request().put("startMs", 1250).put("endMs", 7250).toString());
        verify(audio).runFromAgent(context.ownerId(), context.projectId(), artifactId, itemId, versionId, 4,
                new AudioTrimParameters(1250, 7250, AudioExtractionParameters.Format.WAV),
                "agent-audio-trim:" + operationId, context.runId(), operationId);
        assertThat(result.path("awaitingLocalTask").asBoolean()).isTrue();
        assertThat(result.at("/data/artifactId").asText()).isEqualTo(artifactId.toString());
    }

    @Test void locksOrStaleSelectionsPreventQueueing() {
        allow(Artifact.Kind.VIDEO);
        when(item.locked()).thenReturn(true);
        assertThatThrownBy(() -> tools.extract(context, run, operationId, request().toString()))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("CANVAS_ITEM_LOCKED");
        when(item.locked()).thenReturn(false);
        when(item.version()).thenReturn(5L);
        assertThatThrownBy(() -> tools.extract(context, run, operationId, request().toString()))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("DIRECT_MEDIA_CONFLICT");
        when(item.version()).thenReturn(4L);
        when(item.selectedVersionId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> tools.extract(context, run, operationId, request().toString()))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("DIRECT_MEDIA_CONFLICT");
        verifyNoInteractions(video, audio);
    }

    @Test void wrongKindAndInaccessibleContentPreventQueueing() {
        allow(Artifact.Kind.AUDIO);
        assertThatThrownBy(() -> tools.extract(context, run, operationId, request().toString())).isInstanceOf(ApiProblemException.class);
        when(artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(), snapshot, context.runId(), versionId))
                .thenThrow(new IllegalStateException("scope denied"));
        assertThatThrownBy(() -> tools.trim(context, run, operationId, request().put("startMs", 0).put("endMs", 1000).toString()))
                .hasMessage("scope denied");
        verifyNoInteractions(video, audio);
    }

    @Test void rejectsUnknownIdentityCommandsFractionalTimesAndMalformedUuidBeforeWrites() {
        for (ObjectNode request : java.util.List.of(request().put("runId", UUID.randomUUID().toString()),
                request().put("ffmpeg", "unsafe"), request().put("expectedCanvasItemVersion", 4.5),
                request().put("sourceVersionId", "1-1-1-1-1"), request().put("trackIndex", 32))) {
            assertThatThrownBy(() -> tools.extract(context, run, operationId, request.toString())).isInstanceOf(ApiProblemException.class);
        }
        for (ObjectNode request : java.util.List.of(request().put("startMs", 0.5).put("endMs", 1000),
                request().put("startMs", "0").put("endMs", 1000),
                request().put("startMs", 500).put("endMs", 550), request().put("startMs", 0).put("endMs", 600001))) {
            assertThatThrownBy(() -> tools.trim(context, run, operationId, request.toString())).isInstanceOf(ApiProblemException.class);
        }
        verifyNoInteractions(video, audio);
    }

    private ObjectNode request() { return mapper.createObjectNode().put("canvasItemId", itemId.toString())
            .put("sourceVersionId", versionId.toString()).put("expectedCanvasItemVersion", 4); }
    private void allow(Artifact.Kind kind) {
        when(run.contextSnapshot()).thenReturn(snapshot);
        when(items.requireArtifactItem(context.ownerId(), context.projectId(), itemId)).thenReturn(item);
        when(item.id()).thenReturn(itemId); when(item.subjectId()).thenReturn(artifactId);
        when(item.selectedVersionId()).thenReturn(versionId); when(item.version()).thenReturn(4L);
        ArtifactVersion version = mock(ArtifactVersion.class);
        when(version.artifactId()).thenReturn(artifactId);
        when(artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(), snapshot, context.runId(), versionId)).thenReturn(version);
        Artifact artifact = mock(Artifact.class); when(artifact.kind()).thenReturn(kind);
        when(artifacts.get(context.ownerId(), context.projectId(), artifactId)).thenReturn(new ArtifactService.ArtifactView(artifact, version));
    }
    private Task accepted() {
        Task task = mock(Task.class); when(task.id()).thenReturn(UUID.randomUUID()); when(task.status()).thenReturn(Task.Status.READY);
        when(task.input()).thenReturn(mapper.createObjectNode().put("artifactId", artifactId.toString()).put("canvasItemId", UUID.randomUUID().toString()));
        return task;
    }
}
