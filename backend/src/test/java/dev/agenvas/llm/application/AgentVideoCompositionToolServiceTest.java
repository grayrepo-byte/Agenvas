package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.application.VideoCompositionService;
import dev.agenvas.task.domain.Task;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic service tests: model ordering is preserved while identity and exact input scope stay server owned. */
class AgentVideoCompositionToolServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final VideoCompositionService compositions = mock(VideoCompositionService.class);
    private final AgentVideoCompositionToolService tools = new AgentVideoCompositionToolService(artifacts, compositions, mapper);
    private final TrustedToolContext context = new TrustedToolContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final AgentRun run = mock(AgentRun.class);
    private final ObjectNode snapshot = mapper.createObjectNode();
    private final UUID video = UUID.randomUUID(), audio = UUID.randomUUID();

    @Test
    void authorizesEveryExactVersionBeforeQueuingWithTrustedIdentityAndStableCommandKey() {
        allow(video, Artifact.Kind.VIDEO);
        allow(audio, Artifact.Kind.AUDIO);
        UUID secondAudio = UUID.randomUUID();
        allow(secondAudio, Artifact.Kind.AUDIO);
        ObjectNode request = request();
        ((ObjectNode) request.at("/shots/0/lines/0")).put("startMs", 2100);
        ((ObjectNode) request.at("/shots/0")).withArray("lines").addObject().put("audioVersionId", secondAudio.toString());
        UUID operation = UUID.randomUUID(), taskId = UUID.randomUUID();
        Task task = mock(Task.class);
        when(task.id()).thenReturn(taskId);
        when(task.status()).thenReturn(Task.Status.READY);
        UUID artifactId = UUID.randomUUID(), canvasItemId = UUID.randomUUID();
        when(task.input()).thenReturn(mapper.createObjectNode().put("artifactId", artifactId.toString())
                .put("canvasItemId", canvasItemId.toString()));
        when(compositions.runFromAgent(eq(context.ownerId()), eq(context.projectId()), eq(context.runId()), eq(operation),
                any(), eq("agent-video-composition:" + operation))).thenReturn(task);

        var result = tools.compose(context, run, operation, request.toString());

        var ordered = inOrder(artifacts, compositions);
        ordered.verify(artifacts).requireAgentReadableVersion(context.ownerId(), context.projectId(), snapshot, video);
        ordered.verify(artifacts).requireAgentReadableVersion(context.ownerId(), context.projectId(), snapshot, audio);
        ordered.verify(artifacts).requireAgentReadableVersion(context.ownerId(), context.projectId(), snapshot, secondAudio);
        ordered.verify(compositions).runFromAgent(context.ownerId(), context.projectId(), context.runId(), operation, request,
                "agent-video-composition:" + operation);
        assertThat(result.path("awaitingLocalTask").asBoolean()).isTrue();
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.at("/data/status").asText()).isEqualTo("READY");
        assertThat(result.at("/data/completed").asBoolean()).isFalse();
        assertThat(result.at("/data/queued").asBoolean()).isTrue();
        assertThat(result.at("/taskIds/0").asText()).isEqualTo(taskId.toString());
        assertThat(result.at("/createdIds/0").asText()).isEqualTo(artifactId.toString());
        assertThat(result.at("/createdCanvasItemIds/0").asText()).isEqualTo(canvasItemId.toString());
        assertThat(result.at("/data/canvasItemId").asText()).isEqualTo(canvasItemId.toString());
        assertThat(result.toString()).doesNotContain("approvalId", "artifactVersionId");
    }

    @Test
    void inaccessibleAudioPreventsAnyCompositionSideEffect() {
        allow(video, Artifact.Kind.VIDEO);
        var message = ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid");
        when(artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(), snapshot, audio))
                .thenThrow(new ApiProblemException(HttpStatus.FORBIDDEN, "INPUT_SCOPE_DENIED", message, message, false));

        assertThatThrownBy(() -> tools.compose(context, run, UUID.randomUUID(), request().toString()))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("INPUT_SCOPE_DENIED");
        verifyNoInteractions(compositions);
    }

    @Test
    void mediaKindMustMatchTheSlotBeforeDispatch() {
        allow(video, Artifact.Kind.VIDEO);
        allow(audio, Artifact.Kind.VIDEO);
        assertInvalid(request());
        verifyNoInteractions(compositions);
    }

    @Test
    void modelCannotSupplyIdentityUrlsOrRawCommandsAtAnyLevel() {
        var root = request().put("ownerId", UUID.randomUUID().toString());
        assertInvalid(root);
        var shot = request();
        ((ObjectNode) shot.at("/shots/0")).put("videoUrl", "https://example.invalid/untrusted");
        assertInvalid(shot);
        allow(video, Artifact.Kind.VIDEO);
        var line = request();
        ((ObjectNode) line.at("/shots/0/lines/0")).put("ffmpeg", "untrusted command");
        assertInvalid(line);
        verifyNoInteractions(compositions);
    }

    @Test
    void malformedAndUnboundedRequestsNeverReachTheApplicationService() {
        assertThatThrownBy(() -> tools.compose(context, run, UUID.randomUUID(), "not json"))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("TOOL_ARGUMENT_INVALID");
        ObjectNode tooManyShots = request();
        for (int index = 0; index < 12; index++) tooManyShots.withArray("shots").add(request().at("/shots/0"));
        assertInvalid(tooManyShots);
        allow(video, Artifact.Kind.VIDEO);
        ObjectNode tooManyLines = request();
        for (int index = 0; index < 30; index++) ((ObjectNode) tooManyLines.at("/shots/0"))
                .withArray("lines").addObject().put("audioVersionId", audio.toString());
        assertInvalid(tooManyLines);
        verifyNoInteractions(compositions);
    }

    private void allow(UUID id, Artifact.Kind kind) {
        when(run.contextSnapshot()).thenReturn(snapshot);
        UUID artifactId = UUID.randomUUID();
        var version = mock(ArtifactVersion.class);
        when(version.artifactId()).thenReturn(artifactId);
        when(artifacts.requireAgentReadableVersion(context.ownerId(), context.projectId(), snapshot, id)).thenReturn(version);
        var artifact = mock(Artifact.class);
        when(artifact.kind()).thenReturn(kind);
        when(artifacts.get(context.ownerId(), context.projectId(), artifactId))
                .thenReturn(new ArtifactService.ArtifactView(artifact, version));
    }

    private ObjectNode request() {
        var request = mapper.createObjectNode().put("title", "对白成片").put("rationale", "沿分镜顺序保留两位人物的台词")
                .put("originalAudioMode", "MUTE");
        request.putArray("shots").addObject().put("videoVersionId", video.toString())
                .put("durationMs", 10000).putArray("lines").addObject().put("audioVersionId", audio.toString());
        return request;
    }

    private void assertInvalid(ObjectNode request) {
        assertThatThrownBy(() -> tools.compose(context, run, UUID.randomUUID(), request.toString()))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("TOOL_ARGUMENT_INVALID");
    }
}
