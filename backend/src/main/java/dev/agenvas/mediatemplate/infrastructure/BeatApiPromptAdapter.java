package dev.agenvas.mediatemplate.infrastructure;

import static dev.agenvas.mediatemplate.infrastructure.VideoPromptJson.*;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptAdapter;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptSource;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptValidation;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.*;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** The full public catalog includes both creator prompts and original reusable templates. */
@Component
public class BeatApiPromptAdapter implements ThirdPartyPromptAdapter {
    private final ObjectMapper mapper;
    public BeatApiPromptAdapter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override public Format format() { return Format.BEATAPI_JSON; }
    @Override public List<ThirdPartyPrompt> parse(ThirdPartyPromptSource source, String body) {
        if (source.targetKind() != TargetKind.VIDEO) throw ThirdPartyPromptValidation.invalid();
        var result = new LinkedHashMap<String, ThirdPartyPrompt>();
        for (var item : items(mapper.readTree(body))) {
            String slug = text(item, "slug"), prompt = text(item, "prompt");
            if (slug.isBlank() || prompt.isBlank()) throw ThirdPartyPromptValidation.invalid();
            var references = references(mapper, item);
            var inputs = VideoPromptInputs.resolve(prompt, VideoPromptInputs.mode(text(item, "mode")), references, strings(item, "ingredients"), false);
            String id = source.id() + ":" + slug;
            var value = new Video(id, source.id(), localized(item, "title"), prompt, localized(item, "description"),
                    text(item, "thumbnail"), text(item, "category").isBlank() ? List.of() : List.of(text(item, "category")),
                    text(item.path("source"), "name"), text(item.path("source"), "url"), text(item, "createdAt"),
                    inputs.mode(), source.model(), references, null, text(item, "video"), inputs.missing());
            var previous = result.putIfAbsent(id, value);
            if (previous != null && !previous.equals(value)) throw ThirdPartyPromptValidation.invalid();
        }
        return List.copyOf(result.values());
    }
}
