package dev.agenvas.provider.application;

import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.NativeMediaProtocol;
import dev.agenvas.provider.domain.WanModels;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.WanClient;
import dev.agenvas.provider.infrastructure.WanMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Wan native audio is preserved while the returned MP4 duration is verified before archival. */
@Component
public final class WanVideoAdapter extends WanProtocolAdapter {
    private final MediaToolRunner tools;
    public WanVideoAdapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher, WanClient client,
            WanMediaDownloadPolicy downloads, ObjectMapper mapper, NativeMediaReferences references, MediaToolRunner tools) {
        super(Task.Kind.VIDEO_GENERATION, catalog, cipher, client, downloads, mapper, references); this.tools = tools;
    }
    @Override public String adapterId() { return WanModels.VIDEO_ADAPTER_ID; }
    @Override protected void validateResult(AttemptContext context, NativeMediaProtocol.Profile profile, JsonNode values, Path file) {
        JsonNode probe;
        try { probe = mapper.readTree(tools.ffprobe(List.of("-v", "error", "-show_entries", "stream=codec_type:format=format_name,duration", "-of", "json", file.toString()))); }
        catch (MediaToolRunner.MediaToolException failure) { if (failure.invalidInput()) throw WanClient.protocolFailure(); throw failure; }
        double seconds = probe.path("format").path("duration").asDouble(-1);
        double inputSeconds = context == null ? 0 : context.lease().input().path("nativeBillableInputSeconds").asDouble(0);
        int videos = 0, audios = 0;
        for (JsonNode stream : probe.path("streams")) {
            if ("video".equals(stream.path("codec_type").asText())) videos++;
            if ("audio".equals(stream.path("codec_type").asText())) audios++;
        }
        if (videos != 1 || !probe.path("format").path("format_name").asText().contains("mp4")
                || !Double.isFinite(seconds) || seconds < 1.95 || seconds > 30.05
                || !Double.isFinite(inputSeconds) || inputSeconds < 0 || inputSeconds + seconds > 30.05
                || !values.path("audio").asBoolean(true) && audios > 0
                || values.path("audio").asBoolean(true) && audios == 0
                || !values.path("smart_duration").asBoolean(false) && Math.abs(seconds - values.path("duration").asInt(5)) > 1.5)
            throw WanClient.protocolFailure();
    }
}
