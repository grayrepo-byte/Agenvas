package dev.agenvas.provider.application;

import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.KlingClient;
import dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** All current generation profiles preserve the returned MP4 and its original audio tracks. */
@Component
public class KlingVideoAdapter extends KlingProtocolAdapter {
    private final MediaToolRunner tools;
    public KlingVideoAdapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher, KlingClient client,
            KlingMediaDownloadPolicy downloads, ObjectMapper mapper, KlingPreparedMedia media, MediaToolRunner tools) {
        super(Task.Kind.VIDEO_GENERATION, catalog, cipher, client, downloads, mapper, media);
        this.tools = tools;
    }
    @Override public String adapterId() { return KlingModels.VIDEO_ADAPTER_ID; }

    @Override protected void validateResult(AttemptContext context, KlingModels.Profile profile, JsonNode values, Path file) {
        String probe;
        try { probe = tools.ffprobe(List.of("-v", "error", "-show_entries",
                "stream=codec_type:format=format_name,duration", "-of", "json", file.toString())); }
        catch (MediaToolRunner.MediaToolException failure) {
            if (failure.invalidInput()) throw new KlingMediaDownloadPolicy.Rejected("PROVIDER_PROTOCOL_INVALID");
            throw failure;
        }
        var details = mapper.readTree(probe);
        double duration = details.path("format").path("duration").asDouble(-1);
        int maximum = KlingModels.motion(profile) && "image".equals(values.path("character_orientation").asText())
                ? 10 : KlingModels.maximumSeconds(profile);
        boolean video = false;
        for (var stream : details.path("streams")) video |= "video".equals(stream.path("codec_type").asText());
        if (!video || !details.path("format").path("format_name").asText().contains("mp4")
                || !Double.isFinite(duration) || duration < (KlingModels.motion(profile) ? 2.9 : 1.5)
                || duration > maximum + (KlingModels.motion(profile) ? 0.1 : 1.5))
            throw new KlingMediaDownloadPolicy.Rejected("PROVIDER_PROTOCOL_INVALID");
        // Motion may extract a shorter usable segment; video editing may preserve the source duration.
        if (!KlingModels.motion(profile) && !KlingModels.present(values, "base_video")
                && Math.abs(duration - values.path("duration").asInt(-1)) > 1.5)
            throw new KlingMediaDownloadPolicy.Rejected("PROVIDER_PROTOCOL_INVALID");
    }
}
