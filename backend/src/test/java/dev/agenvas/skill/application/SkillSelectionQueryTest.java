package dev.agenvas.skill.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.SkillAssetArchiveService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.library.application.LibraryService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import dev.agenvas.skill.domain.SkillContent;
import dev.agenvas.skill.infrastructure.JooqSkillInstallRepository;
import dev.agenvas.skill.infrastructure.SkillRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

/** Public selection paths share one binding read while preserving exact-version eligibility. */
class SkillSelectionQueryTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    @ParameterizedTest
    @EnumSource(Entry.class)
    void multipleCandidatesReadBindingsOnceAndRetainOnlyTheirExactPublication(Entry entry) {
        Fixture fixture = new Fixture();

        fixture.invoke(entry);

        verify(fixture.repository).bindings(fixture.owner, fixture.project, fixture.agentId);
        // Preview also reads the current display title; eligibility itself needs one catalogue read.
        int catalogueBudget = entry == Entry.PREVIEW ? 2 : 1;
        verify(fixture.repository, times(catalogueBudget)).skill(fixture.owner, fixture.retainedSkill, false);
        verify(fixture.repository, times(catalogueBudget)).skill(fixture.owner, fixture.changedSkill, false);
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void aBindingToAnOlderVersionDoesNotMakeANewTrashedVersionSelectable(Entry entry) {
        Fixture fixture = new Fixture();
        when(fixture.repository.skill(fixture.owner, fixture.changedSkill, false))
                .thenReturn(Optional.of(fixture.catalogue(fixture.changedSkill, fixture.newVersion, null, NOW)));

        assertThatThrownBy(() -> fixture.invoke(entry)).isInstanceOfSatisfying(ApiProblemException.class,
                problem -> assertThat(problem.code()).isEqualTo("SKILL_TRASHED"));

        verify(fixture.repository).bindings(fixture.owner, fixture.project, fixture.agentId);
        verify(fixture.repository, never()).version(fixture.owner, fixture.changedSkill, fixture.newVersion);
        verify(fixture.agents, never()).touchConfigurationWithinChange(any(), any(), any(), anyLong());
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void evenAnExactRetainedVersionCannotStartNewWorkAfterBuiltinRetirement(Entry entry) {
        Fixture fixture = new Fixture();
        when(fixture.repository.skill(fixture.owner, fixture.retainedSkill, false))
                .thenReturn(Optional.of(fixture.catalogue(fixture.retainedSkill, fixture.retainedVersion,
                        "retired-edition/synthetic-skill", NOW)));

        assertThatThrownBy(() -> fixture.invoke(entry)).isInstanceOfSatisfying(ApiProblemException.class,
                problem -> assertThat(problem.code()).isEqualTo("SKILL_NOT_FOUND"));

        verify(fixture.repository, never()).version(fixture.owner, fixture.retainedSkill, fixture.retainedVersion);
        verify(fixture.agents, never()).touchConfigurationWithinChange(any(), any(), any(), anyLong());
    }

    enum Entry { PREVIEW, FREEZE, SAVE_BINDING }

    private static final class Fixture {
        private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), agentId = UUID.randomUUID();
        private final UUID retainedSkill = UUID.randomUUID(), retainedVersion = UUID.randomUUID();
        private final UUID changedSkill = UUID.randomUUID(), oldVersion = UUID.randomUUID(), newVersion = UUID.randomUUID();
        private final SkillRepository repository = mock(SkillRepository.class);
        private final AgentInstanceService agents = mock(AgentInstanceService.class);
        private final AgentInstance agent = mock(AgentInstance.class);
        private final ObjectMapper mapper = new ObjectMapper();
        private final List<SkillRunService.Choice> choices = List.of(
                new SkillRunService.Choice(retainedSkill, retainedVersion, List.of()),
                new SkillRunService.Choice(changedSkill, newVersion, List.of()));
        private final SkillRunService.Selection selection = new SkillRunService.Selection(
                SkillRunService.SelectionMode.VERSIONS, choices);
        private final SkillRunService runSkills;

        private Fixture() {
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            BuiltinSkillCatalogue builtins = mock(BuiltinSkillCatalogue.class);
            when(builtins.keys()).thenReturn(List.of("current-edition/synthetic-skill"));
            ObjectProvider<LibraryService> library = libraryProvider();
            SkillAssetArchiveService archive = mock(SkillAssetArchiveService.class);
            SkillService skills = new SkillService(repository, new SkillFormat(), builtins, library, archive,
                    mapper, clock, transactions);
            JooqSkillInstallRepository operations = mock(JooqSkillInstallRepository.class);
            when(operations.bindingCommand(owner, project, "synthetic-binding")).thenReturn(Optional.empty());
            ProjectEventService events = mock(ProjectEventService.class);
            when(events.recordChange(eq(owner), eq(project), any())).thenAnswer(invocation -> {
                Supplier<ProjectEventService.Change<?>> mutation = invocation.getArgument(2);
                return new ProjectEventService.RecordedChange<>(mutation.get().value(), null);
            });
            runSkills = new SkillRunService(skills, operations, archive, agents, mock(ArtifactService.class),
                    mock(ProjectService.class), events, mapper, clock, new ShutdownGate(), transactions);
            when(agent.id()).thenReturn(agentId);
            when(agent.version()).thenReturn(1L);
            when(agent.bindings()).thenReturn(List.of());
            when(agents.get(owner, project, agentId)).thenReturn(agent);
            AgentInstance changedAgent = mock(AgentInstance.class);
            when(changedAgent.version()).thenReturn(2L);
            when(agents.touchConfigurationWithinChange(owner, project, agentId, 1)).thenReturn(changedAgent);
            when(repository.bindings(owner, project, agentId)).thenReturn(List.of(
                    new SkillContent.Binding(agentId, project, owner, retainedSkill, retainedVersion, 0, NOW),
                    new SkillContent.Binding(agentId, project, owner, changedSkill, oldVersion, 1, NOW)));
            // This exact personal version remains available through its binding after trashing.
            when(repository.skill(owner, retainedSkill, false))
                    .thenReturn(Optional.of(catalogue(retainedSkill, retainedVersion, null, NOW)));
            when(repository.skill(owner, changedSkill, false))
                    .thenReturn(Optional.of(catalogue(changedSkill, newVersion, null, null)));
            when(repository.version(owner, retainedSkill, retainedVersion))
                    .thenReturn(Optional.of(version(retainedSkill, retainedVersion)));
            when(repository.version(owner, changedSkill, newVersion))
                    .thenReturn(Optional.of(version(changedSkill, newVersion)));
        }

        private void invoke(Entry entry) {
            switch (entry) {
                case PREVIEW -> assertThat(runSkills.preview(owner, project, agentId, selection))
                        .extracting(SkillRunService.Summary::skillVersionId)
                        .containsExactly(retainedVersion, newVersion);
                case FREEZE -> {
                    var context = mapper.createObjectNode();
                    runSkills.freezeIntoRun(owner, project, agent, UUID.randomUUID(), selection, context);
                    assertThat(context.path("creativeSkills")).hasSize(2);
                    assertThat(context.at("/creativeSkills/0/skillVersionId").asText()).isEqualTo(retainedVersion.toString());
                    assertThat(context.at("/creativeSkills/1/skillVersionId").asText()).isEqualTo(newVersion.toString());
                }
                case SAVE_BINDING -> {
                    var selected = choices.stream().map(choice ->
                            new SkillRunService.VersionRef(choice.skillId(), choice.skillVersionId())).toList();
                    var saved = runSkills.saveBinding(owner, project, agentId, 1, selected, "synthetic-binding");
                    assertThat(saved.skills()).containsExactlyElementsOf(selected);
                    assertThat(saved.agentVersion()).isEqualTo(2);
                }
            }
        }

        private SkillContent.Catalogue catalogue(UUID skill, UUID version, String builtinKey, Instant trashedAt) {
            return new SkillContent.Catalogue(skill, owner, "Synthetic method", "Synthetic description",
                    builtinKey, version, trashedAt, 1, NOW, NOW);
        }

        private SkillContent.Version version(UUID skill, UUID version) {
            var bundle = new SkillContent.Bundle(1, "synthetic-method", "Synthetic description", "Synthetic instructions",
                    List.of(Artifact.Kind.TEXT), List.of(), List.of(), List.of());
            return new SkillContent.Version(version, owner, skill, 1, "0".repeat(64), bundle, NOW);
        }

        @SuppressWarnings("unchecked")
        private static ObjectProvider<LibraryService> libraryProvider() {
            return mock(ObjectProvider.class);
        }
    }
}
