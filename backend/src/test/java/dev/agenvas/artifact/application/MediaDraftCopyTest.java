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
import dev.agenvas.provider.domain.SeedreamModels;
import dev.agenvas.settings.application.MediaStyleService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

    @BeforeEach
    void configureSource() {
        var card = mock(CanvasItem.class);
        when(card.subjectId()).thenReturn(artifactId);
        when(items.requireArtifactItem(owner, project, sourceId)).thenReturn(card);
        when(artifacts.get(owner, project, artifactId)).thenReturn(new ArtifactService.ArtifactView(
                new Artifact(artifactId, project, Artifact.Kind.IMAGE, "Synthetic image", null, null, 0, NOW, NOW),
                null));
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

    @Test
    void ordinaryCopyKeepsTheSelectedBatchCount() {
        MediaDraft source = source(4);
        var copied = service.duplicateWithinChange(owner, project, sourceId, targetId, source.version());
        assertThat(copied.parameters()).isEqualTo(source.parameters());
        assertThat(copied.parameters()).isNotSameAs(source.parameters());
    }

    @Test
    void batchOutputOverridesAnInheritedBatchCountWhenTheDraftOmitsIt() {
        MediaDraft source = source(4);
        ((ObjectNode) source.parameters()).remove("generationCount");
        var copied = service.duplicateBatchOutputWithinChange(owner, project, sourceId, targetId, source.version());
        assertThat(copied.parameters().path("generationCount").asInt()).isEqualTo(1);
        assertThat(source.parameters().has("generationCount")).isFalse();
    }

    @Test
    void frozenOutputDoesNotChangeTheAcceptedBatchSnapshot() {
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

    @Test
    void frozenWorkflowOutputKeepsItsProtocolParameters() {
        ObjectNode frozen = mapper.createObjectNode().put("capabilityId", capabilityId.toString());
        frozen.putObject("parameters").putObject(NativeMediaProtocol.VALUES_PROPERTY).put("n", 4);
        service.initializeFrozenWithinChange(project, targetId, Artifact.Kind.IMAGE, frozen);

        var saved = ArgumentCaptor.forClass(MediaDraft.class);
        verify(repository).updateMediaDraft(saved.capture(), eq(0L));
        assertThat(saved.getValue().parameters()).isEqualTo(frozen.path("parameters"));
        assertThat(saved.getValue().parameters().has("generationCount")).isFalse();
    }

    @ParameterizedTest
    @MethodSource("nativeOutputParameters")
    void frozenNativeOutputResetsOnlySelectableImageBatchControls(String protocol, String adapter,
            String model, String operation, Artifact.Kind kind, String originalValues, String expectedValues) {
        ObjectNode frozen = mapper.createObjectNode().put("capabilityId", capabilityId.toString());
        frozen.putObject("parameters").set(NativeMediaProtocol.VALUES_PROPERTY, mapper.readTree(originalValues));
        if (kind == Artifact.Kind.VIDEO)
            frozen.put("mode", "TEXT");
        frozen.putObject("providerParameters").putObject(protocol)
                .put("adapterId", adapter).put("model", model).put("operation", operation);
        service.initializeFrozenWithinChange(project, targetId, kind, frozen);

        var saved = ArgumentCaptor.forClass(MediaDraft.class);
        verify(repository).updateMediaDraft(saved.capture(), eq(0L));
        assertThat(saved.getValue().parameters().path(NativeMediaProtocol.VALUES_PROPERTY))
                .isEqualTo(mapper.readTree(expectedValues));
        assertThat(frozen.at("/parameters/dynamicValues")).isEqualTo(mapper.readTree(originalValues));
        assertThat(saved.getValue().parameters().has("generationCount")).isFalse();
    }

    private static Stream<Arguments> nativeOutputParameters() {
        return Stream.of(
                Arguments.of("native", "WAN_IMAGE", "wan2.7-image", "GENERATE", Artifact.Kind.IMAGE,
                        "{\"n\":4,\"enable_sequential\":false,\"size\":\"2K\"}",
                        "{\"n\":1,\"enable_sequential\":false,\"size\":\"2K\"}"),
                Arguments.of("native", "WAN_IMAGE", "wan2.7-image-pro", "GENERATE", Artifact.Kind.IMAGE,
                        "{\"n\":4,\"enable_sequential\":true,\"size\":\"2K\"}",
                        "{\"n\":1,\"enable_sequential\":false,\"size\":\"2K\"}"),
                Arguments.of("native", SeedreamModels.ADAPTER_ID, SeedreamModels.LITE, SeedreamModels.GENERATE,
                        Artifact.Kind.IMAGE,
                        "{\"sequential_image_generation\":\"auto\",\"max_images\":3,\"output_format\":\"png\"}",
                        "{\"sequential_image_generation\":\"disabled\",\"max_images\":1,\"output_format\":\"png\"}"),
                Arguments.of("kling", "", "kling-v3", "GENERATE", Artifact.Kind.IMAGE,
                        "{\"n\":3,\"resolution\":\"2k\"}", "{\"n\":1,\"resolution\":\"2k\"}"),
                Arguments.of("kling", "", "kling-v3-omni", "GENERATE", Artifact.Kind.IMAGE,
                        "{\"result_type\":\"series\",\"series_amount\":\"auto\",\"resolution\":\"2k\"}",
                        "{\"result_type\":\"single\",\"series_amount\":\"auto\",\"resolution\":\"2k\",\"n\":1}"),
                Arguments.of("native", "WAN_VIDEO", "wan3.0-video", "GENERATE", Artifact.Kind.VIDEO,
                        "{\"resolution\":\"720P\",\"smart_duration\":true}",
                        "{\"resolution\":\"720P\",\"smart_duration\":true}"),
                Arguments.of("native", SeedreamModels.ADAPTER_ID, SeedreamModels.PRO, SeedreamModels.LAYERS,
                        Artifact.Kind.IMAGE,
                        "{\"size\":\"2K\"}", "{\"size\":\"2K\"}"),
                Arguments.of("kling", "", "", "SUBJECT_COMPLETION", Artifact.Kind.IMAGE,
                        "{}", "{}"));
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
