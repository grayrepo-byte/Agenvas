package dev.agenvas.llm.application;

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
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.storyboard.application.StoryboardService;
import dev.agenvas.storyboard.domain.Storyboard;
import dev.agenvas.storyboard.domain.Storyboard.Composition;
import dev.agenvas.storyboard.domain.Storyboard.Production;
import dev.agenvas.storyboard.domain.Storyboard.Role;
import dev.agenvas.storyboard.domain.Storyboard.Shot;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Tool contracts are mocked here; board CAS, events and media approval are covered on PostgreSQL separately. */
class StoryboardAgentToolServiceTest {
    final UUID owner = UUID.randomUUID();
    final UUID project = UUID.randomUUID();
    final UUID operation = UUID.randomUUID();
    final TrustedToolContext context = new TrustedToolContext(owner, project, UUID.randomUUID());
    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode snapshot = mapper.createObjectNode().put("scope", "frozen-run");
    final AgentRun run = mock(AgentRun.class);
    final StoryboardService boards = mock(StoryboardService.class);
    final ArtifactService artifacts = mock(ArtifactService.class);
    final StoryboardAgentToolService tools = new StoryboardAgentToolService(boards, artifacts, mapper);
    final Shot first = shot("SHOT-001");
    final Shot second = shot("SHOT-002");
    final Composition composition = new Composition(UUID.randomUUID(), UUID.randomUUID(), true);
    Storyboard prior;

    @BeforeEach void setup() {
        when(run.contextSnapshot()).thenReturn(snapshot);
        prior = new Storyboard(project, 5, List.of(first, second), composition);
        when(boards.get(owner, project)).thenReturn(prior);
        when(boards.save(eq(owner), eq(project), any())).thenReturn(new Storyboard(project, 6, prior.shots(), composition));
    }

    @Test void directoryReturnsBoundedUnicodePreviewsAndProductionReferencesWithoutLoadingArtifacts() {
        String description = "🎬".repeat(300);
        var longShot = new Shot(first.id(), first.code(), first.scene(), first.title(), description, "台词",
                first.durationSeconds(), first.sourceVersionId(), first.referenceVersionIds(), first.revision(), first.productions());
        when(boards.get(owner, project)).thenReturn(new Storyboard(project, 5, List.of(longShot, second), composition));

        JsonNode result = read("{\"limit\":1}");
        JsonNode data = result.path("data");
        assertThat(data.path("version").longValue()).isEqualTo(5);
        assertThat(data.path("total").intValue()).isEqualTo(2);
        assertThat(data.path("offset").intValue()).isZero();
        assertThat(data.path("nextOffset").intValue()).isEqualTo(1);
        assertThat(data.path("compositionNeedsReview").booleanValue()).isTrue();
        JsonNode shot = data.path("shots").get(0);
        assertThat(shot.path("descriptionPreview").asText()).isEqualTo("🎬".repeat(256));
        assertThat(shot.path("descriptionCharacters").intValue()).isEqualTo(300);
        assertThat(shot.path("descriptionTruncated").booleanValue()).isTrue();
        assertThat(shot.path("dialogueTruncated").booleanValue()).isFalse();
        assertThat(shot.has("description")).isFalse();
        assertThat(shot.path("productions").get(0).path("selectedVersionId").asText())
                .isEqualTo(first.productions().getFirst().selectedVersionId().toString());
        assertThat(result.path("taskIds").isEmpty()).isTrue();
        verifyNoInteractions(artifacts);
        verify(boards, never()).save(any(), any(), any());
    }

