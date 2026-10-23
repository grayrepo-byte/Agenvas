package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.application.MediaDraftRestoreService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.character.application.CharacterModels.*;
import dev.agenvas.character.application.CharacterService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Mock-only metadata projection: character references never become an implicit media read. */
class CanvasCharacterSummaryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
    private final CanvasService canvas = mock(CanvasService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final DirectMediaTaskService media = mock(DirectMediaTaskService.class);
    private final CharacterService characters = mock(CharacterService.class);
    private final AgentRun run = mock(AgentRun.class);
    private final TrustedToolContext context = new TrustedToolContext(owner, project, UUID.randomUUID());
    private final CanvasAgentToolService service = new CanvasAgentToolService(canvas, artifacts, drafts,
            mock(MediaDraftRestoreService.class), media, mapper, characters);

    @Test
    void directoryAndSingleNodeExposeUsefulMetadataWithoutReferenceIdsOrReadAuthority() {
        UUID cardId = UUID.randomUUID(), characterId = UUID.randomUUID(), referenceId = UUID.randomUUID();
        var look = new CharacterLook(UUID.randomUUID(), "Winter", "Blue coat", "Calm", "Rain-soaked traveler",
                List.of(new CharacterView(referenceId, "front", "Front view")));
        var snapshot = new CharacterSnapshot(characterId, 2, "Aster", "Short silver hair", look, null, true, false);
        var profile = profile(characterId, new CharacterContent("Aster", "Short silver hair", List.of(look), null), 3);
        var binding = new CharacterBinding(cardId, 1, 0, null, List.of(snapshot), true,
                List.of(ReviewReason.DRAFT_CHANGED),
                new CharacterContribution(List.of(referenceId), "Applied continuity block", null), null);
        stubIndex(List.of(profile), List.of(binding));
        stubNode(cardId);
        when(characters.frozenBindings(owner, project, cardId)).thenReturn(mapper.createArrayNode());

        var result = read(mapper.createObjectNode().put("limit", 1));
        assertThat(result.at("/characters/0/name").asText()).isEqualTo("Aster");
        assertThat(result.at("/characters/0/looks/0/name").asText()).isEqualTo("Winter");
        assertThat(result.at("/characters/0/textDetailsOmitted").asBoolean()).isTrue();
        assertThat(result.at("/characters/0/looks/0/clothing").isMissingNode()).isTrue();
        assertThat(result.at("/data/0/characterBindings/0/snapshots/0/characterVersion").asLong()).isEqualTo(2);
        assertThat(result.at("/data/0/characterBindings/0/snapshots/0/look/name").asText()).isEqualTo("Winter");
        assertThat(result.at("/data/0/characterBindings/0/needsReview").asBoolean()).isTrue();
        assertThat(result.at("/data/0/characterBindings/0/appliedToCurrentDraft").asBoolean()).isFalse();
        assertThat(result.toString()).doesNotContain(referenceId.toString(), "versionId", "mediaRead", "Blue coat");
        verify(characters).listForAgent(owner, project);
        verifyNoInteractions(artifacts, drafts, media, run);
    }

    @Test
    void limitOneKeepsLargeCharacterCatalogBoundedAndCharacterPagesRemainReachableOnEmptyCanvas() {
        var profiles = largeCatalog();
        stubIndex(profiles, List.of());
        var first = read(mapper.createObjectNode().put("limit", 1));
        assertThat(first.path("characters").size()).isEqualTo(10);
        assertThat(first.path("characterTotal").asInt()).isEqualTo(200);
        assertThat(first.path("nextCharacterOffset").asInt()).isEqualTo(10);
        assertThat(first.path("data").isEmpty()).isTrue();
        assertThat(first.toString().getBytes(StandardCharsets.UTF_8).length).isLessThan(200_000);
        assertThat(first.toString()).doesNotContain("large-description", "large-clothing", "large-expression");

        List<UUID> seen = new ArrayList<>();
        int offset = 0;
        while (true) {
            var page = read(mapper.createObjectNode().put("limit", 1).put("characterOffset", offset).put("characterLimit", 13));
            assertThat(page.path("offset").asInt()).isZero();
            assertThat(page.path("nextOffset").isNull()).isTrue();
            assertThat(page.path("characterOffset").asInt()).isEqualTo(offset);
            assertThat(page.path("characters").size()).isLessThanOrEqualTo(13);
            page.path("characters").forEach(character -> seen.add(UUID.fromString(character.path("characterId").asText())));
            if (page.path("nextCharacterOffset").isNull()) break;
            offset = page.path("nextCharacterOffset").asInt();
        }
        assertThat(seen).containsExactlyElementsOf(profiles.stream().map(CharacterProfile::id).toList());
        assertThat(seen).doesNotHaveDuplicates();
    }

    @Test
    void safeCharacterTextReassemblesAllUnicodeFieldsWithoutMediaIdentities() {
        UUID characterId = UUID.randomUUID(), imageId = UUID.randomUUID(), audioId = UUID.randomUUID();
        var look = new CharacterLook(UUID.randomUUID(), "雨中😀", "绸😀".repeat(600), "微笑中", "中😀文".repeat(900),
                List.of(new CharacterView(imageId, "正面😀", "镜头说明".repeat(400))));
        var voice = new CharacterVoice(audioId, "private-speaker", "声音😀".repeat(500));
        var profile = profile(characterId, new CharacterContent("主角😀", "中😀文".repeat(900), List.of(look), voice), 7);
        stubIndex(List.of(profile), List.of());
        StringBuilder joined = new StringBuilder();
        int offset = 0, pages = 0;
        while (true) {
            var request = mapper.createObjectNode().put("characterId", characterId.toString())
                    .put("characterTextOffset", offset).put("characterTextLimit", 257);
            if (offset > 0) request.put("expectedCharacterVersion", 7);
            JsonNode detail = read(request).path("characterDetails");
            String fragment = detail.path("text").asText();
            JsonNode page = detail.path("textPage");
            assertThat(detail.path("characterVersion").asLong()).isEqualTo(7);
            assertThat(detail.path("format").asText()).isEqualTo("JSON");
            assertThat(page.path("offset").asInt()).isEqualTo(offset);
            assertThat(fragment.codePointCount(0, fragment.length())).isEqualTo(page.path("endOffset").asInt() - offset);
            assertThat(fragment.codePointCount(0, fragment.length())).isLessThanOrEqualTo(257);
            assertThat(fragment.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)).isFalse();
            assertThat(page.path("truncated").asBoolean()).isTrue();
            joined.append(fragment);
            pages++;
            if (page.path("nextOffset").isNull()) {
                assertThat(page.path("endOffset").asInt()).isEqualTo(page.path("total").asInt());
                assertThat(joined.codePointCount(0, joined.length())).isEqualTo(page.path("total").asInt());
                break;
            }
            offset = page.path("nextOffset").asInt();
        }
        assertThat(pages).isGreaterThan(2);
        var expected = mapper.createObjectNode().put("characterId", characterId.toString()).put("characterVersion", 7)
                .put("name", profile.content().name()).put("description", profile.content().description()).put("archived", false);
        var expectedLook = expected.putArray("looks").addObject().put("lookId", look.id().toString()).put("name", look.name())
                .put("clothing", look.clothing()).put("expression", look.expression()).put("description", look.description());
        expectedLook.putArray("views").addObject().put("purpose", "正面😀").put("description", "镜头说明".repeat(400));
        expected.put("voiceDescription", voice.description());
        assertThat(joined.toString()).isEqualTo(expected.toString()).contains("😀")
                .doesNotContain(imageId.toString(), audioId.toString(), "versionId", "private-speaker", "speakerId", "storage");
        assertThat(mapper.readTree(joined.toString())).isEqualTo(expected);
        verifyNoInteractions(artifacts, drafts, media, run);
    }

    @Test
    void characterTextRejectsVersionDriftAndUnownedProfiles() {
        UUID characterId = UUID.randomUUID();
        var original = profile(characterId, new CharacterContent("Before", "Original", List.of(), null), 4);
        var changed = profile(characterId, new CharacterContent("After", "Changed", List.of(), null), 5);
        when(canvas.list(owner, project)).thenReturn(List.of());
        when(characters.listForAgent(owner, project)).thenReturn(new CharacterIndex(List.of(original), List.of()),
                new CharacterIndex(List.of(changed), List.of()));
        var first = read(mapper.createObjectNode().put("characterId", characterId.toString()).put("characterTextLimit", 10));
        assertThat(first.at("/characterDetails/characterVersion").asLong()).isEqualTo(4);
        assertProblem(mapper.createObjectNode().put("characterId", characterId.toString()).put("characterTextOffset", 10)
                .put("expectedCharacterVersion", 4), HttpStatus.CONFLICT, "CHARACTER_VERSION_CONFLICT");
        assertProblem(mapper.createObjectNode().put("characterId", UUID.randomUUID().toString()),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void characterPaginationRejectsInvalidRangesAndUnpinnedContinuations() {
        UUID characterId = UUID.randomUUID();
        stubIndex(List.of(profile(characterId, new CharacterContent("Aster", "Text", List.of(), null), 1)), List.of());
        for (var input : List.of(
                mapper.createObjectNode().put("characterLimit", 0),
                mapper.createObjectNode().put("characterLimit", 21),
                mapper.createObjectNode().put("characterOffset", 2),
                mapper.createObjectNode().put("characterTextLimit", 2),
                mapper.createObjectNode().put("expectedCharacterVersion", 1),
                mapper.createObjectNode().put("characterId", characterId.toString()).put("characterTextLimit", 0),
                mapper.createObjectNode().put("characterId", characterId.toString()).put("characterTextLimit", 4001),
                mapper.createObjectNode().put("characterId", characterId.toString()).put("characterTextOffset", 1),
                mapper.createObjectNode().put("characterId", characterId.toString()).put("expectedCharacterVersion", 1)
                        .put("characterTextOffset", Integer.MAX_VALUE))) {
            assertProblem(input, HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID");
        }
        var complete = read(mapper.createObjectNode().put("characterId", characterId.toString()));
        assertThat(complete.at("/characterDetails/textPage/truncated").asBoolean()).isFalse();
        assertThat(complete.at("/characterDetails/textPage/nextOffset").isNull()).isTrue();
    }

    @Test
    void nodeBindingSummariesDoNotRepeatLongProfileText() {
        UUID cardId = UUID.randomUUID(), referenceId = UUID.randomUUID();
        var profiles = largeCatalog();
        var snapshots = profiles.subList(0, 20).stream().map(profile -> new CharacterSnapshot(profile.id(), profile.version(),
                profile.content().name(), profile.content().description(), profile.content().looks().getFirst(),
                new CharacterVoice(referenceId, "private-speaker", "long voice".repeat(200)), true, true)).toList();
        var binding = new CharacterBinding(cardId, 1, 0, null, snapshots, true, List.of(ReviewReason.PROFILE_CHANGED),
                new CharacterContribution(List.of(referenceId), "long prompt block".repeat(1000), "private-speaker"), null);
        stubIndex(profiles, List.of(binding));
        stubNode(cardId);
        when(characters.frozenBindings(owner, project, cardId)).thenReturn(mapper.createArrayNode().add(mapper.createObjectNode()));
        var result = read(mapper.createObjectNode().put("limit", 1));
        assertThat(result.at("/data/0/characterBindings/0/snapshots").size()).isEqualTo(20);
        assertThat(result.at("/data/0/characterBindings/0/appliedToCurrentDraft").asBoolean()).isTrue();
        assertThat(result.at("/data/0/characterBindings/0/snapshots/0/textDetailsOmitted").asBoolean()).isTrue();
        assertThat(result.toString().getBytes(StandardCharsets.UTF_8).length).isLessThan(250_000);
        assertThat(result.toString()).doesNotContain("large-description", "large-clothing", "large-expression", "long voice",
                "long prompt block", "private-speaker", referenceId.toString());
    }

    @Test
    void readCanvasDefinitionExposesOptionalProgressiveCharacterArguments() {
        var policy = mapper.createObjectNode().put("schemaVersion", RunToolPolicy.CURRENT_SCHEMA_VERSION)
                .put("systemPromptVersion", InitialModelContextService.CURRENT_SYSTEM_PROMPT_VERSION)
                .put("toolPolicyVersion", RunToolPolicy.CURRENT_VERSION);
        policy.set("allowedTools", mapper.valueToTree(RunToolPolicy.CURRENT));
        var definition = new ToolRegistry().modelDefinitions(policy).stream()
                .map(callback -> callback.getToolDefinition()).filter(tool -> tool.name().equals("read_canvas")).findFirst().orElseThrow();
        var schema = mapper.readTree(definition.inputSchema());
        assertThat(schema.path("required").isMissingNode()).isTrue();
        assertThat(schema.at("/properties/characterLimit/maximum").asInt()).isEqualTo(20);
        assertThat(schema.at("/properties/characterLimit/default").asInt()).isEqualTo(10);
        assertThat(schema.at("/properties/characterTextLimit/maximum").asInt()).isEqualTo(4000);
        assertThat(schema.at("/properties/expectedCharacterVersion/type").asText()).isEqualTo("integer");
        assertThat(definition.description()).contains("nextCharacterOffset", "expectedCharacterVersion", "fragment");
    }

    private void stubIndex(List<CharacterProfile> profiles, List<CharacterBinding> bindings) {
        when(canvas.list(owner, project)).thenReturn(List.of());
        when(characters.listForAgent(owner, project)).thenReturn(new CharacterIndex(profiles, bindings));
    }

    private void stubNode(UUID cardId) {
        var card = mock(CanvasItem.class);
        when(card.id()).thenReturn(cardId);
        when(card.subjectId()).thenReturn(UUID.randomUUID());
        when(card.title()).thenReturn("Shot 1");
        var artifact = new Artifact(card.subjectId(), project, Artifact.Kind.TEXT, "Shot", null, null,
                0, Instant.EPOCH, Instant.EPOCH);
        when(canvas.list(owner, project)).thenReturn(List.of(new CanvasService.CanvasEntry(card,
                new ArtifactService.ArtifactView(artifact, null), null)));
    }

    private List<CharacterProfile> largeCatalog() {
        var looks = IntStream.range(0, 20).mapToObj(index -> new CharacterLook(new UUID(1, index), "造".repeat(160),
                "large-clothing".repeat(140), "large-expression".repeat(120), "large-description".repeat(230), List.<CharacterView>of())).toList();
        var content = new CharacterContent("人".repeat(160), "large-description".repeat(230), looks, null);
        return IntStream.range(0, 200).mapToObj(index -> profile(new UUID(0, index), content, 1)).toList();
    }

    private CharacterProfile profile(UUID id, CharacterContent content, long version) {
        return new CharacterProfile(id, project, content, version, false, Instant.EPOCH, Instant.EPOCH);
    }

    private JsonNode read(ObjectNode input) {
        return service.readCanvas(context, run, UUID.randomUUID(), input.toString());
    }

    private void assertProblem(ObjectNode input, HttpStatus status, String code) {
        assertThatThrownBy(() -> read(input)).isInstanceOfSatisfying(ApiProblemException.class, problem -> {
            assertThat(problem.status()).isEqualTo(status);
            assertThat(problem.code()).isEqualTo(code);
        });
    }
}
