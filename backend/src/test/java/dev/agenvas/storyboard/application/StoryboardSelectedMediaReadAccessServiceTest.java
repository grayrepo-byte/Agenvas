package dev.agenvas.storyboard.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.storyboard.domain.Storyboard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoryboardSelectedMediaReadAccessServiceTest {
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), artifact = UUID.randomUUID();
    private final UUID node = UUID.randomUUID(), version = UUID.randomUUID();
    private final StoryboardRepository boards = mock(StoryboardRepository.class);
    private final CanvasItemQueryService items = mock(CanvasItemQueryService.class);
    private final StoryboardSelectedMediaReadAccessService service = new StoryboardSelectedMediaReadAccessService(boards, items);

    @Test void grantsOnlyExactSavedProductionAndCompositionSelections() {
        current(List.of(new Storyboard.Production(Storyboard.Role.KEYFRAME, node, version, 1, false)), null);
        assertThat(allows(version)).isTrue();
        assertThat(allows(UUID.randomUUID())).isFalse();
        current(List.of(), new Storyboard.Composition(node, version, true));
        assertThat(allows(version)).isTrue();
    }

    @Test void sourceReferencesAndEmptyProductionBindingsDoNotGrantMediaReads() {
        current(List.of(new Storyboard.Production(Storyboard.Role.KEYFRAME, node, null, 1, false)), null);
        assertThat(allows(version)).isFalse();
    }

    @Test void aRemovedNodeOrDifferentArtifactCannotReuseASavedSelectionAsAuthority() {
        current(List.of(new Storyboard.Production(Storyboard.Role.KEYFRAME, node, version, 1, false)), null);
        when(items.artifactItems(owner, project, artifact)).thenReturn(List.of());
        assertThat(allows(version)).isFalse();
        assertThat(service.allows(owner, project, UUID.randomUUID(), version)).isFalse();
        assertThat(service.allows(UUID.randomUUID(), project, artifact, version)).isFalse();
    }

    private void current(List<Storyboard.Production> productions, Storyboard.Composition composition) {
        var shot = new Storyboard.Shot(UUID.randomUUID(), "SHOT-001", "", "", "", "", BigDecimal.ONE,
                version, List.of(version), 1, productions);
        when(boards.current(project)).thenReturn(Optional.of(new Storyboard(project, 1, List.of(shot), composition)));
        CanvasItem item = mock(CanvasItem.class);
        when(item.id()).thenReturn(node);
        when(items.artifactItems(owner, project, artifact)).thenReturn(List.of(item));
    }
    private boolean allows(UUID selected) { return service.allows(owner, project, artifact, selected); }
}
