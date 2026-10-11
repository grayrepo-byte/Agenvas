package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaFunctionService;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.MediaFunction;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.VideoOperation;
import dev.agenvas.usage.application.UsageService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class VideoOperationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID sourceVersionId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private final UUID outputId = UUID.randomUUID();
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final CanvasItemQueryService items = mock(CanvasItemQueryService.class);
    private final CanvasService canvas = mock(CanvasService.class);
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final MediaFunctionService functions = mock(MediaFunctionService.class);
    private final MediaCapabilityService catalog = mock(MediaCapabilityService.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final VideoOperationService service = new VideoOperationService(tasks, artifacts, assets,
            items, canvas, drafts, functions, catalog, events, mock(UsageService.class), mapper,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setup() {
        when(events.<Task>recordChange(eq(owner), eq(project), any())).thenAnswer(invocation -> {
            Supplier<ProjectEventService.Change<Task>> mutation = invocation.getArgument(2);
            return new ProjectEventService.RecordedChange<>(mutation.get().value(), null);
        });
        when(tasks.findDirectByStepKey(owner, project, "command")).thenReturn(Optional.empty());
        Artifact artifact = new Artifact(artifactId, project, Artifact.Kind.VIDEO, "Source",
                sourceVersionId, null, 0, NOW, NOW);
        when(artifacts.get(owner, project, artifactId)).thenReturn(new ArtifactService.ArtifactView(artifact, null));
        when(items.requireArtifactItem(owner, project, itemId)).thenReturn(card(itemId));
        ArtifactVersion source = mock(ArtifactVersion.class);
        when(source.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
        when(artifacts.requireMediaVersionForTask(owner, project, sourceVersionId, Artifact.Kind.VIDEO)).thenReturn(source);
        when(assets.metadata(owner, project, assetId)).thenReturn(new Asset(assetId, project,
                Asset.MediaKind.VIDEO, "private/test.mp4", "video/mp4", 1024, "test",
                640, 480, 1435, null, null, null, NOW));
        MediaDraft sourceDraft = mock(MediaDraft.class);
        when(sourceDraft.version()).thenReturn(7L);
        when(drafts.get(owner, project, itemId)).thenReturn(sourceDraft);
        when(drafts.get(owner, project, outputId)).thenReturn(mock(MediaDraft.class));
        when(canvas.forkMediaDerivationWithinChange(eq(owner), eq(project), eq(itemId),
                any(), eq(7L), eq(0), any())).thenReturn(card(outputId));
    }

    @ParameterizedTest
    @CsvSource({"default, 5", "12, 12"})
    void freezesDeclaredDurationAndTheTrustedSourceWithoutMutatingClientParameters(
            String requestedSeconds, int expectedSeconds) {
        runningHub();
        var parameters = mapper.createObjectNode();
        UUID clientSource = UUID.randomUUID();
        var supplied = parameters.putObject("dynamicValues").put("clip", clientSource.toString());
        if (!requestedSeconds.equals("default")) supplied.put("seconds", Integer.parseInt(requestedSeconds));

        Task task = run(parameters);

        assertThat(task.input().path("durationSeconds").asInt()).isEqualTo(expectedSeconds);
        assertThat(task.input().at("/mediaInput/parameters/dynamicValues/seconds").asInt()).isEqualTo(expectedSeconds);
        assertThat(task.input().at("/mediaInput/parameters/dynamicValues/clip").asText()).isEqualTo(sourceVersionId.toString());
        assertThat(parameters.at("/dynamicValues/clip").asText()).isEqualTo(clientSource.toString());
        assertThat(task.input().at("/mediaInput/runningHubContract/targetId").asText()).isEqualTo("123");
        // Hashing occurs before JSONB persistence; keep the accepted serialization order stable.
        assertThat(task.input().propertyNames()).containsExactly("schemaVersion", "artifactId",
                "sourceCanvasItemId", "canvasItemId", "canvasItemVersion", "resultSelectionEpoch",
                "resultDraftVersion", "prompt", "workflowVersion", "parentVersionId",
                "durationSeconds", "videoOperationRequest", "videoOperation", "mediaInput", "providerProtocol");
        assertThat(task.input().path("mediaInput").propertyNames()).containsExactly("mode", "prompt",
                "renderedPrompt", "capabilityId", "capabilityVersion", "parameters", "images",
                "audios", "mentions", "videos", "runningHubContract");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"5\"", "5.5", "2147483648"})
    void rejectsMalformedDurationBeforeCreatingAnOutput(String value) {
        runningHub();
        var parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").set("seconds", mapper.readTree(value));

        assertThatThrownBy(() -> run(parameters)).isInstanceOfSatisfying(ApiProblemException.class,
                error -> assertThat(error.code()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(canvas);
        verify(tasks, never()).create(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 31})
    void rejectsDurationOutsideThePublishedContractBeforeCreatingAnOutput(int seconds) {
        runningHub();
        var parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("seconds", seconds);

        assertThatThrownBy(() -> run(parameters)).isInstanceOfSatisfying(ApiProblemException.class,
                error -> assertThat(error.code()).isEqualTo("RUNNINGHUB_INPUT_INVALID"));
        verifyNoInteractions(canvas);
        verify(tasks, never()).create(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"unknown\":1}", "{\"dynamicValues\":[]}"})
    void rejectsUnexpectedParameterStructureBeforeCreatingAnOutput(String parameters) {
        runningHub();

        assertThatThrownBy(() -> run(mapper.readTree(parameters))).isInstanceOfSatisfying(ApiProblemException.class,
                error -> assertThat(error.code()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(canvas);
        verify(tasks, never()).create(any());
    }

    @Test
    void localOperationsRejectProviderParametersBeforeCreatingAnOutput() {
        MediaCapabilityBinding binding = binding(MediaAdapterRegistry.LOCAL_VIDEO_PROCESSOR);
        when(functions.resolve(MediaFunction.VIDEO_DEPTH_MAP, 0)).thenReturn(binding);

        assertThatThrownBy(() -> service.run(owner, project, artifactId, itemId, sourceVersionId,
                0, VideoOperation.DEPTH_MAP, 0, 1, "", mapper.createObjectNode().put("scale", 2), "command"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(canvas);
        verify(tasks, never()).create(any());
    }

    private void runningHub() {
        MediaCapabilityBinding binding = binding("RUNNINGHUB_VIDEO");
        when(functions.resolve(MediaFunction.VIDEO_UPSCALE, 0)).thenReturn(binding);
        when(catalog.runningHubDefinition(binding)).thenReturn(RunningHubDefinition.parse(mapper,
                mapper.readTree("""
                        {"schemaVersion":1,"protocolVersion":"V2","targetType":"WORKFLOW","targetId":"123",
                         "fields":[{"key":"clip","label":"Video","type":"VIDEO","required":true,"nodeId":"1","fieldName":"video"},
                           {"key":"seconds","label":"Duration","type":"INTEGER","required":true,"source":"DURATION_SECONDS",
                            "defaultValue":5,"minimum":1,"maximum":30,"nodeId":"2","fieldName":"duration"}],
                         "outputs":[{"kind":"VIDEO","primary":true,"maxCount":1}]}
                        """), Task.Kind.VIDEO_GENERATION));
        when(catalog.settings(binding)).thenReturn(mapper.createObjectNode());
    }

    private MediaCapabilityBinding binding(String adapter) {
        return new MediaCapabilityBinding(UUID.randomUUID(), 1, UUID.randomUUID(), 1, adapter, "mapping");
    }

    private Task run(JsonNode parameters) {
        return service.run(owner, project, artifactId, itemId, sourceVersionId, 0,
                VideoOperation.UPSCALE, 0, 1, "", parameters, "command");
    }

    private CanvasItem card(UUID id) {
        return new CanvasItem(id, project, CanvasItem.SubjectType.ARTIFACT, artifactId,
                sourceVersionId, "Source", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("300"), new BigDecimal("300"), 0, null, false, 0, NOW, NOW);
    }
}