    @Test void defaultDirectoryIsPagedAndExplicitShotReadReturnsOnlyThatFullCreativeRecord() {
        List<Shot> manyShots = IntStream.range(0, 41).mapToObj(index -> shot("SHOT-" + index)).toList();
        when(boards.get(owner, project)).thenReturn(new Storyboard(project, 5, manyShots, composition));
        JsonNode directory = read("{}").path("data");
        assertThat(directory.path("shots").size()).isEqualTo(20);
        assertThat(directory.path("nextOffset").intValue()).isEqualTo(20);
        JsonNode last = read("{\"offset\":40,\"limit\":40}").path("data");
        assertThat(last.path("shots").size()).isEqualTo(1);
        assertThat(last.path("nextOffset").isNull()).isTrue();
        assertThat(read("{\"offset\":41}").path("data").path("shots").isEmpty()).isTrue();

        JsonNode record = read(mapper.createObjectNode().set("shotIds",
                mapper.createArrayNode().add(manyShots.get(2).id().toString())).toString()).path("data").path("shots");
        assertThat(record.size()).isEqualTo(1);
        assertThat(record.get(0).path("id").asText()).isEqualTo(manyShots.get(2).id().toString());
        assertThat(record.get(0).path("description").asText()).isEqualTo(manyShots.get(2).description());
        assertThat(record.get(0).path("dialogue").asText()).isEqualTo(manyShots.get(2).dialogue());
        assertThat(record.get(0).has("descriptionPreview")).isFalse();
        verifyNoInteractions(artifacts);
    }

    @Test void appendUsesServerIdentityAndDoesNotRequireOrExposeMediaAuthorization() {
        ObjectNode append = mapper.createObjectNode().put("code", "SHOT-003").put("description", "远景")
                .put("dialogue", "").put("durationSeconds", new BigDecimal("1.125"));
        when(boards.save(eq(owner), eq(project), any())).thenAnswer(invocation -> {
            StoryboardService.Save save = invocation.getArgument(2);
            var created = save.shots().getLast();
            return new Storyboard(project, 6, List.of(first, second, new Shot(created.id(), created.code(),
                    created.scene(), created.title(), created.description(), created.dialogue(), created.durationSeconds(),
                    created.sourceVersionId(), created.referenceVersionIds(), 1, List.of())), composition);
        });

        JsonNode result = write(append);
        var save = savedRequest();
        var created = save.shots().getLast();
        assertThat(save.expectedVersion()).isEqualTo(5);
        assertThat(created.id()).isNotIn(first.id(), second.id());
        assertThat(created.scene()).isEmpty();
        assertThat(created.title()).isEmpty();
        assertThat(created.sourceVersionId()).isNull();
        assertThat(created.productions()).isEmpty();
        assertThat(result.path("createdIds").get(0).asText()).isEqualTo(created.id().toString());
        assertThat(result.path("data").path("shots").size()).isEqualTo(1);
        assertThat(result.path("data").path("shots").get(0).has("description")).isFalse();
        assertThat(result.path("affectedVersions").isEmpty()).isTrue();
        assertThat(result.path("taskIds").isEmpty()).isTrue();
        verifyNoInteractions(artifacts);
    }

    @Test void targetedDialoguePatchRetainsEveryOtherFieldAndNeverConfirmsPinnedResults() {
        JsonNode result = write(patch(first).put("dialogue", "只修改第一镜台词"));
        var save = savedRequest();
        var changed = save.shots().getFirst();
        assertThat(changed.id()).isEqualTo(first.id());
        assertThat(changed.dialogue()).isEqualTo("只修改第一镜台词");
        assertThat(changed.description()).isEqualTo(first.description());
        assertThat(changed.referenceVersionIds()).isEqualTo(first.referenceVersionIds());
        assertThat(changed.sourceVersionId()).isEqualTo(first.sourceVersionId());
        assertThat(save.shots().get(1).id()).isEqualTo(second.id());
        assertThat(save.shots().get(1).dialogue()).isEqualTo(second.dialogue());
        for (var shot : save.shots()) {
            assertThat(shot.productions()).hasSize(1);
            assertThat(shot.productions().getFirst().confirmReviewed()).isFalse();
        }
        assertThat(changed.productions().getFirst().selectedVersionId()).isEqualTo(first.productions().getFirst().selectedVersionId());
        assertThat(save.composition().canvasItemId()).isEqualTo(composition.canvasItemId());
        assertThat(save.composition().selectedVersionId()).isEqualTo(composition.selectedVersionId());
        assertThat(save.composition().confirmReviewed()).isFalse();
        assertThat(result.path("updatedIds").get(0).asText()).isEqualTo(first.id().toString());
        verifyNoInteractions(artifacts);
    }

