package dev.agenvas.storyboard.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.storyboard.domain.Storyboard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoryboardMediaLinkServiceTest {
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), shotId = UUID.randomUUID();
    private final StoryboardRepository boards = mock(StoryboardRepository.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final StoryboardMediaLinkService service = new StoryboardMediaLinkService(boards, projects);

    @Test void freezesOnlyServerShotIdentityRevisionAndKindDerivedRole() {
        current(4, "SHOT-1");
        assertThat(service.freeze(owner, project, shotId, 4, Artifact.Kind.IMAGE))
                .isEqualTo(new StoryboardMediaLinkService.Link(shotId, "SHOT-1", 4, Storyboard.Role.KEYFRAME));
        assertThat(service.freeze(owner, project, shotId, 4, Artifact.Kind.VIDEO).role()).isEqualTo(Storyboard.Role.VIDEO);
        assertThat(service.freeze(owner, project, shotId, 4, Artifact.Kind.AUDIO).role()).isEqualTo(Storyboard.Role.AUDIO);
        verify(projects, org.mockito.Mockito.times(3)).get(owner, project);
    }

    @Test void refusesMissingOrStaleShotAtProposal() {
        current(4, "SHOT-1");
        assertThatThrownBy(() -> service.freeze(owner, project, shotId, 3, Artifact.Kind.VIDEO))
                .isInstanceOfSatisfying(ApiProblemException.class, failure -> assertThat(failure.code()).isEqualTo("TOOL_ARGUMENT_INVALID"));
        assertThatThrownBy(() -> service.freeze(owner, project, UUID.randomUUID(), 4, Artifact.Kind.VIDEO))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test void approvalChecksOnlyTheLinkedCreativeRevision() {
        current(4, "renamed-code");
        service.requireCurrent(owner, project, new StoryboardMediaLinkService.Link(shotId, "old-code", 4, Storyboard.Role.VIDEO));
        current(5, "renamed-code");
        assertThatThrownBy(() -> service.requireCurrent(owner, project,
                new StoryboardMediaLinkService.Link(shotId, "old-code", 4, Storyboard.Role.VIDEO)))
                .isInstanceOfSatisfying(ApiProblemException.class, failure -> assertThat(failure.code()).isEqualTo("AGENT_MEDIA_APPROVAL_STALE"));
    }

    @Test void removingTheShotMakesApprovalStale() {
        when(boards.current(project)).thenReturn(Optional.of(Storyboard.empty(project)));
        assertThatThrownBy(() -> service.requireCurrent(owner, project,
                new StoryboardMediaLinkService.Link(shotId, "SHOT-1", 1, Storyboard.Role.KEYFRAME)))
                .isInstanceOfSatisfying(ApiProblemException.class, failure -> assertThat(failure.code()).isEqualTo("AGENT_MEDIA_APPROVAL_STALE"));
    }

    private void current(int revision, String code) {
        when(boards.current(project)).thenReturn(Optional.of(new Storyboard(project, 10,
                List.of(new Storyboard.Shot(shotId, code, "", "", "", "", BigDecimal.ONE, null, List.of(), revision, List.of())), null)));
    }
}
