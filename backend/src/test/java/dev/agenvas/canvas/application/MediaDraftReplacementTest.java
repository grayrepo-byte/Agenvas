package dev.agenvas.canvas.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.node.JsonNodeFactory;

class MediaDraftReplacementTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID item = UUID.randomUUID();
    private final CanvasConnectionService connections = mock(CanvasConnectionService.class);
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final MediaDraftRestoreService service = new MediaDraftRestoreService(connections, drafts, events);

    @BeforeEach
    void executeMutationUnderTheProjectLock() {
        when(events.recordChange(any(), any(), any())).thenAnswer(invocation -> {
            Supplier<ProjectEventService.Change<MediaDraft>> mutation = invocation.getArgument(2);
            return new ProjectEventService.RecordedChange<>(mutation.get().value(), null);
        });
    }

    @Test
    void aStaleDraftCannotBeginWritingTemplateFields() {
        when(connections.clearTargetMediaConnectionsWithinChange(owner, project, item, 3))
                .thenThrow(new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        ApiMessage.of("api.canvas-connection-service.draft-version-conflict"),
                        ApiMessage.of("api.canvas-connection-service.the-target-draft-has-changed-before-restoring-history-input"), true));
        assertThatThrownBy(() -> service.replaceInputs(owner, project, item, 3,
                "Template prompt", JsonNodeFactory.instance.objectNode(), null, null,
                null, List.of(), List.of(), null)).isInstanceOf(ApiProblemException.class);
        verify(drafts, never()).save(any(), any(), any(), anyLong(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void replacementUsesTheVersionAfterTopologyCleanupAndKeepsGenerationSettings() {
        UUID capability = UUID.randomUUID();
        UUID style = UUID.randomUUID();
        var parameters = JsonNodeFactory.instance.objectNode().put("aspectRatio", "16:9");
        var inputs = List.of(new MediaDraftService.SaveMediaInput(UUID.randomUUID(),
                MediaDraft.InputRole.START_FRAME, "#7C3AED"));
        when(connections.clearTargetMediaConnectionsWithinChange(owner, project, item, 3))
                .thenReturn(5L);
        service.replaceInputs(owner, project, item, 3, "Camera moves forward", parameters,
                5, capability, MediaDraft.VideoInputMode.START_END, inputs, List.of(), style);
        verify(drafts).save(owner, project, item, 5, "Camera moves forward", parameters,
                5, capability, MediaDraft.VideoInputMode.START_END, inputs, List.of(), style);
        var order = inOrder(events, connections, drafts);
        order.verify(events).recordChange(any(), any(), any());
        order.verify(connections).clearTargetMediaConnectionsWithinChange(owner, project, item, 3);
        order.verify(drafts).save(owner, project, item, 5, "Camera moves forward", parameters,
                5, capability, MediaDraft.VideoInputMode.START_END, inputs, List.of(), style);
    }

    @Test
    void capabilitySwitchRetainsMatchedConnectionSourcesBeforeSavingTheCompleteDraft() {
        UUID previous = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        UUID retainedVersion = UUID.randomUUID();
        MediaDraft before = mock(MediaDraft.class);
        when(before.capabilityId()).thenReturn(previous);
        when(drafts.get(owner, project, item)).thenReturn(before);
        var inputs = List.of(new MediaDraftService.SaveMediaInput(retainedVersion,
                MediaDraft.InputRole.REFERENCE, "#7C3AED"));
        when(connections.retainTargetMediaConnectionsWithinChange(owner, project, item, 3,
                java.util.Set.of(retainedVersion))).thenReturn(4L);
        service.save(owner, project, item, 3, "Edit", null, null, next,
                null, inputs, List.of(), null);
        var order = inOrder(events, connections, drafts);
        order.verify(events).recordChange(any(), any(), any());
        order.verify(drafts).get(owner, project, item);
        order.verify(connections).retainTargetMediaConnectionsWithinChange(owner, project, item, 3,
                java.util.Set.of(retainedVersion));
        order.verify(drafts).save(owner, project, item, 4, "Edit", null, null, next,
                null, inputs, List.of(), null);
    }

    @Test
    void ordinaryAutosaveDoesNotClearConnectionOnlyInputs() {
        UUID capability = UUID.randomUUID();
        MediaDraft before = mock(MediaDraft.class);
        when(before.capabilityId()).thenReturn(capability);
        when(drafts.get(owner, project, item)).thenReturn(before);
        service.save(owner, project, item, 3, "Autosave", null, null, capability,
                null, List.of(), List.of(), null);
        verify(connections, never()).retainTargetMediaConnectionsWithinChange(any(), any(), any(), anyLong(), any());
        verify(drafts).save(owner, project, item, 3, "Autosave", null, null, capability,
                null, List.of(), List.of(), null);
    }
}