    @Test void onlyIntroducedReferencesRequireTheFrozenRunReadableScope() {
        UUID source = UUID.randomUUID();
        UUID reference = UUID.randomUUID();
        ObjectNode patch = patch(first).put("sourceVersionId", source.toString());
        patch.putArray("referenceVersionIds").add(first.referenceVersionIds().getFirst().toString()).add(reference.toString());

        write(patch);

        verify(artifacts).requireAgentReadableVersion(owner, project, snapshot, context.runId(), source);
        verify(artifacts).requireAgentReadableVersion(owner, project, snapshot, context.runId(), reference);
        verify(artifacts, never()).requireAgentReadableVersion(owner, project, snapshot, context.runId(), first.referenceVersionIds().getFirst());
        assertThat(savedRequest().shots().getFirst().referenceVersionIds()).containsExactly(first.referenceVersionIds().getFirst(), reference);
    }

    @Test void clearingSourceIsAnEditWithoutAdditionalReadPermission() {
        write(patch(first).putNull("sourceVersionId"));
        assertThat(savedRequest().shots().getFirst().sourceVersionId()).isNull();
        verifyNoInteractions(artifacts);
    }

    @Test void unreadableReferenceRejectsBeforeAnyBoardWrite() {
        UUID denied = UUID.randomUUID();
        var problem = new ApiProblemException(HttpStatus.FORBIDDEN, "INPUT_SCOPE_DENIED",
                ApiMessage.of("api.storyboard.invalid-title"), ApiMessage.of("api.storyboard.invalid-title"), false);
        when(artifacts.requireAgentReadableVersion(owner, project, snapshot, context.runId(), denied)).thenThrow(problem);
        assertThatThrownBy(() -> write(patch(first).put("sourceVersionId", denied.toString()))).isSameAs(problem);
        verify(boards, never()).save(any(), any(), any());
    }

    @Test void readRejectsUnknownScopeFieldsMixedModesMalformedPagesAndMultipleFullBodies() {
        for (String arguments : List.of("null", "[]", "{} {}", "{\"offset\":0,\"offset\":1}",
                "{\"projectId\":\"" + project + "\"}", "{\"limit\":0}", "{\"limit\":41}",
                "{\"offset\":-1}", "{\"offset\":1.5}", "{\"offset\":2147483648}",
                "{\"shotIds\":[]}", "{\"shotIds\":[\"bad\"]}",
                "{\"shotIds\":[\"" + first.id() + "\"],\"offset\":0}",
                "{\"shotIds\":[\"" + first.id() + "\",\"" + second.id() + "\"]}")) {
            assertInvalid(() -> read(arguments));
        }
        assertInvalid(() -> read("{\"offset\":3}"));
        assertInvalid(() -> read("{\"shotIds\":[\"" + UUID.randomUUID() + "\"]}"));
        assertInvalid(() -> read("{}" + " ".repeat(1024 * 1024)));
        verify(boards, never()).save(any(), any(), any());
        verifyNoInteractions(artifacts);
    }

    @Test void rejectsForbiddenWriteFieldsAndInvalidCreativeValuesAsToolArguments() {
        for (String field : List.of("productions", "composition", "selectedVersionId", "revision", "confirmReviewed",
                "needsReview", "projectId", "runId", "userId", "approved", "delete", "position")) {
            assertInvalid(() -> write(patch(first).put("dialogue", "unchanged").put(field, true)));
        }
        for (ObjectNode value : List.of(patch(first), patch(first).put("code", " "), patch(first).put("code", " SHOT-001"),
                patch(first).put("code", "x".repeat(81)), patch(first).put("title", "x".repeat(201)),
                patch(first).put("description", "x".repeat(20_001)), patch(first).putNull("dialogue"),
                patch(first).put("durationSeconds", new BigDecimal("0.099")),
                patch(first).put("durationSeconds", new BigDecimal("3600.001")),
                patch(first).put("durationSeconds", new BigDecimal("1.0001")),
                patch(first).put("durationSeconds", "1"), patch(first).put("sourceVersionId", "bad"))) {
            assertInvalid(() -> write(value));
        }
        ObjectNode duplicateReferences = patch(first);
        duplicateReferences.putArray("referenceVersionIds").add(first.referenceVersionIds().getFirst().toString())
                .add(first.referenceVersionIds().getFirst().toString());
        assertInvalid(() -> write(duplicateReferences));
        ObjectNode excessiveReferences = patch(first);
        var references = excessiveReferences.putArray("referenceVersionIds");
        IntStream.range(0, 31).forEach(index -> references.add(UUID.randomUUID().toString()));
        assertInvalid(() -> write(excessiveReferences));
        assertInvalid(() -> tools.writeShots(context, run, operation, "{\"expectedVersion\":5,\"shots\":[],\"approved\":true}"));
        verify(boards, never()).save(any(), any(), any());
        verifyNoInteractions(artifacts);
    }

