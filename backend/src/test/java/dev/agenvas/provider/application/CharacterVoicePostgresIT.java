package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.character.application.CharacterModels.*;
import dev.agenvas.character.application.CharacterService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL, decoder-validated synthetic WAVs and the production reference loader.
 * This verifies immutable audio inputs, without sending a provider or model request. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class CharacterVoicePostgresIT {
    private static final String SPEAKER = "synthetic_voice_1";
    @TempDir static Path storage;
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.storage.root", () -> storage.toString());
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired AssetService assets;
    @Autowired ArtifactService artifacts;
    @Autowired CanvasService canvas;
    @Autowired MediaDraftService drafts;
    @Autowired CharacterService characters;
    @Autowired DirectMediaTaskService media;
    @Autowired TaskService tasks;
    @Autowired AudioReferenceLoader audioReferences;
    @Autowired ObjectMapper mapper;

    @Test
    void voiceApplicationFreezesExactAudioAndSpeakerAndRejectsAnImageTargetAtomically() {
        var owner = identities.setup("character-voice-admin", "character-voice-password-123");
        var project = projects.create(owner.userId(), "Character voice", Project.AspectRatio.SQUARE_1_1);
        byte[] originalBytes = wav(440);
        UUID originalVoice = audio(owner.userId(), project.id(), originalBytes);
        UUID replacementVoice = audio(owner.userId(), project.id(), wav(660));
        UUID characterId = UUID.randomUUID();
        var character = characters.save(owner.userId(), project.id(), characterId,
                new SaveCharacter(UUID.randomUUID(), 0, content(originalVoice, SPEAKER)));
        var output = artifacts.create(owner.userId(), project.id(), Artifact.Kind.AUDIO, "Voice line", null);
        UUID itemId = place(owner.userId(), project.id(), output.artifact().id(), 0);
        var draft = drafts.save(owner.userId(), project.id(), itemId, 0, "Hello from the synthetic fixture.",
                mapper.createObjectNode(), null, null, null, List.of(), List.of(), null);
        var selected = new CharacterBindingSelection(characterId, character.version(), null, false, true);
        var preview = characters.preview(owner.userId(), project.id(), itemId,
                new PreviewCharacterBindings(0, draft.version(), List.of(selected), ClearMode.REMOVE_OWNED));
        assertThat(preview.candidateDraft().mediaInputs()).singleElement().satisfies(input -> {
            assertThat(input.versionId()).isEqualTo(originalVoice);
            assertThat(input.role()).isEqualTo(MediaDraft.InputRole.AUDIO_REFERENCE);
        });
        assertThat(preview.candidateDraft().parameters().path("speaker").asText()).isEqualTo(SPEAKER);
        assertThat(media.list(owner.userId(), project.id(), output.artifact().id(), itemId)).isEmpty();

        var applied = characters.apply(owner.userId(), project.id(), itemId,
                new ApplyCharacterBindings(UUID.randomUUID(), 0, draft.version(), List.of(selected),
                        ClearMode.REMOVE_OWNED, preview.previewHash()));
        var persisted = drafts.get(owner.userId(), project.id(), itemId);
        assertThat(persisted.mediaInputs()).singleElement().satisfies(input -> {
            assertThat(input.versionId()).isEqualTo(originalVoice);
            assertThat(input.role()).isEqualTo(MediaDraft.InputRole.AUDIO_REFERENCE);
        });
        assertThat(persisted.parameters().path("speaker").asText()).isEqualTo(SPEAKER);
        var preflight = media.preflight(owner.userId(), project.id(), output.artifact().id(), itemId, applied.draft().version());
        assertThat(preflight.safeSummary().at("/characterBindings/0/voice/versionId").asText()).isEqualTo(originalVoice.toString());
        assertThat(preflight.safeSummary().at("/characterContribution/speakerId").asText()).isEqualTo(SPEAKER);
        Task accepted = media.run(owner.userId(), project.id(), output.artifact().id(), itemId,
                applied.draft().version(), "character-voice-acceptance");
        JsonNode frozen = accepted.input().path("mediaInput").deepCopy();
        assertThat(accepted.kind()).isEqualTo(Task.Kind.AUDIO_GENERATION);
        assertThat(frozen.path("images")).isEmpty();
        assertThat(frozen.at("/audios/0/versionId").asText()).isEqualTo(originalVoice.toString());
        assertThat(frozen.at("/audios/0/role").asText()).isEqualTo(MediaDraft.InputRole.AUDIO_REFERENCE.name());
        assertThat(frozen.at("/parameters/speaker").asText()).isEqualTo(SPEAKER);
        assertThat(frozen.at("/characterBindings/0/voice/versionId").asText()).isEqualTo(originalVoice.toString());
        assertThat(frozen.at("/characterBindings/0/look").isNull()).isTrue();
        assertThat(frozen.at("/characterBindings/0/useVoice").asBoolean()).isTrue();
        assertThat(frozen.at("/characterBindings/0/useAppearance").asBoolean()).isFalse();
        assertThat(frozen.at("/characterContribution/versionIds/0").asText()).isEqualTo(originalVoice.toString());
        assertThat(frozen.at("/characterContribution/speakerId").asText()).isEqualTo(SPEAKER);
        assertThat(audioReferences.load(owner.userId(), accepted, true)).singleElement()
                .satisfies(reference -> assertThat(reference.bytes()).isEqualTo(originalBytes));

        var revised = characters.save(owner.userId(), project.id(), characterId,
                new SaveCharacter(UUID.randomUUID(), character.version(), content(replacementVoice, "synthetic_voice_2")));
        Task saved = tasks.get(owner.userId(), project.id(), accepted.id());
        assertJsonSemantics(saved.input().path("mediaInput"), frozen);
        assertThat(audioReferences.load(owner.userId(), saved, true)).singleElement()
                .satisfies(reference -> assertThat(reference.bytes()).isEqualTo(originalBytes));
        assertThat(tasks.listProviderAttempts(owner.userId(), project.id(), accepted.id())).isEmpty();

        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Image without voice support", null);
        UUID imageItem = place(owner.userId(), project.id(), image.artifact().id(), 1000);
        var imageDraft = drafts.save(owner.userId(), project.id(), imageItem, 0, "Portrait only",
                mapper.createObjectNode(), null, null, null, List.of(), List.of(), null);
        // Compare persisted states across the rejected operation; save's in-memory timestamp
        // can have finer precision than PostgreSQL's stored timestamp.
        JsonNode before = mapper.valueToTree(drafts.get(owner.userId(), project.id(), imageItem));
        var incompatible = new CharacterBindingSelection(characterId, revised.version(), null, false, true);
        assertUnsupported(() -> characters.preview(owner.userId(), project.id(), imageItem,
                new PreviewCharacterBindings(0, imageDraft.version(), List.of(incompatible), ClearMode.REMOVE_OWNED)));
        assertUnsupported(() -> characters.apply(owner.userId(), project.id(), imageItem,
                new ApplyCharacterBindings(UUID.randomUUID(), 0, imageDraft.version(), List.of(incompatible),
                        ClearMode.REMOVE_OWNED, "0".repeat(64))));
        assertJsonSemantics(mapper.valueToTree(drafts.get(owner.userId(), project.id(), imageItem)), before);
        assertThat(media.list(owner.userId(), project.id(), image.artifact().id(), imageItem)).isEmpty();
        assertThat(characters.list(owner.userId(), project.id()).bindings()).noneMatch(binding -> binding.canvasItemId().equals(imageItem));
    }

    private void assertUnsupported(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiProblemException.class, problem -> {
            assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(problem.code()).isEqualTo("VALIDATION_ERROR");
        });
    }

    private void assertJsonSemantics(JsonNode actual, JsonNode expected) {
        assertThat(mapper.readTree(actual.toString())).isEqualTo(mapper.readTree(expected.toString()));
    }

    private CharacterContent content(UUID audioVersion, String speaker) {
        return new CharacterContent("Lena", "Synthetic voice identity", List.of(),
                new CharacterVoice(audioVersion, speaker, "Warm synthetic reference"));
    }

    private UUID audio(UUID owner, UUID project, byte[] bytes) {
        var asset = assets.archiveAudio(owner, project, new ByteArrayInputStream(bytes));
        assertThat(asset.durationMs()).isEqualTo(3000);
        return artifacts.create(owner, project, Artifact.Kind.AUDIO, "Synthetic voice reference",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString()))
                .resourceDefaultVersion().id();
    }

    private UUID place(UUID owner, UUID project, UUID artifact, int x) {
        UUID item = UUID.randomUUID();
        canvas.apply(owner, project, List.of(new CanvasService.PlaceArtifact(item, artifact,
                BigDecimal.valueOf(x), BigDecimal.ZERO, new BigDecimal("430"), new BigDecimal("240"), 0, null, false)));
        return item;
    }

    private byte[] wav(int frequency) {
        int sampleRate = 16000, frames = sampleRate * 3;
        byte[] pcm = new byte[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            short sample = (short) (Math.sin(2 * Math.PI * frequency * frame / sampleRate) * 5000);
            pcm[frame * 2] = (byte) sample;
            pcm[frame * 2 + 1] = (byte) (sample >>> 8);
        }
        var format = new AudioFormat(sampleRate, 16, 1, true, false);
        try (var input = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames);
                var output = new ByteArrayOutputStream()) {
            if (AudioSystem.write(input, AudioFileFormat.Type.WAVE, output) <= 0)
                throw new IllegalStateException("WAV encoder unavailable");
            return output.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException("Cannot create synthetic WAV", failure); }
    }
}
