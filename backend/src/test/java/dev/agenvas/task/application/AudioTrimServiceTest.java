package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.AudioExtractionParameters.Format;
import dev.agenvas.task.domain.AudioTrimParameters;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AudioTrimServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), artifactId = UUID.randomUUID(),
            itemId = UUID.randomUUID(), sourceId = UUID.randomUUID(), outputId = UUID.randomUUID(), assetId = UUID.randomUUID();
    private final TaskRepository repository = mock(TaskRepository.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final CanvasItemQueryService items = mock(CanvasItemQueryService.class);
    private final CanvasService canvas = mock(CanvasService.class);
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final MediaCapabilityService catalog = mock(MediaCapabilityService.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final UsageService usage = mock(UsageService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AudioTrimParameters parameters = new AudioTrimParameters(100, 1100, Format.WAV);
    private final AudioTrimService service = new AudioTrimService(repository, artifacts, assets, items, canvas, drafts,
            projects, catalog, events, usage, mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach void setup() {
        when(events.recordChange(eq(owner), eq(project), any())).thenAnswer(call -> {
            Supplier<?> mutation = call.getArgument(2);
            var change = (ProjectEventService.Change<?>) mutation.get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
        var artifact = new Artifact(artifactId, project, Artifact.Kind.AUDIO, "source", sourceId, null, 4, NOW, NOW);
        when(artifacts.get(owner, project, artifactId)).thenReturn(new ArtifactService.ArtifactView(artifact, null));
        var sourceItem = mock(CanvasItem.class);
        when(sourceItem.subjectId()).thenReturn(artifactId);
        when(sourceItem.selectedVersionId()).thenReturn(sourceId);
        when(sourceItem.version()).thenReturn(2L);
        when(items.requireArtifactItem(owner, project, itemId)).thenReturn(sourceItem);
        var source = mock(ArtifactVersion.class);
        when(source.artifactId()).thenReturn(artifactId);
        when(source.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
        when(artifacts.requireMediaVersionForTask(owner, project, sourceId, Artifact.Kind.AUDIO)).thenReturn(source);
        when(assets.metadata(owner, project, assetId)).thenReturn(new Asset(assetId, project, Asset.MediaKind.AUDIO,
                "synthetic", "audio/wav", 2000, "hash", null, null, 2000, null, null, null, NOW));
        var draft = mock(MediaDraft.class);
        when(draft.version()).thenReturn(3L);
        when(drafts.get(owner, project, itemId)).thenReturn(draft);
        when(drafts.get(owner, project, outputId)).thenReturn(mock(MediaDraft.class));
        var output = mock(CanvasItem.class);
        when(output.id()).thenReturn(outputId);
        when(output.subjectId()).thenReturn(artifactId);
        when(output.selectedVersionId()).thenReturn(sourceId);
        when(canvas.forkMediaDerivationWithinChange(eq(owner), eq(project), eq(itemId), any(), eq(3L), eq(0), eq("音频裁剪")))
                .thenReturn(output);
        when(catalog.resolve(AudioTrimService.CAPABILITY_ID, Task.Kind.AUDIO_GENERATION, 0)).thenReturn(
                new MediaCapabilityBinding(UUID.randomUUID(), 1, AudioTrimService.CAPABILITY_ID, 1,
                        MediaAdapterRegistry.LOCAL_AUDIO_TRIMMER, "a".repeat(64)));
    }

    @Test void pinsTheSourceAndCreatesAZeroCostSeparateNode() {
        var task = service.run(owner, project, artifactId, itemId, sourceId, 2, parameters, "trim");
        assertThat(task.isAudioTrim()).isTrue();
        assertThat(task.isCancelableLocalProcessing()).isTrue();
        assertThat(task.compositionRunId()).isNull();
        assertThat(task.input().path("canvasItemId").asText()).isEqualTo(outputId.toString());
        assertThat(task.input().path("parentVersionId").asText()).isEqualTo(sourceId.toString());
        assertThat(task.input().at("/mediaInput/audios/0/versionId").asText()).isEqualTo(sourceId.toString());
        assertThat(task.input().at("/audioTrim/parameters")).isEqualTo(parameters.toJson(mapper));
        verify(repository).createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), project, artifactId, sourceId, 4, outputId));
        verify(usage).reserveMediaTask(owner, task, "LOCAL_NO_COST", 1);
    }

    @Test void exactReplayChecksActiveProjectAndDoesNotRepeatWrites() {
        var task = service.run(owner, project, artifactId, itemId, sourceId, 2, parameters, "trim");
        var persisted = mock(Task.class);
        // The real repository reloads JSONB numbers, including small long CAS versions, as IntNode.
        when(persisted.input()).thenReturn(mapper.readTree(task.input().toString()));
        when(repository.findDirectByStepKey(owner, project, "trim")).thenReturn(Optional.of(persisted));
        var order = inOrder(projects, repository);
        assertThat(service.run(owner, project, artifactId, itemId, sourceId, 2, parameters, "trim")).isEqualTo(persisted);
        order.verify(projects).requireActiveProject(owner, project);
        order.verify(repository).findDirectByStepKey(owner, project, "trim");
        verify(repository).create(task);
        assertThatThrownBy(() -> service.run(owner, project, artifactId, itemId, sourceId, 2,
                new AudioTrimParameters(200, 1200, Format.WAV), "trim"))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test void changedSelectionAndOverrunDoNotCreateOutputNodes() {
        assertThatThrownBy(() -> service.run(owner, project, artifactId, itemId, sourceId, 1, parameters, "trim"))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo("DIRECT_MEDIA_CONFLICT"));
        assertThatThrownBy(() -> service.run(owner, project, artifactId, itemId, sourceId, 2,
                new AudioTrimParameters(1900, 2100, Format.WAV), "trim"))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo("AUDIO_TRIM_INVALID"));
        verify(canvas, never()).forkMediaDerivationWithinChange(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), any());
        verify(repository, never()).create(any());
    }
}
