package dev.agenvas.provider.application;

import static dev.agenvas.provider.domain.MediaAdapterRegistry.GOOGLE_NANO_BANANA_2;
import static dev.agenvas.provider.domain.MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR;
import static dev.agenvas.provider.domain.MediaAdapterRegistry.OPENAI_GPT_IMAGE_2;
import static dev.agenvas.provider.domain.MediaAdapterRegistry.SEED_AUDIO_1;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.provider.domain.AttemptContext;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Rebuilds single-result synchronous artifacts from the fixed input and installed archive. */
@Component
public class ArchivedMediaRecovery {
    private final AssetService assets;
    private final ObjectMapper mapper;

    public ArchivedMediaRecovery(AssetService assets, ObjectMapper mapper) {
        this.assets = assets;
        this.mapper = mapper;
    }

    /** This path does not load current provider credentials or invoke a media adapter. */
    public Optional<ObjectNode> recover(AttemptContext attempt) {
        var task = attempt.lease();
        String adapterId = attempt.binding().adapterId();
        return switch (adapterId) {
            case OPENAI_GPT_IMAGE_2, GOOGLE_NANO_BANANA_2, LOCAL_IMAGE_PROCESSOR ->
                assets.recoverTaskImage(attempt.ownerId(), task.projectId(), task.id())
                        .map(asset -> MediaResult.providerContent(mapper, attempt, asset));
            case SEED_AUDIO_1, MockAudioAdapter.ADAPTER_ID ->
                assets.recoverTaskAudio(attempt.ownerId(), task.projectId(), task.id())
                        .map(asset -> AudioResult.content(mapper, task, asset.id().toString(),
                                MockAudioAdapter.ADAPTER_ID.equals(adapterId)));
            default -> Optional.empty();
        };
    }
}
