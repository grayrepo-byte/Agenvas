package dev.agenvas.artifact.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.domain.ComfyUiWorkflowDefinition;
import dev.agenvas.support.ComfyWorkflowFixture;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.settings.application.MediaStyleService;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic published contracts: connecting a card changes draft inputs, never generation. */
class MediaDraftConnectionsTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID item = UUID.randomUUID();
    private final UUID artifact = UUID.randomUUID();
    private final UUID sourceArtifact = UUID.randomUUID();
    private final UUID version = UUID.randomUUID();
    private final UUID capability = UUID.randomUUID();
    private final UUID connection = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArtifactRepository repository = mock(ArtifactRepository.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final CanvasItemQueryService items = mock(CanvasItemQueryService.class);
    private final MediaCapabilityService capabilities = mock(MediaCapabilityService.class);
    private final MediaCapabilityBinding binding = new MediaCapabilityBinding(UUID.randomUUID(), 1,
            capability, 1, "RUNNINGHUB_VIDEO_V2", "synthetic-hash");
    private final MediaDraftService service = new MediaDraftService(mock(ProjectService.class),
            artifacts, repository, items, mock(ProjectEventService.class), mapper,
            Clock.fixed(NOW, java.time.ZoneOffset.UTC), capabilities, mock(MediaStyleService.class));

    @BeforeEach
    void configureCard() {
        CanvasItem card = mock(CanvasItem.class);
        when(card.subjectId()).thenReturn(artifact);
        when(items.requireArtifactItem(owner, project, item)).thenReturn(card);
        when(artifacts.get(owner, project, artifact)).thenReturn(new ArtifactService.ArtifactView(
                mediaArtifact(artifact, Artifact.Kind.VIDEO), null));
        when(artifacts.get(owner, project, sourceArtifact)).thenReturn(new ArtifactService.ArtifactView(
                mediaArtifact(sourceArtifact, Artifact.Kind.IMAGE), null));
        when(repository.findVersionTarget(project, version)).thenReturn(Optional.of(
                new ArtifactRepository.VersionTarget(version, sourceArtifact, Artifact.Kind.IMAGE)));
        when(repository.updateMediaDraft(any(), anyLong())).thenReturn(true);
        when(capabilities.forDraft(capability, Task.Kind.VIDEO_GENERATION)).thenReturn(binding);
    }

    @Test
    void connectsAnImageToTheNamedSlotOfATextWorkflowDraft() {
        workflow(List.of(slot("subject", RunningHubDefinition.FieldType.IMAGE)));
        draft(mapper.createObjectNode(), List.of());

        MediaDraft saved = service.addConnectionInputWithinChange(owner, project, item, 0,
                version, connection);

        assertThat(saved.parameters().path("dynamicValues").path("subject").asText())
                .isEqualTo(version.toString());
        assertThat(saved.mediaInputs()).singleElement().satisfies(input -> {
            assertThat(input.versionId()).isEqualTo(version);
            assertThat(input.role()).isEqualTo(MediaDraft.InputRole.REFERENCE);
            assertThat(input.sources()).singleElement().satisfies(source ->
                    assertThat(source.connectionId()).isEqualTo(connection));
        });
        assertThat(saved.videoInputMode()).isEqualTo(MediaDraft.VideoInputMode.GENERAL_REFERENCE);
    }

    @Test
    void disconnectingTheFinalSourceClearsTheNamedSlot() {
        workflow(List.of(slot("subject", RunningHubDefinition.FieldType.IMAGE)));
        ObjectNode parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("subject", version.toString());
        draft(parameters, List.of(new MediaDraft.MediaInput(version, sourceArtifact,
                MediaDraft.InputRole.REFERENCE, 0, "#7C3AED", List.of(new MediaDraft.InputSource(
                        UUID.randomUUID(), MediaDraft.SourceType.CONNECTION, connection)))));

        MediaDraft saved = service.removeConnectionInputWithinChange(owner, project, item, 0, connection);

        assertThat(saved.parameters().path("dynamicValues").has("subject")).isFalse();
        assertThat(saved.mediaInputs()).isEmpty();
    }

    @Test
    void multipleCompatibleSlotsFillInPublishedOrderWithoutAnotherInteraction() {
        workflow(List.of(slot("subject", RunningHubDefinition.FieldType.IMAGE),
                slot("background", RunningHubDefinition.FieldType.IMAGE)));
        draft(mapper.createObjectNode(), List.of());
        MediaDraft first = service.addConnectionInputWithinChange(owner, project, item,
                0, version, connection);
        assertThat(first.parameters().path("dynamicValues").path("subject").asText()).isEqualTo(version.toString());
        assertThat(first.parameters().path("dynamicValues").has("background")).isFalse();
        MediaDraft saved = connect("background");
        assertThat(saved.parameters().path("dynamicValues").path("background").asText()).isEqualTo(version.toString());
        assertThat(saved.parameters().path("dynamicValues").has("subject")).isFalse();
    }

    @Test
    void occupiedAndWrongTypeSlotsRejectBeforeChangingTheDraft() {
        workflow(List.of(slot("subject", RunningHubDefinition.FieldType.IMAGE),
                slot("music", RunningHubDefinition.FieldType.AUDIO)));
        ObjectNode parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("subject", UUID.randomUUID().toString());
        draft(parameters, List.of());
        assertThatThrownBy(() -> connect("subject")).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> connect("music")).isInstanceOf(ApiProblemException.class);
    }

    @Test
    void anInactiveConditionalSlotRejectsButItsDeclaredDefaultCanEnableIt() {
        var enabled = new RunningHubDefinition.Field("enabled", "Enabled", null,
                RunningHubDefinition.FieldType.BOOLEAN, false, mapper.valueToTree(true), null,
                null, null, null, false, "1", "enabled", null, null, null, null);
        var image = slot("subject", RunningHubDefinition.FieldType.IMAGE);
        var conditional = new RunningHubDefinition.Field(image.key(), image.label(), null,
                image.type(), true, null, null, null, null, null, false, "2", "image", null,
                null, null, new RunningHubDefinition.Condition("enabled", mapper.valueToTree(true)));
        workflow(List.of(enabled, conditional));
        ObjectNode disabled = mapper.createObjectNode();
        disabled.putObject("dynamicValues").put("enabled", false);
        draft(disabled, List.of());
        assertThatThrownBy(() -> connect("subject")).isInstanceOf(ApiProblemException.class);
        draft(mapper.createObjectNode(), List.of());
        assertThat(connect("subject").mediaInputs()).hasSize(1);
    }

    @Test
    void theSameVersionCanFillAnotherSlotWithoutDuplicatingItsInputOrConnectionSource() {
        workflow(List.of(slot("subject", RunningHubDefinition.FieldType.IMAGE),
                slot("background", RunningHubDefinition.FieldType.IMAGE)));
        ObjectNode parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("subject", version.toString());
        draft(parameters, List.of(connectedInput()));
        MediaDraft saved = connect("background");
        assertThat(saved.mediaInputs()).singleElement().satisfies(input -> assertThat(input.sources()).hasSize(1));
        assertThat(saved.parameters().path("dynamicValues").path("subject").asText()).isEqualTo(version.toString());
        assertThat(saved.parameters().path("dynamicValues").path("background").asText()).isEqualTo(version.toString());
    }

    @Test
    void removingALineKeepsTheSlotWhenAManualSourceStillExists() {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("subject", version.toString());
        var input = connectedInput();
        draft(parameters, List.of(new MediaDraft.MediaInput(input.versionId(), input.artifactId(),
                input.role(), 0, input.color(), List.of(input.sources().getFirst(),
                        new MediaDraft.InputSource(UUID.randomUUID(), MediaDraft.SourceType.MANUAL, null)))));
        MediaDraft saved = service.removeConnectionInputWithinChange(owner, project, item, 0, connection);
        assertThat(saved.parameters().path("dynamicValues").path("subject").asText()).isEqualTo(version.toString());
        assertThat(saved.mediaInputs()).hasSize(1);
    }

    @Test
    void runningHubAcceptsDeclaredVideoAndAudioSlotsOnAnImageOutputCard() {
        when(artifacts.get(owner, project, artifact)).thenReturn(new ArtifactService.ArtifactView(
                mediaArtifact(artifact, Artifact.Kind.IMAGE), null));
        when(capabilities.forDraft(capability, Task.Kind.IMAGE_GENERATION)).thenReturn(binding);
        for (Artifact.Kind kind : List.of(Artifact.Kind.AUDIO, Artifact.Kind.VIDEO)) {
            when(repository.findVersionTarget(project, version)).thenReturn(Optional.of(
                    new ArtifactRepository.VersionTarget(version, sourceArtifact, kind)));
            when(artifacts.get(owner, project, sourceArtifact)).thenReturn(new ArtifactService.ArtifactView(
                    mediaArtifact(sourceArtifact, kind), null));
            workflow(List.of(slot("source", RunningHubDefinition.FieldType.valueOf(kind.name()))));
            draft(mapper.createObjectNode(), List.of());
            assertThat(connect("source").mediaInputs()).singleElement().satisfies(input ->
                    assertThat(MediaDraftService.mediaKind(input.role())).isEqualTo(kind));
        }
    }

    @Test
    void comfyConnectionsPreserveLegacyPositionalSlotsAndBindTheNewSlot() {
        var settings = ComfyWorkflowFixture.settings(mapper, true, true);
        var graph = settings.path("comfyWorkflow").path("graph");
        ((ObjectNode) graph).putObject("15").put("class_type", "LoadImage").putObject("inputs").put("image", "second.png");
        ((ObjectNode) graph.path("14").path("inputs")).putArray("secondReference").add("15").add(0);
        ((tools.jackson.databind.node.ArrayNode) settings.path("comfyWorkflow").path("bindings"))
                .addObject().put("nodeId", "15").put("inputName", "image")
                .put("source", "REFERENCE_IMAGE").put("referenceIndex", 1);
        when(capabilities.comfyWorkflowDefinition(binding)).thenReturn(ComfyUiWorkflowDefinition.parse(
                mapper, settings.path("comfyWorkflow"), Task.Kind.VIDEO_GENERATION));
        UUID previous = UUID.randomUUID();
        draft(mapper.createObjectNode(), List.of(new MediaDraft.MediaInput(previous, sourceArtifact,
                MediaDraft.InputRole.REFERENCE, 0, "#7C3AED", List.of(new MediaDraft.InputSource(
                        UUID.randomUUID(), MediaDraft.SourceType.MANUAL, null)))));
        var saved = service.addConnectionInputWithinChange(owner, project, item, 0, version, connection);
        assertThat(saved.parameters().path("dynamicValues").path("reference_0").asText()).isEqualTo(previous.toString());
        assertThat(saved.parameters().path("dynamicValues").path("reference_1").asText()).isEqualTo(version.toString());
    }

    @Test
    void aStaleConnectionCannotAssignAnySlot() {
        workflow(List.of(slot("subject", RunningHubDefinition.FieldType.IMAGE)));
        draft(mapper.createObjectNode(), List.of());
        assertThatThrownBy(() -> service.addConnectionInputWithinChange(owner, project, item,
                1, version, connection, false, "subject")).isInstanceOf(ApiProblemException.class);
    }

    @Test
    void explicitLibraryReplacementDropsOnlyAnOldVersionUnusedByOtherSlots() {
        workflow(List.of(slot("subject", RunningHubDefinition.FieldType.IMAGE),
                slot("background", RunningHubDefinition.FieldType.IMAGE)));
        UUID old = UUID.randomUUID();
        ObjectNode parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("subject", old.toString());
        var inputs = List.of(new MediaDraftService.SaveMediaInput(old, MediaDraft.InputRole.REFERENCE, "#7C3AED"));
        var replaced = service.assignWorkflowSlotParameters(owner, project, item, parameters,
                "Synthetic prompt", null, capability, inputs, Artifact.Kind.IMAGE, version, "subject");
        assertThat(replaced.removedVersionId()).isEqualTo(old);
        assertThat(replaced.parameters().path("dynamicValues").path("subject").asText()).isEqualTo(version.toString());

        parameters.withObject("dynamicValues").put("background", old.toString());
        var shared = service.assignWorkflowSlotParameters(owner, project, item, parameters,
                "Synthetic prompt", null, capability, inputs, Artifact.Kind.IMAGE, version, "subject");
        assertThat(shared.removedVersionId()).isNull();
        assertThat(shared.parameters().path("dynamicValues").path("background").asText()).isEqualTo(old.toString());
    }

    @Test
    void disconnectingAnInputPreservesAStringParameterWithTheSameUuidText() {
        workflow(List.of(slot("subject", RunningHubDefinition.FieldType.IMAGE),
                slot("caption", RunningHubDefinition.FieldType.STRING)));
        ObjectNode parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("subject", version.toString()).put("caption", version.toString());
        draft(parameters, List.of(connectedInput()));
        var saved = service.removeConnectionInputWithinChange(owner, project, item, 0, connection);
        assertThat(saved.parameters().path("dynamicValues").has("subject")).isFalse();
        assertThat(saved.parameters().path("dynamicValues").path("caption").asText()).isEqualTo(version.toString());
    }

    private MediaDraft connect(String slotKey) {
        return service.addConnectionInputWithinChange(owner, project, item, 0, version, connection,
                false, slotKey);
    }

    private MediaDraft.MediaInput connectedInput() {
        return new MediaDraft.MediaInput(version, sourceArtifact, MediaDraft.InputRole.REFERENCE,
                0, "#7C3AED", List.of(new MediaDraft.InputSource(UUID.randomUUID(),
                        MediaDraft.SourceType.CONNECTION, connection)));
    }

    private void workflow(List<RunningHubDefinition.Field> fields) {
        when(capabilities.declaredDraftInputs(capability)).thenReturn(fields);
        when(capabilities.runningHubDefinition(binding)).thenReturn(new RunningHubDefinition(1,
                "V2", RunningHubDefinition.TargetType.WORKFLOW, "123456", fields, List.of(), List.of(),
                "default", false, false, null, null));
    }

    private void draft(ObjectNode parameters, List<MediaDraft.MediaInput> inputs) {
        when(repository.findMediaDraft(project, item)).thenReturn(Optional.of(new MediaDraft(project,
                item, "Synthetic prompt", parameters, null, capability, null, MediaDraft.VideoInputMode.TEXT,
                inputs, List.of(), MediaDraft.DisplayMode.DRAFT, 0, NOW, NOW)));
    }

    private RunningHubDefinition.Field slot(String key, RunningHubDefinition.FieldType type) {
        return new RunningHubDefinition.Field(key, key, null, type, true, null, null, null,
                null, null, false, "1", key, RunningHubDefinition.Source.PARAMETER,
                RunningHubDefinition.Encoding.NATIVE, RunningHubDefinition.ResourceFormat.FILE_NAME, null);
    }

    private Artifact mediaArtifact(UUID id, Artifact.Kind kind) {
        return new Artifact(id, project, kind, "Synthetic media", null, null, 0, NOW, NOW);
    }
}
