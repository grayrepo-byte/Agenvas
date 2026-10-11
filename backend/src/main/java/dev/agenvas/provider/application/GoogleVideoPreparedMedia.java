package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.GoogleVideoModels;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.infrastructure.GoogleVideoClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Google uses original inline bytes, except extensions which require an acknowledged Google-generated file. */
@Component
public class GoogleVideoPreparedMedia {
    private final NativeMediaReferences references;
    public GoogleVideoPreparedMedia(NativeMediaReferences references) { this.references = references; }

    public Map<String, GoogleVideoClient.Input> load(AttemptContext context, NativeMediaProfiles.Selection selected,
            JsonNode values, boolean submitting) {
        Map<String, NativeMediaReferences.Input> originals = references.load(context, selected, values, submitting);
        Map<String, GoogleVideoClient.Input> prepared = new LinkedHashMap<>();
        long total = 0;
        for (var entry : originals.entrySet()) {
            var input = entry.getValue();
            if (GoogleVideoModels.veo(selected.profile()) && "base_video".equals(entry.getKey())) {
                String name = extensionFile(context, input.sourceContent());
                prepared.put(entry.getKey(), new GoogleVideoClient.Input("video/mp4", null, name));
            } else {
                total += input.bytes().length;
                if (total > GoogleVideoModels.MAX_INPUT_BYTES || input.url() != null) throw GoogleVideoModels.invalid();
                prepared.put(entry.getKey(), new GoogleVideoClient.Input(input.contentType(), input.bytes()));
            }
        }
        return Map.copyOf(prepared);
    }

    static String extensionFile(AttemptContext context, JsonNode content) {
        JsonNode result = content.at("/parameters/nativeProviderResult");
        if (!result.isObject() || !result.propertyNames().equals(Set.of("adapterId", "model", "connectionId", "connectionVersion", "fileName"))
                || !GoogleVideoModels.VEO.equals(result.path("adapterId").asText())
                || !GoogleVideoModels.VEO_MODELS.contains(result.path("model").asText())
                || !context.binding().connectionId().toString().equals(result.path("connectionId").asText())
                || !result.path("connectionVersion").isIntegralNumber()
                || context.binding().connectionVersion() != result.path("connectionVersion").asInt(-1)
                || !GoogleVideoClient.validFileName(result.path("fileName").asText())) throw GoogleVideoModels.invalid();
        return result.path("fileName").asText();
    }
}
