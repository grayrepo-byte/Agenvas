package dev.agenvas.artifact.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.NativeMediaProtocol;
import dev.agenvas.settings.application.MediaStyleService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Ordinary copies preserve batch intent; independent batch results start with one output. */
class MediaDraftCopyTest {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID(), targetId = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID(), referenceId = UUID.randomUUID();
    private final UUID capabilityId = UUID.randomUUID(), styleId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArtifactRepository repository = mock(ArtifactRepository.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final CanvasItemQueryService items = mock(CanvasItemQueryService.class);
    private final MediaDraftService service = new MediaDraftService(mock(ProjectService.class),
            artifacts, repository, items, mock(ProjectEventService.class), mapper,
            Clock.fixed(NOW, ZoneOffset.UTC), mock(MediaCapabilityService.class), mock(MediaStyleService.class));

    @BeforeEach void configureSource() {
        var card = mock(CanvasItem.class);
        when(card.subjectId()).thenReturn(artifactId);
        when(items.requireArtifactItem(owner, project, sourceId)).thenReturn(card);
        when(artifacts.get(owner, project, artifactId)).thenReturn(new ArtifactService.ArtifactView(
                new Artifact(artifactId, project, Artifact.Kind.IMAGE, "Synthetic image", null, null, 0, NOW, NOW), null));
        when(repository.updateMediaDraft(any(), anyLong())).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void batchOutputKeepsCreativeInputsButGeneratesOneImage(int count) {
        MediaDraft source = source(count);
        var copied = service.duplicateBatchOutputWithinChange(owner, project, sourceId, targetId, source.version());

        assertThat(copied.parameters()).isEqualTo(parameters(1));
        assertThat(source.parameters().path("generationCount").asInt()).isEqualTo(count);
        assertThat(copied.prompt()).isEqualTo(source.prompt());
        assertThat(copied.capabilityId()).isEqualTo(capabilityId);
        assertThat(copied.styleId()).isEqualTo(styleId);
        assertThat(copied.mentions()).isEqualTo(source.mentions());
        assertThat(copied.mediaInputs()).singleElement().satisfies(input -> {
            assertThat(input.versionId()).isEqualTo(referenceId);
            assertThat(input.color()).isEqualTo(source.mediaInputs().getFirst().color());
            assertThat(input.sources()).singleElement().satisfies(origin -> {
                assertThat(origin.type()).isEqualTo(MediaDraft.SourceType.MANUAL);
                assertThat(origin.connectionId()).isNull();
            });
        });
    }

    @Test void ordinaryCopyKeepsTheSelectedBatchCount() {
        MediaDraft source = source(4);
        var copied = service.duplicateWithinChange(owner, project, sourceId, targetId, source.version());
        assertThat(copied.parameters()).isEqualTo(source.parameters());
        assertThat(copied.parameters()).isNotSameAs(source.parameters());
    }

    @Test void batchOutputOverridesAnInheritedBatchCountWhenTheDraftOmitsIt() {
        MediaDraft source = source(4);
        ((ObjectNode) source.parameters()).remove("generationCount");
        var copied = service.duplicateBatchOutputWithinChange(owner, project, sourceId, targetId, source.version());
        assertThat(copied.parameters().path("generationCount").asInt()).isEqualTo(1);
        assertThat(source.parameters().has("generationCount")).isFalse();
    }

    @Test void frozenOutputDoesNotChangeTheAcceptedBatchSnapshot() {
        ObjectNode frozen = mapper.createObjectNode().put("prompt", "Accepted prompt")
                .put("capabilityId", capabilityId.toString());
        frozen.set("parameters", parameters(4));
        frozen.putObject("style").put("id", styleId.toString());
        service.initializeFrozenWithinChange(project, targetId, Artifact.Kind.IMAGE, frozen);

        var saved = ArgumentCaptor.forClass(MediaDraft.class);
        verify(repository).updateMediaDraft(saved.capture(), eq(0L));
        assertThat(saved.getValue().parameters().path("generationCount").asInt()).isEqualTo(1);
        assertThat(saved.getValue().prompt()).isEqualTo("Accepted prompt");
        assertThat(saved.getValue().styleId()).isEqualTo(styleId);
        assertThat(frozen.at("/parameters/generationCount").asInt()).isEqualTo(4);
    }

    @Test void frozenNativeOutputKeepsItsProtocolParameters() {
        ObjectNode frozen = mapper.createObjectNode().put("capabilityId", capabilityId.toString());
        frozen.putObject("parameters").putObject(NativeMediaProtocol.VALUES_PROPERTY).put("imageCount", 4);
        service.initializeFrozenWithinChange(project, targetId, Artifact.Kind.IMAGE, frozen);

        var saved = ArgumentCaptor.forClass(MediaDraft.class);
        verify(repository).updateMediaDraft(saved.capture(), eq(0L));
        assertThat(saved.getValue().parameters()).isEqualTo(frozen.path("parameters"));
        assertThat(saved.getValue().parameters().has("generationCount")).isFalse();
    }

    private MediaDraft source(int count) {
        var input = new MediaDraft.MediaInput(referenceId, UUID.randomUUID(), MediaDraft.InputRole.REFERENCE,
                0, "#7C3AED", List.of(new MediaDraft.InputSource(UUID.randomUUID(),
                        MediaDraft.SourceType.CONNECTION, UUID.randomUUID())));
        var source = new MediaDraft(project, sourceId, "Use \uFFFC as reference", parameters(count), null,
                capabilityId, styleId, null, List.of(input),
                List.of(new MediaDraft.PromptMention(referenceId, MediaDraft.InputRole.REFERENCE)),
                MediaDraft.DisplayMode.DRAFT, 3, NOW, NOW);
        when(repository.findMediaDraft(project, sourceId)).thenReturn(Optional.of(source));
        return source;
    }

    private ObjectNode parameters(int count) {
        return mapper.createObjectNode().put("generationCount", count).put("aspectRatio", "9:16")
                .put("resolution", "4K").put("quality", "high").put("transparentBackground", true);
    }
}
