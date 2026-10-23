package dev.agenvas.storyboard.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.AgentMediaApprovalService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.storyboard.application.StoryboardService.*;
import dev.agenvas.storyboard.domain.Storyboard;
import dev.agenvas.storyboard.domain.Storyboard.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Focused review-state rules; persistence/CAS and exact-node ownership are exercised separately on PostgreSQL. */
class StoryboardServiceTest {
    final UUID owner = UUID.randomUUID();
    final UUID project = UUID.randomUUID();
    final StoryboardRepository repository = mock(StoryboardRepository.class);
    final ProjectEventService events = mock(ProjectEventService.class);
    final ArtifactService artifacts = mock(ArtifactService.class);
    final CanvasItemQueryService items = mock(CanvasItemQueryService.class);
    final CanvasService canvas = mock(CanvasService.class);
    final StoryboardService service = new StoryboardService(repository, mock(ProjectService.class), events,
            artifacts, items, canvas, mock(AgentMediaApprovalService.class), new ObjectMapper());
    Shot first;
    Shot second;
    Composition composition;

    @BeforeEach void setup() {
        first = shot("SHOT-001", "first"); second = shot("SHOT-002", "second");
        composition = new Composition(UUID.randomUUID(), UUID.randomUUID(), false);
        when(repository.current(project)).thenReturn(Optional.of(new Storyboard(project, 1, List.of(first, second), composition)));
        when(repository.append(any(), anyLong())).thenReturn(true);
        when(events.recordChange(any(), any(), any())).thenAnswer(invocation -> {
            Supplier<ProjectEventService.Change<Storyboard>> change = invocation.getArgument(2);
            return new ProjectEventService.RecordedChange<>(change.get().value(), null);
        });
    }

    @Test void creativeChangeOnlyMarksItsOwnPinnedResultsAndCompositionForReview() {
        var changed = input(first, "new dialogue", false);
        var next = service.save(owner, project, new Save(1, List.of(changed, input(second, second.dialogue(), false)), film(false)));
        assertThat(next.shots().getFirst().revision()).isEqualTo(2);
        assertThat(next.shots().getFirst().productions().getFirst().selectedVersionId()).isEqualTo(first.productions().getFirst().selectedVersionId());
        assertThat(next.shots().getFirst().productions().getFirst().reviewedRevision()).isEqualTo(1);
        assertThat(next.shots().getFirst().productions().getFirst().needsReview()).isTrue();
        assertThat(next.shots().get(1)).isEqualTo(second);
        assertThat(next.composition().needsReview()).isTrue();
        verifyNoInteractions(artifacts, items, canvas);
    }

    @Test void explicitConfirmationReviewsTheCurrentRevisionWithoutChangingSelectedResults() {
        var next = service.save(owner, project, new Save(1,
                List.of(input(first, "new dialogue", true), input(second, second.dialogue(), false)), film(true)));
        var result = next.shots().getFirst().productions().getFirst();
        assertThat(result.reviewedRevision()).isEqualTo(2);
        assertThat(result.needsReview()).isFalse();
        assertThat(next.composition().needsReview()).isFalse();
        verify(repository).append(next, 1);
    }

    @Test void reorderingOnlyMarksCompositionWhileMetadataKeepsShotRevision() {
        var renamed = new ShotInput(first.id(), "SHOT-003", "SC-02", "Renamed", first.description(), first.dialogue(),
                first.durationSeconds(), null, List.of(), input(first, first.dialogue(), false).productions());
        var next = service.save(owner, project, new Save(1, List.of(input(second, second.dialogue(), false), renamed), film(false)));
        assertThat(next.shots().get(1).revision()).isEqualTo(1);
        assertThat(next.shots().get(1).productions().getFirst().needsReview()).isFalse();
        assertThat(next.composition().needsReview()).isTrue();
    }

    @Test void confirmingSlotsInADifferentSerializationOrderDoesNotInvalidateComposition() {
        var audio = first.productions().getFirst();
        var keyframe = new Production(Role.KEYFRAME, UUID.randomUUID(), UUID.randomUUID(), 1, false);
        var multiRole = new Shot(first.id(), first.code(), first.scene(), first.title(), first.description(),
                first.dialogue(), first.durationSeconds(), null, List.of(), 1, List.of(audio, keyframe));
        when(repository.current(project)).thenReturn(Optional.of(new Storyboard(project, 1,
                List.of(multiRole, second), composition)));
        var reordered = new ShotInput(first.id(), first.code(), first.scene(), first.title(), first.description(),
                first.dialogue(), first.durationSeconds(), null, List.of(),
                List.of(new ProductionInput(keyframe.role(), keyframe.canvasItemId(), keyframe.selectedVersionId(), true),
                        new ProductionInput(audio.role(), audio.canvasItemId(), audio.selectedVersionId(), false)));
        var next = service.save(owner, project, new Save(1,
                List.of(reordered, input(second, second.dialogue(), false)), film(false)));
        assertThat(next.composition().needsReview()).isFalse();
        assertThat(next.shots().getFirst().revision()).isEqualTo(1);
    }

    private Shot shot(String code, String dialogue) {
        return new Shot(UUID.randomUUID(), code, "SC-01", "title", "description", dialogue, BigDecimal.ONE,
                null, List.of(), 1, List.of(new Production(Role.AUDIO, UUID.randomUUID(), UUID.randomUUID(), 1, false)));
    }
    private ShotInput input(Shot shot, String dialogue, boolean confirm) {
        var p = shot.productions().getFirst();
        return new ShotInput(shot.id(), shot.code(), shot.scene(), shot.title(), shot.description(), dialogue,
                shot.durationSeconds(), shot.sourceVersionId(), shot.referenceVersionIds(),
                List.of(new ProductionInput(p.role(), p.canvasItemId(), p.selectedVersionId(), confirm)));
    }
    private CompositionInput film(boolean confirm) {
        return new CompositionInput(composition.canvasItemId(), composition.selectedVersionId(), confirm);
    }
}
