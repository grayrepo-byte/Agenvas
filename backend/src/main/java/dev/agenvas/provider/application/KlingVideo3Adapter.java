package dev.agenvas.provider.application;

import dev.agenvas.artifact.domain.VideoGenerationParameters;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.KlingProtocol;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.KlingClient;
import dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** New Kling 3 single-shot video protocol; local cancellation never cancels other remote jobs. */
@Component
public class KlingVideo3Adapter extends KlingAdapterSupport {
    private final MediaToolRunner mediaTools;
    public KlingVideo3Adapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher,
            KlingClient client, KlingMediaDownloadPolicy downloads, ObjectMapper mapper, KlingReferenceLoader references,
            MediaToolRunner mediaTools) {
        super(catalog, cipher, client, downloads, mapper, references);
        this.mediaTools = mediaTools;
    }
    @Override public String adapterId() { return KlingProtocol.VIDEO_ADAPTER_ID; }
    @Override protected boolean video() { return true; }
    @Override public boolean supports(PortInput input) {
        return input.kind() == Task.Kind.VIDEO_GENERATION && input.durationSeconds() >= KlingProtocol.MINIMUM_SECONDS
                && input.durationSeconds() <= KlingProtocol.MAXIMUM_SECONDS;
    }

    @Override protected ObjectNode prepare(AttemptContext context, boolean submitting) {
        var task = context.lease();
        var mediaInput = task.input().path("mediaInput");
        var parameters = VideoGenerationParameters.parse(mediaInput.path("parameters"));
        String ratio = mediaInput.path("providerParameters").path("aspectRatio").asText();
        return client.prepareVideo(prompt(context), ratio, parameters.videoResolution(),
                task.input().path("durationSeconds").asInt(-1), references.load(context, true),
                submitting ? context.requestKey() : "preflight-only");
    }

    @Override protected void validateResult(AttemptContext context, Path file) {
        String probe;
        try { probe = mediaTools.ffprobe(List.of("-v", "error", "-show_entries",
                "stream=codec_type:format=format_name,duration", "-of", "json", file.toString())); }
        catch (MediaToolRunner.MediaToolException failure) {
            if (failure.invalidInput()) throw new KlingMediaDownloadPolicy.Rejected("PROVIDER_PROTOCOL_INVALID");
            throw failure;
        }
        var details = mapper.readTree(probe);
        double duration = details.path("format").path("duration").asDouble(-1);
        int expected = context.lease().input().path("durationSeconds").asInt(-1);
        boolean videoStream = false;
        for (var stream : details.path("streams")) videoStream |= "video".equals(stream.path("codec_type").asText());
        if (!videoStream || !details.path("format").path("format_name").asText().contains("mp4")
                || !Double.isFinite(duration) || expected < KlingProtocol.MINIMUM_SECONDS || expected > KlingProtocol.MAXIMUM_SECONDS
                || duration < expected - 1.5 || duration > expected + 1.5)
            throw new KlingMediaDownloadPolicy.Rejected("PROVIDER_PROTOCOL_INVALID");
    }
}