    @Test void rejectsUnknownOrDuplicateShotIdsCodeCollisionsAndExcessiveBatches() {
        assertInvalid(() -> write(patch(first).put("shotId", UUID.randomUUID().toString()).put("dialogue", "new")));
        assertInvalid(() -> write(patch(first).put("dialogue", "one"), patch(first).put("dialogue", "two")));
        assertInvalid(() -> write(patch(first).put("code", second.code())));
        assertInvalid(() -> write(IntStream.range(0, 7).mapToObj(index -> mapper.createObjectNode().put("code", "NEW-" + index)
                .put("description", "").put("dialogue", "").put("durationSeconds", 1)).toArray(ObjectNode[]::new)));
        assertInvalid(() -> write());
        assertInvalid(() -> tools.writeShots(context, run, operation, "{\"expectedVersion\":5.5,\"shots\":[{}]}"));
        verify(boards, never()).save(any(), any(), any());
        verifyNoInteractions(artifacts);
    }

    @Test void rejectsAppendPastBoardCapacity() {
        var fullBoard = new Storyboard(project, 5, IntStream.range(0, 200).mapToObj(index -> shot("FULL-" + index)).toList(), null);
        when(boards.get(owner, project)).thenReturn(fullBoard);
        assertInvalid(() -> write(mapper.createObjectNode().put("code", "NEW").put("description", "").put("dialogue", "")
                .put("durationSeconds", 1)));
        verify(boards, never()).save(any(), any(), any());
    }

    @Test void preservesBothStaleReadAndConcurrentSaveConflictCodes() {
        ObjectNode request = request(patch(first).put("dialogue", "new")).put("expectedVersion", 4);
        assertThatThrownBy(() -> tools.writeShots(context, run, operation, request.toString()))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.code()).isEqualTo("STORYBOARD_VERSION_CONFLICT");
                    assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(boards, never()).save(any(), any(), any());
        var conflict = new ApiProblemException(HttpStatus.CONFLICT, "STORYBOARD_VERSION_CONFLICT",
                ApiMessage.of("api.storyboard.conflict-title"), ApiMessage.of("api.storyboard.conflict-detail"), false);
        when(boards.save(eq(owner), eq(project), any())).thenThrow(conflict);
        assertThatThrownBy(() -> write(patch(first).put("dialogue", "new"))).isSameAs(conflict);
    }

    private JsonNode read(String arguments) { return tools.readStoryboard(context, run, operation, arguments); }
    private JsonNode write(ObjectNode... patches) { return tools.writeShots(context, run, operation, request(patches).toString()); }
    private ObjectNode request(ObjectNode... patches) {
        ObjectNode request = mapper.createObjectNode().put("expectedVersion", 5);
        var shots = request.putArray("shots");
        for (ObjectNode patch : patches) shots.add(patch);
        return request;
    }
    private ObjectNode patch(Shot shot) { return mapper.createObjectNode().put("shotId", shot.id().toString()); }
    private StoryboardService.Save savedRequest() {
        var argument = ArgumentCaptor.forClass(StoryboardService.Save.class);
        verify(boards).save(eq(owner), eq(project), argument.capture());
        return argument.getValue();
    }
    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiProblemException.class,
                problem -> assertThat(problem.code()).isEqualTo("TOOL_ARGUMENT_INVALID"));
    }
    private Shot shot(String code) {
        return new Shot(UUID.randomUUID(), code, "SC-01", "title", "画面", "台词", BigDecimal.ONE,
                UUID.randomUUID(), List.of(UUID.randomUUID()), 2,
                List.of(new Production(Role.VIDEO, UUID.randomUUID(), UUID.randomUUID(), 1, true)));
    }
}
