package dev.agenvas.provider.application;

import dev.agenvas.artifact.domain.AudioGenerationParameters;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.artifact.application.ArtifactService;
import java.nio.file.Files;
import java.io.IOException;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.SeedAudioClient;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.io.ByteArrayInputStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Direct Seed speech synthesis uses encrypted pinned credentials and never resubmits UNKNOWN. */
@Component
public final class SeedAudioAdapter implements MediaAdapter {
    private final JooqMediaCapabilityRepository catalog;
    private final CredentialCipher cipher;
    private final SeedAudioClient client;
    private final AssetService assets;
    private final ObjectMapper mapper;
    private final AudioReferenceLoader audioReferences;
    private final ArtifactService artifacts;
    public SeedAudioAdapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher,
            SeedAudioClient client, AssetService assets, ObjectMapper mapper, AudioReferenceLoader audioReferences, ArtifactService artifacts) {
        this.catalog = catalog; this.cipher = cipher; this.client = client; this.assets = assets; this.mapper = mapper;
        this.audioReferences = audioReferences; this.artifacts = artifacts;
    }
    @Override public String adapterId() { return "VOLC_SEED_AUDIO_1"; }
    @Override public boolean supports(PortInput input) { return input.kind() == Task.Kind.AUDIO_GENERATION; }
    @Override public String preflightFailure(AttemptContext context) {
        try { credential(context); }
        catch (RuntimeException unavailable) { return "MEDIA_CREDENTIAL_UNAVAILABLE"; }
        try { references(context); return null; }
        catch (RuntimeException invalid) { return "PROVIDER_UNSUPPORTED_INPUT"; }
    }
    @Override public Submission submit(AttemptContext context) {
        Task task = context.lease();
        var parameters = AudioGenerationParameters.parse(task.input().path("mediaInput").path("parameters"));
        try {
            var asset = assets.archiveTaskAudio(context.ownerId(), task.projectId(), task.id(), () ->
                    new ByteArrayInputStream(client.synthesize(credential(context), context.requestKey(),
                            task.input().path("prompt").asText(), parameters, references(context))));
            return completed(task, asset.id().toString(), false);
        } catch (SeedAudioClient.Rejected rejected) { return new Submission.Rejected("SEED_AUDIO_REJECTED"); }
        catch (SeedAudioClient.Uncertain uncertain) { return new Submission.Unknown("SEED_AUDIO_RESULT_UNKNOWN"); }
    }
    @Override public Submission reconcile(AttemptContext context) {
        var recovered = assets.recoverTaskAudio(context.ownerId(), context.lease().projectId(), context.lease().id());
        return recovered.<Submission>map(asset -> completed(context.lease(), asset.id().toString(), false))
                .orElseGet(() -> new Submission.Unknown("SEED_AUDIO_RESULT_UNKNOWN"));
    }
    private List<SeedAudioClient.Reference> references(AttemptContext context) {
        Task task = context.lease();
        var parameters = AudioGenerationParameters.parse(task.input().path("mediaInput").path("parameters"));
        if (task.input().path("prompt").asText().codePointCount(0, task.input().path("prompt").asText().length()) > AudioGenerationParameters.MAX_PROMPT_LENGTH)
            throw new IllegalArgumentException("Audio prompt too long");
        var images = FrozenMediaInputs.images(task);
        var audios = audioReferences.load(context.ownerId(), task, true);
        if (images.size() > AudioGenerationParameters.MAX_REFERENCE_IMAGES || !images.isEmpty() && (!audios.isEmpty() || !parameters.speaker().isEmpty())
                || audios.size() + (parameters.speaker().isEmpty() ? 0 : 1) > AudioGenerationParameters.MAX_REFERENCE_AUDIOS)
            throw new IllegalArgumentException("Audio reference combination unsupported");
        List<SeedAudioClient.Reference> result = new ArrayList<>();
        for (var audio : audios) result.add(new SeedAudioClient.Reference("audio_data", audio.bytes()));
        for (var image : images) {
            var version = artifacts.requireVersion(context.ownerId(), task.projectId(), image.artifactId(), image.versionId());
            var file = assets.get(context.ownerId(), task.projectId(), UUID.fromString(version.content().path("assetId").asText()));
            if (file.asset().mediaKind() != dev.agenvas.asset.domain.Asset.MediaKind.IMAGE
                    || file.asset().byteSize() > AudioReferenceLoader.SEED_MAX_BYTES)
                throw new IllegalArgumentException("Image reference size unsupported");
            try (var input = Files.newInputStream(file.path())) {
                byte[] bytes = input.readNBytes(AudioReferenceLoader.SEED_MAX_BYTES + 1);
                if (bytes.length > AudioReferenceLoader.SEED_MAX_BYTES) throw new IllegalArgumentException("Image too large");
                result.add(new SeedAudioClient.Reference("image_data", bytes));
            } catch (IOException failure) { throw new IllegalStateException("Image reference unavailable", failure); }
        }
        return List.copyOf(result);
    }

    private String credential(AttemptContext context) {
        var b = context.binding();
        var snapshot = catalog.snapshotAt(b.capabilityId(), b.capabilityVersion(), b.connectionId(), b.connectionVersion())
                .orElseThrow(() -> new IllegalStateException("Pinned speech capability missing"));
        if (!adapterId().equals(snapshot.adapterId()) || !b.mappingSha256().equals(snapshot.mappingSha256()))
            throw new IllegalStateException("Pinned speech mapping differs");
        var version = snapshot.connectionVersion();
        return cipher.decryptMedia(version.connectionId(), version.version(), new CredentialCipher.Encrypted(
                version.credentialCiphertext(), version.credentialNonce(), version.credentialKeyVersion()));
    }
    private Submission completed(Task task, String assetId, boolean mock) {
        return new Submission.CompletedArtifact(AudioResult.content(mapper, task, assetId, mock));
    }
}
